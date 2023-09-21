package xfacthd.depextract.data;

import java.nio.file.Path;

public record FileEntry(Path srcPath, Path filePath, JarInJarMeta jijMeta)
{
    public static FileEntry of(SourceAwarePath path)
    {
        return new FileEntry(path.srcPath(), path.filePath(), null);
    }
}
