package xfacthd.depextract.data.dependency;

public record DepResult(String installedVersion, boolean installed, boolean inRange, boolean valid)
{
}
