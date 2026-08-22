package tomato.gui.dps;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight right-click menu rendered on the anchor frame's
 * {@link JLayeredPane}, mirroring the pattern used by {@link OverlayTooltip}.
 *
 * <p>Standard {@link javax.swing.JPopupMenu}s spawn heavyweight windows at
 * Java's default window level, which on macOS end up behind our overlay
 * frames (they're promoted to {@code NSScreenSaverWindowLevel}). Painting on
 * the anchor's layered pane guarantees the menu shares the overlay's
 * z-order.
 */
final class OverlayContextMenu {

    private OverlayContextMenu() { }

    static final class Item {
        final String label;
        final Runnable action;
        final boolean separator;

        private Item(String label, Runnable action, boolean separator) {
            this.label = label;
            this.action = action;
            this.separator = separator;
        }

        static Item of(String label, Runnable action) {
            return new Item(label, action, false);
        }

        static Item separator() {
            return new Item(null, null, true);
        }
    }

    // Single active menu at a time — right-clicking again dismisses the prior one.
    private static JPanel active;
    private static JFrame activeAnchor;
    private static AWTEventListener globalListener;
    private static java.awt.KeyEventDispatcher escDispatcher;

    /**
     * Show a menu on {@code anchor}'s layered pane near screen coordinates
     * {@code screenX}/{@code screenY}. Clicking outside or pressing Escape
     * dismisses it.
     */
    static void show(JFrame anchor, int screenX, int screenY, List<Item> items) {
        if (anchor == null || items == null || items.isEmpty()) return;
        SwingUtilities.invokeLater(() -> doShow(anchor, screenX, screenY, items));
    }

    private static void doShow(JFrame anchor, int screenX, int screenY, List<Item> items) {
        hide();

        JPanel menu = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(25, 25, 30));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setColor(new Color(80, 80, 90));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.dispose();
            }
        };
        menu.setOpaque(false);
        menu.setLayout(new BorderLayout());
        menu.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new javax.swing.BoxLayout(col, javax.swing.BoxLayout.Y_AXIS));
        col.setBorder(new EmptyBorder(4, 4, 4, 4));

        Font font = new Font("Dialog", Font.PLAIN, 12);
        List<JLabel> itemLabels = new ArrayList<>();
        for (Item it : items) {
            if (it.separator) {
                JPanel sep = new JPanel();
                sep.setOpaque(true);
                sep.setBackground(new Color(70, 70, 80));
                Dimension d = new Dimension(10, 1);
                sep.setPreferredSize(d);
                sep.setMinimumSize(d);
                sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
                sep.setAlignmentX(Component.LEFT_ALIGNMENT);
                sep.setBorder(new EmptyBorder(4, 0, 4, 0));
                col.add(sep);
                continue;
            }
            JLabel lbl = new JLabel(it.label);
            lbl.setOpaque(true);
            lbl.setBackground(new Color(25, 25, 30));
            lbl.setForeground(new Color(235, 235, 235));
            lbl.setFont(font);
            lbl.setBorder(new EmptyBorder(4, 10, 4, 14));
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            final Runnable action = it.action;
            lbl.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    lbl.setBackground(new Color(60, 100, 160));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    lbl.setBackground(new Color(25, 25, 30));
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && lbl.contains(e.getPoint())) {
                        hide();
                        if (action != null) SwingUtilities.invokeLater(action);
                    }
                }
            });
            col.add(lbl);
            itemLabels.add(lbl);
        }

        // Give every enabled label the same width so hover highlights span
        // the full menu rather than just the shorter labels.
        int maxW = 0;
        for (JLabel lbl : itemLabels) {
            maxW = Math.max(maxW, lbl.getPreferredSize().width);
        }
        for (JLabel lbl : itemLabels) {
            Dimension d = lbl.getPreferredSize();
            d.width = maxW;
            lbl.setPreferredSize(d);
            lbl.setMinimumSize(d);
            lbl.setMaximumSize(new Dimension(Integer.MAX_VALUE, d.height));
        }

        JScrollPane scroll = new JScrollPane(col,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.getVerticalScrollBar().setOpaque(false);

        menu.add(scroll, BorderLayout.CENTER);
        Dimension colPref = col.getPreferredSize();
        int borderPad = 4; // 2px inset on each side around the scroll pane

        JLayeredPane lp = anchor.getLayeredPane();
        Point anchorScreen;
        try {
            anchorScreen = lp.getLocationOnScreen();
        } catch (Exception ex) {
            return;
        }
        int x = screenX - anchorScreen.x;
        int y = screenY - anchorScreen.y;
        // Never allow the menu to be clamped upward past the anchor point —
        // that's what previously let a tall menu cover the title-bar buttons
        // and made the overlay unclosable. Instead, cap the menu height to the
        // space available BELOW the anchor and let the scroll pane handle
        // overflow.
        y = Math.max(2, y);
        int availH = Math.max(40, lp.getHeight() - y - 4);
        int menuH = Math.min(colPref.height + borderPad, availH);
        boolean needScroll = (colPref.height + borderPad) > availH;
        int scrollBarW = needScroll
            ? scroll.getVerticalScrollBar().getPreferredSize().width
            : 0;
        int menuW = Math.min(lp.getWidth() - 4, colPref.width + borderPad + scrollBarW);
        x = Math.max(2, Math.min(x, lp.getWidth() - menuW - 2));
        menu.setBounds(x, y, menuW, menuH);

        lp.add(menu, JLayeredPane.POPUP_LAYER);
        lp.repaint();

        active = menu;
        activeAnchor = anchor;

        installGlobalListeners();
    }

    static void hide() {
        removeGlobalListeners();
        if (active == null || activeAnchor == null) {
            active = null;
            activeAnchor = null;
            return;
        }
        JLayeredPane lp = activeAnchor.getLayeredPane();
        lp.remove(active);
        lp.repaint();
        active = null;
        activeAnchor = null;
    }

    private static void installGlobalListeners() {
        // Dismiss on any mouse press outside the menu, anywhere on screen.
        globalListener = new AWTEventListener() {
            @Override
            public void eventDispatched(AWTEvent event) {
                if (!(event instanceof MouseEvent)) return;
                MouseEvent me = (MouseEvent) event;
                if (me.getID() != MouseEvent.MOUSE_PRESSED) return;
                if (active == null) return;
                Component src = me.getComponent();
                if (src != null && SwingUtilities.isDescendingFrom(src, active)) return;
                hide();
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(globalListener, AWTEvent.MOUSE_EVENT_MASK);

        escDispatcher = e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                hide();
                return true;
            }
            return false;
        };
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(escDispatcher);
    }

    private static void removeGlobalListeners() {
        if (globalListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(globalListener);
            globalListener = null;
        }
        if (escDispatcher != null) {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(escDispatcher);
            escDispatcher = null;
        }
    }
}
