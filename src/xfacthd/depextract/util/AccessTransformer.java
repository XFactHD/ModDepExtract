package xfacthd.depextract.util;

import xfacthd.depextract.html.Html;
import xfacthd.depextract.html.HtmlWriter;

import java.util.*;

public record AccessTransformer(Target type, String modifier, String targetClass, String targetName, String targetDescriptor, boolean flagged)
{
    private static final String COLOR_MODIFIER = "#cb7731";
    private static final String COLOR_CLASS = "#698650";
    private static final String COLOR_TARGET = "#9876aa";
    private static final String COLOR_PRIMITIVE = "#5390ba";
    private static final String COLOR_TYPE = "#ffc66d";

    public void toHtml(HtmlWriter writer)
    {
        Html.span(writer, style(COLOR_MODIFIER), modifier);
        Html.span(writer, style(COLOR_CLASS), targetClass);

        if (type != Target.CLASS)
        {
            writer.disableNewLine();

            Html.span(writer, style(COLOR_TARGET, true), Html.escape(targetName));

            if (type != Target.FIELD)
            {
                String descriptor = targetDescriptor + '\n';
                StringBuilder primitiveGroup = new StringBuilder();

                for (int i = 0; i < descriptor.length(); i++)
                {
                    char c = descriptor.charAt(i);
                    boolean array = false;
                    if (c == '[')
                    {
                        array = true;
                        c = descriptor.charAt(i + 1);
                    }

                    if ((c == 'L' || c == ')' || c == '\n') && primitiveGroup.length() > 0)
                    {
                        Html.span(writer, style(COLOR_PRIMITIVE), primitiveGroup.toString());
                        primitiveGroup = new StringBuilder();
                    }

                    if (c == 'L')
                    {
                        String type = descriptor.substring(i);
                        int typeEnd = type.indexOf(';');
                        type = type.substring(0, typeEnd + 1);
                        Html.span(writer, style(COLOR_TYPE, false), type);

                        i += typeEnd;
                    }
                    else if (c == '(' || c == ')')
                    {
                        writer.print(String.valueOf(c));
                    }
                    else if (c != '\n')
                    {
                        if (array)
                        {
                            primitiveGroup.append('[');
                        }
                        primitiveGroup.append(c);
                    }
                }
            }

            writer.enableNewLine();
        }
    }

    private static String style(String color) { return style(color, false); }

    private static String style(String color, boolean underline)
    {
        return String.format("style=\"color: %s;%s\"", color, underline ? " text-decoration: underline;" : "");
    }

    public static AccessTransformer parse(String line, List<String> flaggedATs)
    {
        int commentStart = line.indexOf('#');
        if (commentStart != -1)
        {
            line = line.substring(0, commentStart).trim();
        }

        List<String> parts = Arrays.asList(line.split(" "));

        Target type = Target.CLASS;
        String modifier = parts.get(0);
        String targetClass = parts.get(1);
        String targetName = "";
        String targetDescriptor = "";

        if (parts.size() > 2)
        {
            targetName = parts.get(2);
            targetDescriptor = "";

            int argListStart = targetName.indexOf('(');
            if (targetName.contains("("))
            {
                targetDescriptor = targetName.substring(argListStart);
                targetName = targetName.substring(0, argListStart);
            }

            type = targetDescriptor.isEmpty() ? Target.FIELD : Target.METHOD;
        }

        String shortName;
        if (type == Target.CLASS)
        {
            shortName = targetClass.substring(targetClass.lastIndexOf('.'));
        }
        else
        {
            shortName = targetName;
        }
        boolean flagged = flaggedATs.contains(shortName);

        return new AccessTransformer(type, modifier, targetClass, targetName, targetDescriptor, flagged);
    }

    public enum Target { CLASS, METHOD, FIELD }
}
