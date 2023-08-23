package xfacthd.depextract.log;

import java.io.PrintWriter;

final class LogPrintWriter extends PrintWriter
{
    private final String levelColor;

    public LogPrintWriter(String levelColor)
    {
        super(System.out, true);
        this.levelColor = levelColor;
    }

    @Override
    public void println(Object x)
    {
        String s = String.valueOf(x);
        super.println(levelColor + s + "\033[0m");
    }
}
