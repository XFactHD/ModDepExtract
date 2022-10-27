package xfacthd.depextract.extractor;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.moandjiezana.toml.Toml;
import joptsimple.*;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.maven.artifact.versioning.*;
import xfacthd.depextract.Main;
import xfacthd.depextract.data.dependency.*;
import xfacthd.depextract.html.*;
import xfacthd.depextract.util.*;

import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DependencyExtractor extends DataExtractor
{
    public static final String DEP_RESULT_FILE_NAME = "dependencies.html";
    private static final Comparator<ModEntry> ENTRY_COMPARATOR = (entryOne, entryTwo) -> compareModIDs(entryOne.modId(), entryTwo.modId());
    private static final Comparator<Dependency> DEP_COMPARATOR = (depOne, depTwo) -> compareModIDs(depOne.modId(), depTwo.modId());
    private static final Dependency NULL_DEPENDENCY = new Dependency(null, null, false);
    private static final DepResult NULL_RESULT = new DepResult(null, false, false, false);
    private static final Attributes.Name AUTO_MOD_NAME = new Attributes.Name("Automatic-Module-Name");
    private static final Attributes.Name IMPL_TITLE_NAME = new Attributes.Name("Implementation-Title");
    private static final Attributes.Name IMPL_VER_NAME = new Attributes.Name("Implementation-Version");
    private static final Pattern LANG_PROVIDER_NAME_PATTERN = Pattern.compile("public String name\\(\\) \\{\\R\s+return \"([a-z\\d_.-]+)\";\\R\s+}");

    private final Map<String, ModEntry> modEntries = new HashMap<>();
    private final Table<ModEntry, Dependency, DepResult> depResults = HashBasedTable.create(0, 4);
    private OptionSpec<String> minecraftOpt = null;
    private OptionSpec<String> forgeOpt = null;
    private OptionSpec<Boolean> onlyUnsatisfiedOpt = null;
    private OptionSpec<Boolean> onlySatisfiedOpt = null;
    private String mcVersion = "";
    private String forgeVersion = "";
    private boolean onlyUnsatisfied = false;
    private boolean onlySatisfied = false;
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

        onlySatisfiedOpt = parser.accepts("onlySatisfied", "If true, only mods with all dependencies satisfed will be printed")
                .withRequiredArg()
                .ofType(Boolean.class);

        onlyUnsatisfiedOpt = parser.accepts("onlyUnsatisfied", "If true, only mods with at least one unsatisfied dependency will be printed")
                .withRequiredArg()
                .ofType(Boolean.class);
    }

    @Override
    public void readOptions(OptionSet options)
    {
        this.mcVersion = options.valueOf(minecraftOpt);
        this.forgeVersion = Utils.getForgeVersion(options.valueOf(forgeOpt));
        this.onlySatisfied = options.has(onlySatisfiedOpt) && options.valueOf(onlySatisfiedOpt);
        this.onlyUnsatisfied = options.has(onlyUnsatisfiedOpt) && options.valueOf(onlyUnsatisfiedOpt);
    }

    @Override
    public boolean isActive() { return true; }

    @Override
    public void acceptFile(String fileName, JarFile modJar)
    {
        JarEntry tomlEntry = modJar.getJarEntry("META-INF/mods.toml");
        Manifest manifest = findManifest(modJar, fileName);
        if (tomlEntry != null)
        {
            InputStream tomlStream = getInputStreamForEntry(modJar, tomlEntry, fileName);
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

            cleanupJarEntryInputStream(tomlStream, tomlEntry, fileName);

            jarCount++;
        }
        else
        {
            if (compareManifestEntry(manifest, "FMLModType", "LANGPROVIDER"))
            {
                parseLanguageProvider(fileName, modJar, manifest);
                jarCount++;
            }
            else
            {
                Main.LOG.warning("Mod definition not found in mod JAR '%s', skipping", fileName);
            }
        }
    }

    @Override
    public void postProcessData()
    {
        Main.LOG.info("Validating dependency satisfaction...");

        addDefaultMods();

        for (ModEntry entry : modEntries.values())
        {
            if (entry.modId().equals("minecraft") || entry.modId().equals("forge"))
            {
                // Don't add MC and Forge to the results
                continue;
            }

            if (entry.dependencies().isEmpty())
            {
                depResults.put(entry, NULL_DEPENDENCY, NULL_RESULT);
                continue;
            }

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

        if (onlySatisfied || onlyUnsatisfied)
        {
            Iterator<ModEntry> it = depResults.rowKeySet().iterator();
            while (it.hasNext())
            {
                ModEntry entry = it.next();
                boolean allSatisfied = depResults.rowMap()
                        .get(entry)
                        .values()
                        .stream()
                        .filter(res -> onlySatisfied || res != NULL_RESULT)
                        .allMatch(DepResult::valid);

                if ((onlySatisfied && !allSatisfied) || (onlyUnsatisfied && allSatisfied))
                {
                    it.remove();
                }
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
                    Html.element(body, "h1", "", "Mod Dependency Analysis");

                    body.println(String.format("Minecraft version: %s", mcVersion));
                    body.println(String.format("Forge version: %s-%s", mcVersion, forgeVersion));
                    body.println(String.format("Found %d mods in %d mod JARs", modCount, jarCount));

                    if (onlySatisfied)
                    {
                        int satCount = depResults.rowKeySet().size();
                        body.println(String.format("Only showing mods with satisfied dependencies (%d out of %d mods)", satCount, modCount));
                    }
                    else if (onlyUnsatisfied)
                    {
                        int unsatCount = depResults.rowKeySet().size();
                        body.println(String.format("Only showing mods with unsatisfied dependencies (%d out of %d mods)", unsatCount, modCount));
                    }

                    body.print("All dependencies satisfied:");
                    boolean satisfied = depResults.cellSet()
                            .stream()
                            .map(Table.Cell::getValue)
                            .filter(Objects::nonNull)
                            .filter(res -> res != NULL_RESULT)
                            .allMatch(DepResult::valid);
                    Html.writeBoolean(body, "", satisfied);
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
                                Html.tableHeader(row, tableAttrib, "Satisfied");

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
                                    boolean nullResult = result == NULL_RESULT;

                                    String installedVersion = result.installedVersion();
                                    String installedAttrib = tableAttrib;
                                    if (!nullResult && installedVersion.equals("0.0NONE"))
                                    {
                                        installedVersion = Html.escape("<invalid>");
                                        installedAttrib = String.format("%s %s", tableAttrib, Html.getBoolColor(false));
                                    }

                                    Html.tableCell(row, tableAttrib, nullResult ? "" : dep.modId());
                                    Html.tableCell(row, tableAttrib, nullResult ? "" : dep.versionRange().toString());
                                    Html.tableCell(row, installedAttrib, nullResult ? "" : installedVersion);
                                    Html.tableCell(row, tableAttrib, cell -> printBooleanOrEmpty(cell, dep.mandatory(), nullResult));
                                    Html.tableCell(row, tableAttrib, cell -> printBooleanOrEmpty(cell, result.installed(), nullResult));
                                    Html.tableCell(row, tableAttrib, cell -> printBooleanOrEmpty(cell, result.inRange(), nullResult));
                                    Html.tableCell(row, tableAttrib, cell -> printBooleanOrEmpty(cell, result.valid(), nullResult));
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

        Toml deps;
        if (toml.containsTable("dependencies"))
        {
            deps = toml.getTable("dependencies");
        }
        else
        {
            if (toml.containsTableArray("dependencies"))
            {
                Main.LOG.warning("Mod definition in mod JAR '%s' declares 'dependencies' as a list instead of a table, this is invalid and will be skipped!", fileName);
            }
            else if (toml.contains("dependencies"))
            {
                Optional<Object> depOpt = toml.entrySet()
                        .stream()
                        .filter(entry -> entry.getKey().equals("dependencies"))
                        .map(Map.Entry::getValue)
                        .findFirst();
                Main.LOG.warning("Mod definition in mod JAR '%s' declares 'dependencies' as '%s' instead of a table, this is invalid and will be skipped!", fileName, depOpt.orElseThrow().getClass());
            }
            deps = null;
        }

        for (Map<String, Object> mod : mods)
        {
            String modId = (String) mod.get("modId");

            List<Dependency> dependencies = new ArrayList<>();
            parseDependencies(deps, modId, dependencies);

            String version = (String) mod.get("version");
            if (version != null && version.equals("${file.jarVersion}"))
            {
                version = manifest.getMainAttributes().getValue(IMPL_VER_NAME);
            }
            if (version == null)
            {
                version = "0.0NONE";
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

    private void parseLanguageProvider(String fileName, JarFile modJar, Manifest manifest)
    {
        Attributes attrs = manifest.getMainAttributes();

        String displayName;
        if (attrs.containsKey(IMPL_TITLE_NAME))
        {
            displayName = attrs.getValue(IMPL_TITLE_NAME);
        }
        else
        {
            Main.LOG.warning("Can't determine name of language provider in JAR '%s', skipping", fileName);
            return;
        }

        String providerName = null;
        if (Decompiler.isDecompilerPresent())
        {
            providerName = getProviderNameFromDecomp(fileName, modJar);
        }

        // The name deduction without the decompiler is an ugly guess
        if (providerName == null)
        {
            if (attrs.containsKey(AUTO_MOD_NAME))
            {
                providerName = attrs.getValue(AUTO_MOD_NAME);
            }
            else
            {
                providerName = displayName.toLowerCase(Locale.ROOT);
            }
        }

        String version = "0.0";
        if (attrs.containsKey(IMPL_VER_NAME))
        {
            version = attrs.getValue(IMPL_VER_NAME);
        }

        modEntries.put(providerName, new ModEntry(fileName, providerName, displayName, new DefaultArtifactVersion(version), List.of()));
    }

    private static String getProviderNameFromDecomp(String fileName, JarFile modJar)
    {
        JarEntry serviceEntry = modJar.getJarEntry("META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider");
        if (serviceEntry == null)
        {
            Main.LOG.error("Language Provider in JAR '%s' doesn't contain an IModLanguageProvider service file, this is invalid", fileName);
            return null;
        }

        String serviceClassName;
        try
        {
            InputStream serviceStream = modJar.getInputStream(serviceEntry);
            serviceClassName = new String(serviceStream.readAllBytes());
            serviceStream.close();
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to read IModLanguageProvider service class name from JAR '%s'", fileName);
            return null;
        }

        serviceClassName = serviceClassName.replace('.', '/').replaceAll("\\R", "");

        if (!Decompiler.copyToInput(fileName, modJar, serviceClassName + ".class"))
        {
            Main.LOG.error("Failed to prepare JAR '%s' for decompilation");
            return null;
        }

        JarFile decompJar = Decompiler.decompile(fileName);
        if (decompJar == null)
        {
            Main.LOG.error("Failed to decompile JAR '%s'", fileName);
            Decompiler.cleanup(fileName);
            return null;
        }

        JarEntry serviceClass = decompJar.getJarEntry(serviceClassName + ".java");
        if (serviceClass == null)
        {
            Main.LOG.error("Failed to find IModLanguageProvider service");
            Decompiler.cleanup(fileName, decompJar);
            return null;
        }

        String serviceCode;
        try
        {
            InputStream serviceStream = decompJar.getInputStream(serviceClass);
            serviceCode = new String(serviceStream.readAllBytes());
            serviceStream.close();
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to read IModLanguageProvider service class code from decompiled JAR '%s'", fileName);
            return null;
        }
        finally
        {
            Decompiler.cleanup(fileName, decompJar);
        }

        Matcher matcher = LANG_PROVIDER_NAME_PATTERN.matcher(serviceCode);
        if (!matcher.find())
        {
            Main.LOG.error("Failed to find language provider name in service class code from decompiled JAR '%s'", fileName);
            return null;
        }

        String result = matcher.group();
        return result.substring(result.indexOf('\"') + 1, result.lastIndexOf('\"'));
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

    @SuppressWarnings("SameParameterValue")
    private static boolean compareManifestEntry(Manifest manifest, String key, String target)
    {
        if (manifest == null) { return false; }

        String value = manifest.getMainAttributes().getValue(key);
        return value != null && value.equals(target);
    }

    private static void printBooleanOrEmpty(HtmlWriter cell, boolean value, boolean hide)
    {
        if (hide)
        {
            cell.print("");
        }
        else
        {
            Html.writeBoolean(cell, "", value);
        }
    }
}
