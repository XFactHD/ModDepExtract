package xfacthd.depextract.extractor;

import com.google.gson.*;
import joptsimple.*;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import xfacthd.depextract.Main;
import xfacthd.depextract.data.coremod.CoremodConfig;
import xfacthd.depextract.html.Css;
import xfacthd.depextract.html.Html;
import xfacthd.depextract.util.*;

import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class CoremodExtractor extends DataExtractor
{
    private static final String COREMOD_RESULT_FILE_NAME = "coremods.html";
    private static final Gson GSON = new Gson();

    private final Map<String, CoremodConfig> coremodEntries = new HashMap<>();
    private OptionSpec<Boolean> extractCoremodsOpt = null;
    private boolean active = false;

    @Override
    public void registerOptions(OptionParser parser)
    {
        extractCoremodsOpt = parser.accepts("extract_coremods", "Extract JS coremods from mods")
                .withOptionalArg()
                .ofType(Boolean.class);
    }

    @Override
    public void readOptions(OptionSet options)
    {
        active = options.has(extractCoremodsOpt) && options.valueOf(extractCoremodsOpt);
    }

    @Override
    public boolean isActive() { return active; }

    @Override
    public void acceptFile(String fileName, JarFile modJar)
    {
        JarEntry cmEntry = modJar.getJarEntry("META-INF/coremods.json");
        if (cmEntry == null) { return; }

        InputStream cmStream = getInputStreamForEntry(modJar, cmEntry, fileName);
        if (cmStream == null) { return; }

        JsonElement cmElem = GSON.fromJson(new InputStreamReader(cmStream), JsonObject.class);
        cleanupJarEntryInputStream(cmStream, cmEntry, fileName);
        if (!cmElem.isJsonObject())
        {
            Main.LOG.error("Encountered invalid coremods config in mod JAR '%s'", fileName);
            return;
        }

        CoremodConfig config = CoremodConfig.fromJson(cmElem.getAsJsonObject());
        if (!config.coremods().isEmpty())
        {
            coremodEntries.put(fileName, config);
        }
    }

    @Override
    public void postProcessData() { }

    @Override
    public void printResults(boolean darkMode, int modCount)
    {
        Main.LOG.info("Building Coremod display...");

        PrintWriter writer = Utils.makePrintWriter(COREMOD_RESULT_FILE_NAME);
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
                    Html.element(head, "title", "", "Coremod Dump");
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
                    Html.element(body, "h1", "", "Coremod Dump");

                    long coremodCount = coremodEntries.values()
                            .stream()
                            .mapToLong(cfg -> cfg.coremods().size())
                            .sum();

                    body.println(String.format("Found %d Coremods in %d out of %d mods.<br>", coremodCount, coremodEntries.size(), modCount));

                    String tableAttrib = "class=\"mod_table\"";
                    MutableObject<String> lastCoremodOwner = new MutableObject<>("");
                    Html.table(
                            body,
                            tableAttrib,
                            thead ->
                            {
                                Html.element(thead, "tr", tableAttrib, row ->
                                {
                                    Html.tableHeader(row, tableAttrib + " rowspan=\"2\"", "Mod file (Coremod count)");
                                    Html.tableHeader(row, tableAttrib + " colspan=\"2\"", "Coremods");
                                });

                                Html.element(thead, "tr", tableAttrib, row ->
                                {
                                    Html.tableHeader(row, tableAttrib, "Name");
                                    Html.tableHeader(row, tableAttrib, "Path");
                                });
                            },
                            tbody -> coremodEntries.keySet().stream().sorted(String::compareToIgnoreCase).forEachOrdered(fileName ->
                            {
                                Map<String, Pair<String, String>> coremods = coremodEntries.get(fileName).coremods();
                                coremods.keySet().forEach(entry -> Html.tableRow(tbody, tableAttrib, row ->
                                {
                                    if (!fileName.equals(lastCoremodOwner.getValue()))
                                    {
                                        lastCoremodOwner.setValue(fileName);

                                        String rowStyle = String.format("%s rowspan=\"%d\"", tableAttrib, coremods.size());
                                        Html.tableCell(
                                                row,
                                                rowStyle,
                                                String.format("%s (%d)", fileName, coremods.size())
                                        );
                                    }

                                    Pair<String, String> coremod = coremods.get(entry);
                                    Html.tableCell(row, tableAttrib, coremod.getLeft());
                                    Html.tableCell(row, tableAttrib, coremod.getRight());
                                }));
                            }));
                }
        );

        writer.close();

        Main.LOG.info("Coremod display built");
    }
}
