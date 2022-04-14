package xfacthd.depextract.extractor;

import com.google.gson.*;
import joptsimple.*;
import org.apache.commons.lang3.mutable.MutableObject;
import xfacthd.depextract.Main;
import xfacthd.depextract.html.*;
import xfacthd.depextract.util.*;

import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.IntStream;

public class MixinExtractor extends DataExtractor
{
    private static final String MIXIN_RESULT_FILE_NAME = "mixins.html";
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
            if (!mixinElem.isJsonObject())
            {
                Main.LOG.error("Encountered invalid Mixin config '%s' in mod JAR '%s'", configName, fileName);
                continue;
            }

            MixinConfig config = MixinConfig.fromJson(configName, mixinElem.getAsJsonObject());
            if (config.mixinCount() > 0)
            {
                mixinEntries.computeIfAbsent(fileName, $ -> new ArrayList<>()).add(config);
            }
        }
    }

    @Override
    public void postProcessData() { }

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
                                    Html.tableHeader(row, tableAttrib + " colSpan=\"7\"", "Config");
                                });

                                Html.element(thead, "tr", tableAttrib, row ->
                                {
                                    Html.tableHeader(row, tableAttrib, "Name");
                                    Html.tableHeader(row, tableAttrib, "Min Version");
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
                                            Html.tableCell(row, cellStyle, config.minVersion());
                                            Html.tableCell(row, cellStyle, config.compatLevel());
                                            Html.tableCell(row, cellStyle, config.plugin());
                                        }

                                        printMixinEntry(row, config.mixins(), i, maxMixinCount, tableAttrib);
                                        printMixinEntry(row, config.clientMixins(), i, maxMixinCount, tableAttrib);
                                        printMixinEntry(row, config.serverMixins(), i, maxMixinCount, tableAttrib);
                                    }));
                                });
                            })
                    );
                }
        );

        writer.close();

        Main.LOG.info("Mixin display built");
    }

    private static void printMixinEntry(HtmlWriter row, List<String> mixins, int i, int maxMixinCount, String cellStyle)
    {
        if (i < mixins.size())
        {
            Html.tableCell(row, cellStyle, mixins.get(i));
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
