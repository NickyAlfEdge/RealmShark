package tomato.gui.dps;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;

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
    private static boolean is64Bit;

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
            } catch (Throwable t) {
                findWindowW = null;
                getWindowLongW = null;
                setWindowLongW = null;
                return false;
            }
            return true;
        }
    }
}
