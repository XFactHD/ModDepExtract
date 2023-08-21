package xfacthd.depextract.html;

import com.google.common.base.Preconditions;

import java.io.*;

public class HtmlWriter
{
    private final StringWriter stringWriter = new StringWriter();
    private final PrintWriter writer = new PrintWriter(stringWriter);
    private final PrintWriter outWriter;
    private final boolean minify;
    private int indent = 0;
    private boolean newLine = true;

    public HtmlWriter(PrintWriter writer, boolean minify)
    {
        this.outWriter = writer;
        this.minify = minify;
    }

    public void print(String line)
    {
        if (newLine)
        {
            printIndent();
            writer.println(line);
        }
        else
        {
            writer.print(line);
        }
    }

    public void printMultiLine(String text)
    {
        Preconditions.checkState(newLine, "Multiline print is only available when newlines are enabled");
        for (String line : text.split("\n"))
        {
            if (minify)
            {
                line = line.trim();
                if (line.isEmpty())
                {
                    continue;
                }
            }

            printIndent();
            writer.println(line);
        }
    }

    public void println(String line) { print(line + "<br>"); }

    public void printIndent()
    {
        if (!minify)
        {
            for (int i = 0; i < indent * 4; i++)
            {
                writer.write(' ');
            }
        }
    }

    public void push() { indent++; }

    public void pop() { indent = Math.max(indent - 1, 0); }

    public void enableNewLine() { newLine = true; }

    public void disableNewLine() { newLine = false; }

    public void end()
    {
        writer.close();
        String contents = stringWriter.toString();
        if (minify)
        {
            contents = contents.replaceAll("( {2,})", " ");
            contents = contents.replaceAll("\r", "");
            contents = contents.replaceAll("(\n{2,})", "\n");
        }
        outWriter.write(contents);
    }
}
