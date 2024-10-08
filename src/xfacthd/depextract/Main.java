package xfacthd.depextract;

import com.google.common.base.Preconditions;
import com.google.gson.*;
import joptsimple.*;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;
import org.apache.maven.artifact.versioning.*;
import xfacthd.depextract.data.*;
import xfacthd.depextract.extractor.*;
import xfacthd.depextract.log.Log;
import xfacthd.depextract.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public class Main
{
    public static final Log LOG = new Log("main");
    private static final Gson GSON = new Gson();

    public static void main(String[] args)
    {
        List<DataExtractor> extractors = new ArrayList<>();

        DependencyExtractor depExtractor = new DependencyExtractor();
        extractors.add(depExtractor);
        extractors.add(new AccessTransformerExtractor());
        extractors.add(new MixinExtractor());
        extractors.add(new CoremodExtractor());
        extractors.add(new ClassFinderExtractor());

        OptionParser parser = new OptionParser();
        OptionSpec<Path> directoryOpt = parser.accepts("directory", "The root directory of the Minecraft installation")
                .withRequiredArg()
                .withValuesConvertedBy(new PathConverter(PathProperties.DIRECTORY_EXISTING))
                .required();
        OptionSpec<String> additionalModDirsOpt = parser.accepts("add_mod_dirs", "List of additional non-standard mod directories")
                .withRequiredArg()
                .withValuesSeparatedBy(",")
                .ofType(String.class);
        OptionSpec<Boolean> darkOpt = parser.accepts("dark", "Dark mode for the resulting web page")
                .withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(false);
        OptionSpec<Boolean> minifyOpt = parser.accepts("minify", "Minify the resulting web page")
                .withOptionalArg()
                .ofType(Boolean.class)
                .defaultsTo(true);
        OptionSpec<Boolean> openResultOpt = parser.accepts("open_result", "Automatically open the resulting web page in the standard browser")
                .withRequiredArg()
                .ofType(Boolean.class)
                .defaultsTo(false);
        extractors.forEach(extractor -> extractor.registerOptions(parser));

        if (args.length == 0 || (args.length == 1 && (args[0].equals("help") || args[0].equals("--help"))))
        {
            try
            {
                parser.printHelpOn(System.out);
            }
            catch (IOException e)
            {
                LOG.error("Failed to print help", e);
            }
            return;
        }

        OptionSet options = parser.parse(args);
        extractors.forEach(extractor -> extractor.readOptions(options));
        extractors = extractors.stream().filter(DataExtractor::isActive).toList();

        Path directory = options.valueOf(directoryOpt);
        List<String> additionalModDirs = options.hasArgument(additionalModDirsOpt) ? options.valuesOf(additionalModDirsOpt) : List.of();
        boolean darkMode = options.valueOf(darkOpt);
        boolean minify = options.valueOf(minifyOpt);
        boolean openResult = options.valueOf(openResultOpt);

        LOG.info("Minecraft version: " + depExtractor.getMCVersion());
        LOG.info("NeoForge version: " + depExtractor.getNeoForgeVersion());
        LOG.info("Instance directory: " + directory);

        Preconditions.checkArgument(Files.isDirectory(directory), "Expected a directory for argument --directory, got a file");
        Path modFolder = directory.resolve("mods");
        Preconditions.checkArgument(Files.exists(modFolder) && Files.isDirectory(modFolder), "Expected to find a mods directory");

        List<Path> modFolders = new ArrayList<>();
        modFolders.add(modFolder);
        if (!additionalModDirs.isEmpty())
        {
            additionalModDirs.forEach(dir ->
            {
                Path addModFolder = directory.resolve(dir);
                Preconditions.checkArgument(
                        Files.exists(addModFolder) && Files.isDirectory(addModFolder),
                        "%s doesn't exist or is not a directory",
                        dir
                );
                modFolders.add(addModFolder);
            });
        }

        LOG.info("Listing all mod JARs...");
        List<FileEntry> mods;
        try (Stream<SourceAwarePath> files = modFolders.stream().flatMap(Utils::listFiles))
        {
            mods = files.filter(path -> path.filePath().getFileName().toString().endsWith(".jar"))
                    .map(FileEntry::of)
                    .toList();
        }
        catch (UncheckedIOException e)
        {
            LOG.error("Encountered an error while listing contents of mods folder(s)", e);
            return;
        }
        if (mods.isEmpty())
        {
            LOG.info("Mods folder empty, aborting!");
            return;
        }
        LOG.info("Found %d mod JARs", mods.size());

        LOG.info("Discovering mod entries...");
        discoverModEntries(mods, extractors, false);
        int modCount = depExtractor.getModCount();
        LOG.info("Discovered %d mod entries in %d mod JARs", modCount, mods.size());

        extractors.forEach(DataExtractor::postProcessData);
        extractors.forEach(extractor -> extractor.printResults(darkMode, minify, modCount));

        if (openResult)
        {
            LOG.debug("Opening in default app...");
            Utils.openFileInDefaultSoftware(DependencyExtractor.DEP_RESULT_FILE_NAME);
        }

        LOG.info("Done, terminating");
    }

    private static void discoverModEntries(
            List<FileEntry> mods, List<DataExtractor> extractors, boolean nested
    )
    {
        for (FileEntry modEntry : mods)
        {
            Path modFile = modEntry.filePath();
            LOG.debug("Reading mod JAR '%s'...", modFile.getFileName());

            try (FileSystem jarFs = FileSystems.newFileSystem(modFile))
            {
                String fileName = modFile.getFileName().toString();
                extractJiJedMods(modFile, fileName, jarFs, extractors);
                extractors.forEach(extractor ->
                {
                    try
                    {
                        extractor.acceptFile(fileName, jarFs, nested, modEntry);
                    }
                    catch (IOException e)
                    {
                        LOG.error("Extractor '%s' failed to process mod JAR '%s'", extractor.name(), fileName, e);
                    }
                });
            }
            catch (IOException e)
            {
                LOG.error("Encountered an exception while reading mod JAR '%s'!", modFile.getFileName(), e);
            }
        }
    }

    private static void extractJiJedMods(Path modPath, String fileName, FileSystem modJar, List<DataExtractor> extractors)
    {
        Path jijMetaPath = modJar.getPath("META-INF/jarjar/metadata.json");
        if (!Files.exists(jijMetaPath))
        {
            return;
        }

        LOG.debug("Found JiJ metadata in mod JAR '%s'", fileName);

        JsonObject metadata;
        try
        {
            InputStream metaStream = Files.newInputStream(jijMetaPath);
            metadata = GSON.fromJson(new InputStreamReader(metaStream), JsonObject.class);
        }
        catch (IOException e)
        {
            LOG.error("Encountered an exception while reading JiJ metadata from mod JAR '%s'", fileName, e);
            return;
        }

        if (!metadata.has("jars") || metadata.getAsJsonArray("jars").isEmpty())
        {
            return;
        }

        JsonArray jars = metadata.getAsJsonArray("jars");
        List<FileEntry> jarEntries = new ArrayList<>();

        for (JsonElement elem : jars)
        {
            JsonObject obj = elem.getAsJsonObject();
            Path path = modJar.getPath(obj.get("path").getAsString());
            if (!Files.exists(path))
            {
                LOG.error("JiJed mod JAR at path '%s' is missing from mod JAR '%s'", path, fileName);
                continue;
            }

            JsonObject identifier = obj.getAsJsonObject("identifier");
            String group = identifier.get("group").getAsString();
            String artifact = identifier.get("artifact").getAsString();

            JsonObject version = obj.getAsJsonObject("version");
            String versionString = version.get("range").getAsString();
            VersionRange range = null;
            try
            {
                range = VersionRange.createFromVersionSpec(versionString);
            }
            catch (InvalidVersionSpecificationException e)
            {
                LOG.error("Found invalid version range '%s' for artifact '%s' in mod JAR '%s'", versionString, artifact, fileName);
            }
            ArtifactVersion artifactVersion = new DefaultArtifactVersion(version.get("artifactVersion").getAsString());

            boolean obfuscated = obj.has("obfuscated") && obj.get("obfuscated").getAsBoolean();

            JarInJarMeta jijMeta = new JarInJarMeta(group, artifact, range, artifactVersion, obfuscated);

            jarEntries.add(new FileEntry(modPath, path, jijMeta));
        }

        discoverModEntries(jarEntries, extractors, true);
    }
}
