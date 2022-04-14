package xfacthd.depextract.util;

import com.google.gson.*;
import xfacthd.depextract.Main;

import java.io.IOException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public record MixinConfig(String name, String compatLevel, String plugin,
                          List<MixinEntry> mixins, List<MixinEntry> clientMixins, List<MixinEntry> serverMixins,
                          List<Mixin> resolvedMixins, List<Mixin> resolvedClientMixins, List<Mixin> resolvedServerMixins
)
{
    public int mixinCount() { return mixins.size() + clientMixins.size() + serverMixins.size(); }



    public static MixinConfig fromJson(String configName, JarFile modJar, JsonObject obj)
    {
        String mixinPackage = obj.get("package").getAsString();

        List<MixinEntry> mixins = new ArrayList<>();
        if (obj.has("mixins"))
        {
            JsonArray mixinArr = obj.getAsJsonArray("mixins");
            mixinArr.forEach(entry -> extractMixin(mixins, modJar, mixinPackage, entry));
        }

        List<MixinEntry> clientMixins = new ArrayList<>();
        if (obj.has("client"))
        {
            JsonArray mixinArr = obj.getAsJsonArray("client");
            mixinArr.forEach(entry -> extractMixin(clientMixins, modJar, mixinPackage, entry));
        }

        List<MixinEntry> serverMixins = new ArrayList<>();
        if (obj.has("server"))
        {
            JsonArray mixinArr = obj.getAsJsonArray("server");
            mixinArr.forEach(entry -> extractMixin(serverMixins, modJar, mixinPackage, entry));
        }

        return new MixinConfig(
                configName,
                obj.has("compatibilityLevel") ? obj.get("compatibilityLevel").getAsString() : "Unknown",
                obj.has("plugin") ? removePackage(obj.get("plugin").getAsString()) : "None",
                mixins,
                clientMixins,
                serverMixins,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>()
        );
    }

    private static void extractMixin(List<MixinEntry> mixins, JarFile modJar, String mixinPackage, JsonElement entry)
    {
        if (!entry.isJsonNull())
        {
            String classPath = entry.getAsString();
            String fullPath = mixinPackage + "." + classPath;

            mixins.add(new MixinEntry(
                    removePackage(classPath),
                    fullPath,
                    extractMixinClass(modJar, fullPath)
            ));
        }
    }

    private static final byte[] EMPTY_ARRAY = new byte[0];

    private static byte[] extractMixinClass(JarFile modJar, String classPath)
    {
        JarEntry entry = modJar.getJarEntry(classPath.replace('.', '/') + ".class");
        if (entry == null)
        {
            Main.LOG.error("Mixin class '%s' is missing from mod JAR '%s'", classPath, modJar.getName());
            return EMPTY_ARRAY;
        }

        try
        {
            return modJar.getInputStream(entry).readAllBytes();
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to read Mixin class '%s' from mod JAR '%s'", classPath, modJar.getName());
            return EMPTY_ARRAY;
        }
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
