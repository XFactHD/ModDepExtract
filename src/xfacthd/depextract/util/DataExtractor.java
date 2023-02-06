package xfacthd.depextract.util;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import xfacthd.depextract.Main;
import xfacthd.depextract.data.JarInJarMeta;

import java.io.IOException;
import java.nio.file.*;
import java.util.jar.*;

public abstract class DataExtractor
{
    public abstract void registerOptions(OptionParser parser);

    public abstract void readOptions(OptionSet options);

    public abstract boolean isActive();

    public abstract String name();

    public abstract void acceptFile(String fileName, FileSystem modJar, boolean jij, JarInJarMeta jijMeta, Path sourcePath) throws IOException;

    public abstract void postProcessData();

    public abstract void printResults(boolean darkMode, int modCount);



    protected static Manifest findManifest(FileSystem file, String fileName)
    {
        Manifest manifest;

        try
        {
            manifest = new Manifest(Files.newInputStream(file.getPath(JarFile.MANIFEST_NAME)));
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to get Manifest from mod JAR '%s'", fileName);
            return null;
        }

        return manifest;
    }
}
