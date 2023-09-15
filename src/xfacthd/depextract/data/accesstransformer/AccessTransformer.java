package xfacthd.depextract.data.accesstransformer;

import xfacthd.depextract.html.HtmlWriter;
import xfacthd.depextract.util.Utils;

import java.util.*;

public record AccessTransformer(Target type, String modifier, String targetClass, String targetName, String targetDescriptor, boolean flagged)
{
    public String target()
    {
        return type.toString() + "_" + targetClass + "_" + targetName + "_" + targetDescriptor;
    }

    public void toHtml(HtmlWriter writer)
    {
        String member = null;
        String desc = null;
        if (type != Target.CLASS)
        {
            member = targetName;
            if (type != Target.FIELD)
            {
                desc = targetDescriptor;
            }
        }
        Utils.printDescriptor(writer, modifier, targetClass, member, desc);
    }

    public String prettyPrintTarget(boolean shortened)
    {
        String result = targetClass;
        if (shortened)
        {
            int lastDotIdx = targetClass.lastIndexOf('.');
            if (lastDotIdx > -1)
            {
                result = targetClass.substring(lastDotIdx + 1);
            }
        }
        if (type != Target.CLASS)
        {
            result += "#" + targetName;
            if (type != Target.FIELD)
            {
                result += shortened ? "()" : targetDescriptor;
            }
        }
        return result;
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
