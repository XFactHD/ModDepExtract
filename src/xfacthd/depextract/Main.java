package xfacthd.depextract;

import com.google.common.base.Preconditions;
import joptsimple.*;
import xfacthd.depextract.extractor.*;
import xfacthd.depextract.log.Log;
import xfacthd.depextract.util.*;

import java.io.*;
import java.util.*;
import java.util.jar.*;

public class Main
{
    public static final Log LOG = new Log("main");

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
        discoverModEntries(mods, extractors);
        int modCount = depExtractor.getModCount();
        LOG.info("Discovered %d mod entries in %d mod JARs", modCount, mods.length);

        extractors.forEach(DataExtractor::postProcessData);
        extractors.forEach(extractor -> extractor.printResults(darkMode, modCount));

        if (openResult)
        {
            LOG.debug("Opening in default app...");
            Utils.openFileInDefaultSoftware(DependencyExtractor.DEP_RESULT_FILE_NAME);
        }
    }

    private static void discoverModEntries(File[] mods, List<DataExtractor> extractors)
    {
        for (File modFile : mods)
        {
            try
            {
                LOG.debug("Reading mod JAR '%s'...", modFile.getName());

                JarFile modJar = new JarFile(modFile);
                extractors.forEach(extractor -> extractor.acceptFile(modFile.getName(), modJar));
                modJar.close();
            }
            catch (IOException e)
            {
                LOG.error("Encountered an exception while reading mod JAR '%s'!", modFile.getName());
            }
        }
    }
}
