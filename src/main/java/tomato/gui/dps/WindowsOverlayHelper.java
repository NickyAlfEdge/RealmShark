package tomato.gui.dps;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows-only helper that toggles click-through on a Swing window (identified
 * by its title) via {@code SetWindowLongPtrW} on {@code user32.dll}. When
 * click-through is enabled, mouse events pass through the overlay to whatever
 * lies behind (the game), so the user can continue playing without losing
 * focus to the overlay window.
 *
 * All calls are best-effort — if JNA can't load {@code user32}, if the JDK
 * blocks reflective access, or if the platform isn't Windows, calls silently
 * no-op.
 *
 * We do NOT need a platform helper for making the window appear over a
 * fullscreen game on Windows: Java's {@code setAlwaysOnTop(true)} maps to
 * {@code WS_EX_TOPMOST}, which is sufficient for windowed / borderless
 * fullscreen games. Exclusive-fullscreen (DirectX takeover) cannot be
 * overlaid from userland without graphics-driver hooks.
 */
final class WindowsOverlayHelper {

    private static final long GWL_EXSTYLE       = -20L;
    private static final long WS_EX_LAYERED     = 0x00080000L;
    private static final long WS_EX_TRANSPARENT = 0x00000020L;

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final Object LOCK = new Object();
    private static boolean triedLoad = false;
    private static Function findWindowW;
    private static Function getWindowLongW;
    private static Function setWindowLongW;
    private static Function setForegroundWindow;
    private static Function showWindow;
    private static Function isIconic;
    private static Function enumWindows;
    private static Function getWindowTextW;
    private static Function getWindowTextLengthW;
    private static Function isWindowVisible;
    private static boolean is64Bit;

    // SW_RESTORE for ShowWindow — un-minimizes without changing size/position.
    private static final int SW_RESTORE = 9;

    private WindowsOverlayHelper() { }

    /**
     * Toggle click-through on the window whose title matches {@code title}.
     * When {@code ignore} is true, the OR mask {@code WS_EX_LAYERED |
     * WS_EX_TRANSPARENT} is applied to the extended window style; when false,
     * those bits are cleared.
     */
    static boolean setIgnoresMouseEvents(String title, boolean ignore) {
        if (!IS_WINDOWS || title == null) return false;
        if (!ensureLoaded()) return false;

        try {
            Pointer hwnd = (Pointer) findWindowW.invoke(
                Pointer.class, new Object[]{ null, new WString(title) });
            if (hwnd == null || hwnd == Pointer.NULL) return false;

            long current;
            if (is64Bit) {
                current = ((Number) getWindowLongW.invoke(
                    long.class, new Object[]{ hwnd, (int) GWL_EXSTYLE })).longValue();
            } else {
                current = ((Number) getWindowLongW.invoke(
                    int.class, new Object[]{ hwnd, (int) GWL_EXSTYLE })).longValue();
            }

            long next = ignore
                ? (current | WS_EX_LAYERED | WS_EX_TRANSPARENT)
                : (current & ~WS_EX_TRANSPARENT);

            if (is64Bit) {
                setWindowLongW.invoke(long.class,
                    new Object[]{ hwnd, (int) GWL_EXSTYLE, next });
            } else {
                setWindowLongW.invoke(int.class,
                    new Object[]{ hwnd, (int) GWL_EXSTYLE, (int) next });
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Bring the top-level window whose title matches {@code title} to the
     * foreground, restoring it first if minimized. Returns {@code true} when
     * both {@code FindWindowW} located an HWND and {@code SetForegroundWindow}
     * reported success.
     */
    static boolean focusWindow(String title) {
        if (!IS_WINDOWS || title == null) return false;
        if (!ensureLoaded()) return false;
        try {
            Pointer hwnd = (Pointer) findWindowW.invoke(
                Pointer.class, new Object[]{ null, new WString(title) });
            if (hwnd == null || hwnd == Pointer.NULL) return false;
            return bringHwndToFront(hwnd);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Try to focus the RotMG Exalt game window using several candidate
     * strategies:
     *   1. Exact-match {@code FindWindowW} for each candidate title.
     *   2. Failing that, {@code EnumWindows} scanning every top-level visible
     *      window for one whose title contains any candidate substring
     *      (case-insensitive).
     *
     * We use candidate matching because the game's window title on Windows
     * is not always "RotMGExalt" — depending on client build it can be
     * "Realm of the Mad God Exalt" or similar. Falling back to a substring
     * scan lets us locate the existing game window rather than giving up
     * (and, historically, letting callers accidentally launch a fresh
     * instance as a fallback).
     */
    static boolean focusAnyWindow(String[] titleCandidates) {
        if (!IS_WINDOWS || titleCandidates == null || titleCandidates.length == 0) return false;
        if (!ensureLoaded()) return false;

        // 1. Exact-title FindWindowW pass — cheap and precise.
        for (String cand : titleCandidates) {
            if (cand == null || cand.isEmpty()) continue;
            try {
                Pointer hwnd = (Pointer) findWindowW.invoke(
                    Pointer.class, new Object[]{ null, new WString(cand) });
                if (hwnd != null && hwnd != Pointer.NULL) {
                    if (bringHwndToFront(hwnd)) return true;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. EnumWindows partial-match scan.
        Pointer hwnd = findWindowByTitleSubstring(titleCandidates);
        if (hwnd != null && hwnd != Pointer.NULL) {
            return bringHwndToFront(hwnd);
        }
        return false;
    }

    private static boolean bringHwndToFront(Pointer hwnd) {
        try {
            if (isIconic != null) {
                Object iconic = isIconic.invoke(int.class, new Object[]{ hwnd });
                if (iconic instanceof Number && ((Number) iconic).intValue() != 0) {
                    showWindow.invoke(int.class, new Object[]{ hwnd, SW_RESTORE });
                }
            }
            Object ok = setForegroundWindow.invoke(int.class, new Object[]{ hwnd });
            return ok instanceof Number && ((Number) ok).intValue() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** JNA callback signature matching {@code WNDENUMPROC}. */
    public interface WndEnumProc extends Callback {
        boolean callback(Pointer hwnd, Pointer lParam);
    }

    /**
     * Enumerate all top-level windows and return the first HWND whose title
     * contains any of {@code candidates} as a case-insensitive substring and
     * is visible. Returns {@code null} if nothing matches.
     */
    private static Pointer findWindowByTitleSubstring(String[] candidates) {
        if (enumWindows == null || getWindowTextW == null || getWindowTextLengthW == null) return null;

        final List<Pointer> match = new ArrayList<>(1);
        WndEnumProc proc = new WndEnumProc() {
            @Override
            public boolean callback(Pointer hwnd, Pointer lParam) {
                try {
                    if (isWindowVisible != null) {
                        Object vis = isWindowVisible.invoke(int.class, new Object[]{ hwnd });
                        if (!(vis instanceof Number) || ((Number) vis).intValue() == 0) return true;
                    }
                    int len = ((Number) getWindowTextLengthW.invoke(
                        int.class, new Object[]{ hwnd })).intValue();
                    if (len <= 0) return true;
                    // +1 for terminating null; wide chars are 2 bytes each.
                    Memory buf = new Memory((long) (len + 1) * 2L);
                    int copied = ((Number) getWindowTextW.invoke(
                        int.class, new Object[]{ hwnd, buf, len + 1 })).intValue();
                    if (copied <= 0) return true;
                    String title = buf.getWideString(0);
                    if (title == null || title.isEmpty()) return true;
                    String lower = title.toLowerCase();
                    for (String cand : candidates) {
                        if (cand == null || cand.isEmpty()) continue;
                        if (lower.contains(cand.toLowerCase())) {
                            match.add(hwnd);
                            return false; // stop enumeration
                        }
                    }
                } catch (Throwable ignored) {
                }
                return true;
            }
        };
        try {
            enumWindows.invoke(int.class, new Object[]{ proc, Pointer.NULL });
        } catch (Throwable ignored) {
        }
        return match.isEmpty() ? null : match.get(0);
    }

    private static boolean ensureLoaded() {
        synchronized (LOCK) {
            if (triedLoad) return findWindowW != null;
            triedLoad = true;
            NativeLibrary user32;
            try {
                user32 = NativeLibrary.getInstance("user32");
            } catch (Throwable t) {
                return false;
            }
            try {
                findWindowW = user32.getFunction("FindWindowW");
                // GetWindowLongPtrW / SetWindowLongPtrW on 64-bit, GetWindowLongW on 32-bit.
                is64Bit = "64".equals(System.getProperty("sun.arch.data.model"));
                if (is64Bit) {
                    getWindowLongW = user32.getFunction("GetWindowLongPtrW");
                    setWindowLongW = user32.getFunction("SetWindowLongPtrW");
                } else {
                    getWindowLongW = user32.getFunction("GetWindowLongW");
                    setWindowLongW = user32.getFunction("SetWindowLongW");
                }
                setForegroundWindow = user32.getFunction("SetForegroundWindow");
                showWindow = user32.getFunction("ShowWindow");
                isIconic = user32.getFunction("IsIconic");
                // Optional: used only by focusAnyWindow's substring-scan
                // fallback. Missing symbols disable the fallback but do not
                // break exact-title focus.
                try { enumWindows = user32.getFunction("EnumWindows"); } catch (Throwable ignored) {}
                try { getWindowTextW = user32.getFunction("GetWindowTextW"); } catch (Throwable ignored) {}
                try { getWindowTextLengthW = user32.getFunction("GetWindowTextLengthW"); } catch (Throwable ignored) {}
                try { isWindowVisible = user32.getFunction("IsWindowVisible"); } catch (Throwable ignored) {}
            } catch (Throwable t) {
                findWindowW = null;
                getWindowLongW = null;
                setWindowLongW = null;
                setForegroundWindow = null;
                showWindow = null;
                isIconic = null;
                enumWindows = null;
                getWindowTextW = null;
                getWindowTextLengthW = null;
                isWindowVisible = null;
                return false;
            }
            return true;
        }
    }
}
