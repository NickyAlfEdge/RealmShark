package tomato.realmshark;

import util.Util;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Crash logger, storing crash data to file.
 */
public class CrashLogger {

    /**
     * Logger for crashes captured by the exception handler.
     *
     * @param error Error to be logged.
     */
    public static void printCrash(Exception error) {
        Util.printLogs("Main crash:");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        error.printStackTrace(pw);
        Util.printLogs(sw.toString());
        // Also write to a stable on-disk log so double-clicked (javaw.exe)
        // launches — which silently discard System.err — leave a trace users
        // and developers can inspect.
        writeToDiskLog("Main crash", error);
    }

    /**
     * Class loader method in java. Loading class here in case of stackoverflow errors.
     */
    public static void loadThisClass() {
    }

    /**
     * Install a JVM-wide uncaught-exception handler that logs to disk. This
     * is critical on Windows when the jar is launched via file association
     * (double-click) because Windows uses {@code javaw.exe}, which discards
     * {@code stdout}/{@code stderr}. Without this, an unhandled exception
     * on the packet-capture thread (or any other background thread) dies
     * silently and the user just sees "the app runs but no DPS / quest data
     * shows up" — while running from {@code java -jar} in a console prints
     * the stack trace and works around the problem.
     *
     * Safe to call multiple times; only the first call installs the handler.
     */
    public static synchronized void installGlobalUncaughtHandler() {
        if (installed) return;
        installed = true;
        Thread.UncaughtExceptionHandler handler = (t, e) -> {
            try {
                Util.printLogs("Uncaught exception on thread \"" + t.getName() + "\":");
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                Util.printLogs(sw.toString());
            } catch (Throwable ignored) {
            }
            writeToDiskLog("Uncaught on thread \"" + t.getName() + "\"", e);
        };
        Thread.setDefaultUncaughtExceptionHandler(handler);
        // Route AWT / Swing EDT dispatch failures to the same sink. The AWT
        // handler is picked up by the EDT the next time it dispatches after
        // this property is set, so setting it early in main() is sufficient.
        System.setProperty("sun.awt.exception.handler", AwtHandler.class.getName());
    }

    /** Referenced by name via the {@code sun.awt.exception.handler} property. */
    public static class AwtHandler {
        public void handle(Throwable t) {
            try {
                Util.printLogs("Uncaught EDT exception:");
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                Util.printLogs(sw.toString());
            } catch (Throwable ignored) {
            }
            writeToDiskLog("Uncaught on EDT", t);
        }
    }

    private static volatile boolean installed = false;

    /**
     * Append a timestamped stack trace to {@code tomato-crash.log} next to
     * the running jar (or next to the {@code classes} dir when running from
     * an IDE). Any I/O error is swallowed — we cannot afford to throw from
     * the crash logger.
     */
    private static void writeToDiskLog(String context, Throwable error) {
        try {
            File dir = resolveLogDirectory();
            if (dir == null) return;
            File out = new File(dir, "tomato-crash.log");
            try (FileWriter fw = new FileWriter(out, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.println("---- " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
                    + " " + context + " ----");
                if (error != null) error.printStackTrace(pw);
                pw.println();
            }
        } catch (Throwable ignored) {
            // Best-effort; nothing we can do if the disk is unwritable.
        }
    }

    /**
     * Locate a writable directory for the crash log. Prefer the folder
     * containing the running jar; fall back to the JVM working directory
     * and finally to {@code java.io.tmpdir}.
     */
    private static File resolveLogDirectory() {
        try {
            URL src = CrashLogger.class.getProtectionDomain().getCodeSource().getLocation();
            if (src != null) {
                File f = new File(src.toURI());
                if (f.isFile()) f = f.getParentFile(); // .jar → parent dir
                if (f != null && f.isDirectory() && (f.canWrite() || !f.exists())) return f;
            }
        } catch (URISyntaxException | IllegalArgumentException | SecurityException ignored) {
        }
        File cwd = new File(".").getAbsoluteFile();
        if (cwd.isDirectory() && cwd.canWrite()) return cwd;
        String tmp = System.getProperty("java.io.tmpdir");
        return tmp == null ? null : new File(tmp);
    }
}
