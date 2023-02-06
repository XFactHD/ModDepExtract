package xfacthd.depextract.data;

import java.nio.file.Path;

public record FileEntry(Path path, JarInJarMeta jijMeta)
{
    public static FileEntry of(Path path)
    {
        return new FileEntry(path, null);
    }
}
