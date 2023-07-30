package xfacthd.depextract.data.classfinder;

import java.util.ArrayList;
import java.util.List;

public record ContainingClass(String className, List<LocatedTarget> locatedTargets)
{
    public ContainingClass(String className)
    {
        this(className, new ArrayList<>());
    }
}
