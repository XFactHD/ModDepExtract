package xfacthd.depextract.html;

import java.util.function.Consumer;

public final class Css
{
    public static void declareSelector(HtmlWriter writer, String name, Consumer<HtmlWriter> styleWriter)
    {
        writer.print(String.format("%s {", name));
        writer.push();
        styleWriter.accept(writer);
        writer.pop();
        writer.print("}");
    }

    public static void property(HtmlWriter writer, String prop, String value)
    {
        writer.print(String.format("%s: %s;", prop, value));
    }

    public static void declareStickyHeader(HtmlWriter writer, boolean darkMode)
    {
        String shadowColor = darkMode ? "#FFFFFF" : "#000000";

        declareSelector(writer, "thead", style ->
        {
            property(style, "background-color", darkMode ? "#253041" : "#ddd");
            property(style, "position", "sticky");
            property(style, "top", "0");
            property(style, "z-index", "2");

            property(style, "box-shadow", String.format("inset -1px 0px %s", shadowColor));
        });

        declareSelector(writer, "thead th", style ->
            property(style, "box-shadow", String.format("inset 0px -1px %s, -1px 0px %s", shadowColor, shadowColor))
        );
    }
}
