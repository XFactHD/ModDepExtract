package xfacthd.depextract.data.mixin;

import com.google.gson.*;
import xfacthd.depextract.Main;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.StreamSupport;

public record MixinConfig(
        String name, String compatLevel, String plugin,
        List<MixinEntry> mixins, List<MixinEntry> clientMixins, List<MixinEntry> serverMixins,
        List<Mixin> resolvedMixins, List<Mixin> resolvedClientMixins, List<Mixin> resolvedServerMixins
)
{
    private MixinConfig(
            String name, String compatLevel, String plugin, List<MixinEntry> mixins,
            List<MixinEntry> clientMixins, List<MixinEntry> serverMixins
    )
    {
        this(name, compatLevel, plugin, mixins, clientMixins, serverMixins, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public int mixinCount() { return mixins.size() + clientMixins.size() + serverMixins.size(); }



    public static MixinConfig fromJson(String configName, JarFile modJar, JsonObject obj)
    {
        String mixinPackage = obj.get("package").getAsString();

        List<MixinEntry> mixins = new ArrayList<>();
        if (obj.has("mixins"))
        {
            JsonArray mixinArr = obj.getAsJsonArray("mixins");
            streamDistinctMixins(mixinArr, entry -> extractMixin(mixins, modJar, mixinPackage, entry));
        }

        List<MixinEntry> clientMixins = new ArrayList<>();
        if (obj.has("client"))
        {
            JsonArray mixinArr = obj.getAsJsonArray("client");
            streamDistinctMixins(mixinArr, entry -> extractMixin(clientMixins, modJar, mixinPackage, entry));
        }

        List<MixinEntry> serverMixins = new ArrayList<>();
        if (obj.has("server"))
        {
            JsonArray mixinArr = obj.getAsJsonArray("server");
            streamDistinctMixins(mixinArr, entry -> extractMixin(serverMixins, modJar, mixinPackage, entry));
        }

        return new MixinConfig(
                configName,
                obj.has("compatibilityLevel") ? obj.get("compatibilityLevel").getAsString() : "Unknown",
                obj.has("plugin") ? removePackage(obj.get("plugin").getAsString()) : "None",
                mixins,
                clientMixins,
                serverMixins
        );
    }

    private static void streamDistinctMixins(JsonArray mixins, Consumer<JsonElement> consumer)
    {
        // Guard against Mixin configs listing a Mixin multiple times
        StreamSupport.stream(mixins.spliterator(), false).distinct().forEach(consumer);
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
            InputStream stream = modJar.getInputStream(entry);
            byte[] result = stream.readAllBytes();
            stream.close();
            return result;
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
