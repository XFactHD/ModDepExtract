package xfacthd.depextract.data.mixin;

import java.util.HashMap;
import java.util.Map;

public record MixinTargetDescriptor(Map<String, Object> target)
{
    public MixinTargetDescriptor()
    {
        this(new HashMap<>());
    }

    public <T> void put(String key, T value)
    {
        target.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key)
    {
        return (T)target.get(key);
    }
}
