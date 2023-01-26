package xfacthd.depextract.data.dependency;

import org.apache.maven.artifact.versioning.ArtifactVersion;

import java.util.List;

public record ModEntry(String fileName, String modId, String modName, ArtifactVersion version, List<Dependency> dependencies, boolean jij)
{
}
