package xfacthd.depextract.extractor;

import joptsimple.*;
import org.apache.commons.lang3.mutable.MutableObject;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import xfacthd.depextract.Main;
import xfacthd.depextract.data.FileEntry;
import xfacthd.depextract.data.classfinder.LocatedTarget;
import xfacthd.depextract.data.classfinder.ContainingClass;
import xfacthd.depextract.html.Css;
import xfacthd.depextract.html.Html;
import xfacthd.depextract.util.DataExtractor;
import xfacthd.depextract.util.Utils;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class ClassFinderExtractor extends DataExtractor
{
    private static final String CLASSFINDER_RESULT_FILE_NAME = "classfinder.html";

    private OptionSpec<Boolean> searchClassesOpt = null;
    private OptionSpec<String> targetClassesOpt = null;
    private OptionSpec<String> ignoredClassesOpt = null;
    private boolean active = false;
    private final Set<String> targetClasses = new HashSet<>();
    private final Set<String> ignoredClasses = new HashSet<>();
    private final Map<String, List<ContainingClass>> searchResults = new HashMap<>();

    @Override
    public void registerOptions(OptionParser parser)
    {
        searchClassesOpt = parser.accepts("search_classes", "Search references to arbitrary classes")
                .withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(false);

        targetClassesOpt = parser.accepts("target_classes", "Fully qualified names of classes to be searched")
                .availableIf(searchClassesOpt)
                .requiredIf(searchClassesOpt)
                .withRequiredArg()
                .withValuesSeparatedBy(",")
                .ofType(String.class);

        ignoredClassesOpt = parser.accepts("ignored_classes", "Fully qualified names of classes whose usage of target types should be ignored")
                .availableIf(searchClassesOpt)
                .withRequiredArg()
                .withValuesSeparatedBy(",")
                .ofType(String.class);
    }

    @Override
    public void readOptions(OptionSet options)
    {
        targetClasses.clear();
        ignoredClasses.clear();

        active = options.valueOf(searchClassesOpt);
        if (!active)
        {
            return;
        }

        options.valuesOf(targetClassesOpt).stream()
                .map(ClassFinderExtractor::correctClassName)
                .forEach(targetClasses::add);

        if (options.has(ignoredClassesOpt))
        {
            ignoredClasses.addAll(options.valuesOf(ignoredClassesOpt));
        }
    }

    private static String correctClassName(String name)
    {
        name = name.replace(".", "/");
        if (!name.startsWith("L"))
        {
            name = "L" + name;
        }
        if (!name.endsWith(";"))
        {
            name += ";";
        }
        return name;
    }

    @Override
    public boolean isActive() { return active; }

    @Override
    public String name() { return "ClassFinder"; }

    @Override
    public void acceptFile(String fileName, FileSystem modJar, boolean jij, FileEntry modInfo) throws IOException
    {
        try (Stream<Path> stream = Files.walk(modJar.getPath("/")))
        {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .forEach(p -> scanClass(fileName, p));
        }
    }

    private void scanClass(String jarName, Path classPath)
    {
        byte[] result;
        try
        {
            InputStream stream = Files.newInputStream(classPath);
            result = stream.readAllBytes();
            stream.close();
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to read class file '%s' from mod JAR '%s'", classPath, jarName, e);
            return;
        }

        ClassReader reader = new ClassReader(result);
        String className = reader.getClassName();
        if (ignoredClasses.contains(className))
        {
            return;
        }

        ClassNode clazz = new ClassNode(Opcodes.ASM9);
        reader.accept(clazz, 0);

        String path = className + " -> ";
        ContainingClass containingClass = new ContainingClass(className);
        Consumer<LocatedTarget> resultConsumer = containingClass.locatedTargets()::add;

        Utils.forEach(clazz.visibleAnnotations, anno -> scanAnnotation(resultConsumer, path, anno));
        Utils.forEach(clazz.invisibleAnnotations, anno -> scanAnnotation(resultConsumer, path, anno));
        Utils.forEach(clazz.visibleTypeAnnotations, anno -> scanAnnotation(resultConsumer, path, anno));
        Utils.forEach(clazz.invisibleTypeAnnotations, anno -> scanAnnotation(resultConsumer, path, anno));
        Utils.forEach(clazz.interfaces, itf -> scanType(resultConsumer, path, Type.getType("L" + itf + ";")));
        Utils.forEach(clazz.recordComponents, component -> scanRecordComponent(resultConsumer, path, component));
        Utils.forEach(clazz.fields, field -> scanField(resultConsumer, path, field));
        Utils.forEach(clazz.methods, method -> scanMethod(resultConsumer, path, method));
        scanType(resultConsumer, path, Type.getType("L" + clazz.superName + ";"));

        if (!containingClass.locatedTargets().isEmpty())
        {
            searchResults.computeIfAbsent(jarName, $ -> new ArrayList<>()).add(containingClass);
        }
    }

    private void scanAnnotation(Consumer<LocatedTarget> resultConsumer, String path, AnnotationNode annotation)
    {
        scanType(resultConsumer, path + "@", Type.getType(annotation.desc));
        if (annotation.values != null)
        {
            path += "@" + annotation.desc + " -> ";
            for (int i = 0; i < annotation.values.size(); i += 2)
            {
                String name = (String) annotation.values.get(i);
                Object value = annotation.values.get(i + 1);
                scanAnnotationValue(resultConsumer, path, name, value);
            }
        }
    }

    private void scanAnnotationValue(Consumer<LocatedTarget> resultConsumer, String path, String name, Object value)
    {
        String fPath = path + name + " -> ";
        if (value instanceof String[] arr)
        {
            scanType(resultConsumer, fPath, Type.getType(arr[0]));
        }
        else if (value instanceof AnnotationNode innerAnno)
        {
            scanAnnotation(resultConsumer, fPath, innerAnno);
        }
        else if (value instanceof Type type)
        {
            scanType(resultConsumer, fPath, type);
        }
        else if (value instanceof List<?> list)
        {
            list.forEach(innerValue -> scanAnnotationValue(resultConsumer, fPath, name, innerValue));
        }
    }

    private void scanRecordComponent(Consumer<LocatedTarget> resultConsumer, String path, RecordComponentNode component)
    {
        String fPath = path + component.name + " -> ";
        Utils.forEach(component.visibleAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        Utils.forEach(component.invisibleAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        Utils.forEach(component.visibleTypeAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        Utils.forEach(component.invisibleTypeAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        scanType(resultConsumer, fPath, Type.getType(component.descriptor));
    }

    private void scanField(Consumer<LocatedTarget> resultConsumer, String path, FieldNode field)
    {
        String fPath = path + field.name + " -> ";
        Utils.forEach(field.visibleAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        Utils.forEach(field.invisibleAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        Utils.forEach(field.visibleTypeAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        Utils.forEach(field.invisibleTypeAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        scanType(resultConsumer, fPath, Type.getType(field.desc));
    }

    private void scanMethod(Consumer<LocatedTarget> resultConsumer, String path, MethodNode method)
    {
        String fPath = path + method.name + "() -> ";
        int paramCount = Type.getArgumentTypes(method.desc).length;
        Utils.forEach(method.visibleAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        Utils.forEach(method.invisibleAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        Utils.forEach(method.visibleTypeAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        Utils.forEach(method.invisibleTypeAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        Utils.forEach(method.visibleParameterAnnotations, annos -> Utils.forEach(annos, (i, anno) ->
        {
            String pfPath = fPath + "param" + i + " -> ";
            scanAnnotation(resultConsumer, pfPath, anno);
        }));
        Utils.forEach(method.invisibleParameterAnnotations, annos -> Utils.forEach(annos, (i, anno) ->
        {
            String pfPath = fPath + "param" + i + " -> ";
            scanAnnotation(resultConsumer, pfPath, anno);
        }));
        Utils.forEach(method.visibleLocalVariableAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        Utils.forEach(method.invisibleLocalVariableAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        Utils.forEach(method.localVariables, var ->
        {
            int idx = (method.access & Opcodes.ACC_STATIC) != 0 ? var.index : (var.index - 1);
            String type = idx < paramCount ? " (param)" : " (local var)";
            String vfPath = fPath + var.name + type + " -> ";
            scanType(resultConsumer, vfPath, Type.getType(var.desc));
        });
        Utils.forEach(method.exceptions, exc -> scanType(resultConsumer, fPath, Type.getObjectType(exc)));
        Utils.forEach(method.tryCatchBlocks, tryCatch -> scanTryCatchBlock(resultConsumer, fPath, tryCatch));
        scanType(resultConsumer, fPath, Type.getReturnType(method.desc));
    }

    private void scanTryCatchBlock(Consumer<LocatedTarget> resultConsumer, String path, TryCatchBlockNode tryCatch)
    {
        String fPath = path + "try...catch -> ";
        Utils.forEach(tryCatch.visibleTypeAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        Utils.forEach(tryCatch.invisibleTypeAnnotations, anno -> scanAnnotation(resultConsumer, fPath, anno));
        if (tryCatch.type != null)
        {
            scanType(resultConsumer, fPath, Type.getObjectType(tryCatch.type));
        }
    }

    private void scanType(Consumer<LocatedTarget> resultConsumer, String path, Type type)
    {
        if (type.getSort() == Type.ARRAY)
        {
            scanType(resultConsumer, path + "[", type.getElementType());
        }
        else if (type.getSort() == Type.OBJECT)
        {
            String desc = type.getDescriptor();
            if (desc.startsWith("L"))
            {
                if (targetClasses.contains(desc))
                {
                    resultConsumer.accept(new LocatedTarget(
                            path.substring(0, path.length() - 4),
                            desc.substring(1, desc.length() - 1)
                    ));
                }
            }
        }
    }

    @Override
    public void postProcessData() { }

    @Override
    public void printResults(boolean darkMode, boolean minify, int modCount)
    {
        Main.LOG.info("Building ClassFinder display...");

        PrintWriter writer = Utils.makePrintWriter(CLASSFINDER_RESULT_FILE_NAME);
        if (writer == null)
        {
            Main.LOG.error("Failed to write result page!");
            return;
        }

        Html.html(
                writer,
                minify,
                darkMode ? "style=\"background-color: #0d1117; color: #f0f6fc;\"" : "",
                head ->
                {
                    Html.element(head, "title", "", "ClassFinder Result");

                    Html.style(head, style ->
                    {
                        Css.declareSelector(style, ".mod_table", clazz ->
                        {
                            Css.property(clazz, "border", String.format("1px solid %s", darkMode ? "#c9d1d9" : "black"));
                            Css.property(clazz, "border-collapse", "collapse");
                            Css.property(clazz, "padding", "4px");
                            Css.property(clazz, "vertical-align", "top");
                        });

                        Css.declareSelector(style, ".at_entry", clazz -> Css.property(clazz, "font-family", "'Courier New', monospace"));
                        Css.declareStickyHeader(style, darkMode);
                    });
                },
                body ->
                {
                    Html.element(body, "h1", "", "ClassFinder Result");

                    Html.element(body, "h3", "", "Target classes");
                    Html.unorderedList(body, list -> targetClasses.forEach(clazz -> Html.listEntry(list, clazz)));

                    if (!ignoredClasses.isEmpty())
                    {
                        Html.element(body, "h3", "", "Ignored classes");
                        Html.unorderedList(body, list -> ignoredClasses.forEach(clazz -> Html.listEntry(list, clazz)));
                    }

                    Html.element(body, "h3", "", "Search results");

                    String tableAttrib = "class=\"mod_table\"";
                    MutableObject<String> lastOwner = new MutableObject<>("");
                    MutableObject<String> lastClass = new MutableObject<>("");
                    Html.table(
                            body,
                            tableAttrib,
                            thead -> Html.element(thead, "tr", tableAttrib, row ->
                            {
                                Html.tableHeader(row, tableAttrib, "Mod file (Classes)");
                                Html.tableHeader(row, tableAttrib, "Containing class");
                                Html.tableHeader(row, tableAttrib, "Located class");
                            }),
                            tbody -> searchResults.keySet().stream().sorted(String::compareToIgnoreCase).forEachOrdered(fileName ->
                            {
                                List<ContainingClass> containingClasses = searchResults.get(fileName);
                                containingClasses.forEach(clazz ->
                                {
                                    List<LocatedTarget> targets = clazz.locatedTargets();
                                    targets.forEach(target -> Html.tableRow(tbody, tableAttrib, row ->
                                    {
                                        if (!fileName.equals(lastOwner.getValue()))
                                        {
                                            lastOwner.setValue(fileName);
                                            lastClass.setValue("");

                                            int count = containingClasses.stream()
                                                    .map(ContainingClass::locatedTargets)
                                                    .mapToInt(List::size)
                                                    .sum();

                                            String rowStyle = String.format("%s rowspan=\"%d\"", tableAttrib, count);
                                            Html.tableCell(row, rowStyle, String.format("%s (%s)", fileName, count));
                                        }

                                        if (!clazz.className().equals(lastClass.getValue()))
                                        {
                                            lastClass.setValue(clazz.className());

                                            int count = targets.size();
                                            String rowStyle = String.format("%s rowspan=\"%d\"", tableAttrib, count);
                                            Html.tableCell(row, rowStyle, clazz.className());
                                        }

                                        Html.tableCell(row, tableAttrib, cell ->
                                        {
                                            Html.span(cell, "", target.clazz());
                                            cell.println("");
                                            Html.span(cell, "", target.path().replace("->", "&rarr;"));
                                        });
                                    }));
                                });
                            })
                    );
                }
        );

        writer.close();

        Main.LOG.info("ClassFinder display built");
    }
}
