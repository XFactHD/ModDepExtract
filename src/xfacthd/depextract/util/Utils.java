package xfacthd.depextract.util;

import xfacthd.depextract.Main;
import xfacthd.depextract.html.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

public class Utils
{
    private static final String COLOR_MODIFIER = "#cb7731";
    private static final String COLOR_CLASS = "#698650";
    private static final String COLOR_TARGET = "#9876aa";
    private static final String COLOR_PRIMITIVE = "#5390ba";
    private static final String COLOR_TYPE = "#ffc66d";
    private static final String CLASS_MODIFIER = "desc_modifier";
    private static final String CLASS_CLASS = "desc_class";
    private static final String CLASS_TARGET = "desc_target";
    private static final String CLASS_PRIMITIVE = "desc_primitive";
    private static final String CLASS_TYPE = "desc_type";
    private static final String ATTR_CLASS_MODIFIER = "class=\"" + CLASS_MODIFIER + "\"";
    private static final String ATTR_CLASS_CLASS = "class=\"" + CLASS_CLASS + "\"";
    private static final String ATTR_CLASS_TARGET = "class=\"" + CLASS_TARGET + "\"";
    private static final String ATTR_CLASS_PRIMITIVE = "class=\"" + CLASS_PRIMITIVE + "\"";
    private static final String ATTR_CLASS_TYPE = "class=\"" + CLASS_TYPE + "\"";

    public static String getForgeVersion(String forgeVersion)
    {
        if (forgeVersion.contains("-"))
        {
            forgeVersion = forgeVersion.substring(forgeVersion.indexOf("-") + 1);
        }
        return forgeVersion;
    }

    public static PrintWriter makePrintWriter(String fileName)
    {
        File outFile = new File(fileName);
        if (!outFile.exists())
        {
            try
            {
                //noinspection ResultOfMethodCallIgnored
                outFile.createNewFile();
            }
            catch (IOException e)
            {
                Main.LOG.error("Output file doesn't exist and file creation failed!");
                e.printStackTrace();
                return null;
            }
        }

        PrintWriter writer;
        try
        {
            writer = new PrintWriter(outFile);
        }
        catch (FileNotFoundException e)
        {
            Main.LOG.error("Can't open output file for writing!");
            e.printStackTrace();
            return null;
        }

        return writer;
    }

    public static <T> Predicate<T> customDistinct(Function<T, Object> keyExtractor)
    {
        Set<Object> seen = ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }

    public static String removePackage(String name)
    {
        int lastDot = name.lastIndexOf('.');
        if (lastDot != -1)
        {
            return name.substring(lastDot + 1);
        }
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash != -1)
        {
            return name.substring(lastSlash + 1);
        }
        return name;
    }

    public static String toFirstCharLower(String text)
    {
        return text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
    }

    public static String toLowerExceptFirst(String text)
    {
        return text.charAt(0) + text.substring(1).toLowerCase();
    }

    public static <T, R extends T> Optional<R> findAnnotationValue(List<Object> values, String name, Class<T> type)
    {
        if (values == null || values.isEmpty())
        {
            return Optional.empty();
        }

        for (int i = 0; i < values.size(); i += 2)
        {
            if (values.get(i).equals(name))
            {
                Object value = values.get(i + 1);
                if (type.isAssignableFrom(value.getClass()))
                {
                    //noinspection unchecked
                    return Optional.of((R) value);
                }
            }
        }
        return Optional.empty();
    }

    public static void declareDescriptorSelectors(HtmlWriter writer)
    {
        Css.declareSelector(writer, "." + CLASS_MODIFIER, clazz ->
                Css.property(clazz, "color", COLOR_MODIFIER)
        );
        Css.declareSelector(writer, "." + CLASS_CLASS, clazz ->
                Css.property(clazz, "color", COLOR_CLASS)
        );
        Css.declareSelector(writer, "." + CLASS_TARGET, clazz ->
        {
            Css.property(clazz, "color", COLOR_TARGET);
            Css.property(clazz, "text-decoration", "underline");
        });
        Css.declareSelector(writer, "." + CLASS_PRIMITIVE, clazz ->
                Css.property(clazz, "color", COLOR_PRIMITIVE)
        );
        Css.declareSelector(writer, "." + CLASS_TYPE, clazz ->
                Css.property(clazz, "color", COLOR_TYPE)
        );
    }

    public static void printDescriptor(HtmlWriter writer, String modifier, String className, String memberName, String descriptor)
    {
        if (modifier != null)
        {
            //Html.span(writer, style(COLOR_MODIFIER), modifier);
            Html.span(writer, ATTR_CLASS_MODIFIER, modifier);
        }
        if (className != null)
        {
            //Html.span(writer, style(COLOR_CLASS), className);
            Html.span(writer, ATTR_CLASS_CLASS, className);
        }

        if (memberName == null && descriptor == null)
        {
            return;
        }

        writer.disableNewLine();
        writer.printIndent();
        if (memberName != null)
        {
            //Html.span(writer, style(COLOR_TARGET, true), Html.escape(memberName));
            Html.span(writer, ATTR_CLASS_TARGET, Html.escape(memberName));
        }
        if (descriptor != null)
        {
            descriptor += '\n';
            StringBuilder primitiveGroup = new StringBuilder();
            if (!descriptor.startsWith("("))
            {
                // Field descriptor
                writer.print(":");
            }

            for (int i = 0; i < descriptor.length(); i++)
            {
                char c = descriptor.charAt(i);
                boolean array = false;
                if (c == '[')
                {
                    array = true;
                    c = descriptor.charAt(i + 1);
                }

                if ((c == 'L' || c == ')' || c == '\n') && !primitiveGroup.isEmpty())
                {
                    Html.span(writer, ATTR_CLASS_PRIMITIVE, primitiveGroup.toString());
                    primitiveGroup = new StringBuilder();
                }

                if (c == 'L')
                {
                    String type = descriptor.substring(i);
                    int typeEnd = type.indexOf(';');
                    type = type.substring(0, typeEnd + 1);
                    Html.span(writer, ATTR_CLASS_TYPE, type);

                    i += typeEnd;
                }
                else if (c == '(' || c == ')')
                {
                    writer.print(String.valueOf(c));
                }
                else if (c != '\n')
                {
                    if (array)
                    {
                        primitiveGroup.append('[');
                    }
                    primitiveGroup.append(c);
                }
            }
        }
        writer.print("\n");
        writer.enableNewLine();
    }

    public static Descriptor splitMethodDescriptor(String method, String altDesc)
    {
        String clazz = null;
        String desc = altDesc;
        int classEnd = method.indexOf('.');
        if (classEnd >= 0)
        {
            clazz = method.substring(0, classEnd);
            method = method.substring(classEnd + 1);
        }
        int descStart = method.indexOf('(');
        if (descStart >= 0)
        {
            desc = method.substring(descStart);
            method = method.substring(0, descStart);
        }
        classEnd = method.indexOf(';');
        //noinspection ConstantValue
        if (clazz == null && classEnd >= 0 && (descStart < 0 || classEnd < descStart))
        {
            clazz = method.substring(0, classEnd);
            method = method.substring(classEnd + 1);
        }
        descStart = method.indexOf(':');
        if ((desc == null || desc.equals(altDesc)) && descStart > 0)
        {
            desc = method.substring(descStart + 1);
            method = method.substring(0, descStart);
        }
        return new Descriptor(clazz, method, desc);
    }

    public static void openFileInDefaultSoftware(String fileName)
    {
        OS os = OS.get();
        if (os == null) { return; }

        try
        {
            String fileUrl = new File(fileName).toURI().toURL().toString();

            String command = switch (os)
            {
                case WINDOWS -> String.format("rundll32 url.dll,FileProtocolHandler \"%s\"", fileUrl);
                case LINUX -> String.format("xdg-open %s", fileUrl);
                case MAC -> String.format("open %s", fileUrl);
            };

            Runtime.getRuntime().exec(command);
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to open file in default app");
            e.printStackTrace();
        }
    }

    public enum OS
    {
        WINDOWS,
        LINUX,
        MAC;

        public static OS get()
        {
            String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);

            if (osName.contains("win")) { return WINDOWS; }
            if (osName.contains("linux") || osName.contains("unix")) { return LINUX; }
            if (osName.contains("mac")) { return MAC; }

            Main.LOG.error("Unknown operating system: %s", osName);
            return null;
        }
    }
}
