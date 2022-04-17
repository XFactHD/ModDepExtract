package xfacthd.depextract.util;

import com.google.common.base.Suppliers;
import joptsimple.*;
import xfacthd.depextract.Main;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.zip.ZipOutputStream;

public class Decompiler
{
    private static final String DEFAULT_DECOMP_PATH = "./forgeflower-1.5.498.29.jar";
    private static OptionSpec<String> decompilerPathOpt = null;
    private static String decompPath = DEFAULT_DECOMP_PATH;
    private static final Supplier<Boolean> DECOMP_PRESENT = Suppliers.memoize(Decompiler::checkDecompilerPresent);

    public static void registerOptions(OptionParser parser)
    {
        decompilerPathOpt = parser.accepts("mixin_decompiler", "Path to the ForgeFlower decompiler JAR")
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
            Files.createDirectories(Path.of("./decomp_in"));

            File jarFile = new File("decomp_in/" + fileName);

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

    public static JarFile decompile(String fileName)
    {
        try
        {
            Files.createDirectories(Path.of("./decomp_out"));

            Process decomp = new ProcessBuilder().command("java", "-jar", decompPath, "-nls=1", "./decomp_in/" + fileName, "./decomp_out").start();
            InputStream stream = decomp.getInputStream();
            while (decomp.isAlive())
            {
                if (stream.available() > 0)
                {
                    stream.readAllBytes();
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

        File resultFile = new File("decomp_out/" + fileName);
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

            Files.deleteIfExists(Path.of("./decomp_in/" + fileName));
            Files.deleteIfExists(Path.of("./decomp_out/" + fileName));
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
