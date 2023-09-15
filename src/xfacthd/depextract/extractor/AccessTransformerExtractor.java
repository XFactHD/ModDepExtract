package xfacthd.depextract.extractor;

import joptsimple.*;
import org.apache.commons.lang3.mutable.MutableObject;
import xfacthd.depextract.Main;
import xfacthd.depextract.data.accesstransformer.AccessTransformer;
import xfacthd.depextract.data.JarInJarMeta;
import xfacthd.depextract.data.accesstransformer.ChartType;
import xfacthd.depextract.html.Css;
import xfacthd.depextract.html.Html;
import xfacthd.depextract.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class AccessTransformerExtractor extends DataExtractor
{
    private static final String AT_RESULT_FILE_NAME = "accesstransformers.html";
    private static final String CHART_JS_SRC = "https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.3.2/chart.umd.js";
    private static final String CHART_JS_INTEGRITY = "sha512-KIq/d78rZMlPa/mMe2W/QkRgg+l0/GAAu4mGBacU0OQyPV/7EPoGQChDb269GigVoPQit5CqbNRFbgTjXHHrQg==";

    private final List<String> flaggedATs = new ArrayList<>();
    private final Map<String, List<AccessTransformer>> atEntries = new HashMap<>();
    private final Map<AccessTransformer, Integer> atCounts = new HashMap<>();
    private OptionSpec<Boolean> extractATsOpt = null;
    private OptionSpec<String> flaggedATsOpt = null;
    private OptionSpec<ChartType.Compound> createGraphOpt = null;
    private boolean active = false;
    private ChartType.Compound createGraph = ChartType.Compound.NONE;

    @Override
    public void registerOptions(OptionParser parser)
    {
        extractATsOpt = parser.accepts("extract_ats", "Extract AccessTransformers from mods")
                .withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(false);

        flaggedATsOpt = parser.accepts("flagged_ats", "Mark AT targets to be flagged")
                .availableIf(extractATsOpt)
                .withRequiredArg()
                .withValuesSeparatedBy(",")
                .ofType(String.class);

        createGraphOpt = parser.accepts("create_at_graph", "Create a graph showing the amount of ATs per target depending on the specified type (none, class, method, field or all")
                .availableIf(extractATsOpt)
                .withRequiredArg()
                .withValuesConvertedBy(ChartType.ChartTypeValueConverter.INSTANCE)
                .defaultsTo(ChartType.Compound.NONE);
    }

    @Override
    public void readOptions(OptionSet options)
    {
        active = options.valueOf(extractATsOpt);
        createGraph = options.valueOf(createGraphOpt);

        if (active)
        {
            flaggedATs.addAll(options.valuesOf(flaggedATsOpt));

            if (!flaggedATs.isEmpty())
            {
                Main.LOG.info("Flagged ATs: " + flaggedATs);
            }
        }
    }

    @Override
    public boolean isActive() { return active; }

    @Override
    public String name() { return "AccessTransformers"; }

    @Override
    public void acceptFile(String fileName, FileSystem modJar, boolean jij, JarInJarMeta jijMeta, Path sourcePath) throws IOException
    {
        Path atEntry = modJar.getPath("META-INF/accesstransformer.cfg");
        if (!Files.exists(atEntry)) { return; }

        InputStream atStream = Files.newInputStream(atEntry);
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
            Main.LOG.error("Encountered an error while parsing AccessTransformers for mod JAR '%s'!", fileName, e);
        }

        if (!ats.isEmpty())
        {
            atEntries.put(fileName, ats);
        }
    }

    @Override
    public void postProcessData()
    {
        if (createGraph.isActive())
        {
            atEntries.values()
                    .stream()
                    .flatMap(List::stream)
                    .filter(createGraph::matches)
                    .forEach(e -> atCounts.compute(e, (at, i) -> i == null ? 1 : (i + 1)));
        }
    }

    @Override
    public void printResults(boolean darkMode, boolean minify, int modCount)
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
                minify,
                darkMode ? "style=\"background-color: #0d1117; color: #f0f6fc;\"" : "",
                head ->
                {
                    Html.element(head, "title", "", "AccessTransformer Dump");
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
                        Utils.declareDescriptorSelectors(style);
                    });

                    if (createGraph.isActive())
                    {
                        String attrib = "src=\"%s\" integrity=\"%s\" crossorigin=\"anonymous\" referrerpolicy=\"no-referrer\""
                                .formatted(CHART_JS_SRC, CHART_JS_INTEGRITY);
                        Html.element(head, "script", attrib, "");
                    }
                },
                body ->
                {
                    Html.element(body, "h1", "", "AccessTransformer Dump");

                    long count = atEntries.values()
                            .stream()
                            .mapToLong(List::size)
                            .sum();
                    long unique = atEntries.values()
                            .stream()
                            .flatMap(List::stream)
                            .filter(Utils.customDistinct(AccessTransformer::target))
                            .count();
                    long flagged = atEntries.values()
                            .stream()
                            .flatMap(List::stream)
                            .filter(AccessTransformer::flagged)
                            .count();

                    body.println(String.format("Found %d AccessTransformer entries (%d unique entries) in %d out of %d mods.", count, unique, atEntries.size(), modCount));
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

                    if (createGraph.isActive())
                    {
                        body.println("");

                        String title = "%sATs per target".formatted(createGraph.getTitlePrefix());

                        Html.element(body, "h3", "", title);
                        Html.element(body, "canvas", "id=\"graph\"", "");

                        Html.element(body, "script", "type=\"application/javascript\"", script ->
                        {
                            String labels = atCounts.entrySet()
                                    .stream()
                                    .filter(e -> e.getValue() > 1)
                                    .sorted(Comparator.comparingInt(Map.Entry::getValue))
                                    .map(Map.Entry::getKey)
                                    .map(at -> at.prettyPrintTarget(false))
                                    .map(target -> "'" + target + "'")
                                    .collect(Collectors.joining(","));
                            String shortLabels = atCounts.entrySet()
                                    .stream()
                                    .filter(e -> e.getValue() > 1)
                                    .sorted(Comparator.comparingInt(Map.Entry::getValue))
                                    .map(Map.Entry::getKey)
                                    .map(at -> at.prettyPrintTarget(true))
                                    .map(target -> "'" + target + "'")
                                    .collect(Collectors.joining(","));
                            String values = atCounts.entrySet()
                                    .stream()
                                    .filter(e -> e.getValue() > 1)
                                    .sorted(Comparator.comparingInt(Map.Entry::getValue))
                                    .map(Map.Entry::getValue)
                                    .map(Object::toString)
                                    .collect(Collectors.joining(","));

                            script.printMultiLine("""
                                    const graph = new Chart('graph', {
                                        type: 'bar',
                                        data: {
                                            labels: [%s],
                                            shortLabels: [%s],
                                            datasets: [{
                                                label: '%s',
                                                data: [%s]
                                            }]
                                        },
                                        options: {
                                            scales: {
                                                x: {
                                                    ticks: {
                                                        callback: function(value, index, ticks) {
                                                            return this.chart.config.data.shortLabels[index];
                                                        }
                                                    }
                                                },
                                                y: {
                                                    beginAtZero: true
                                                }
                                            }
                                        }
                                    });
                                    """.formatted(labels, shortLabels, title, values)
                            );
                        });
                    }
                }
        );

        writer.close();

        Main.LOG.info("AT display built");
    }
}
