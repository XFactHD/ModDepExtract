package xfacthd.depextract.util;

public record DepResult(String installedVersion, boolean installed, boolean inRange, boolean valid)
{
}
