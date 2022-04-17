package xfacthd.depextract.log;

import java.util.Locale;

public record Marker(String name, boolean active)
{
    public static final Marker NONE = new Marker("");

    public Marker(String name) { this(name, checkMarkerActive(name)); }

    public boolean isActive() { return active; }



    private static boolean checkMarkerActive(String marker)
    {
        if (marker.isEmpty()) { return true; } //Marker.NONE is always active

        String value = System.getProperty("depextract.log.marker." + marker.toLowerCase(Locale.ROOT));
        if (value == null) { return false; }
        return value.substring(value.indexOf('=') + 1).equals("allow");
    }
}
