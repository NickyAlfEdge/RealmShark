package tomato.gui.dps;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JToggleButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * Centralised look for overlay title-bar buttons.
 *
 * <p>Default Swing L&amp;Fs — especially the Windows L&amp;F — draw their own
 * border, focus ring, and rollover fill on {@link JButton} / {@link JToggleButton}.
 * Even with {@code setContentAreaFilled(false)} that chrome still leaks
 * through and makes the overlay title bar look boxy and inconsistent versus
 * macOS. This helper flattens every button the overlays use to the same
 * transparent, flat, custom-painted look regardless of L&amp;F.
 */
final class OverlayButtonStyle {

    private static final Color FG = new Color(235, 235, 235);
    /** Vertical size floor so buttons don't collapse to font-height only. */
    private static final int MIN_H = 20;

    private OverlayButtonStyle() { }

    static JButton toolButton(String text) {
        JButton b = new JButton(text);
        flatten(b);
        return b;
    }

    static JToggleButton pillToggle(String text, Color activeColor) {
        JToggleButton b = new JToggleButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                if (isSelected()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(activeColor);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        flatten(b);
        b.setMargin(new Insets(0, 6, 0, 6));
        b.setBorder(BorderFactory.createEmptyBorder(1, 7, 1, 7));
        return b;
    }

    /** Toggle button that shows text based on state (used for the lock button). */
    static JToggleButton textToggle(String initialText) {
        JToggleButton b = new JToggleButton(initialText);
        flatten(b);
        return b;
    }

    private static void flatten(AbstractButton b) {
        b.setFocusable(false);
        b.setMargin(new Insets(0, 4, 0, 4));
        b.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
        b.setForeground(FG);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(false);
        Dimension pref = b.getPreferredSize();
        if (pref != null && pref.height < MIN_H) {
            b.setPreferredSize(new Dimension(pref.width, MIN_H));
        }
    }
}
