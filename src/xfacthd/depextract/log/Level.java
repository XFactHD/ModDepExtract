package xfacthd.depextract.log;

import java.util.Locale;

public enum Level
{
    TRACE,
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    FATAL;

    public boolean higherOrEqual(Level other) { return ordinal() >= other.ordinal(); }

    public static Level fromProperty(String prop)
    {
        String value = System.getProperty(prop);
        if (value == null) { return INFO; }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
