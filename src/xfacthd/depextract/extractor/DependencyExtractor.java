package xfacthd.depextract.extractor;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.moandjiezana.toml.Toml;
import joptsimple.*;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.maven.artifact.versioning.*;
import xfacthd.depextract.Main;
import xfacthd.depextract.html.Css;
import xfacthd.depextract.html.Html;
import xfacthd.depextract.util.*;

import java.io.*;
import java.util.*;
import java.util.jar.*;

public class DependencyExtractor extends DataExtractor
{
    public static final String DEP_RESULT_FILE_NAME = "dependencies.html";
    private static final Comparator<ModEntry> ENTRY_COMPARATOR = (entryOne, entryTwo) -> compareModIDs(entryOne.modId(), entryTwo.modId());
    private static final Comparator<Dependency> DEP_COMPARATOR = (depOne, depTwo) -> compareModIDs(depOne.modId(), depTwo.modId());

    private final Map<String, ModEntry> modEntries = new HashMap<>();
    private final Table<ModEntry, Dependency, DepResult> depResults = HashBasedTable.create(modEntries.size(), 4);
    private OptionSpec<String> minecraftOpt = null;
    private OptionSpec<String> forgeOpt = null;
    private String mcVersion = "";
    private String forgeVersion = "";
    private int jarCount = 0;
    private int hiddenModCount = 0;

    @Override
    public void registerOptions(OptionParser parser)
    {
        minecraftOpt = parser.accepts("minecraft", "The version of Minecraft being used")
                .withRequiredArg()
                .ofType(String.class);

        forgeOpt = parser.accepts("forge", "The version of Forge being used")
                .withRequiredArg()
                .ofType(String.class);
    }

    @Override
    public void readOptions(OptionSet options)
    {
        this.mcVersion = options.valueOf(minecraftOpt);
        this.forgeVersion = options.valueOf(forgeOpt);
    }

    @Override
    public boolean isActive() { return true; }

    @Override
    public void acceptFile(String fileName, JarFile modJar)
    {
        JarEntry tomlEntry = modJar.getJarEntry("META-INF/mods.toml");
        if (tomlEntry != null)
        {
            InputStream tomlStream = getInputStreamForEntry(modJar, tomlEntry, fileName);
            Manifest manifest = findManifest(modJar, fileName);
            if (tomlStream == null || manifest == null)
            {
                Main.LOG.error("Encountered an error while retrieving metadata from mod JAR '%s'", fileName);
                return;
            }

            Map<String, ModEntry> entries = parseModEntriesInFile(fileName, tomlStream, manifest);
            if (!entries.isEmpty())
            {
                modEntries.putAll(entries);
                Main.LOG.debug("Found %d mod(s) in mod JAR '%s'", entries.size(), fileName);
            }
            else
            {
                Main.LOG.error("Failed to parse mod definition for mod JAR '%s'", fileName);
            }

            jarCount++;
        }
        else
        {
            Main.LOG.warning("Mod definition not found in mod JAR '%s', skipping", fileName);
        }
    }

    @Override
    public void postProcessData()
    {
        Main.LOG.info("Validating dependency satisfaction...");

        addDefaultMods();

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

        Main.LOG.info("Dependencies validated");
    }

    @Override
    public void printResults(boolean darkMode, int modCount)
    {
        Main.LOG.info("Building dependency display...");

        PrintWriter writer = Utils.makePrintWriter(DEP_RESULT_FILE_NAME);
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
                    body.println(String.format("Found %d mods in %d mod JARs", modCount, jarCount));
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

        Main.LOG.info("Dependency display built");
    }

    public int getModCount() { return modEntries.size() - hiddenModCount; }

    public String getMCVersion() { return mcVersion; }

    public String getForgeVersion() { return forgeVersion; }

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
            if (version == null)
            {
                version = "0.0";
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
                Main.LOG.error("Found dependency for '%s' with invalid version range '%s' in mod '%s'!", depModId, versionRange, modId);
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

    private void addDefaultMods()
    {
        modEntries.put("minecraft", new ModEntry("", "minecraft", "Minecraft", new DefaultArtifactVersion(mcVersion), Collections.emptyList()));
        modEntries.put("forge", new ModEntry("", "forge", "Minecraft Forge", new DefaultArtifactVersion(forgeVersion), Collections.emptyList()));
        hiddenModCount = 2;
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
