package xfacthd.depextract.util;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import xfacthd.depextract.Main;

import java.io.IOException;
import java.io.InputStream;
import java.util.jar.*;

public abstract class DataExtractor
{
    public abstract void registerOptions(OptionParser parser);

    public abstract void readOptions(OptionSet options);

    public abstract boolean isActive();

    public abstract void acceptFile(String fileName, JarFile modJar);

    public abstract void postProcessData();

    public abstract void printResults(boolean darkMode, int modCount);

    protected static InputStream getInputStreamForEntry(JarFile file, JarEntry entry, String fileName)
    {
        try
        {
            return file.getInputStream(entry);
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to get JarEntry '%s' from mod JAR '%s'", entry.getName(), fileName);
            return null;
        }
    }

    protected static void cleanupJarEntryInputStream(InputStream stream, JarEntry entry, String fileName)
    {
        try
        {
            stream.close();
        }
        catch (IOException e)
        {
            Main.LOG.error("Encountered an error while closing JarEntry '%s' from mod JAR '%s'", entry.getName(), fileName);
        }
    }

    protected static Manifest findManifest(JarFile file, String fileName)
    {
        Manifest manifest;

        try
        {
            manifest = file.getManifest();
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to get Manifest from mod JAR '%s'", fileName);
            return null;
        }

        return manifest;
    }
}
