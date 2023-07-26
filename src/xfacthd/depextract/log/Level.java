package xfacthd.depextract.log;

import java.util.Locale;

public enum Level
{
    TRACE("30"),
    DEBUG("36"),
    INFO("32"),
    WARNING("33"),
    ERROR("31"),
    FATAL("31");

    private final String ansiColor;

    Level(String ansiColor)
    {
        this.ansiColor = "\033[22;" + ansiColor + "m";
    }

    public boolean higherOrEqual(Level other)
    {
        return ordinal() >= other.ordinal();
    }

    public String getAnsiColor()
    {
        return ansiColor;
    }

    public static Level fromProperty(String prop)
    {
        String value = System.getProperty(prop);
        if (value == null)
        {
            return INFO;
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
