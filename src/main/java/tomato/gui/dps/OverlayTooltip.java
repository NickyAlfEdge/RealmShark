package tomato.gui.dps;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Tooltip helper for the overlay windows.
 *
 * Swing tooltips are shown as their own heavyweight {@code JWindow}s, and on
 * macOS those windows sit at Java's default window level. Because our overlays
 * are promoted to {@code NSScreenSaverWindowLevel (1000)}, any tooltip
 * spawned from an overlay button ends up rendering BEHIND the overlay itself.
 *
 * This helper avoids that by painting the tooltip as a {@link JLabel} on the
 * overlay frame's own {@link JLayeredPane} at the popup layer, so the tooltip
 * shares the overlay's z-order and can never fall behind it.
 */
public final class OverlayTooltip {

    private OverlayTooltip() { }

    /**
     * Attach an in-window tooltip to {@code target}. The owning frame is looked up at
     * hover time via {@link SwingUtilities#getWindowAncestor(java.awt.Component)}, so
     * the tooltip follows the button if it is reparented into another overlay frame
     * (e.g. the companion controls window shown while an overlay is locked).
     * {@code owner} is accepted for backward compatibility but is not retained.
     */
    public static void install(JComponent target, JFrame owner, String text) {
        if (target == null || text == null || text.isEmpty()) return;
        // Suppress default Swing tooltip so the two don't fight.
        target.setToolTipText(null);
        target.addMouseListener(new HoverHandler(target, text));
    }

    private static final class HoverHandler extends MouseAdapter {
        private final JComponent target;
        private final String text;
        private JLabel bubble;
        private JFrame anchor;

        HoverHandler(JComponent target, String text) {
            this.target = target;
            this.text = text;
        }

        @Override
        public void mouseEntered(MouseEvent e) {
            hide();
            java.awt.Window w = SwingUtilities.getWindowAncestor(target);
            if (!(w instanceof JFrame)) return;
            anchor = (JFrame) w;

            bubble = new JLabel(text);
            bubble.setOpaque(true);
            bubble.setBackground(new Color(250, 248, 210));
            bubble.setForeground(new Color(30, 30, 30));
            bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80)),
                new EmptyBorder(2, 6, 2, 6)));
            Dimension pref = bubble.getPreferredSize();
            bubble.setSize(pref);

            JLayeredPane lp = anchor.getLayeredPane();
            // Anchor just below the button, then clamp so the bubble stays inside the frame.
            Point loc = SwingUtilities.convertPoint(target, 0, target.getHeight() + 2, lp);
            int x = Math.min(loc.x, Math.max(2, lp.getWidth() - pref.width - 2));
            x = Math.max(2, x);
            int y = Math.min(loc.y, Math.max(2, lp.getHeight() - pref.height - 2));
            y = Math.max(2, y);
            bubble.setLocation(x, y);

            lp.add(bubble, JLayeredPane.POPUP_LAYER);
            lp.repaint();
        }

        @Override
        public void mouseExited(MouseEvent e) {
            hide();
        }

        private void hide() {
            if (bubble == null || anchor == null) { bubble = null; anchor = null; return; }
            JLayeredPane lp = anchor.getLayeredPane();
            lp.remove(bubble);
            lp.repaint();
            bubble = null;
            anchor = null;
        }
    }
}
