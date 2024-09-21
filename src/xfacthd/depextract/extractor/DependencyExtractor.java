package xfacthd.depextract.extractor;

import com.google.common.collect.*;
import com.moandjiezana.toml.Toml;
import joptsimple.*;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.maven.artifact.versioning.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import xfacthd.depextract.Main;
import xfacthd.depextract.data.FileEntry;
import xfacthd.depextract.data.dependency.*;
import xfacthd.depextract.html.*;
import xfacthd.depextract.util.DataExtractor;
import xfacthd.depextract.util.Utils;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class DependencyExtractor extends DataExtractor
{
    public static final String DEP_RESULT_FILE_NAME = "dependencies.html";
    private static final Comparator<ModEntry> ENTRY_COMPARATOR = (entryOne, entryTwo) -> compareModIDs(entryOne.modId(), entryTwo.modId());
    private static final Comparator<Dependency> DEP_COMPARATOR = (depOne, depTwo) -> compareModIDs(depOne.modId(), depTwo.modId());
    private static final Dependency NULL_DEPENDENCY = new Dependency(null, null, Dependency.Type.OPTIONAL);
    private static final DepResult NULL_RESULT = new DepResult(null, false, false, false);
    private static final Attributes.Name AUTO_MOD_NAME = new Attributes.Name("Automatic-Module-Name");
    private static final Attributes.Name IMPL_TITLE_NAME = new Attributes.Name("Implementation-Title");
    private static final Attributes.Name IMPL_VER_NAME = new Attributes.Name("Implementation-Version");
    private static final Attributes.Name MOD_TYPE_NAME = new Attributes.Name("FMLModType");

    private final Multimap<String, ModEntry> modEntries = HashMultimap.create();
    private final Table<ModEntry, Dependency, DepResult> depResults = HashBasedTable.create(0, 4);
    private final Multimap<String, ModEntry> duplicates = HashMultimap.create();
    private OptionSpec<String> minecraftOpt = null;
    private OptionSpec<String> neoForgeOpt = null;
    private OptionSpec<Boolean> onlyUnsatisfiedOpt = null;
    private OptionSpec<Boolean> onlySatisfiedOpt = null;
    private String mcVersion = "";
    private String neoForgeVersion = "";
    private boolean onlyUnsatisfied = false;
    private boolean onlySatisfied = false;
    private int jarCount = 0;
    private int hiddenModCount = 0;

    @Override
    public void registerOptions(OptionParser parser)
    {
        minecraftOpt = parser.accepts("minecraft", "The version of Minecraft being used")
                .withRequiredArg()
                .ofType(String.class)
                .required();

        neoForgeOpt = parser.accepts("neoforge", "The version of NeoForge being used")
                .withRequiredArg()
                .ofType(String.class)
                .required();

        onlySatisfiedOpt = parser.accepts("onlySatisfied", "If true, only mods with all dependencies satisfed will be printed")
                .withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(false);

        onlyUnsatisfiedOpt = parser.accepts("onlyUnsatisfied", "If true, only mods with at least one unsatisfied dependency will be printed")
                .withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(false);
    }

    @Override
    public void readOptions(OptionSet options)
    {
        this.mcVersion = options.valueOf(minecraftOpt);
        this.neoForgeVersion = options.valueOf(neoForgeOpt);
        this.onlySatisfied = options.valueOf(onlySatisfiedOpt);
        this.onlyUnsatisfied = options.valueOf(onlyUnsatisfiedOpt);
    }

    @Override
    public boolean isActive() { return true; }

    @Override
    public String name() { return "Dependencies"; }

    @Override
    public void acceptFile(String fileName, FileSystem modJar, boolean jij, FileEntry modInfo) throws IOException
    {
        Path sourcePath = modInfo.srcPath();
        Path tomlEntry = modJar.getPath("META-INF/neoforge.mods.toml");
        Manifest manifest = findManifest(modJar, fileName);
        if (Files.exists(tomlEntry))
        {
            InputStream tomlStream = Files.newInputStream(tomlEntry);
            if (manifest == null)
            {
                Main.LOG.error("Encountered an error while retrieving metadata from mod JAR '%s'", fileName);
                return;
            }

            Multimap<String, ModEntry> entries = parseModEntriesInFile(sourcePath, fileName, tomlStream, manifest, jij);
            if (!entries.isEmpty())
            {
                modEntries.putAll(entries);
                Main.LOG.debug("Found %d mod(s) in mod JAR '%s'", entries.size(), fileName);
            }
            else
            {
                Main.LOG.error("Failed to parse mod definition for mod JAR '%s'", fileName);
            }

            tomlStream.close();

            jarCount++;
        }
        else
        {
            if (compareManifestEntry(manifest, MOD_TYPE_NAME, "LANGPROVIDER"))
            {
                parseLanguageProvider(sourcePath, fileName, modJar, manifest, jij);
                jarCount++;
            }
            else if (compareManifestEntry(manifest, MOD_TYPE_NAME, "GAMELIBRARY", "LIBRARY") || jij)
            {
                String name = fileName.toLowerCase(Locale.ROOT);
                String version = jij ? modInfo.jijMeta().version().toString() : "NONE";
                String modType = "GAMELIBRARY";// JiJed JARs without mod metadata are considered GAMELIBRARIEs

                if (manifest != null)
                {
                    Attributes attribs = manifest.getMainAttributes();
                    if (attribs.containsKey(AUTO_MOD_NAME))
                    {
                        name = attribs.getValue(AUTO_MOD_NAME);
                    }
                    else if (attribs.containsKey(IMPL_TITLE_NAME))
                    {
                        name = attribs.getValue(IMPL_TITLE_NAME);
                    }
                    if (attribs.containsKey(IMPL_VER_NAME))
                    {
                        version = attribs.getValue(IMPL_VER_NAME);
                    }
                    if (attribs.containsKey(MOD_TYPE_NAME))
                    {
                        modType = attribs.getValue(MOD_TYPE_NAME);
                    }
                }

                String modId = name.toLowerCase(Locale.ROOT).replace(' ', '_').replace(".jar", "");

                modEntries.put(fileName, new ModEntry(
                        fileName, modId, name, new DefaultArtifactVersion(version), List.of(), modType, jij, sourcePath
                ));
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

        Map<String, ModEntry> processed = new HashMap<>();
        for (ModEntry entry : modEntries.values())
        {
            if (entry.modId().equals("minecraft") || entry.modId().equals("neoforge"))
            {
                // Don't add MC and NeoForge to the results
                continue;
            }

            if (processed.containsKey(entry.modId()))
            {
                ModEntry other = processed.get(entry.modId());

                Main.LOG.warning(
                        "Found duplicated mod '%s' in mod JAR '%s', previously found in mod JAR '%s'",
                        entry.modId(),
                        entry.fileName(),
                        other.fileName()
                );

                duplicates.put(entry.modId(), other);
                duplicates.put(entry.modId(), entry);
            }
            else
            {
                processed.put(entry.modId(), entry);
            }

            if (entry.dependencies().isEmpty())
            {
                depResults.put(entry, NULL_DEPENDENCY, NULL_RESULT);
                continue;
            }

            for (Dependency dep : entry.dependencies())
            {
                Collection<ModEntry> entries = modEntries.get(dep.modId());
                ModEntry depMod = entries.isEmpty() ? null : entries.iterator().next();

                boolean installed = depMod != null;
                boolean inRange = installed && dep.isVersionRangeSatisfied(depMod.version());
                boolean valid = dep.type().isSatisfied(installed, inRange);
                String installedVersion = installed ? depMod.version().toString() : "-";

                depResults.put(entry, dep, new DepResult(installedVersion, installed, inRange, valid));
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

        List<String> toRemove = new ArrayList<>();
        for (String modId : duplicates.keySet())
        {
            Collection<ModEntry> entries = duplicates.get(modId);
            if (entries.stream().allMatch(ModEntry::jij))
            {
                toRemove.add(modId);
            }
        }
        toRemove.forEach(duplicates::removeAll);

        Main.LOG.info("Dependencies validated");
    }

    @Override
    public void printResults(boolean darkMode, boolean minify, int modCount)
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
                minify,
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
                    body.println(String.format("NeoForge version: %s", neoForgeVersion));
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

                    if (!duplicates.isEmpty())
                    {
                        Html.element(body, "h2", "", "Duplicated mods");
                        body.println("This information may not be fully accurate and these duplicates may not actually cause issues<br>");

                        MutableObject<String> lastEntryId = new MutableObject<>("");
                        Html.table(
                                body,
                                tableAttrib,
                                thead -> Html.tableRow(thead, tableAttrib, row ->
                                {
                                    Html.tableHeader(row, tableAttrib, "Mod ID");
                                    Html.tableHeader(row, tableAttrib, "File name");
                                    Html.tableHeader(row, tableAttrib, "File source");
                                    Html.tableHeader(row, tableAttrib, "Mod version");
                                }),
                                tbody -> duplicates.keySet().stream().sorted(DependencyExtractor::compareModIDs).forEachOrdered(id ->
                                {
                                    Collection<ModEntry> entries = duplicates.get(id);
                                    entries.forEach(entry -> Html.tableRow(tbody, tableAttrib, row ->
                                    {
                                        if (!entry.modId().equals(lastEntryId.getValue()))
                                        {
                                            lastEntryId.setValue(entry.modId());

                                            Html.tableCell(
                                                    row,
                                                    String.format("%s rowspan=\"%d\"", tableAttrib, entries.size()),
                                                    entry.modId()
                                            );
                                        }

                                        Html.tableCell(row, tableAttrib, entry.fileName());
                                        Html.tableCell(row, tableAttrib, cell -> printFileSource(cell, entry));
                                        Html.tableCell(row, tableAttrib, entry.version().toString());
                                    }));
                                }));

                        body.print("<br><br>");
                    }

                    Html.element(body, "h2", "", "Dependency details");

                    MutableObject<ModEntry> lastDepEntry = new MutableObject<>(null);
                    Html.table(
                            body,
                            tableAttrib,
                            thead -> Html.tableRow(thead, tableAttrib, row ->
                            {
                                Html.tableHeader(row, tableAttrib, "Mod (ID)");
                                Html.tableHeader(row, tableAttrib, "Mod type");
                                Html.tableHeader(row, tableAttrib, "Mod version");
                                Html.tableHeader(row, tableAttrib, "File source");
                                Html.tableHeader(row, tableAttrib, "Dependency");
                                Html.tableHeader(row, tableAttrib, "Requested range");
                                Html.tableHeader(row, tableAttrib, "Installed version");
                                Html.tableHeader(row, tableAttrib, "Type");
                                Html.tableHeader(row, tableAttrib, "Installed");
                                Html.tableHeader(row, tableAttrib, "In range");
                                Html.tableHeader(row, tableAttrib, "Satisfied");

                            }),
                            tbody -> depResults.rowKeySet().stream().sorted(ENTRY_COMPARATOR).forEachOrdered(entry ->
                            {
                                Map<Dependency, DepResult> deps = depResults.row(entry);
                                deps.keySet().stream().sorted(DEP_COMPARATOR).forEachOrdered(dep -> Html.tableRow(tbody, tableAttrib, row ->
                                {
                                    if (lastDepEntry.getValue() != entry)
                                    {
                                        lastDepEntry.setValue(entry);

                                        String rowStyle = String.format("%s rowspan=\"%d\"", tableAttrib, Math.max(deps.size(), 1));
                                        Html.tableCell(
                                                row,
                                                rowStyle,
                                                String.format("%s<br>(%s)", entry.modName(), entry.modId())
                                        );
                                        Html.tableCell(row, rowStyle, entry.modType());
                                        Html.tableCell(row, rowStyle, entry.version().toString());
                                        Html.tableCell(row, rowStyle, cell -> printFileSource(cell, entry));
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
                                    Html.tableCell(row, tableAttrib, nullResult ? "" : dep.getVersionRangeString());
                                    Html.tableCell(row, installedAttrib, nullResult ? "" : installedVersion);
                                    Html.tableCell(row, tableAttrib, cell -> dep.type().print(cell, nullResult));
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

    public String getNeoForgeVersion() { return neoForgeVersion; }



    private static Multimap<String, ModEntry> parseModEntriesInFile(Path sourcePath, String fileName, InputStream tomlStream, Manifest manifest, boolean jij)
    {
        Multimap<String, ModEntry> modList = HashMultimap.create();

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
                    dependencies,
                    "MOD",
                    jij,
                    sourcePath
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
            if (versionRange == null)
            {
                versionRange = Dependency.UNBOUNDED_VERSION;
            }

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

            String typeEntry = (String) depEntry.get("type");
            Dependency.Type type = Dependency.Type.parse(typeEntry);
            if (type == null)
            {
                Main.LOG.error("Found dependency for '%s' with invalid type '%s' in mod '%s', skipping!", depModId, typeEntry, modId);
                continue;
            }

            depList.add(new Dependency(depModId, range, type));
        }
    }

    private void parseLanguageProvider(Path sourcePath, String fileName, FileSystem modJar, Manifest manifest, boolean jij)
    {
        Attributes attrs = manifest.getMainAttributes();

        String displayName;
        if (attrs.containsKey(IMPL_TITLE_NAME))
        {
            displayName = attrs.getValue(IMPL_TITLE_NAME);
        }
        else if (attrs.containsKey(AUTO_MOD_NAME))
        {
            displayName = attrs.getValue(AUTO_MOD_NAME);
        }
        else
        {
            Main.LOG.warning("Can't determine name of language provider in JAR '%s', skipping", fileName);
            return;
        }

        String providerName = getProviderNameFromByteCode(fileName, modJar);

        // The name deduction when the bytecode analysis fails is an ugly guess
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

        modEntries.put(providerName, new ModEntry(
                fileName,
                providerName,
                displayName,
                new DefaultArtifactVersion(version),
                List.of(),
                "LANGPROVIDER",
                jij,
                sourcePath
        ));
    }

    private static String getProviderNameFromByteCode(String fileName, FileSystem modJar)
    {
        Path serviceEntry = modJar.getPath("META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader");
        if (!Files.exists(serviceEntry))
        {
            Main.LOG.error("Language Provider in JAR '%s' doesn't contain an IModLanguageProvider service file, this is invalid", fileName);
            return null;
        }

        String serviceClassName;
        try
        {
            InputStream serviceStream = Files.newInputStream(serviceEntry);
            serviceClassName = new String(serviceStream.readAllBytes());
            serviceStream.close();
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to read IModLanguageProvider service class name from JAR '%s'", fileName, e);
            return null;
        }

        serviceClassName = serviceClassName.replace('.', '/').replaceAll("\\R", "");

        Path serviceClass = modJar.getPath(serviceClassName + ".class");
        if (!Files.exists(serviceClass))
        {
            Main.LOG.error("LanguageProvider class '%s' is missing from mod JAR '%s'", serviceClassName, fileName);
            return null;
        }

        byte[] code;
        try
        {
            InputStream stream = Files.newInputStream(serviceClass);
            code = stream.readAllBytes();
            stream.close();
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to read LanguageProvider class '%s' from mod JAR '%s'", serviceClassName, fileName, e);
            return null;
        }

        ClassReader reader = new ClassReader(code);
        ClassNode clazz = new ClassNode(Opcodes.ASM9);
        reader.accept(clazz, 0);

        if (clazz.methods == null)
        {
            Main.LOG.error("LanguageProvider class '%s' from mod JAR '%s' is invalid", serviceClassName, fileName);
            return null;
        }

        for (MethodNode method : clazz.methods)
        {
            if (method.name.equals("name") && method.desc.equals("()Ljava/lang/String;"))
            {
                for (AbstractInsnNode insn : method.instructions)
                {
                    if (insn.getOpcode() == Opcodes.LDC)
                    {
                        Object constant = ((LdcInsnNode) insn).cst;
                        if (constant instanceof String string)
                        {
                            return string;
                        }
                    }
                }
                break;
            }
        }

        Main.LOG.error("Failed to locate the LanguageProvider name in class '%s' in mod JAR '%s'", serviceClassName, fileName);
        return null;
    }

    private void addDefaultMods()
    {
        modEntries.put("minecraft", new ModEntry("", "minecraft", "Minecraft", new DefaultArtifactVersion(mcVersion), List.of(), "MOD", false, null));
        modEntries.put("neoforge", new ModEntry("", "neoforge", "NeoForge", new DefaultArtifactVersion(neoForgeVersion), List.of(), "MOD", false, null));
        hiddenModCount = 2;
    }

    private static int compareModIDs(String idOne, String idTwo)
    {
        if (idOne.equals(idTwo)) { return 0; }

        if (idOne.equals("minecraft")) { return -1; }
        if (idTwo.equals("minecraft")) { return 1; }
        if (idOne.equals("neoforge")) { return -1; }
        if (idTwo.equals("neoforge")) { return 1; }

        return idOne.compareTo(idTwo);
    }

    @SuppressWarnings("SameParameterValue")
    private static boolean compareManifestEntry(Manifest manifest, Attributes.Name key, String... targets)
    {
        if (manifest == null) { return false; }

        String value = manifest.getMainAttributes().getValue(key);
        if (value == null)
        {
            return false;
        }
        for (String target : targets)
        {
            if (value.equals(target))
            {
                return true;
            }
        }
        return false;
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

    private static void printFileSource(HtmlWriter writer, ModEntry entry)
    {
        Html.abbreviation(
                writer,
                entry.fileSource().toAbsolutePath().toString(),
                content ->
                {
                    if (entry.jij())
                    {
                        content.print("JiJ in");
                        Html.element(
                                content,
                                "b",
                                "style=\"text-weight: bold\"",
                                Utils.trySubstringAfterLast(entry.fileSource().toString().replaceAll("\\\\", "/"), '/')
                        );
                    }
                    else
                    {
                        content.print("Mods folder");
                    }
                }
        );
    }
}
