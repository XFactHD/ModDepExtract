package xfacthd.depextract.html;

import java.io.PrintWriter;
import java.util.function.Consumer;

public final class Html
{
    public static final String COLOR_RED = "#f85149";
    public static final String COLOR_GREEN = "#2ea043";

    public static void html(PrintWriter writer, String bodyAttribs, Consumer<HtmlWriter> headWriter, Consumer<HtmlWriter> bodyWriter)
    {
        HtmlWriter htmlWriter = new HtmlWriter(writer);

        htmlWriter.print("<!DOCTYPE html>");
        element(htmlWriter, "html", "lang=\"en\"", html ->
        {
            element(html, "head", "", headWriter);
            element(html, "body", bodyAttribs, bodyWriter);
        });
    }

    public static void element(HtmlWriter writer, String type, String attribs, String content)
    {
        element(writer, type, attribs, contentWriter -> contentWriter.print(content));
    }

    public static void element(HtmlWriter writer, String type, String attribs, String content, boolean needsClosingTag)
    {
        element(writer, type, attribs, contentWriter -> contentWriter.print(content), needsClosingTag);
    }

    public static void element(HtmlWriter writer, String type, String attribs, Consumer<HtmlWriter> contentWriter) {
        element(writer, type, attribs, contentWriter, true);
    }

    public static void element(HtmlWriter writer, String type, String attribs, Consumer<HtmlWriter> contentWriter, boolean needsClosingTag)
    {
        if (attribs != null && !attribs.isEmpty())
        {
            writer.print(String.format("<%s %s>", type, attribs));
        }
        else
        {
            writer.print(String.format("<%s>", type));
        }

        writer.push();
        contentWriter.accept(writer);
        writer.pop();

        if (needsClosingTag)
            writer.print(String.format("</%s>", type));
    }

    public static void style(HtmlWriter writer, Consumer<HtmlWriter> styleWriter) { element(writer, "style", "", styleWriter); }

    public static void div(HtmlWriter writer, String attribs, String content) { element(writer, "div", attribs, content); }

    public static void div(HtmlWriter writer, String attribs, Consumer<HtmlWriter> contentWriter) { element(writer, "div", attribs, contentWriter); }

    public static void span(HtmlWriter writer, String attribs, String content) { element(writer, "span", attribs, content); }

    public static void span(HtmlWriter writer, String attribs, Consumer<HtmlWriter> contentWriter) { element(writer, "span", attribs, contentWriter); }

    public static void table(HtmlWriter writer, String attribs, Consumer<HtmlWriter> headWriter, Consumer<HtmlWriter> bodyWriter)
    {
        element(writer, "table", attribs, table ->
        {
            element(table, "thead", "", headWriter);
            element(table, "tbody", "", bodyWriter);
        });
    }

    public static void tableRow(HtmlWriter writer, String attribs, String content) { element(writer, "tr", attribs, content); }

    public static void tableRow(HtmlWriter writer, String attribs, Consumer<HtmlWriter> contentWriter) { element(writer, "tr", attribs, contentWriter); }

    public static void tableHeader(HtmlWriter writer, String attribs, String content) { element(writer, "th", attribs, content); }

    public static void tableHeader(HtmlWriter writer, String attribs, Consumer<HtmlWriter> contentWriter) { element(writer, "th", attribs, contentWriter); }

    public static void tableCell(HtmlWriter writer, String attribs, String content) { element(writer, "td", attribs, content); }

    public static void tableCell(HtmlWriter writer, String attribs, Consumer<HtmlWriter> contentWriter) { element(writer, "td", attribs, contentWriter); }

    public static void abbreviation(HtmlWriter writer, String title, String content)
    {
        abbreviation(writer, title, contentWriter -> contentWriter.print(content));
    }

    public static void abbreviation(HtmlWriter writer, String title, Consumer<HtmlWriter> contentWriter)
    {
        String attribs = String.format("title=\"%s\"", title);
        element(writer, "abbr", attribs, contentWriter);
    }

    public static void unorderedList(HtmlWriter writer, Consumer<HtmlWriter> contentWriter)
    {
        unorderedList(writer, "", contentWriter);
    }

    public static void unorderedList(HtmlWriter writer, String attribs, Consumer<HtmlWriter> contentWriter)
    {
        element(writer, "ul", attribs, contentWriter);
    }

    public static void orderedList(HtmlWriter writer, Consumer<HtmlWriter> contentWriter)
    {
        orderedList(writer, "", contentWriter);
    }

    public static void orderedList(HtmlWriter writer, String attribs, Consumer<HtmlWriter> contentWriter)
    {
        element(writer, "ol", attribs, contentWriter);
    }

    public static void listEntry(HtmlWriter writer, String content)
    {
        listEntry(writer, "", content);
    }

    public static void listEntry(HtmlWriter writer, Consumer<HtmlWriter> contentWriter)
    {
        listEntry(writer, "", contentWriter);
    }

    public static void listEntry(HtmlWriter writer, String attribs, String content)
    {
        listEntry(writer, attribs, contentWriter -> contentWriter.print(content));
    }

    public static void listEntry(HtmlWriter writer, String attribs, Consumer<HtmlWriter> contentWriter)
    {
        element(writer, "li", attribs, contentWriter);
    }



    public static void writeBoolean(HtmlWriter writer, String attribs, boolean value)
    {
        attribs = appendAttribs(attribs, getBoolColor(value));
        element(writer, "span", attribs, value ? "true" : "false");
    }

    public static String getBoolColor(boolean value)
    {
        return String.format("style=\"color: %s;\"", value ? COLOR_GREEN : COLOR_RED);
    }

    public static String escape(String text)
    {
        return text.replace("<", "&lt;")
                   .replace(">", "&gt;");
    }

    public static String appendAttribs(String attribs, String append)
    {
        if (!attribs.isEmpty()) { attribs += " "; }
        return attribs + append;
    }



    private Html() {}
}
