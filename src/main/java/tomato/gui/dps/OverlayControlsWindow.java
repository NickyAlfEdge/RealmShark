package tomato.gui.dps;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.net.URL;

/**
 * Small companion window that hosts an overlay's title-bar button strip while
 * the overlay is locked. The main overlay frame is set to click-through when
 * locked (so the game receives clicks through the DPS/loot/quest content), but
 * the user still needs a way to unlock, hide, minimize, or trigger the other
 * button actions. This window sits over the top-right of the main frame's
 * title bar and stays clickable.
 *
 * <p>Ownership of the button panel is transferred back and forth: while shown,
 * the button {@link JPanel} lives inside this window; while hidden, callers
 * put it back in the main title bar.
 *
 * <p>The window uses per-pixel translucency (transparent background) rather
 * than a uniform {@code setOpacity}. Two overlapping semi-opaque windows would
 * otherwise stack their alpha and darken the button strip visibly. Per-pixel
 * transparency means only the button glyphs draw and the main title bar's
 * appearance underneath is preserved unchanged. A single unit of alpha is
 * kept on the background so Windows' {@code WS_EX_LAYERED} hit-testing still
 * accepts clicks on the empty gaps between buttons.
 */
final class OverlayControlsWindow {

    private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    // Alpha 1 (near-invisible) keeps the whole window rect clickable on Windows'
    // WS_EX_LAYERED per-pixel hit-testing without visibly darkening the main frame.
    private static final Color TRANSPARENT_HIT = new Color(0, 0, 0, 1);

    private final String title;
    private final int titleBarHeight;
    private final JFrame frame;
    private final JPanel root;

    OverlayControlsWindow(String title, int titleBarHeight, URL iconUrl) {
        this.title = title;
        this.titleBarHeight = titleBarHeight;

        frame = new JFrame(title);
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setFocusableWindowState(false);
        frame.setAutoRequestFocus(false);
        if (!IS_MAC) {
            frame.setAlwaysOnTop(true);
        }
        if (iconUrl != null) {
            frame.setIconImage(Toolkit.getDefaultToolkit().getImage(iconUrl));
        }
        try {
            frame.setBackground(TRANSPARENT_HIT);
        } catch (Throwable ignored) {
        }

        root = new JPanel(new BorderLayout());
        root.setOpaque(false);
        frame.setContentPane(root);
    }

    String getTitle() {
        return title;
    }

    boolean isVisible() {
        return frame.isVisible();
    }

    /**
     * Install {@code buttons} into this window, position over the top-right of
     * {@code mainFrame}'s title bar, and show the window. The button panel is
     * removed from its previous parent.
     */
    void showFor(JFrame mainFrame, JPanel buttons) {
        if (buttons.getParent() != null) buttons.getParent().remove(buttons);
        root.removeAll();
        root.add(buttons, BorderLayout.CENTER);

        Dimension pref = buttons.getPreferredSize();
        int w = Math.max(60, pref.width + 4);
        int h = Math.max(titleBarHeight, pref.height + 2);
        int x = mainFrame.getX() + mainFrame.getWidth() - w - 4;
        int y = mainFrame.getY() + 2;
        frame.setBounds(x, y, w, h);
        frame.setVisible(true);

        // Ensure controls sit above the main frame in the same window level; on
        // macOS Java's toFront() would demote the NSWindow level and let the
        // main frame cover the controls (which then eats the click since main
        // is set to ignoreMouseEvents).
        SwingUtilities.invokeLater(() -> {
            if (IS_MAC) {
                MacOSOverlayHelper.promoteToAllSpacesFloating(title);
                MacOSOverlayHelper.orderFrontRegardless(title);
            } else {
                frame.toFront();
            }
        });

        root.revalidate();
        root.repaint();
    }

    /** Remove the button panel from this window (so caller can reinsert it) and hide. */
    JPanel hideAndReleaseButtons() {
        JPanel buttons = null;
        if (root.getComponentCount() > 0 && root.getComponent(0) instanceof JPanel) {
            buttons = (JPanel) root.getComponent(0);
        }
        root.removeAll();
        frame.setVisible(false);
        return buttons;
    }

    /** Reassert always-on-top / macOS Space membership. Called periodically. */
    void reassertOnTop() {
        if (!frame.isVisible()) return;
        try {
            if (IS_MAC) {
                MacOSOverlayHelper.promoteToAllSpacesFloating(title);
                MacOSOverlayHelper.orderFrontRegardless(title);
            } else {
                frame.toFront();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * No-op kept for API compatibility with the main overlay windows. This
     * window uses per-pixel translucency (a fully transparent background) so
     * uniform {@code setOpacity} cannot be used at the same time and would
     * throw {@code IllegalComponentStateException}. The main frame's own
     * opacity slider still applies to the main overlay underneath the buttons.
     */
    void applyOpacity() {
        if (frame.isVisible()) frame.repaint();
    }

    /** Reposition to track {@code mainFrame}. Used if the main frame moves while locked. */
    void syncPosition(JFrame mainFrame) {
        if (!frame.isVisible()) return;
        Dimension size = frame.getSize();
        int x = mainFrame.getX() + mainFrame.getWidth() - size.width - 4;
        int y = mainFrame.getY() + 2;
        frame.setLocation(x, y);
    }

    /** For layout debugging / callers that need to compose. */
    Container getContentContainer() {
        return root;
    }
}

