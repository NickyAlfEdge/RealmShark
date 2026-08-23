package tomato.gui.dps;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * macOS-only helper that promotes a Swing window (identified by its title) to
 * a floating panel that appears above every other application AND on every
 * Space — including the private Space owned by another app in native
 * "Full Screen" mode (e.g. RotMG Exalt with the green button).
 *
 * Pure Java on macOS cannot do this via the public API. We use JNA to call
 * libobjc and adjust the NSWindow's collectionBehavior + level.
 *
 * IMPORTANT: NSWindow geometry/state mutations must happen on Cocoa's main
 * thread. Swing's EDT is NOT the AppKit main thread on macOS, so calling
 * {@code [NSWindow setCollectionBehavior:]} from the EDT trips an
 * NSInternalInconsistencyException. We use {@code libdispatch}
 * ({@code dispatch_async_f} onto {@code _dispatch_main_q}) to hop the work
 * onto the AppKit main thread.
 *
 * Applied flags:
 *   collectionBehavior = CanJoinAllSpaces | FullScreenAuxiliary
 *                      | Transient       | IgnoresCycle
 *   level              = NSStatusWindowLevel (25)
 *
 * All calls are best-effort — if JNA can't load libobjc/libdispatch, if the
 * JDK blocks reflective access, or if the platform isn't macOS, calls
 * silently no-op.
 */
final class MacOSOverlayHelper {

    private static final long NSWindowCollectionBehaviorCanJoinAllSpaces    = 1L << 0;
    private static final long NSWindowCollectionBehaviorTransient           = 1L << 3;
    private static final long NSWindowCollectionBehaviorStationary          = 1L << 4;
    private static final long NSWindowCollectionBehaviorIgnoresCycle        = 1L << 6;
    private static final long NSWindowCollectionBehaviorFullScreenAuxiliary = 1L << 8;

    // NSScreenSaverWindowLevel — highest practical window level for a gaming
    // overlay: sits above every normal window (including fullscreen game
    // chrome) and only screen-saver-level windows sit above.
    private static final long NS_OVERLAY_WINDOW_LEVEL = 1000L;

    private static final boolean IS_MAC =
        System.getProperty("os.name", "").toLowerCase().contains("mac");

    private static final Object LOCK = new Object();
    private static boolean triedLoad = false;

    private static Function objc_getClass;
    private static Function sel_registerName;
    private static Function msgSend;

    private static Function dispatch_async_f;
    private static Pointer mainQueue;

    // Retain callbacks until libdispatch invokes them, otherwise JNA GCs the
    // trampoline and the native call crashes.
    private static final Set<MainThreadTask> pendingTasks =
        Collections.synchronizedSet(new HashSet<>());

    private MacOSOverlayHelper() { }

    /**
     * Activate a macOS application whose name / bundle matches one of
     * {@code candidates}, ONLY IF an instance is already running. If no
     * matching process is currently running this returns {@code false} — we
     * deliberately do NOT fall back to {@code open -a <name>}, because
     * LaunchServices would happily launch a fresh copy of RotMG Exalt in
     * that case, which is not what the DPS chat button should ever do.
     *
     * Strategy:
     *   1. Scan {@code ps -eo command} for a process whose executable path
     *      contains any candidate substring (case-insensitive).
     *   2. If found and the path lives inside a {@code .app} bundle, call
     *      {@code open -a "<full/path/to.app>"} — with a full path this
     *      activates the already-running instance instead of launching a
     *      new one.
     *   3. As a secondary path, try {@code osascript} + System Events on
     *      the running process's short name; this only touches running
     *      processes (never launches).
     *   4. If none of the above succeed, return {@code false}. The caller
     *      ({@link DpsChatSender#sendToGameChat}) then leaves the message
     *      on the clipboard as a fallback.
     */
    static boolean activateAnyApp(String[] candidates) {
        if (!IS_MAC || candidates == null || candidates.length == 0) return false;

        String runningPath = findRunningExecutablePath(candidates);
        if (runningPath == null) return false; // not running → do not launch

        String appBundle = enclosingDotAppPath(runningPath);
        if (appBundle != null && runProcess(1500, "/usr/bin/open", "-a", appBundle)) {
            return true;
        }

        // Secondary: System Events targeting a running process name. This
        // path DOES require the Automation permission the first time and
        // will silently fail without it, but it CANNOT launch a fresh copy
        // (the "first process whose name is X" clause fails if X isn't
        // already running).
        String procName = shortProcessName(runningPath);
        if (procName != null && isSafeAppleScriptLiteral(procName)) {
            String script = "tell application \"System Events\" to set frontmost of (first process whose name is \""
                + procName + "\") to true";
            if (runProcess(1500, "/usr/bin/osascript", "-e", script)) return true;
        }

        return false;
    }

    private static boolean isSafeAppleScriptLiteral(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20) return false;
        }
        return true;
    }

    /** Basename of the running executable path, without extension. */
    private static String shortProcessName(String executablePath) {
        if (executablePath == null) return null;
        int slash = executablePath.lastIndexOf('/');
        String base = slash < 0 ? executablePath : executablePath.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    /** Runs {@code ps -eo command} and returns the first line matching any candidate. */
    private static String findRunningExecutablePath(String[] candidates) {
        try {
            Process p = new ProcessBuilder("/bin/ps", "-eo", "command").redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line;
            String selfCmd = System.getProperty("java.command", "");
            while ((line = r.readLine()) != null) {
                String lower = line.toLowerCase();
                // Skip lines that are clearly this JVM to avoid activating ourselves.
                if (!selfCmd.isEmpty() && line.contains(selfCmd)) continue;
                if (lower.contains("tomato")) continue;
                for (String cand : candidates) {
                    if (cand == null || cand.isEmpty()) continue;
                    if (lower.contains(cand.toLowerCase())) {
                        p.destroyForcibly();
                        // ps prints "command args..." — first token is the executable path.
                        int sp = line.indexOf(' ');
                        return sp < 0 ? line : line.substring(0, sp);
                    }
                }
            }
            p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Walk up the path looking for the enclosing {@code .app} directory. */
    private static String enclosingDotAppPath(String executablePath) {
        if (executablePath == null) return null;
        int idx = executablePath.toLowerCase().lastIndexOf(".app/");
        if (idx < 0) {
            if (executablePath.toLowerCase().endsWith(".app")) return executablePath;
            return null;
        }
        return executablePath.substring(0, idx + 4);
    }

    private static boolean runProcess(long timeoutMs, String... argv) {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Idempotently promote every NSWindow whose title matches {@code title} to
     * an all-Spaces floating panel. Returns true if the work was submitted;
     * the actual promotion happens asynchronously on the AppKit main thread.
     */
    static boolean promoteToAllSpacesFloating(final String title) {
        if (!IS_MAC || title == null) return false;
        if (!ensureLoaded()) return false;

        final MainThreadTask task = new MainThreadTask() {
            @Override
            public void invoke(Pointer context) {
                try {
                    doPromoteOnMainThread(title);
                } catch (Throwable ignored) {
                } finally {
                    pendingTasks.remove(this);
                }
            }
        };
        pendingTasks.add(task);

        try {
            dispatch_async_f.invoke(void.class, new Object[]{ mainQueue, Pointer.NULL, task });
            return true;
        } catch (Throwable t) {
            pendingTasks.remove(task);
            return false;
        }
    }

    /** Runs on Cocoa's main thread — safe to touch NSWindow properties. */
    private static void doPromoteOnMainThread(String title) {
        Pointer selSharedApp        = registerSel("sharedApplication");
        Pointer selWindows          = registerSel("windows");
        Pointer selCount            = registerSel("count");
        Pointer selObjAtIdx         = registerSel("objectAtIndex:");
        Pointer selTitle            = registerSel("title");
        Pointer selUTF8String       = registerSel("UTF8String");
        Pointer selSetCollBeh       = registerSel("setCollectionBehavior:");
        Pointer selSetLevel         = registerSel("setLevel:");
        Pointer selSetHidesOnDeact  = registerSel("setHidesOnDeactivate:");

        Pointer nsappCls = getClassPtr("NSApplication");
        if (nsappCls == null || nsappCls == Pointer.NULL) return;

        Pointer nsApp = sendPtr(nsappCls, selSharedApp);
        if (nsApp == null || nsApp == Pointer.NULL) return;

        Pointer windowsArr = sendPtr(nsApp, selWindows);
        if (windowsArr == null || windowsArr == Pointer.NULL) return;

        long count = sendLong(windowsArr, selCount);
        long behavior = NSWindowCollectionBehaviorCanJoinAllSpaces
                      | NSWindowCollectionBehaviorFullScreenAuxiliary
                      | NSWindowCollectionBehaviorStationary
                      | NSWindowCollectionBehaviorTransient
                      | NSWindowCollectionBehaviorIgnoresCycle;

        for (long i = 0; i < count; i++) {
            Pointer win = sendPtrLong(windowsArr, selObjAtIdx, i);
            if (win == null || win == Pointer.NULL) continue;
            if (!windowTitleMatches(win, selTitle, selUTF8String, title)) continue;

            sendVoidLong(win, selSetCollBeh, behavior);
            sendVoidLong(win, selSetLevel, NS_OVERLAY_WINDOW_LEVEL);
            // NSWindow default is NO but persistent panels sometimes default YES;
            // explicitly clear so we don't vanish when the game gets focus.
            sendVoidLong(win, selSetHidesOnDeact, 0L);
        }
    }

    /**
     * Toggle click-through: when {@code ignore} is true, mouse events pass through
     * to whatever is behind the overlay (i.e. the game), so the user can shoot
     * "through" the overlay panel without losing game focus.
     */
    static boolean setIgnoresMouseEvents(final String title, final boolean ignore) {
        if (!IS_MAC || title == null) return false;
        if (!ensureLoaded()) return false;

        final MainThreadTask task = new MainThreadTask() {
            @Override
            public void invoke(Pointer context) {
                try {
                    doSetIgnoresMouseEventsOnMainThread(title, ignore);
                } catch (Throwable ignored) {
                } finally {
                    pendingTasks.remove(this);
                }
            }
        };
        pendingTasks.add(task);
        try {
            dispatch_async_f.invoke(void.class, new Object[]{ mainQueue, Pointer.NULL, task });
            return true;
        } catch (Throwable t) {
            pendingTasks.remove(task);
            return false;
        }
    }

    private static void doSetIgnoresMouseEventsOnMainThread(String title, boolean ignore) {
        Pointer selSharedApp   = registerSel("sharedApplication");
        Pointer selWindows     = registerSel("windows");
        Pointer selCount       = registerSel("count");
        Pointer selObjAtIdx    = registerSel("objectAtIndex:");
        Pointer selTitle       = registerSel("title");
        Pointer selUTF8String  = registerSel("UTF8String");
        Pointer selSetIgnores  = registerSel("setIgnoresMouseEvents:");

        Pointer nsappCls = getClassPtr("NSApplication");
        if (nsappCls == null || nsappCls == Pointer.NULL) return;
        Pointer nsApp = sendPtr(nsappCls, selSharedApp);
        if (nsApp == null || nsApp == Pointer.NULL) return;
        Pointer windowsArr = sendPtr(nsApp, selWindows);
        if (windowsArr == null || windowsArr == Pointer.NULL) return;

        long count = sendLong(windowsArr, selCount);
        for (long i = 0; i < count; i++) {
            Pointer win = sendPtrLong(windowsArr, selObjAtIdx, i);
            if (win == null || win == Pointer.NULL) continue;
            if (!windowTitleMatches(win, selTitle, selUTF8String, title)) continue;
            sendVoidLong(win, selSetIgnores, ignore ? 1L : 0L);
        }
    }

    /**
     * Raise the matching NSWindow above other windows at the same level without
     * changing its level. Unlike Java's {@link java.awt.Window#toFront()} — which
     * resets the NSWindow level back to Java's default and clobbers our
     * screen-saver-level promotion — this preserves the promoted level.
     */
    static boolean orderFrontRegardless(final String title) {
        if (!IS_MAC || title == null) return false;
        if (!ensureLoaded()) return false;

        final MainThreadTask task = new MainThreadTask() {
            @Override
            public void invoke(Pointer context) {
                try {
                    doOrderFrontRegardlessOnMainThread(title);
                } catch (Throwable ignored) {
                } finally {
                    pendingTasks.remove(this);
                }
            }
        };
        pendingTasks.add(task);
        try {
            dispatch_async_f.invoke(void.class, new Object[]{ mainQueue, Pointer.NULL, task });
            return true;
        } catch (Throwable t) {
            pendingTasks.remove(task);
            return false;
        }
    }

    private static void doOrderFrontRegardlessOnMainThread(String title) {
        Pointer selSharedApp   = registerSel("sharedApplication");
        Pointer selWindows     = registerSel("windows");
        Pointer selCount       = registerSel("count");
        Pointer selObjAtIdx    = registerSel("objectAtIndex:");
        Pointer selTitle       = registerSel("title");
        Pointer selUTF8String  = registerSel("UTF8String");
        Pointer selOrderFront  = registerSel("orderFrontRegardless");

        Pointer nsappCls = getClassPtr("NSApplication");
        if (nsappCls == null || nsappCls == Pointer.NULL) return;
        Pointer nsApp = sendPtr(nsappCls, selSharedApp);
        if (nsApp == null || nsApp == Pointer.NULL) return;
        Pointer windowsArr = sendPtr(nsApp, selWindows);
        if (windowsArr == null || windowsArr == Pointer.NULL) return;

        long count = sendLong(windowsArr, selCount);
        for (long i = 0; i < count; i++) {
            Pointer win = sendPtrLong(windowsArr, selObjAtIdx, i);
            if (win == null || win == Pointer.NULL) continue;
            if (!windowTitleMatches(win, selTitle, selUTF8String, title)) continue;
            sendPtr(win, selOrderFront);
        }
    }

    private static boolean windowTitleMatches(Pointer win, Pointer selTitle,
                                              Pointer selUTF8String, String expected) {
        Pointer nsTitle = sendPtr(win, selTitle);
        if (nsTitle == null || nsTitle == Pointer.NULL) return false;
        Pointer utf8 = sendPtr(nsTitle, selUTF8String);
        if (utf8 == null || utf8 == Pointer.NULL) return false;
        try {
            return expected.equals(utf8.getString(0));
        } catch (Throwable t) {
            return false;
        }
    }

    // --- JNA plumbing -------------------------------------------------

    /** JNA callback signature matching {@code dispatch_function_t}. */
    public interface MainThreadTask extends Callback {
        void invoke(Pointer context);
    }

    private static boolean ensureLoaded() {
        synchronized (LOCK) {
            if (triedLoad) return msgSend != null && dispatch_async_f != null;
            triedLoad = true;

            NativeLibrary objcLib = null;
            try {
                objcLib = NativeLibrary.getInstance("objc.A");
            } catch (Throwable t) {
                try {
                    objcLib = NativeLibrary.getInstance("objc");
                } catch (Throwable t2) {
                    return false;
                }
            }
            try {
                objc_getClass    = objcLib.getFunction("objc_getClass");
                sel_registerName = objcLib.getFunction("sel_registerName");
                msgSend          = objcLib.getFunction("objc_msgSend");
            } catch (Throwable t) {
                objc_getClass = null;
                sel_registerName = null;
                msgSend = null;
                return false;
            }

            // libdispatch lives inside libSystem on macOS.
            NativeLibrary sysLib;
            try {
                sysLib = NativeLibrary.getInstance("System");
            } catch (Throwable t) {
                try {
                    sysLib = NativeLibrary.getInstance("dispatch");
                } catch (Throwable t2) {
                    return false;
                }
            }
            try {
                dispatch_async_f = sysLib.getFunction("dispatch_async_f");
                mainQueue = sysLib.getGlobalVariableAddress("_dispatch_main_q");
            } catch (Throwable t) {
                dispatch_async_f = null;
                mainQueue = null;
                return false;
            }
            return mainQueue != null && mainQueue != Pointer.NULL;
        }
    }

    private static Pointer getClassPtr(String name) {
        return (Pointer) objc_getClass.invoke(Pointer.class, new Object[]{ name });
    }

    private static Pointer registerSel(String name) {
        return (Pointer) sel_registerName.invoke(Pointer.class, new Object[]{ name });
    }

    private static Pointer sendPtr(Pointer receiver, Pointer selector) {
        return (Pointer) msgSend.invoke(Pointer.class, new Object[]{ receiver, selector });
    }

    private static Pointer sendPtrLong(Pointer receiver, Pointer selector, long arg) {
        return (Pointer) msgSend.invoke(Pointer.class, new Object[]{ receiver, selector, arg });
    }

    private static long sendLong(Pointer receiver, Pointer selector) {
        return ((Number) msgSend.invoke(long.class, new Object[]{ receiver, selector })).longValue();
    }

    private static void sendVoidLong(Pointer receiver, Pointer selector, long arg) {
        msgSend.invoke(void.class, new Object[]{ receiver, selector, arg });
    }
}
