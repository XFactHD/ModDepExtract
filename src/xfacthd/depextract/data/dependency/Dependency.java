package xfacthd.depextract.data.dependency;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import xfacthd.depextract.html.Html;
import xfacthd.depextract.html.HtmlWriter;

import java.util.Locale;

public record Dependency(String modId, VersionRange versionRange, Type type)
{
    public static final String UNBOUNDED_VERSION = " ";

    public boolean isVersionRangeSatisfied(ArtifactVersion version)
    {
        return versionRange != null && versionRange.containsVersion(version);
    }

    public String getVersionRangeString()
    {
        if (versionRange == null)
        {
            return Html.escape("<invalid>");
        }
        String stringified = versionRange.toString();
        if (stringified.equals(UNBOUNDED_VERSION))
        {
            return Html.escape("<any>");
        }
        return stringified;
    }

    public enum Type
    {
        REQUIRED("Required"),
        OPTIONAL("Optional"),
        DISCOURAGED("Discouraged"),
        INCOMPATIBLE("Incompatible");

        private final String name;

        Type(String name)
        {
            this.name = name;
        }

        public boolean isSatisfied(boolean installed, boolean inRange)
        {
            return switch (this)
            {
                case REQUIRED -> installed && inRange;
                case OPTIONAL -> !installed || inRange;
                case DISCOURAGED, INCOMPATIBLE -> !installed || !inRange;
            };
        }

        public void print(HtmlWriter cell, boolean hide)
        {
            if (hide)
            {
                cell.print("");
            }
            else
            {
                String color = String.format("style=\"color: %s;\"", switch (this)
                {
                    case REQUIRED, OPTIONAL -> Html.COLOR_GREEN;
                    case DISCOURAGED -> Html.COLOR_ORANGE;
                    case INCOMPATIBLE -> Html.COLOR_RED;
                });
                Html.element(cell, "span", color, name);
            }
        }

        public static Type parse(String name)
        {
            if (name == null)
            {
                return REQUIRED;
            }
            try
            {
                return valueOf(name.toUpperCase(Locale.ROOT));
            }
            catch (Exception e)
            {
                return null;
            }
        }
    }
}
