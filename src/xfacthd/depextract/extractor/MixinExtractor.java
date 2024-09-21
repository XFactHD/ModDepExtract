package xfacthd.depextract.extractor;

import com.google.gson.*;
import joptsimple.*;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import xfacthd.depextract.Main;
import xfacthd.depextract.data.FileEntry;
import xfacthd.depextract.data.mixin.*;
import xfacthd.depextract.html.*;
import xfacthd.depextract.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.*;

public class MixinExtractor extends DataExtractor
{
    private static final String MIXIN_RESULT_FILE_NAME = "mixins.html";
    private static final MixinTarget[] EMPTY_ARRAY = new MixinTarget[0];
    private static final MixinInjection[] EMPTY_INJ_ARRAY = new MixinInjection[0];
    private static final Gson GSON = new Gson();
    private static final String CHART_JS_SRC = "https://cdn.jsdelivr.net/npm/chart.js@4.3.2/auto/+esm";

    private final Map<String, List<MixinConfig>> mixinEntries = new HashMap<>();
    private final List<Pair<String, List<String>>> mixinsPerTarget = new ArrayList<>();
    private OptionSpec<Boolean> extractMixinsOpt = null;
    private OptionSpec<Boolean> filterAccessorsOpt = null;
    private OptionSpec<Boolean> createGraphOpt = null;
    private boolean active = false;
    private boolean filterAccessors = false;
    private boolean createGraph = false;

    @Override
    public void registerOptions(OptionParser parser)
    {
        extractMixinsOpt = parser.accepts("extract_mixins", "Extract Mixin configs from mods")
                .withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(false);
        filterAccessorsOpt = parser.accepts("filter_accessors", "Remove accessor and invoker Mixins from the list")
                .availableIf(extractMixinsOpt)
                .withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(false);
        createGraphOpt = parser.accepts("create_graph", "Create a graph showing the amount of Mixins per target class")
                .availableIf(extractMixinsOpt)
                .withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(false);
    }

    @Override
    public void readOptions(OptionSet options)
    {
        active = options.valueOf(extractMixinsOpt);
        filterAccessors = options.valueOf(filterAccessorsOpt);
        createGraph = options.valueOf(createGraphOpt);
    }

    @Override
    public boolean isActive() { return active; }

    @Override
    public String name() { return "Mixins"; }

    @Override
    public void acceptFile(String fileName, FileSystem modJar, boolean jij, FileEntry modInfo) throws IOException
    {
        Manifest manifest = findManifest(modJar, fileName);
        if (manifest == null) { return; }

        String mixinCfgName = manifest.getMainAttributes().getValue("MixinConfigs");
        if (mixinCfgName == null || mixinCfgName.isEmpty()) { return; }

        List<String> mixinConfigs = Arrays.stream(mixinCfgName.split(",")).map(String::trim).toList();

        for (String configName : mixinConfigs)
        {
            Path configEntry = modJar.getPath(configName);
            if (!Files.exists(configEntry))
            {
                Main.LOG.error("Encountered non-existent Mixin config '%s' in mod JAR '%s'", configName, fileName);
                continue;
            }

            InputStream mixinStream = Files.newInputStream(configEntry);
            JsonElement mixinElem = GSON.fromJson(new InputStreamReader(mixinStream), JsonObject.class);
            mixinStream.close();

            if (!mixinElem.isJsonObject())
            {
                Main.LOG.error("Encountered invalid Mixin config '%s' in mod JAR '%s'", configName, fileName);
                continue;
            }

            MixinConfig config = MixinConfig.fromJson(fileName, configName, modJar, mixinElem.getAsJsonObject());
            if (config.mixinCount() > 0)
            {
                mixinEntries.computeIfAbsent(fileName, $ -> new ArrayList<>()).add(config);
            }
        }
    }

    @Override
    public void postProcessData()
    {
        Main.LOG.info("Collecting Mixin targets...");

        Map<String, List<String>> mixinsPerTargetMap = new HashMap<>();
        mixinEntries.values().stream().flatMap(List::stream).forEach(config ->
        {
            config.mixins().forEach(entry ->
            {
                Pair<MixinTarget[], MixinInjection[]> targets = analyseMixinClass(entry);
                config.resolvedMixins().add(new Mixin(entry.name(), targets.getLeft(), targets.getRight()));
            });

            config.clientMixins().forEach(entry ->
            {
                Pair<MixinTarget[], MixinInjection[]> targets = analyseMixinClass(entry);
                config.resolvedClientMixins().add(new Mixin(entry.name(), targets.getLeft(), targets.getRight()));
            });

            config.serverMixins().forEach(entry ->
            {
                Pair<MixinTarget[], MixinInjection[]> targets = analyseMixinClass(entry);
                config.resolvedServerMixins().add(new Mixin(entry.name(), targets.getLeft(), targets.getRight()));
            });

            if (filterAccessors)
            {
                config.filterAccessors();
            }

            if (createGraph)
            {
                Stream.of(config.resolvedMixins(), config.resolvedClientMixins(), config.resolvedServerMixins())
                        .flatMap(List::stream)
                        .forEach(mixin ->
                        {
                            for (MixinTarget target : mixin.targets())
                            {
                                mixinsPerTargetMap.computeIfAbsent(
                                        target.qualifiedName(),
                                        $ -> new ArrayList<>()
                                ).add(mixin.name());
                            }
                        });
            }
        });

        if (createGraph)
        {
            mixinsPerTarget.addAll(mixinsPerTargetMap.entrySet()
                    .stream()
                    .map(e -> Pair.of(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparingInt(e -> -e.getValue().size()))
                    .toList()
            );
        }

        Main.LOG.info("Mixin targets collected");
    }

    private Pair<MixinTarget[], MixinInjection[]> analyseMixinClass(MixinEntry entry)
    {
        ClassReader reader = new ClassReader(entry.classFile());
        ClassNode clazz = new ClassNode(Opcodes.ASM9);
        reader.accept(clazz, 0);

        AnnotationNode mixinNode = null;
        for (AnnotationNode anno : clazz.invisibleAnnotations)
        {
            if (anno.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
            {
                mixinNode = anno;
                break;
            }
        }

        MixinTarget[] targets = resolveMixinTargets(entry.name(), mixinNode);
        MixinInjection[] injections = resolveMixinInjections(clazz.methods);
        return Pair.of(targets, injections);
    }

    private MixinTarget[] resolveMixinTargets(String name, AnnotationNode anno)
    {
        if (anno == null)
        {
            Main.LOG.error("Found invalid Mixin entry '" + name + "', missing @Mixin annotation");
            return EMPTY_ARRAY;
        }

        List<MixinTarget> targets = new ArrayList<>();

        List<Object> values = anno.values;
        List<Type> annoValues = List.of();
        List<String> annoTargets = List.of();
        for (int i = 0; i < values.size(); i += 2)
        {
            String key = (String) values.get(i);
            if (key.equals("value"))
            {
                //noinspection unchecked
                annoValues = (List<Type>) values.get(i + 1);
            }
            else if (key.equals("targets"))
            {
                //noinspection unchecked
                annoTargets = (List<String>) values.get(i + 1);
            }
        }

        for (Type type : annoValues)
        {
            String target = type.getClassName();
            targets.add(new MixinTarget(Utils.removePackage(target), target));
        }
        for (String target : annoTargets)
        {
            targets.add(new MixinTarget(Utils.removePackage(target), target));
        }

        return targets.toArray(MixinTarget[]::new);
    }

    private MixinInjection[] resolveMixinInjections(List<MethodNode> methods)
    {
        if (methods == null || methods.isEmpty())
        {
            return EMPTY_INJ_ARRAY;
        }

        List<MixinInjection> injections = new ArrayList<>();

        for (MethodNode mth : methods)
        {
            List<AnnotationNode> annos = mth.visibleAnnotations;
            if (annos == null || annos.isEmpty())
            {
                continue;
            }

            annos.forEach(anno -> resolveInjection(mth, anno, injections));
        }

        return injections.toArray(MixinInjection[]::new);
    }

    private void resolveInjection(MethodNode mth, AnnotationNode anno, List<MixinInjection> injections)
    {
        if (anno == null)
        {
            return;
        }

        MixinInjection inj = switch (anno.desc)
        {
            case "Lorg/spongepowered/asm/mixin/gen/Accessor;" ->
            {
                MixinTargetDescriptor target = MixinInjectionType.ACCESSOR.parseAnnotationData(mth, anno);
                yield new MixinInjection(MixinInjectionType.ACCESSOR, mth, target);
            }
            case "Lorg/spongepowered/asm/mixin/gen/Invoker;" ->
            {
                MixinTargetDescriptor target = MixinInjectionType.INVOKER.parseAnnotationData(mth, anno);
                yield new MixinInjection(MixinInjectionType.INVOKER, mth, target);
            }
            case "Lorg/spongepowered/asm/mixin/injection/Inject;" ->
            {
                MixinTargetDescriptor target = MixinInjectionType.INJECT.parseAnnotationData(mth, anno);
                yield new MixinInjection(MixinInjectionType.INJECT, mth, target);
            }
            case "Lorg/spongepowered/asm/mixin/injection/Redirect;" ->
            {
                MixinTargetDescriptor target = MixinInjectionType.REDIRECT.parseAnnotationData(mth, anno);
                yield new MixinInjection(MixinInjectionType.REDIRECT, mth, target);
            }
            case "Lorg/spongepowered/asm/mixin/injection/ModifyArg;" ->
            {
                MixinTargetDescriptor target = MixinInjectionType.MODIFY_ARG.parseAnnotationData(mth, anno);
                yield new MixinInjection(MixinInjectionType.MODIFY_ARG, mth, target);
            }
            case "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;" ->
            {
                MixinTargetDescriptor target = MixinInjectionType.MODIFY_ARGS.parseAnnotationData(mth, anno);
                yield new MixinInjection(MixinInjectionType.MODIFY_ARGS, mth, target);
            }
            case "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;" ->
            {
                MixinTargetDescriptor target = MixinInjectionType.MODIFY_CONSTANT.parseAnnotationData(mth, anno);
                yield new MixinInjection(MixinInjectionType.MODIFY_CONSTANT, mth, target);
            }
            case "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;" ->
            {
                MixinTargetDescriptor target = MixinInjectionType.MODIFY_VARIABLE.parseAnnotationData(mth, anno);
                yield new MixinInjection(MixinInjectionType.MODIFY_VARIABLE, mth, target);
            }
            case "Lorg/spongepowered/asm/mixin/Overwrite;" ->
            {
                MixinTargetDescriptor target = MixinInjectionType.OVERWRITE.parseAnnotationData(mth, anno);
                yield new MixinInjection(MixinInjectionType.OVERWRITE, mth, target);
            }
            default -> null;
        };

        if (inj != null)
        {
            injections.add(inj);
        }
    }

    @Override
    public void printResults(boolean darkMode, boolean minify, int modCount)
    {
        Main.LOG.info("Building Mixin display...");

        PrintWriter writer = Utils.makePrintWriter(MIXIN_RESULT_FILE_NAME);
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
                    Html.element(head, "title", "", "Mixin Dump");
                    Html.style(head, style ->
                    {
                        Css.declareSelector(style, ".mod_table", clazz ->
                        {
                            Css.property(clazz, "border", String.format("1px solid %s", darkMode ? "#c9d1d9" : "black"));
                            Css.property(clazz, "border-collapse", "collapse");
                            Css.property(clazz, "padding", "4px");
                            Css.property(clazz, "vertical-align", "top");
                        });
                        Css.declareSelector(style, ".tooltip", clazz ->
                        {
                            Css.property(clazz, "position", "relative");
                            Css.property(clazz, "display", "inline-block");
                            Css.property(clazz, "cursor", "pointer");
                            Css.property(clazz, "width", "1.2em");
                            Css.property(clazz, "height", "1.2em");
                            Css.property(clazz, "text-align", "center");
                            Css.property(clazz, "background-color", darkMode ? "#253041" : "#ddd");
                            Css.property(clazz, "border", String.format("1px solid %s", darkMode ? "#c9d1d9" : "black"));
                            Css.property(clazz, "border-radius", "3px");
                        });
                        Css.declareSelector(style, ".tooltip_content", clazz ->
                        {
                            Css.property(clazz, "display", "none");
                            Css.property(clazz, "width", "500px");
                            Css.property(clazz, "background-color", "#333");
                            Css.property(clazz, "color", "#fff");
                            Css.property(clazz, "border-radius", "6px");
                            Css.property(clazz, "padding", "8px");
                            Css.property(clazz, "position", "absolute");
                            Css.property(clazz, "z-index", "1");
                            Css.property(clazz, "top", "100%");
                            Css.property(clazz, "left", "50%");
                            Css.property(clazz, "transform", "translate(-50%, 10px)");
                            Css.property(clazz, "text-align", "left");
                            Css.property(clazz, "cursor", "auto");
                        });
                        Css.declareSelector(style, ".tooltip_content::before", clazz ->
                        {
                            Css.property(clazz, "content", "''");
                            Css.property(clazz, "position", "fixed");
                            Css.property(clazz, "bottom", "100%");
                            Css.property(clazz, "left", "48.15%");
                            Css.property(clazz, "border-width", "10px");
                            Css.property(clazz, "border-style", "solid");
                            Css.property(clazz, "border-color", "transparent transparent #333 transparent");
                        });
                        Css.declareSelector(style, ".show", clazz ->
                            Css.property(clazz, "display", "inline")
                        );
                        Css.declareSelector(style, ".no_y_margin", clazz ->
                        {
                            Css.property(clazz, "margin-top", "0");
                            Css.property(clazz, "margin-bottom", "0");
                        });
                        Css.declareStickyHeader(style, darkMode);
                        Utils.declareDescriptorSelectors(style);
                    });

                    if (createGraph)
                    {
                        Html.element(head, "link", "rel=\"modulepreload\" href=\"%s\"".formatted(CHART_JS_SRC), contentWriter -> {
                            contentWriter.disableNewLine();
                            contentWriter.print("");
                            contentWriter.enableNewLine();
                        }, false);

                        // used by chart.js
                        Html.element(head, "link", "rel=\"modulepreload\" href=\"https://cdn.jsdelivr.net/npm/@kurkle/color@0.3.2/+esm\"", contentWriter -> {
                            contentWriter.disableNewLine();
                            contentWriter.print("");
                            contentWriter.enableNewLine();
                        }, false);
                    }
                },
                body ->
                {
                    Html.element(body, "h1", "", "Mixin Dump");

                    List<Mixin> allMixins = mixinEntries.values()
                            .stream()
                            .flatMap(List::stream)
                            .map(MixinConfig::allMixins)
                            .flatMap(List::stream)
                            .toList();

                    long configCount = mixinEntries.values()
                            .stream()
                            .mapToLong(List::size)
                            .sum();

                    long mixinCount = mixinEntries.values()
                            .stream()
                            .flatMap(List::stream)
                            .mapToLong(MixinConfig::mixinCount)
                            .sum();

                    long accessorCount = allMixins
                            .stream()
                            .filter(Mixin::isAccessor)
                            .count();

                    long nonMcMixins = allMixins.stream()
                            .map(Mixin::targets)
                            .flatMap(Arrays::stream)
                            .map(MixinTarget::qualifiedName)
                            .filter(name -> !name.startsWith("net.minecraft.") && !name.startsWith("com.mojang"))
                            .count();

                    body.println(String.format("Found %d Mixins in %d Mixin configs in %d out of %d mods.", mixinCount, configCount, mixinEntries.size(), modCount));
                    body.println(String.format("%d out of %d Mixins are pure Accessors/Invokers.", accessorCount, mixinCount));
                    body.println(String.format("%d out of %d Mixins target non-Minecraft classes.", nonMcMixins, mixinCount));
                    body.println("");

                    Html.element(body, "h3", "", "Count by injection type");
                    int totalInjectionCount = allMixins.stream()
                            .map(Mixin::injections)
                            .mapToInt(arr -> arr.length)
                            .sum();
                    body.println("Total injections: " + totalInjectionCount);
                    Html.element(body, "ul", "", list ->
                    {
                        for (MixinInjectionType type : MixinInjectionType.values())
                        {
                            long injectionCount = allMixins.stream()
                                    .map(Mixin::injections)
                                    .flatMap(Arrays::stream)
                                    .filter(m -> m.type() == type)
                                    .count();
                            Html.element(list, "li", "", String.format("%s: %d", type.toString(), injectionCount));
                        }
                    });
                    body.println("");

                    Html.element(body, "h3", "", "Details");
                    if (filterAccessors)
                    {
                        body.println("Accessor and Invoker Mixins are filtered out of the list");
                        body.println("");
                    }

                    String tableAttrib = "class=\"mod_table\"";
                    MutableObject<String> lastMixinOwner = new MutableObject<>("");
                    Html.table(
                            body,
                            tableAttrib,
                            thead ->
                            {
                                Html.element(thead, "tr", tableAttrib, row ->
                                {
                                    Html.tableHeader(row, tableAttrib + " rowSpan=\"2\"", "Mod file (Configs) [Mixins]");
                                    Html.tableHeader(row, tableAttrib + " colSpan=\"6\"", "Config");
                                });

                                Html.element(thead, "tr", tableAttrib, row ->
                                {
                                    Html.tableHeader(row, tableAttrib, "Name");
                                    Html.tableHeader(row, tableAttrib, "Compat Level");
                                    Html.tableHeader(row, tableAttrib, "Plugin");
                                    Html.tableHeader(row, tableAttrib, "Mixins");
                                    Html.tableHeader(row, tableAttrib, "Client Mixins");
                                    Html.tableHeader(row, tableAttrib, "Server Mixins");
                                });
                            },
                            tbody -> mixinEntries.keySet().stream().sorted(String::compareToIgnoreCase).forEachOrdered(fileName ->
                            {
                                List<MixinConfig> configs = mixinEntries.get(fileName);
                                long modMaxMixinCount = configs.stream().mapToInt(this::getMaxMixinCount).sum();
                                configs.forEach(config ->
                                {
                                    int maxMixinCount = getMaxMixinCount(config);

                                    IntStream.range(0, maxMixinCount).forEach(i -> Html.tableRow(tbody, tableAttrib, row ->
                                    {
                                        if (!fileName.equals(lastMixinOwner.getValue()))
                                        {
                                            lastMixinOwner.setValue(fileName);

                                            long modMixinCount = configs.stream().mapToLong(MixinConfig::mixinCount).sum();
                                            String cellStyle = String.format("%s rowspan=\"%d\"", tableAttrib, modMaxMixinCount);
                                            Html.tableCell(
                                                    row,
                                                    cellStyle,
                                                    String.format("%s (%d) [%d]", fileName, configs.size(), modMixinCount)
                                            );
                                        }

                                        if (i == 0)
                                        {
                                            String cellStyle = String.format("%s rowspan=\"%d\"", tableAttrib, maxMixinCount);
                                            Html.tableCell(row, cellStyle, config.name());
                                            Html.tableCell(row, cellStyle, config.compatLevel());
                                            Html.tableCell(row, cellStyle, config.plugin());
                                        }

                                        printMixinEntry(row, config.resolvedMixins(filterAccessors), i, maxMixinCount, tableAttrib);
                                        printMixinEntry(row, config.resolvedClientMixins(filterAccessors), i, maxMixinCount, tableAttrib);
                                        printMixinEntry(row, config.resolvedServerMixins(filterAccessors), i, maxMixinCount, tableAttrib);
                                    }));
                                });
                            })
                    );

                    if (createGraph)
                    {
                        body.println("");

                        Html.element(body, "h3", "", "Mixins per target");
                        Html.element(body, "canvas", "id=\"graph\"", "");
                    }

                    Html.element(body, "script", "type=\"module\" async", script ->
                    {
                        if (createGraph)
                            script.print("import { Chart } from \"%s\"\n".formatted(CHART_JS_SRC));

                        script.printMultiLine("""
                                function closeAllTooltips() {
                                    const tooltips = document.getElementsByClassName("tooltip_content");
                                    for (const item of tooltips) {
                                        item.classList.remove("show")
                                    }
                                }
                                
                                function toggleTooltip(event, tooltip) {
                                    if (!tooltip.classList.contains("show"))
                                        closeAllTooltips()
                                
                                    tooltip.classList.toggle("show")
                                    
                                    event.stopPropagation()
                                }
                                
                                const buttons = document.getElementsByClassName("tooltip")
                                for (const item of buttons) {
                                    const tooltip = item.querySelector(".tooltip_content")
                                    item.addEventListener("click", event => toggleTooltip(event, tooltip))
                                    tooltip.addEventListener("click", event => {
                                        if (tooltip.style.display !== "none")
                                            event.stopPropagation()
                                    })
                                }
                                document.body.addEventListener("click", () => closeAllTooltips())
                                """
                        );

                        if (createGraph)
                        {
                            script.print(""); // empty line

                            String labels = mixinsPerTarget.stream()
                                    .filter(e -> e.getValue().size() > 1)
                                    .map(Pair::getKey)
                                    .map(target -> "'" + target + "'")
                                    .collect(Collectors.joining(", "));
                            String values = mixinsPerTarget.stream()
                                    .filter(e -> e.getValue().size() > 1)
                                    .map(Pair::getValue)
                                    .map(mixins -> Integer.toString(mixins.size()))
                                    .collect(Collectors.joining(", "));
                            script.printMultiLine("""
                                    const graph = new Chart("graph", {
                                        type: "bar",
                                        data: {
                                            labels: [%s],
                                            datasets: [{
                                                label: "Mixins per target",
                                                data: [%s]
                                            }]
                                        },
                                        options: {
                                            scales: {
                                                y: { beginAtZero: true }
                                            }
                                        }
                                    })
                                    """.formatted(labels, values)
                            );
                        }
                    });
                }
        );

        writer.close();

        Main.LOG.info("Mixin display built");
    }

    private static int popupIdx = 0;

    private static void printMixinEntry(HtmlWriter row, List<Mixin> mixins, int i, int maxMixinCount, String cellStyle)
    {
        if (i < mixins.size())
        {
            Mixin mixin = mixins.get(i);
            Html.tableCell(row, cellStyle, cell ->
            {
                cell.print(mixin.name());
                Html.div(cell, "class=\"tooltip\"", div ->
                {
                    div.print("(i)");
                    Html.span(div, "class=\"tooltip_content\" id=\"tooltip_" + popupIdx + "\"", span ->
                    {
                        MixinInjection[] injections = mixin.injections();
                        if (injections.length == 0)
                        {
                            span.print("No details available");
                            return;
                        }

                        for (int j = 0; j < injections.length; j++)
                        {
                            MixinInjection inj = injections[j];

                            span.print(inj.type().toString() + ": ");
                            Utils.printDescriptor(span, null, null, inj.methodName(), inj.methodDesc());

                            inj.type().printTarget(span, inj.target());
                            if (j < injections.length - 1)
                            {
                                span.println("");
                            }
                        }
                    });
                });
                popupIdx++;

                int targetCount = mixin.targets().length;
                if (targetCount > 0)
                {
                    cell.println("");

                    cell.disableNewLine();
                    cell.printIndent();
                    cell.print("[");
                    for (int idx = 0; idx < targetCount; idx++)
                    {
                        MixinTarget target = mixin.targets()[idx];
                        Html.abbreviation(cell, target.qualifiedName(), target.className());

                        if (idx < mixin.targets().length - 1)
                        {
                            cell.print(", ");
                        }
                    }
                    cell.print("]");
                    cell.print("\n");
                    cell.enableNewLine();
                }
            });
        }
        else if (i == mixins.size())
        {
            cellStyle = String.format("%s rowspan=\"%d\"", cellStyle, maxMixinCount - mixins.size());
            Html.tableCell(row, cellStyle, "");
        }
    }

    private int getMaxMixinCount(MixinConfig config)
    {
        if (filterAccessors)
        {
            return (int) Math.max(
                    config.resolvedMixins().stream().filter(mixin -> !mixin.isAccessor()).count(),
                    Math.max(
                            config.resolvedClientMixins().stream().filter(mixin -> !mixin.isAccessor()).count(),
                            config.resolvedServerMixins().stream().filter(mixin -> !mixin.isAccessor()).count()
                    )
            );
        }
        return Math.max(config.mixins().size(), Math.max(config.clientMixins().size(), config.serverMixins().size()));
    }
}
