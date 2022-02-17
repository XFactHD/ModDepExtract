package xfacthd.depextract.util;

import xfacthd.depextract.Main;

import java.io.*;
import java.util.Locale;

public class Utils
{
    public static String getForgeVersion(String forgeVersion)
    {
        if (forgeVersion.contains("-"))
        {
            forgeVersion = forgeVersion.substring(forgeVersion.indexOf("-") + 1);
        }
        return forgeVersion;
    }

    public static PrintWriter makePrintWriter(String fileName)
    {
        File outFile = new File(fileName);
        if (!outFile.exists())
        {
            try
            {
                //noinspection ResultOfMethodCallIgnored
                outFile.createNewFile();
            }
            catch (IOException e)
            {
                Main.LOG.error("Output file doesn't exist and file creation failed!");
                e.printStackTrace();
                return null;
            }
        }

        PrintWriter writer;
        try
        {
            writer = new PrintWriter(outFile);
        }
        catch (FileNotFoundException e)
        {
            Main.LOG.error("Can't open output file for writing!");
            e.printStackTrace();
            return null;
        }

        return writer;
    }

    public static void openFileInDefaultSoftware(String fileName)
    {
        OS os = OS.get();
        if (os == null) { return; }

        try
        {
            String fileUrl = new File(fileName).toURI().toURL().toString();

            String command = switch (os)
            {
                case WINDOWS -> String.format("rundll32 url.dll,FileProtocolHandler \"%s\"", fileUrl);
                case LINUX -> String.format("xdg-open %s", fileUrl);
                case MAC -> String.format("open %s", fileUrl);
            };

            Runtime.getRuntime().exec(command);
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to open file in default app");
            e.printStackTrace();
        }
    }

    public enum OS
    {
        WINDOWS,
        LINUX,
        MAC;

        public static OS get()
        {
            String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);

            if (osName.contains("win")) { return WINDOWS; }
            if (osName.contains("linux") || osName.contains("unix")) { return LINUX; }
            if (osName.contains("mac")) { return MAC; }

            Main.LOG.error("Unknown operating system: %s", osName);
            return null;
        }
    }
}
