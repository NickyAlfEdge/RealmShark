package tomato.gui.dps;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
 * <p>Visually the window paints the same dark, rounded background that the
 * main overlay title bar uses (see the root panel in {@link DpsOverlayGUI}),
 * so the locked-mode controls read as an extension of that title bar on both
 * macOS and Windows. Earlier revisions relied on per-pixel translucency to
 * make the window "invisible around the buttons", which fell back to a raw
 * grey window on Windows where per-pixel alpha isn't honoured for undecorated
 * frames.
 */
final class OverlayControlsWindow {

    private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    private static final Color BG = new Color(15, 15, 18);

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
            frame.setOpacity(OverlayOpacityController.alphaToOpacity(OverlayOpacityController.getAlpha()));
        } catch (Throwable ignored) {
        }

        root = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
            }
        };
        root.setOpaque(false);
        root.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
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
     *
     * @return extra vertical pixels the companion uses beyond {@code titleBarHeight}.
     *         Callers should shift their overlay content down by this amount so
     *         the (possibly taller) locked controls window doesn't overlap the
     *         label / content area underneath the button row.
     */
    int showFor(JFrame mainFrame, JPanel buttons) {
        if (buttons.getParent() != null) buttons.getParent().remove(buttons);
        root.removeAll();
        root.add(buttons, BorderLayout.CENTER);

        Dimension pref = buttons.getPreferredSize();
        // Snug fit around the button strip so no dead grey band shows on the
        // left of the buttons on narrow overlays.
        int w = Math.max(pref.width + 10, 40);
        int h = Math.max(titleBarHeight, pref.height + 4);
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

        return Math.max(0, h - titleBarHeight);
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

    /** Track opacity changes from the shared {@link OverlayOpacityController}. */
    void applyOpacity() {
        try {
            frame.setOpacity(OverlayOpacityController.alphaToOpacity(OverlayOpacityController.getAlpha()));
        } catch (Throwable ignored) {
        }
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
