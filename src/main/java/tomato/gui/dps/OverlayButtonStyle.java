package tomato.gui.dps;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JToggleButton;
import java.awt.Color;
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

    private OverlayButtonStyle() { }

    static JButton toolButton(String text) {
        JButton b = new JButton(text);
        flatten(b);
        return b;
    }

    /**
     * Chip-style button used for per-row actions (e.g. the "Chat" button on
     * each DPS overlay row). Renders a visible rounded background so users
     * get a clear, easy-to-hit click target rather than a bare label. The
     * background brightens on rollover / press for feedback.
     */
    static JButton rowChatButton(String text) {
        final Color base = new Color(60, 90, 130, 200);
        final Color hover = new Color(85, 130, 180, 230);
        final Color pressed = new Color(45, 70, 105, 240);
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                javax.swing.ButtonModel m = getModel();
                Color fill = base;
                if (m.isPressed()) fill = pressed;
                else if (m.isRollover()) fill = hover;
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setMargin(new Insets(1, 8, 1, 8));
        b.setBorder(BorderFactory.createEmptyBorder(2, 9, 2, 9));
        b.setFocusable(false);
        b.setForeground(new Color(245, 245, 245));
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
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
        // Border/margin must be set before flatten so preferred-width calc includes them.
        b.setMargin(new Insets(0, 6, 0, 6));
        b.setBorder(BorderFactory.createEmptyBorder(1, 7, 1, 7));
        flatten(b);
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
        if (b.getBorder() == null) {
            b.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
        }
        if (b.getMargin() == null || (b.getMargin().left == 0 && b.getMargin().right == 0)) {
            b.setMargin(new Insets(0, 4, 0, 4));
        }
        b.setForeground(FG);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(false);
    }
}
