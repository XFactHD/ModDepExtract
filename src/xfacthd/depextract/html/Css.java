package xfacthd.depextract.html;

import java.util.function.Consumer;

public final class Css
{
    public static void declareClass(HtmlWriter writer, String name, Consumer<HtmlWriter> styleWriter)
    {
        writer.print(String.format(".%s {", name));
        writer.push();
        styleWriter.accept(writer);
        writer.pop();
        writer.print("}");
    }

    public static void property(HtmlWriter writer, String prop, String value)
    {
        writer.print(String.format("%s: %s;", prop, value));
    }
}
