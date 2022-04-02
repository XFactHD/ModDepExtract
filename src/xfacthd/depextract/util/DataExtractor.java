package xfacthd.depextract.util;

import xfacthd.depextract.Main;

import java.io.IOException;
import java.io.InputStream;
import java.util.jar.*;

public abstract class DataExtractor
{
    public abstract void acceptFile(String fileName, JarFile modJar);

    public abstract void postProcessData();

    public abstract void printResults(boolean darkMode, int modCount);

    protected static InputStream getInputStreamForEntry(JarFile file, JarEntry entry, String fileName)
    {
        InputStream stream;

        try
        {
            stream = file.getInputStream(entry);
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to get JarEntry '%s' from mod JAR '%s'", entry.getName(), fileName);
            return null;
        }

        return stream;
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
