package xfacthd.depextract.util;

import com.google.common.base.Suppliers;
import joptsimple.*;
import xfacthd.depextract.Main;
import xfacthd.depextract.log.Marker;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipOutputStream;

public class Decompiler
{
    private static final Marker DECOMP_MARKER = new Marker("Decompiler");
    private static final String DEFAULT_DECOMP_PATH = "./forgeflower-1.5.498.29.jar";
    private static final String DECOMP_IN_PATH = "./decomp_in";
    private static final String DECOMP_OUT_PATH = "./decomp_out";
    private static OptionSpec<String> decompilerPathOpt = null;
    private static String decompPath = DEFAULT_DECOMP_PATH;
    private static final Supplier<Boolean> DECOMP_PRESENT = Suppliers.memoize(Decompiler::checkDecompilerPresent);

    public static void registerOptions(OptionParser parser)
    {
        decompilerPathOpt = parser.accepts("decompiler_path", "Path to the ForgeFlower decompiler JAR")
                .withOptionalArg()
                .ofType(String.class)
                .defaultsTo(DEFAULT_DECOMP_PATH);
    }

    public static void readOptions(OptionSet options)
    {
        decompPath = options.valueOf(decompilerPathOpt);
        DECOMP_PRESENT.get();
    }

    public static boolean isDecompilerPresent() { return DECOMP_PRESENT.get(); }

    public static boolean writeInput(String fileName, ThrowingConsumer<ZipOutputStream, IOException> streamConsumer)
    {
        try
        {
            Files.createDirectories(Path.of(DECOMP_IN_PATH));

            File jarFile = new File(DECOMP_IN_PATH + "/" + fileName);

            if (!jarFile.exists() && !jarFile.createNewFile())
            {
                return false;
            }

            FileOutputStream fileStream = new FileOutputStream(jarFile);

            ZipOutputStream zipStream = new ZipOutputStream(fileStream);
            streamConsumer.accept(zipStream);
            zipStream.close();

            fileStream.close();

            return true;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    public static boolean copyToInput(String fileName, JarFile jar, String... fileFilter)
    {
        List<String> filters = fileFilter != null && fileFilter.length > 0 ? List.of(fileFilter) : List.of();
        return writeInput(fileName, zipStream ->
        {
            List<JarEntry> entries = jar.stream()
                    .filter(entry -> filters.isEmpty() || filters.contains(entry.getName()))
                    .toList();

            for (JarEntry entry : entries)
            {
                zipStream.putNextEntry(new JarEntry(entry));

                InputStream stream = jar.getInputStream(entry);
                zipStream.write(stream.readAllBytes());
                stream.close();
            }
        });
    }

    public static JarFile decompile(String fileName)
    {
        try
        {
            Files.createDirectories(Path.of(DECOMP_OUT_PATH));

            Process decomp = new ProcessBuilder().command("java", "-jar", decompPath, "-nls=1", DECOMP_IN_PATH + "/" + fileName, DECOMP_OUT_PATH).start();
            InputStream stream = decomp.getInputStream();
            while (decomp.isAlive())
            {
                if (stream.available() > 0)
                {
                    String output = new String(stream.readAllBytes());
                    Main.LOG.debug(DECOMP_MARKER, "[Decompiler] %s", output);
                }
            }
            if (decomp.waitFor() != 0)
            {
                Main.LOG.error("Decompiler exited with non-zero exit code '%d'", decomp.exitValue());
                return null;
            }
        }
        catch (IOException | InterruptedException e)
        {
            Main.LOG.error("Encountered an error while decompiling collected Mixins");
            e.printStackTrace();
            return null;
        }

        File resultFile = new File(DECOMP_OUT_PATH + "/" + fileName);
        if (!resultFile.exists())
        {
            Main.LOG.error("Decompilation result archive not found for file '%s'", fileName);
            return null;
        }

        try
        {
            return new JarFile(resultFile);
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to create JarFile for decompilation result in file '%s'", fileName);
            return null;
        }
    }

    public static void cleanup(String fileName, JarFile... files)
    {
        try
        {
            for (JarFile file : files)
            {
                if (file != null)
                {
                    file.close();
                }
            }

            Files.deleteIfExists(Path.of(DECOMP_IN_PATH + "/" + fileName));
            Files.deleteIfExists(Path.of(DECOMP_OUT_PATH + "/" + fileName));
        }
        catch (IOException e)
        {
            Main.LOG.error("Encountered an error while cleaning up decompilation artifacts of '%s'", fileName);
        }
    }



    private static boolean checkDecompilerPresent()
    {
        if (!Files.exists(Path.of(decompPath)))
        {
            if (!decompPath.equals(DEFAULT_DECOMP_PATH))
            {
                Main.LOG.warning("Non-default decompiler path provided but decompiler was not found: '%s'", decompPath);
            }
            Main.LOG.info("Decompiler not found, features depending on the decompiler will not be available");

            return false;
        }
        return true;
    }
}
