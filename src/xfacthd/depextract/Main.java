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
    private static final OptionParser PARSER = new OptionParser();
    private static final OptionSpec<String> MINECRAFT_OPT = PARSER.accepts("minecraft", "The version of Minecraft being used").withRequiredArg().ofType(String.class);
    private static final OptionSpec<String> FORGE_OPT = PARSER.accepts("forge", "The version of Forge being used").withRequiredArg().ofType(String.class);
    private static final OptionSpec<File> DIRECTORY_OPT = PARSER.accepts("directory", "The root directory of the Minecraft installation").withRequiredArg().ofType(File.class);
    private static final OptionSpec<Boolean> EXTRACT_ATS_OPT = PARSER.accepts("extract_ats", "Extract AccessTransformers from mods").withOptionalArg().ofType(Boolean.class);
    private static final OptionSpec<String> FLAGGED_ATS_OPT = PARSER.accepts("flagged_ats", "Mark AT targets to be flagged").availableIf(EXTRACT_ATS_OPT).withOptionalArg().ofType(String.class);
    private static final OptionSpec<Boolean> EXTRACT_MIXINS_OPT = PARSER.accepts("extract_mixins", "Extract Mixin configs from mods").withOptionalArg().ofType(Boolean.class);
    private static final OptionSpec<Boolean> EXTRACT_COREMODS_OPT = PARSER.accepts("extract_coremods", "Extract JS coremods from mods").withOptionalArg().ofType(Boolean.class);
    private static final OptionSpec<Boolean> DARK_OPT = PARSER.accepts("dark", "Dark mode for the resulting web page").withOptionalArg().ofType(Boolean.class);
    private static final OptionSpec<Boolean> OPEN_RESULT_OPT = PARSER.accepts("open_result", "Automatically open the resulting web page in the standard browser").withOptionalArg().ofType(Boolean.class);

    public static void main(String[] args)
    {
        OptionSet options = PARSER.parse(args);

        String mcVersion = options.valueOf(MINECRAFT_OPT);
        String forgeVersion = Utils.getForgeVersion(options.valueOf(FORGE_OPT));
        File directory = options.valueOf(DIRECTORY_OPT);
        boolean extractATs = options.has(EXTRACT_ATS_OPT) && options.valueOf(EXTRACT_ATS_OPT);
        List<String> flaggedATs = Arrays.asList(options.valueOf(FLAGGED_ATS_OPT).split(","));
        boolean extractMixins = options.hasArgument(EXTRACT_MIXINS_OPT) && options.valueOf(EXTRACT_MIXINS_OPT);
        boolean extractCoremods = options.has(EXTRACT_COREMODS_OPT) && options.valueOf(EXTRACT_COREMODS_OPT);
        boolean darkMode = options.hasArgument(DARK_OPT) && options.valueOf(DARK_OPT);
        boolean openResult = options.hasArgument(OPEN_RESULT_OPT) && options.valueOf(OPEN_RESULT_OPT);

        LOG.info("Minecraft version: " + mcVersion);
        LOG.info("Forge version: " + forgeVersion);
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

        DependencyExtractor depExtractor = new DependencyExtractor(mcVersion, forgeVersion);

        List<DataExtractor> extractors = new ArrayList<>();
        extractors.add(depExtractor);
        if (extractATs)
        {
            extractors.add(new AccessTransformerExtractor(flaggedATs));
        }
        if (extractMixins)
        {
            extractors.add(new MixinExtractor());
        }
        if (extractCoremods)
        {
            extractors.add(new CoremodExtractor());
        }

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
            }
            catch (IOException e)
            {
                LOG.error("Encountered an exception while reading mod JAR '%s'!", modFile.getName());
            }
        }
    }
}
