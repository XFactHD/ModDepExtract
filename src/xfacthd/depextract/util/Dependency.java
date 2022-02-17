package xfacthd.depextract.util;

import org.apache.maven.artifact.versioning.VersionRange;

public record Dependency(String modId, VersionRange versionRange, boolean mandatory)
{
}
