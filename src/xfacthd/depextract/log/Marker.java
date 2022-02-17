package xfacthd.depextract.log;

public record Marker(String name)
{
    public static final Marker NONE = new Marker("");
}
