package xfacthd.depextract.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public record MixinConfig(String name, String minVersion, String compatLevel, String plugin, List<String> mixins, List<String> clientMixins, List<String> serverMixins)
{
    public int mixinCount() { return mixins.size() + clientMixins().size() + serverMixins().size(); }



    public static MixinConfig fromJson(String configName, JsonObject obj)
    {
        List<String> mixins = new ArrayList<>();
        if (obj.has("mixins"))
        {
            JsonArray mixinArr = obj.getAsJsonArray("mixins");
            mixinArr.forEach(entry -> mixins.add(removePackage(entry.getAsString())));
        }

        List<String> clientMixins = new ArrayList<>();
        if (obj.has("client"))
        {
            JsonArray mixinArr = obj.getAsJsonArray("client");
            mixinArr.forEach(entry -> clientMixins.add(removePackage(entry.getAsString())));
        }

        List<String> serverMixins = new ArrayList<>();
        if (obj.has("server"))
        {
            JsonArray mixinArr = obj.getAsJsonArray("server");
            mixinArr.forEach(entry -> serverMixins.add(removePackage(entry.getAsString())));
        }

        return new MixinConfig(
                configName,
                obj.has("minVersion") ? obj.get("minVersion").getAsString() : "Unknown",
                obj.has("compatibilityLevel") ? obj.get("compatibilityLevel").getAsString() : "Unknown",
                obj.has("plugin") ? removePackage(obj.get("plugin").getAsString()) : "None",
                mixins,
                clientMixins,
                serverMixins
        );
    }

    private static String removePackage(String name)
    {
        int lastDot = name.lastIndexOf('.');
        if (lastDot != -1)
        {
            return name.substring(lastDot + 1);
        }
        return name;
    }
}
