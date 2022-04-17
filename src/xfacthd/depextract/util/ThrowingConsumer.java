package xfacthd.depextract.util;

@FunctionalInterface
public interface ThrowingConsumer<T, X extends Throwable>
{
    void accept(T t) throws X;
}
