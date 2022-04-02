package xfacthd.depextract.extractor;

import org.apache.commons.lang3.mutable.MutableObject;
import xfacthd.depextract.Main;
import xfacthd.depextract.html.Css;
import xfacthd.depextract.html.Html;
import xfacthd.depextract.util.*;

import java.io.*;
import java.util.*;
import java.util.jar.*;

public class AccessTransformerExtractor extends DataExtractor
{
    private static final String AT_RESULT_FILE_NAME = "accesstransformers.html";

    private final List<String> flaggedATs;
    private final Map<String, List<AccessTransformer>> atEntries = new HashMap<>();

    public AccessTransformerExtractor(List<String> flaggedATs)
    {
        this.flaggedATs = flaggedATs;
        if (!flaggedATs.isEmpty())
        {
            Main.LOG.info("Flagged ATs: " + flaggedATs);
        }
    }

    @Override
    public void acceptFile(String fileName, JarFile modJar)
    {
        JarEntry atEntry = modJar.getJarEntry("META-INF/accesstransformer.cfg");
        if (atEntry == null) { return; }

        InputStream atStream = getInputStreamForEntry(modJar, atEntry, fileName);
        if (atStream == null) { return; }

        BufferedReader reader = new BufferedReader(new InputStreamReader(atStream));

        List<AccessTransformer> ats = new ArrayList<>();

        try
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                {
                    continue;
                }
                ats.add(AccessTransformer.parse(line, flaggedATs));
            }
        }
        catch (IOException e)
        {
            Main.LOG.error("Encountered an error while parsing AccessTransformers for mod JAR '%s'!", fileName);
        }

        if (!ats.isEmpty())
        {
            atEntries.put(fileName, ats);
        }
    }

    @Override
    public void postProcessData() { }

    @Override
    public void printResults(boolean darkMode, int modCount)
    {
        Main.LOG.info("Building AT display...");

        PrintWriter writer = Utils.makePrintWriter(AT_RESULT_FILE_NAME);
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
                    Html.element(head, "title", "", "AccessTransformer Dump");
                    Html.style(head, style ->
                    {
                        Css.declareClass(style, "mod_table", clazz ->
                        {
                            Css.property(clazz, "border", String.format("1px solid %s", darkMode ? "#c9d1d9" : "black"));
                            Css.property(clazz, "border-collapse", "collapse");
                            Css.property(clazz, "padding", "4px");
                            Css.property(clazz, "vertical-align", "top");
                        });

                        Css.declareClass(style, "at_entry", clazz -> Css.property(clazz, "font-family", "'Courier New', monospace"));
                    });
                },
                body ->
                {
                    Html.element(body, "h1", "", "AccessTransformer Dump");

                    long count = atEntries.values()
                            .stream()
                            .mapToLong(List::size)
                            .sum();
                    long flagged = atEntries.values()
                            .stream()
                            .flatMap(List::stream)
                            .filter(AccessTransformer::flagged)
                            .count();

                    body.println(String.format("Found %d AccessTransformer entries in %d out of %d mods.", count, atEntries.size(), modCount));
                    body.print("Found");
                    Html.span(body, Html.getBoolColor(flagged == 0), Long.toString(flagged));
                    body.println("flagged AccessTransformer entries.<br>");

                    String tableAttrib = "class=\"mod_table\"";
                    MutableObject<String> lastAtOwner = new MutableObject<>("");
                    Html.table(
                            body,
                            tableAttrib,
                            thead -> Html.element(thead, "tr", tableAttrib, row ->
                            {
                                Html.tableHeader(row, tableAttrib, "Mod file (AT count)");
                                Html.tableHeader(row, tableAttrib, "AccessTransformer");
                                Html.tableHeader(row, tableAttrib, "Flagged");
                            }),
                            tbody -> atEntries.keySet().stream().sorted(String::compareToIgnoreCase).forEachOrdered(fileName ->
                            {
                                List<AccessTransformer> entries = atEntries.get(fileName);
                                entries.forEach(entry -> Html.tableRow(tbody, tableAttrib, row ->
                                {
                                    if (!fileName.equals(lastAtOwner.getValue()))
                                    {
                                        lastAtOwner.setValue(fileName);

                                        String rowStyle = String.format("%s rowspan=\"%d\"", tableAttrib, entries.size());
                                        Html.tableCell(
                                                row,
                                                rowStyle,
                                                String.format("%s (%d)", fileName, entries.size())
                                        );
                                    }

                                    Html.element(row, "td", tableAttrib, cell -> Html.span(cell, "class=\"at_entry\"", entry::toHtml));
                                    Html.element(row, "td", tableAttrib, cell ->
                                    {
                                        if (entry.flagged())
                                        {
                                            Html.span(cell, String.format("style=\"color: %s;\"", Html.COLOR_RED), "X");
                                        }
                                    });
                                }));
                            })
                    );
                }
        );

        writer.close();

        Main.LOG.info("AT display built");
    }
}
