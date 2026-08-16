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
