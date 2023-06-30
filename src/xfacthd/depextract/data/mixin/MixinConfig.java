package xfacthd.depextract.data.mixin;

import com.google.gson.*;
import xfacthd.depextract.Main;
import xfacthd.depextract.util.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;

public record MixinConfig(
        String name, String compatLevel, String plugin,
        List<MixinEntry> mixins, List<MixinEntry> clientMixins, List<MixinEntry> serverMixins,
        List<Mixin> resolvedMixins, List<Mixin> resolvedClientMixins, List<Mixin> resolvedServerMixins,
        List<Mixin> resolvedMixinsNoAccessor, List<Mixin> resolvedClientMixinsNoAccessor, List<Mixin> resolvedServerMixinsNoAccessor
)
{
    private MixinConfig(
            String name, String compatLevel, String plugin, List<MixinEntry> mixins,
            List<MixinEntry> clientMixins, List<MixinEntry> serverMixins
    )
    {
        this(name, compatLevel, plugin, mixins, clientMixins, serverMixins, new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()
        );
    }

    public void filterAccessors()
    {
        resolvedMixins.stream().filter(mixin -> !mixin.isAccessor()).forEach(resolvedMixinsNoAccessor::add);
        resolvedClientMixins.stream().filter(mixin -> !mixin.isAccessor()).forEach(resolvedClientMixinsNoAccessor::add);
        resolvedServerMixins.stream().filter(mixin -> !mixin.isAccessor()).forEach(resolvedServerMixinsNoAccessor::add);
    }

    public List<Mixin> resolvedMixins(boolean filterAccessors)
    {
        return filterAccessors ? resolvedMixinsNoAccessor : resolvedMixins;
    }

    public List<Mixin> resolvedClientMixins(boolean filterAccessors)
    {
        return filterAccessors ? resolvedClientMixinsNoAccessor : resolvedClientMixins;
    }

    public List<Mixin> resolvedServerMixins(boolean filterAccessors)
    {
        return filterAccessors ? resolvedServerMixinsNoAccessor : resolvedServerMixins;
    }

    public int mixinCount() { return mixins.size() + clientMixins.size() + serverMixins.size(); }

    public List<Mixin> allMixins()
    {
        List<Mixin> allMixins = new ArrayList<>(resolvedMixins.size() + resolvedClientMixins.size() + resolvedServerMixins.size());
        allMixins.addAll(resolvedMixins);
        allMixins.addAll(resolvedClientMixins);
        allMixins.addAll(resolvedServerMixins);
        return allMixins;
    }



    public static MixinConfig fromJson(String fileName, String configName, FileSystem modJar, JsonObject obj)
    {
        String mixinPackage = obj.has("package") ? obj.get("package").getAsString() : null;
        if (mixinPackage == null)
        {
            Main.LOG.warning("Mixin config '%s' in mod JAR '%s' does not declare a Mixin package", configName, fileName);
        }

        List<MixinEntry> mixins = new ArrayList<>();
        if (obj.has("mixins"))
        {
            JsonArray mixinArr = obj.getAsJsonArray("mixins");
            streamDistinctMixins(mixinArr, entry -> extractMixin(fileName, mixins, modJar, mixinPackage, entry));
        }

        List<MixinEntry> clientMixins = new ArrayList<>();
        if (obj.has("client"))
        {
            JsonArray mixinArr = obj.getAsJsonArray("client");
            streamDistinctMixins(mixinArr, entry -> extractMixin(fileName, clientMixins, modJar, mixinPackage, entry));
        }

        List<MixinEntry> serverMixins = new ArrayList<>();
        if (obj.has("server"))
        {
            JsonArray mixinArr = obj.getAsJsonArray("server");
            streamDistinctMixins(mixinArr, entry -> extractMixin(fileName, serverMixins, modJar, mixinPackage, entry));
        }

        return new MixinConfig(
                configName,
                obj.has("compatibilityLevel") ? obj.get("compatibilityLevel").getAsString() : "Unknown",
                obj.has("plugin") ? Utils.removePackage(obj.get("plugin").getAsString()) : "None",
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

    private static void extractMixin(String fileName, List<MixinEntry> mixins, FileSystem modJar, String mixinPackage, JsonElement entry)
    {
        if (!entry.isJsonNull())
        {
            String classPath = entry.getAsString();
            String fullPath = mixinPackage == null ? classPath : (mixinPackage + "." + classPath);

            mixins.add(new MixinEntry(
                    Utils.removePackage(classPath),
                    fullPath,
                    extractMixinClass(fileName, modJar, fullPath)
            ));
        }
    }

    private static final byte[] EMPTY_ARRAY = new byte[0];

    private static byte[] extractMixinClass(String fileName, FileSystem modJar, String classPath)
    {
        Path entry = modJar.getPath(classPath.replace('.', '/') + ".class");
        if (!Files.exists(entry))
        {
            Main.LOG.error("Mixin class '%s' is missing from mod JAR '%s'", classPath, fileName);
            return EMPTY_ARRAY;
        }

        try
        {
            InputStream stream = Files.newInputStream(entry);
            byte[] result = stream.readAllBytes();
            stream.close();
            return result;
        }
        catch (IOException e)
        {
            Main.LOG.error("Failed to read Mixin class '%s' from mod JAR '%s'", classPath, fileName);
            return EMPTY_ARRAY;
        }
    }
}
