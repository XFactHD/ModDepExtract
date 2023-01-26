package xfacthd.depextract;

import com.google.common.base.Preconditions;
import com.google.gson.*;
import joptsimple.*;
import xfacthd.depextract.extractor.*;
import xfacthd.depextract.log.Log;
import xfacthd.depextract.util.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;

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

        OptionParser parser = new OptionParser();
        OptionSpec<File> directoryOpt = parser.accepts("directory", "The root directory of the Minecraft installation")
                .withRequiredArg()
                .ofType(File.class);
        OptionSpec<Boolean> darkOpt = parser.accepts("dark", "Dark mode for the resulting web page")
                .withOptionalArg()
                .ofType(Boolean.class);
        OptionSpec<Boolean> openResultOpt = parser.accepts("open_result", "Automatically open the resulting web page in the standard browser")
                .withOptionalArg()
                .ofType(Boolean.class);
        Decompiler.registerOptions(parser);
        extractors.forEach(extractor -> extractor.registerOptions(parser));

        OptionSet options = parser.parse(args);
        Decompiler.readOptions(options);
        extractors.forEach(extractor -> extractor.readOptions(options));
        extractors = extractors.stream().filter(DataExtractor::isActive).toList();

        File directory = options.valueOf(directoryOpt);
        boolean darkMode = options.hasArgument(darkOpt) && options.valueOf(darkOpt);
        boolean openResult = options.hasArgument(openResultOpt) && options.valueOf(openResultOpt);

        LOG.info("Minecraft version: " + depExtractor.getMCVersion());
        LOG.info("Forge version: " + depExtractor.getForgeVersion());
        LOG.info("Instance directory: " + directory.getAbsolutePath());

        Preconditions.checkArgument(directory.isDirectory(), "Expected a directory for argument --directory, got a file");
        File modFolder = directory.toPath().resolve("mods").toFile();
        Preconditions.checkArgument(modFolder.exists() && modFolder.isDirectory(), "Expected to find a mods directory");

        LOG.info("Listing all mod JARs...");
        File[] mods = modFolder.listFiles(file -> file.getName().endsWith(".jar"));
        if (mods == null || mods.length == 0)
        {
            LOG.info("Mods folder empty, aborting!");
            return;
        }
        LOG.info("Found %d mod JARs", mods.length);

        LOG.info("Discovering mod entries...");
        List<File> jijTemp = discoverModEntries(mods, extractors, false);
        int modCount = depExtractor.getModCount();
        LOG.info("Discovered %d mod entries in %d mod JARs", modCount, mods.length);

        extractors.forEach(DataExtractor::postProcessData);
        extractors.forEach(extractor -> extractor.printResults(darkMode, modCount));

        if (openResult)
        {
            LOG.debug("Opening in default app...");
            Utils.openFileInDefaultSoftware(DependencyExtractor.DEP_RESULT_FILE_NAME);
        }

        cleanupJiJTempCopies(jijTemp);
    }

    private static List<File> discoverModEntries(File[] mods, List<DataExtractor> extractors, boolean nested)
    {
        List<File> jijTemp = nested ? List.of() : new ArrayList<>();
        for (File modFile : mods)
        {
            try
            {
                LOG.debug("Reading mod JAR '%s'...", modFile.getName());

                JarFile modJar = new JarFile(modFile);
                if (!nested)
                {
                    // JiJ doesn't support recursive discovery
                    jijTemp.addAll(extractJiJedMods(modJar, extractors));
                }
                extractors.forEach(extractor -> extractor.acceptFile(modFile.getName(), modJar, nested));
                modJar.close();
            }
            catch (IOException e)
            {
                LOG.error("Encountered an exception while reading mod JAR '%s'!", modFile.getName());
            }
        }
        return jijTemp;
    }

    private static List<File> extractJiJedMods(JarFile modJar, List<DataExtractor> extractors)
    {
        JarEntry jijMeta = modJar.getJarEntry("META-INF/jarjar/metadata.json");
        if (jijMeta == null)
        {
            return List.of();
        }

        LOG.debug("Found JiJ metadata in mod JAR '%s'", modJar.getName());

        JsonObject metadata;
        try
        {
            InputStream metaStream = modJar.getInputStream(jijMeta);
            metadata = GSON.fromJson(new InputStreamReader(metaStream), JsonObject.class);
        }
        catch (IOException e)
        {
            LOG.error("Encountered an exception while reading JiJ metadata from mod JAR '%s'", modJar.getName());
            return List.of();
        }

        if (!metadata.has("jars") || metadata.getAsJsonArray("jars").isEmpty())
        {
            return List.of();
        }

        JsonArray jars = metadata.getAsJsonArray("jars");
        List<JarEntry> jarEntries = new ArrayList<>();

        for (JsonElement elem : jars)
        {
            JsonObject obj = elem.getAsJsonObject();
            String path = obj.get("path").getAsString();
            JarEntry jarEntry = modJar.getJarEntry(path);
            if (jarEntry == null)
            {
                LOG.error("JiJed mod JAR at path '%s' is missing from mod JAR '%s'", path, modJar.getName());
                continue;
            }

            jarEntries.add(jarEntry);
        }

        try
        {
            Files.createDirectories(Path.of("./jij_temp"));
        }
        catch (IOException e)
        {
            LOG.error("Failed to create JiJ temp directory");
            return List.of();
        }

        List<File> innerMods = new ArrayList<>();

        for (JarEntry entry : jarEntries)
        {
            String jarName = entry.getName();
            if (jarName.contains("/"))
            {
                jarName = jarName.substring(jarName.lastIndexOf('/') + 1);
            }
            try
            {
                File file = new File("./jij_temp/" + jarName);
                if (!file.exists() && !file.createNewFile())
                {
                    LOG.error("Failed to create temporary file for JiJed JAR '%s'", jarName);
                    continue;
                }

                InputStream stream = modJar.getInputStream(entry);
                OutputStream outStream = new FileOutputStream(file);
                outStream.write(stream.readAllBytes());
                outStream.close();
                stream.close();

                innerMods.add(file);
            }
            catch (IOException e)
            {
                LOG.error("Failed to copy JiJed JAR '%s' to temp folder", jarName);
            }
        }

        discoverModEntries(innerMods.toArray(File[]::new), extractors, true);

        return innerMods;
    }

    private static void cleanupJiJTempCopies(List<File> jijMods)
    {
        for (File file : jijMods)
        {
            try
            {
                Files.delete(file.toPath());
            }
            catch (IOException e)
            {
                LOG.error("Failed to delete temp copy of JiJed mod JAR '%s'", file.getName());
            }
        }
    }
}
