package xfacthd.depextract.data.mixin;

import java.util.Arrays;

public record Mixin(String name, MixinTarget[] targets, MixinInjection[] injections)
{
    public boolean isAccessor()
    {
        return Arrays.stream(injections).allMatch(MixinInjection::isAccessor);
    }
}
