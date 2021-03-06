package xfacthd.depextract.extractor;

import com.google.gson.*;
import joptsimple.*;
import org.apache.commons.lang3.mutable.MutableObject;
import xfacthd.depextract.Main;
import xfacthd.depextract.data.mixin.*;
import xfacthd.depextract.html.*;
import xfacthd.depextract.util.*;

import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.*;

public class MixinExtractor extends DataExtractor
{
    private static final String MIXIN_DECOMP_FILE_NAME = "mixins.jar";
    private static final String MIXIN_RESULT_FILE_NAME = "mixins.html";
    private static final Pattern SIMPLE_PATTERN = Pattern.compile("@Mixin\\((.+)\\)");
    private static final Pattern COMPLEX_PATTERN = Pattern.compile("@Mixin\\(\\R(([ a-zA-Z_]+ = \\{?[a-zA-Z0-9./, $\"-]+}?,?\\R)+)\\)");
    private static final MixinTarget[] EMPTY_ARRAY = new MixinTarget[0];
    private static final Gson GSON = new Gson();

    private final Map<String, List<MixinConfig>> mixinEntries = new HashMap<>();
    private OptionSpec<Boolean> extractMixinsOpt = null;
    private boolean active = false;

    @Override
    public void registerOptions(OptionParser parser)
    {
        extractMixinsOpt = parser.accepts("extract_mixins", "Extract Mixin configs from mods")
                .withOptionalArg()
                .ofType(Boolean.class);
    }

    @Override
    public void readOptions(OptionSet options)
    {
        active = options.has(extractMixinsOpt) && options.valueOf(extractMixinsOpt);
    }

    @Override
    public boolean isActive() { return active; }

    @Override
    public void acceptFile(String fileName, JarFile modJar)
    {
        Manifest manifest = findManifest(modJar, fileName);
        if (manifest == null) { return; }

        String mixinCfgName = manifest.getMainAttributes().getValue("MixinConfigs");
        if (mixinCfgName == null || mixinCfgName.isEmpty()) { return; }

        List<String> mixinConfigs = Arrays.stream(mixinCfgName.split(",")).map(String::trim).toList();

        for (String configName : mixinConfigs)
        {
            JarEntry configEntry = modJar.getJarEntry(configName);
            if (configEntry == null)
            {
                Main.LOG.error("Encountered non-existant Mixin config '%s' in mod JAR '%s'", configName, fileName);
                continue;
            }

            InputStream mixinStream = getInputStreamForEntry(modJar, configEntry, fileName);
            if (mixinStream == null) { continue; }

            JsonElement mixinElem = GSON.fromJson(new InputStreamReader(mixinStream), JsonObject.class);
            cleanupJarEntryInputStream(mixinStream, configEntry, fileName);
            if (!mixinElem.isJsonObject())
            {
                Main.LOG.error("Encountered invalid Mixin config '%s' in mod JAR '%s'", configName, fileName);
                continue;
            }

            MixinConfig config = MixinConfig.fromJson(configName, modJar, mixinElem.getAsJsonObject());
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

        JarFile decompJar = decompileMixinClasses();

        mixinEntries.values().stream().flatMap(List::stream).forEach(config ->
        {
            config.mixins().forEach(entry ->
            {
                MixinTarget[] targets = resolveTargets(decompJar, entry.classPath());
                config.resolvedMixins().add(new Mixin(entry.name(), targets));
            });

            config.clientMixins().forEach(entry ->
            {
                MixinTarget[] targets = resolveTargets(decompJar, entry.classPath());
                config.resolvedClientMixins().add(new Mixin(entry.name(), targets));
            });

            config.serverMixins().forEach(entry ->
            {
                MixinTarget[] targets = resolveTargets(decompJar, entry.classPath());
                config.resolvedServerMixins().add(new Mixin(entry.name(), targets));
            });
        });

        Decompiler.cleanup(MIXIN_DECOMP_FILE_NAME, decompJar);

        Main.LOG.info("Mixin targets collected");
    }



    private JarFile decompileMixinClasses()
    {
        if (!Decompiler.isDecompilerPresent()) { return null; }

        Map<String, byte[]> mixinClasses = mixinEntries.values()
                .stream()
                .flatMap(List::stream)
                .flatMap(config -> Stream.concat(
                        config.mixins().stream(),
                        Stream.concat(
                                config.clientMixins().stream(),
                                config.serverMixins().stream()
                        )
                ))
                .collect(Collectors.toMap(entry -> entry.classPath().replace('.', '/'), MixinEntry::classFile));

        boolean success = Decompiler.writeInput(MIXIN_DECOMP_FILE_NAME, zipStream ->
        {
            for (Map.Entry<String, byte[]> entry : mixinClasses.entrySet())
            {
                zipStream.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                zipStream.write(entry.getValue());
            }
        });

        if (!success)
        {
            Main.LOG.error("Encountered an error while building archive of collected Mixins");
            return null;
        }

        JarFile result = Decompiler.decompile(MIXIN_DECOMP_FILE_NAME);
        if (result == null)
        {
            Main.LOG.error("Decompilation of collected Mixins failed");
        }
        return result;
    }

    private MixinTarget[] resolveTargets(JarFile decompJar, String classPath)
    {
        if (decompJar == null) { return EMPTY_ARRAY; }

        JarEntry entry = decompJar.getJarEntry(classPath.replace('.', '/') + ".java");
        if (entry == null)
        {
            Main.LOG.error("Failed to find decompiled Mixin '%s' in JAR", classPath);
            return EMPTY_ARRAY;
        }

        String mixinCode;
        try
        {
            InputStream stream = decompJar.getInputStream(entry);
            mixinCode = new String(stream.readAllBytes());
            stream.close();
        }
        catch (IOException e)
        {
            Main.LOG.error("Encountered an error while reading decompiled Mixin '%s' from JAR", classPath);
            return EMPTY_ARRAY;
        }

        String imports = mixinCode.substring(0, mixinCode.indexOf("@Mixin("));

        Matcher simpleMatcher = SIMPLE_PATTERN.matcher(mixinCode);
        if (simpleMatcher.find())
        {
            String match = simpleMatcher.group();
            Main.LOG.debug("Found target specifier '%s' in Mixin class '%s'", match, classPath);

            List<MixinTarget> targets = new ArrayList<>();

            String literalTargets = match.substring(match.indexOf('{') + 1, match.lastIndexOf('}'));
            for (String target : literalTargets.split(","))
            {
                target = target.trim().replace(".class", "");
                String fqn = findQualifiedName(classPath, imports, target);
                targets.add(new MixinTarget(target, fqn));
            }

            return targets.toArray(MixinTarget[]::new);
        }

        Matcher matcher = COMPLEX_PATTERN.matcher(mixinCode);
        if (matcher.find())
        {
            String match = matcher.group().trim();
            Main.LOG.debug("Found target specifier '%s' in Mixin class '%s'", match.replaceAll("\\R", ""), classPath);

            List<MixinTarget> targets = new ArrayList<>();

            int literalIndex = match.indexOf("value = {");
            if (literalIndex != -1)
            {
                String literalTargets = match.substring(match.indexOf('{', literalIndex) + 1, match.indexOf('}', literalIndex));
                for (String target : literalTargets.split(","))
                {
                    target = target.trim().replace(".class", "");
                    String fqn = findQualifiedName(classPath, imports, target);
                    targets.add(new MixinTarget(target, fqn));
                }
            }

            int stringIndex = match.indexOf("targets = {");
            if (stringIndex != -1)
            {
                String stringTargets = match.substring(match.indexOf('{', stringIndex) + 1, match.indexOf('}', stringIndex));
                for (String target : stringTargets.split(","))
                {
                    target = target.replace("\"", "");
                    String qualifiedName = target.trim().replace('/', '.');

                    int lastDot = target.lastIndexOf('.');
                    if (lastDot != -1)
                    {
                        target = target.substring(lastDot + 1);
                    }

                    int lastSlash = target.lastIndexOf('/');
                    if (lastSlash != -1)
                    {
                        target = target.substring(lastSlash + 1);
                    }

                    targets.add(new MixinTarget(target, qualifiedName));
                }
            }

            return targets.toArray(MixinTarget[]::new);
        }

        Main.LOG.error("Failed to find @Mixin annotation in Mixin class '%s'", classPath);
        return EMPTY_ARRAY;
    }

    private String findQualifiedName(String classPath, String imports, String target)
    {
        Pattern pattern = Pattern.compile("import [a-zA-Z0-9._]+\\." + target + ";");
        Matcher matcher = pattern.matcher(imports);
        if (matcher.find())
        {
            String match = matcher.group();
            return match.substring(match.indexOf(' ') + 1, match.indexOf(';'));
        }

        Main.LOG.error("Failed to find fully qualified name for class '%s' (targeted by Mixin class '%s')", target, classPath);
        return "";
    }

    @Override
    public void printResults(boolean darkMode, int modCount)
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
                darkMode ? "style=\"background-color: #0d1117; color: #f0f6fc;\"" : "",
                head ->
                {
                    Html.element(head, "title", "", "Mixin Dump");
                    Html.style(head, style -> Css.declareClass(style, "mod_table", clazz ->
                    {
                        Css.property(clazz, "border", String.format("1px solid %s", darkMode ? "#c9d1d9" : "black"));
                        Css.property(clazz, "border-collapse", "collapse");
                        Css.property(clazz, "padding", "4px");
                        Css.property(clazz, "vertical-align", "top");
                    }));
                },
                body ->
                {
                    Html.element(body, "h1", "", "Mixin Dump");

                    long configCount = mixinEntries.values()
                            .stream()
                            .mapToLong(List::size)
                            .sum();

                    long mixinCount = mixinEntries.values()
                            .stream()
                            .flatMap(List::stream)
                            .mapToLong(MixinConfig::mixinCount)
                            .sum();

                    body.println(String.format("Found %d Mixins in %d Mixin configs in %d out of %d mods.<br>", mixinCount, configCount, mixinEntries.size(), modCount));

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
                                long modMaxMixinCount = configs.stream().mapToInt(MixinExtractor::getMaxMixinCount).sum();
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

                                        printMixinEntry(row, config.resolvedMixins(), i, maxMixinCount, tableAttrib);
                                        printMixinEntry(row, config.resolvedClientMixins(), i, maxMixinCount, tableAttrib);
                                        printMixinEntry(row, config.resolvedServerMixins(), i, maxMixinCount, tableAttrib);
                                    }));
                                });
                            })
                    );
                }
        );

        writer.close();

        Main.LOG.info("Mixin display built");
    }

    private static void printMixinEntry(HtmlWriter row, List<Mixin> mixins, int i, int maxMixinCount, String cellStyle)
    {
        if (i < mixins.size())
        {
            Mixin mixin = mixins.get(i);
            Html.tableCell(row, cellStyle, cell ->
            {
                cell.print(mixin.name());

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

                        if (idx < mixin.targets().length -1)
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

    private static int getMaxMixinCount(MixinConfig config)
    {
        return Math.max(config.mixins().size(), Math.max(config.clientMixins().size(), config.serverMixins().size()));
    }
}
