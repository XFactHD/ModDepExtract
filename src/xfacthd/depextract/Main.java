package xfacthd.depextract;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.moandjiezana.toml.Toml;
import joptsimple.*;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.maven.artifact.versioning.*;
import xfacthd.depextract.html.Css;
import xfacthd.depextract.html.Html;
import xfacthd.depextract.log.Log;
import xfacthd.depextract.util.*;

import java.io.*;
import java.util.*;
import java.util.jar.*;

public class Main
{
    public static final Log LOG = new Log("main");
    private static final OptionParser PARSER = new OptionParser();
    private static final OptionSpec<String> MINECRAFT_OPT = PARSER.accepts("minecraft", "The version of Minecraft being used").withRequiredArg().ofType(String.class);
    private static final OptionSpec<String> FORGE_OPT = PARSER.accepts("forge", "The version of Forge being used").withRequiredArg().ofType(String.class);
    private static final OptionSpec<File> DIRECTORY_OPT = PARSER.accepts("directory", "The root directory of the Minecraft installation").withRequiredArg().ofType(File.class);
    private static final OptionSpec<Boolean> EXTRACT_ATS_OPT = PARSER.accepts("extract_ats", "Extract AccessTransformers from mods").withOptionalArg().ofType(Boolean.class);
    private static final OptionSpec<String> FLAGGED_ATS_OPT = PARSER.accepts("flagged_ats", "Mark AT targets to be flagged").availableIf(EXTRACT_ATS_OPT).withOptionalArg().ofType(String.class);
    private static final OptionSpec<Boolean> DARK_OPT = PARSER.accepts("dark", "Dark mode for the resulting web page").withOptionalArg().ofType(Boolean.class);
    private static final OptionSpec<Boolean> OPEN_RESULT_OPT = PARSER.accepts("open_result", "Automatically open the resulting web page in the standard browser").withOptionalArg().ofType(Boolean.class);
    private static final String DEP_RESULT_FILE_NAME = "dependencies.html";
    private static final String AT_RESULT_FILE_NAME = "accesstransformers.html";
    private static final Comparator<ModEntry> ENTRY_COMPARATOR = (entryOne, entryTwo) -> compareModIDs(entryOne.modId(), entryTwo.modId());
    private static final Comparator<Dependency> DEP_COMPARATOR = (depOne, depTwo) -> compareModIDs(depOne.modId(), depTwo.modId());

    public static void main(String[] args)
    {
        OptionSet options = PARSER.parse(args);
        String mcVersion = options.valueOf(MINECRAFT_OPT);
        String forgeVersion = Utils.getForgeVersion(options.valueOf(FORGE_OPT));
        File directory = options.valueOf(DIRECTORY_OPT);
        boolean extractATs = options.valueOf(EXTRACT_ATS_OPT);
        List<String> flaggedATs = Arrays.asList(options.valueOf(FLAGGED_ATS_OPT).split(","));
        boolean darkMode = options.hasArgument(DARK_OPT) && options.valueOf(DARK_OPT);
        boolean openResult = options.hasArgument(OPEN_RESULT_OPT) && options.valueOf(OPEN_RESULT_OPT);

        LOG.info("Minecraft version: " + mcVersion);
        LOG.info("Forge version: " + forgeVersion);
        LOG.info("Instance directory: " + directory.getAbsolutePath());
        if (extractATs && !flaggedATs.isEmpty())
        {
            LOG.info("Flagged ATs: " + flaggedATs);
        }

        Preconditions.checkArgument(directory.isDirectory(), "Expected a directory for argument --directory, got a file");
        File modFolder = directory.toPath().resolve("mods").toFile();
        Preconditions.checkArgument(modFolder.exists() && modFolder.isDirectory(), "Expected to find a mods directory");

        LOG.info("Listing all mod JARs...");
        File[] mods = modFolder.listFiles(file -> file.getName().endsWith(".jar"));
        if (mods == null)
        {
            LOG.info("Mods folder empty, aborting!");
            return;
        }
        LOG.info("Found %d mod JARs", mods.length);

        LOG.info("Discovering mod entries...");
        Map<String, ModEntry> modEntries = new HashMap<>();
        Map<String, List<AccessTransformer>> atEntries = new HashMap<>();
        addDefaultMods(modEntries, mcVersion, forgeVersion);
        discoverModEntries(mods, modEntries, extractATs, flaggedATs, atEntries);
        LOG.info("Discovered %d mod entries in %d mod JARs", modEntries.size() - 2, mods.length);

        LOG.info("Validating dependency satisfaction...");
        Table<ModEntry, Dependency, DepResult> depResults = HashBasedTable.create(modEntries.size(), 4);
        validateDependenciesSatisfied(modEntries, depResults);
        LOG.info("Dependencies validated");

        LOG.info("Building dependency display...");
        writeDependencyResultPage(depResults, mcVersion, forgeVersion, darkMode);
        LOG.info("Dependency display built");

        if (extractATs)
        {
            LOG.info("Building AT display...");
            writeAccessTransformerResultPage(atEntries, darkMode);
            LOG.info("AT display built");
        }

        if (openResult)
        {
            LOG.debug("Opening in default app...");
            Utils.openFileInDefaultSoftware(DEP_RESULT_FILE_NAME);
        }
    }

    private static void addDefaultMods(Map<String, ModEntry> modEntries, String mcVersion, String forgeVersion)
    {
        modEntries.put("minecraft", new ModEntry("", "minecraft", "Minecraft", new DefaultArtifactVersion(mcVersion), Collections.emptyList()));
        modEntries.put("forge", new ModEntry("", "forge", "Minecraft Forge", new DefaultArtifactVersion(forgeVersion), Collections.emptyList()));
    }

    private static void discoverModEntries(File[] mods, Map<String, ModEntry> modEntries, boolean extractATs, List<String> flaggedATs, Map<String, List<AccessTransformer>> modATs)
    {
        for (File modFile : mods)
        {
            try
            {
                LOG.debug("Reading mod JAR '%s'...", modFile.getName());

                JarFile modJar = new JarFile(modFile);
                JarEntry tomlEntry = modJar.getJarEntry("META-INF/mods.toml");
                if (tomlEntry != null)
                {
                    Map<String, ModEntry> entries = parseModEntriesInFile(modFile.getName(), modJar.getInputStream(tomlEntry), modJar.getManifest());
                    if (!entries.isEmpty())
                    {
                        modEntries.putAll(entries);
                    }
                    else
                    {
                        LOG.error("Failed to parse mod definition for mod JAR '%s'", modFile.getName());
                    }
                }
                else
                {
                    LOG.warning("Mod definition not found in mod JAR '%s', skipping", modFile.getName());
                }

                if (extractATs)
                {
                    JarEntry atEntry = modJar.getJarEntry("META-INF/accesstransformer.cfg");
                    if (atEntry != null)
                    {
                        List<AccessTransformer> atEntries = parseAccessTransformers(modFile.getName(), modJar.getInputStream(atEntry), flaggedATs);
                        if (!atEntries.isEmpty())
                        {
                            modATs.put(modFile.getName(), atEntries);
                        }
                    }
                }
            }
            catch (IOException e)
            {
                LOG.error("Encountered an exception while reading mod JAR '%s'!", modFile.getName());
            }
        }
    }

    private static Map<String, ModEntry> parseModEntriesInFile(String fileName, InputStream tomlStream, Manifest manifest)
    {
        Map<String, ModEntry> modList = new HashMap<>();

        Toml toml = new Toml().read(tomlStream);

        List<Map<String, Object>> mods = toml.getList("mods");
        Toml deps = toml.getTable("dependencies");

        for (Map<String, Object> mod : mods)
        {
            String modId = (String) mod.get("modId");

            List<Dependency> dependencies = new ArrayList<>();
            parseDependencies(deps, modId, dependencies);

            String version = (String) mod.get("version");
            if (version.equals("${file.jarVersion}"))
            {
                version = manifest.getMainAttributes().getValue("Implementation-Version");
            }

            ModEntry entry = new ModEntry(
                    fileName,
                    modId,
                    (String) mod.get("displayName"),
                    new DefaultArtifactVersion(version),
                    dependencies
            );
            modList.put(modId, entry);
        }

        return modList;
    }

    private static void parseDependencies(Toml deps, String modId, List<Dependency> depList)
    {
        if (deps == null) { return; }

        List<Map<String, Object>> modDeps = deps.getList(modId);
        if (modDeps == null) { return; }

        for (Map<String, Object> depEntry : modDeps)
        {
            String depModId = (String) depEntry.get("modId");
            String versionRange = (String) depEntry.get("versionRange");

            VersionRange range;
            try
            {
                range = VersionRange.createFromVersionSpec(versionRange);
            }
            catch (InvalidVersionSpecificationException e)
            {
                LOG.error("Found dependency for '%s' with invalid version range '%s' in mod '%s'!", depModId, versionRange, modId);
                range = null;
            }

            Dependency dependency = new Dependency(
                    depModId,
                    range,
                    (Boolean) depEntry.get("mandatory")
            );
            depList.add(dependency);
        }
    }

    private static List<AccessTransformer> parseAccessTransformers(String fileName, InputStream atStream, List<String> flaggedATs)
    {
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
            LOG.error("Encountered an error while parsing AccessTransformers for mod JAR '%s'!", fileName);
        }

        return ats;
    }

    private static void validateDependenciesSatisfied(Map<String, ModEntry> modEntries, Table<ModEntry, Dependency, DepResult> depResults)
    {
        for (ModEntry entry : modEntries.values())
        {
            for (Dependency dep : entry.dependencies())
            {
                ModEntry depMod = modEntries.get(dep.modId());

                boolean installed = depMod != null;
                boolean inRange = installed && dep.versionRange().containsVersion(depMod.version());
                boolean valid = installed ? inRange : !dep.mandatory();

                depResults.put(
                        entry,
                        dep,
                        new DepResult(
                                installed ? depMod.version().toString() : "-",
                                installed,
                                inRange,
                                valid
                        )
                );
            }
        }
    }

    private static void writeDependencyResultPage(
            Table<ModEntry, Dependency, DepResult> depResults,
            String mcVersion,
            String forgeVersion,
            boolean darkMode
    )
    {
        PrintWriter writer = Utils.makePrintWriter(DEP_RESULT_FILE_NAME);
        if (writer == null)
        {
            LOG.error("Failed to write result page!");
            return;
        }

        Html.html(
                writer,
                darkMode ? "style=\"background-color: #0d1117; color: #f0f6fc;\"" : "",
                head ->
                {
                    Html.element(head, "title", "", "Mod Dependency Analysis");
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
                    Html.element(body, "h1", "", "Mod Dependency Analysis");

                    body.println(String.format("Minecraft version: %s", mcVersion));
                    body.println(String.format("Forge version: %s-%s", mcVersion, forgeVersion));
                    body.print("All dependencies valid:");
                    Html.writeBoolean(body, "", depResults.cellSet().stream().map(Table.Cell::getValue).filter(Objects::nonNull).allMatch(DepResult::valid));
                    body.print("<br><br>");

                    String tableAttrib = "class=\"mod_table\"";
                    MutableObject<String> lastDepId = new MutableObject<>("");
                    Html.table(
                            body,
                            tableAttrib,
                            thead -> Html.tableRow(thead, tableAttrib, row ->
                            {
                                Html.tableHeader(row, tableAttrib, "Mod (ID)");
                                Html.tableHeader(row, tableAttrib, "Mod version");
                                Html.tableHeader(row, tableAttrib, "Dependency");
                                Html.tableHeader(row, tableAttrib, "Requested range");
                                Html.tableHeader(row, tableAttrib, "Installed version");
                                Html.tableHeader(row, tableAttrib, "Required");
                                Html.tableHeader(row, tableAttrib, "Installed");
                                Html.tableHeader(row, tableAttrib, "In range");
                                Html.tableHeader(row, tableAttrib, "Valid");

                            }),
                            tbody -> depResults.rowKeySet().stream().sorted(ENTRY_COMPARATOR).forEachOrdered(entry ->
                            {
                                Map<Dependency, DepResult> deps = depResults.row(entry);
                                deps.keySet().stream().sorted(DEP_COMPARATOR).forEachOrdered(dep -> Html.tableRow(tbody, tableAttrib, row ->
                                {
                                    if (!entry.modId().equals(lastDepId.getValue()))
                                    {
                                        lastDepId.setValue(entry.modId());

                                        String rowStyle = String.format("%s rowspan=\"%d\"", tableAttrib, deps.size());
                                        Html.tableCell(
                                                row,
                                                rowStyle,
                                                String.format("%s<br>(%s)", entry.modName(), entry.modId())
                                        );
                                        Html.tableCell(
                                                row,
                                                rowStyle,
                                                entry.version().toString()
                                        );
                                    }

                                    DepResult result = deps.get(dep);

                                    Html.tableCell(row, tableAttrib, dep.modId());
                                    Html.tableCell(row, tableAttrib, dep.versionRange().toString());
                                    Html.tableCell(row, tableAttrib, result.installedVersion());
                                    Html.tableCell(row, tableAttrib, cell -> Html.writeBoolean(cell, "", dep.mandatory()));
                                    Html.tableCell(row, tableAttrib, cell -> Html.writeBoolean(cell, "", result.installed()));
                                    Html.tableCell(row, tableAttrib, cell -> Html.writeBoolean(cell, "", result.inRange()));
                                    Html.tableCell(row, tableAttrib, cell -> Html.writeBoolean(cell, "", result.valid()));
                                }));
                            })
                    );
                }
        );

        writer.close();
    }

    private static void writeAccessTransformerResultPage(Map<String, List<AccessTransformer>> atEntries, boolean darkMode)
    {
        PrintWriter writer = Utils.makePrintWriter(AT_RESULT_FILE_NAME);
        if (writer == null)
        {
            LOG.error("Failed to write result page!");
            return;
        }

        Html.html(
                writer,
                darkMode ? "style=\"background-color: #0d1117; color: #f0f6fc;\"" : "",
                head ->
                {
                    Html.element(head, "title", "", "Mod Dependency Analysis");
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

                    body.println(String.format("Found %d AccessTransformer entries.", count));
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
                            tbody -> atEntries.keySet().stream().sorted(String::compareTo).forEachOrdered(fileName ->
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
    }

    private static int compareModIDs(String idOne, String idTwo)
    {
        if (idOne.equals(idTwo)) { return 0; }

        if (idOne.equals("minecraft")) { return -1; }
        if (idTwo.equals("minecraft")) { return 1; }
        if (idOne.equals("forge")) { return -1; }
        if (idTwo.equals("forge")) { return 1; }

        return idOne.compareTo(idTwo);
    }
}
