package xfacthd.depextract.data.mixin;

import org.objectweb.asm.tree.MethodNode;

import java.util.Map;

public record MixinInjection(MixinInjectionType type, String methodName, String methodDesc, Map<String, String> target)
{
    public MixinInjection(MixinInjectionType type, MethodNode mth, Map<String, String> target)
    {
        this(type, mth.name, mth.desc, target);
    }

    public boolean isAccessor()
    {
        return type == MixinInjectionType.ACCESSOR || type == MixinInjectionType.INVOKER;
    }
}
