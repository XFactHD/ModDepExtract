package xfacthd.depextract.data;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

public record JarInJarMeta(String group, String artifact, VersionRange range, ArtifactVersion version, boolean obfuscated)
{

}
