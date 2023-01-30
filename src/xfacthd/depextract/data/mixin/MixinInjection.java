package xfacthd.depextract.data.mixin;

import org.objectweb.asm.tree.MethodNode;

public record MixinInjection(MixinInjectionType type, String methodName, String methodDesc, MixinTargetDescriptor target)
{
    public MixinInjection(MixinInjectionType type, MethodNode mth, MixinTargetDescriptor target)
    {
        this(type, mth.name, mth.desc, target);
    }

    public boolean isAccessor()
    {
        return type == MixinInjectionType.ACCESSOR || type == MixinInjectionType.INVOKER;
    }
}
