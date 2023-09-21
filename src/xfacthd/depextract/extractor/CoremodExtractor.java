package xfacthd.depextract.extractor;

import com.google.gson.*;
import joptsimple.*;
import org.apache.commons.lang3.mutable.MutableObject;
import xfacthd.depextract.Main;
import xfacthd.depextract.data.FileEntry;
import xfacthd.depextract.data.coremod.CoremodConfig;
import xfacthd.depextract.html.Css;
import xfacthd.depextract.html.Html;
import xfacthd.depextract.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

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
                .withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(false);
    }

    @Override
    public void readOptions(OptionSet options)
    {
        active = options.valueOf(extractCoremodsOpt);
    }

    @Override
    public boolean isActive() { return active; }

    @Override
    public String name() { return "Coremods"; }

    @Override
    public void acceptFile(String fileName, FileSystem modJar, boolean jij, FileEntry modInfo) throws IOException
    {
        Path cmEntry = modJar.getPath("META-INF/coremods.json");
        if (!Files.exists(cmEntry)) { return; }

        InputStream cmStream = Files.newInputStream(cmEntry);
        JsonElement cmElem = GSON.fromJson(new InputStreamReader(cmStream), JsonObject.class);
        cmStream.close();

        if (!cmElem.isJsonObject())
        {
            Main.LOG.error("Encountered invalid coremods config in mod JAR '%s'", fileName);
            return;
        }

        CoremodConfig config = CoremodConfig.fromJson(cmElem.getAsJsonObject());
        if (!config.coremods().isEmpty())
        {
            config.coremods().forEach((name, path) ->
            {
                Path jsEntry = modJar.getPath(path);
                config.jsPresent().put(name, Files.exists(jsEntry));
            });

            coremodEntries.put(fileName, config);
        }
    }

    @Override
    public void postProcessData() { }

    @Override
    public void printResults(boolean darkMode, boolean minify, int modCount)
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
                minify,
                darkMode ? "style=\"background-color: #0d1117; color: #f0f6fc;\"" : "",
                head ->
                {
                    Html.element(head, "title", "", "Coremod Dump");
                    Html.style(head, style ->
                    {
                        Css.declareSelector(style, ".mod_table", clazz ->
                        {
                            Css.property(clazz, "border", String.format("1px solid %s", darkMode ? "#c9d1d9" : "black"));
                            Css.property(clazz, "border-collapse", "collapse");
                            Css.property(clazz, "padding", "4px");
                            Css.property(clazz, "vertical-align", "top");
                        });
                        Css.declareStickyHeader(style, darkMode);
                    });
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
                                    Html.tableHeader(row, tableAttrib + " colspan=\"3\"", "Coremods");
                                });

                                Html.element(thead, "tr", tableAttrib, row ->
                                {
                                    Html.tableHeader(row, tableAttrib, "Name");
                                    Html.tableHeader(row, tableAttrib, "Path");
                                    Html.tableHeader(row, tableAttrib, "JS present");
                                });
                            },
                            tbody -> coremodEntries.keySet().stream().sorted(String::compareToIgnoreCase).forEachOrdered(fileName ->
                            {
                                CoremodConfig cfg = coremodEntries.get(fileName);
                                Map<String, String> coremods = cfg.coremods();
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

                                    Html.tableCell(row, tableAttrib, entry);
                                    Html.tableCell(row, tableAttrib, coremods.get(entry));
                                    boolean present = cfg.jsPresent().get(entry);
                                    Html.tableCell(row, tableAttrib, cell -> Html.writeBoolean(cell, "", present));
                                }));
                            }));
                }
        );

        writer.close();

        Main.LOG.info("Coremod display built");
    }
}
