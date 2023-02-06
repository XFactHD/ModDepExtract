package xfacthd.depextract.data.dependency;

import org.apache.maven.artifact.versioning.ArtifactVersion;

import java.nio.file.Path;
import java.util.List;

public record ModEntry(
        String fileName,
        String modId,
        String modName,
        ArtifactVersion version,
        List<Dependency> dependencies,
        String modType,
        boolean jij,
        Path fileSource
)
{
}
