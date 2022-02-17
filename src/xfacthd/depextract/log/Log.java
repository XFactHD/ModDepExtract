package xfacthd.depextract.log;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

public record Log(String name)
{
    private static final Level MIN_LEVEL = Level.fromProperty("depextract.log.min_level");
    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("ddLLL.yyyy HH:mm:ss.SSS");



    public void trace(String message) { log(Level.TRACE, message); }

    public void trace(String message, Object... params) { log(Level.TRACE, message, params); }

    public void trace(Marker marker, String message) { log(Level.TRACE, marker, message); }

    public void trace(Marker marker, String message, Object... params) { log(Level.TRACE, marker, message, params); }



    public void debug(String message) { log(Level.DEBUG, message); }

    public void debug(String message, Object... params) { log(Level.DEBUG, message, params); }

    public void debug(Marker marker, String message) { log(Level.DEBUG, marker, message); }

    public void debug(Marker marker, String message, Object... params) { log(Level.DEBUG, marker, message, params); }



    public void info(String message) { log(Level.INFO, message); }

    public void info(String message, Object... params) { log(Level.INFO, message, params); }

    public void info(Marker marker, String message) { log(Level.INFO, marker, message); }

    public void info(Marker marker, String message, Object... params) { log(Level.INFO, marker, message, params); }



    public void warning(String message) { log(Level.WARNING, message); }

    public void warning(String message, Object... params) { log(Level.WARNING, message, params); }

    public void warning(Marker marker, String message) { log(Level.WARNING, marker, message); }

    public void warning(Marker marker, String message, Object... params) { log(Level.WARNING, marker, message, params); }



    public void error(String message) { log(Level.ERROR, message); }

    public void error(String message, Object... params) { log(Level.ERROR, message, params); }

    public void error(Marker marker, String message) { log(Level.ERROR, marker, message); }

    public void error(Marker marker, String message, Object... params) { log(Level.ERROR, marker, message, params); }



    public void fatal(String message) { log(Level.FATAL, message); }

    public void fatal(String message, Object... params) { log(Level.FATAL, message, params); }

    public void fatal(Marker marker, String message) { log(Level.FATAL, marker, message); }

    public void fatal(Marker marker, String message, Object... params) { log(Level.FATAL, marker, message, params); }



    public void log(Level level, String message) { log(level, Marker.NONE, message); }

    public void log(Level level, String message, Object... params) { log(level, Marker.NONE, message, params); }

    public void log(Level level, Marker marker, String message, Object... params) { log(level, marker, String.format(Locale.ROOT, message, params)); }

    public void log(Level level, Marker marker, String message)
    {
        if (!level.higherOrEqual(MIN_LEVEL)) { return; }

        String time = ZonedDateTime.now().format(DT_FORMAT);
        System.out.printf("[%s] [%s/%s] [%s/%s]: %s\n", time, name, level, findCaller(), marker.name(), message);
    }



    private static String findCaller()
    {
        StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        Optional<StackWalker.StackFrame> optFrame = walker.walk(stream -> stream.filter(Log::isNotLogFrame).findFirst());
        return optFrame.map(StackWalker.StackFrame::getClassName).orElse("UNKNOWN");
    }

    private static final String LOGGER_CLASS = Log.class.getName();
    private static boolean isNotLogFrame(StackWalker.StackFrame frame)
    {
        return !frame.getClassName().equals(LOGGER_CLASS);
    }
}
