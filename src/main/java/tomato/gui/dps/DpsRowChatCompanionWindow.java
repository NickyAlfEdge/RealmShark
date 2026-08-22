package tomato.gui.dps;

import tomato.backend.data.Damage;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.IllegalComponentStateException;
import java.awt.Point;
import java.awt.Toolkit;
import java.net.URL;
import java.util.List;

/**
 * Companion overlay window that mirrors the per-row "Chat" buttons of
 * {@link DpsOverlayGUI} while the main overlay is locked.
 *
 * <p>The locked main overlay is set to native click-through so the game
 * receives clicks through the DPS content area. That would leave the per-row
 * Chat buttons unusable, so this window sits over the right edge of the main
 * overlay's viewport and hosts a real, clickable button aligned with each
 * visible row. The window itself is not click-through, and the surrounding
 * area (everything not covered by a button) is fully transparent so the game
 * still receives clicks there.
 *
 * <p>Mirrors the {@link OverlayControlsWindow} pattern for platform hints
 * (macOS all-spaces floating promotion, Windows always-on-top).
 */
final class DpsRowChatCompanionWindow {

    private static final boolean IS_MAC =
        System.getProperty("os.name", "").toLowerCase().contains("mac");

    private static final int BUTTON_WIDTH = 46;
    private static final int BUTTON_HEIGHT = 18;
    private static final int RIGHT_INSET = 10;
    private static final int STRIP_WIDTH = BUTTON_WIDTH + RIGHT_INSET;

    private final String title;
    private final JFrame frame;
    private final JPanel root;

    DpsRowChatCompanionWindow(String title, URL iconUrl) {
        this.title = title;

        frame = new JFrame(title);
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setFocusableWindowState(false);
        frame.setAutoRequestFocus(false);
        // Per-pixel translucency: only the button pill pixels take clicks;
        // the rest of the strip is fully transparent (game keeps click-through).
        try {
            frame.setBackground(new Color(0, 0, 0, 0));
        } catch (Throwable ignored) {
        }
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

        root = new JPanel();
        root.setLayout(null); // absolute positioning of the row buttons
        root.setOpaque(false);
        frame.setContentPane(root);
    }

    String getTitle() { return title; }

    boolean isVisible() { return frame.isVisible(); }

    /**
     * Rebuild the button strip so each entry in {@code rows} has a Chat button
     * aligned with the row's current screen position, and show the window.
     * Rows outside the viewport are skipped.
     */
    void syncRows(JFrame mainFrame, JScrollPane scrollPane, List<RowChatInfo> rows) {
        if (mainFrame == null || scrollPane == null || rows == null || rows.isEmpty()
                || !mainFrame.isShowing()) {
            hide();
            return;
        }
        JViewport vp = scrollPane.getViewport();
        if (vp == null || !vp.isShowing()) {
            hide();
            return;
        }

        Point vpScreen;
        try {
            vpScreen = vp.getLocationOnScreen();
        } catch (IllegalComponentStateException e) {
            hide();
            return;
        }
        Dimension vpSize = vp.getSize();

        int companionX = vpScreen.x + vpSize.width - STRIP_WIDTH;
        int companionY = vpScreen.y;
        int companionH = Math.max(1, vpSize.height);

        root.removeAll();

        int placed = 0;
        for (RowChatInfo info : rows) {
            if (info == null || info.row == null || !info.row.isShowing()) continue;
            Point rowScreen;
            try {
                rowScreen = info.row.getLocationOnScreen();
            } catch (IllegalComponentStateException e) {
                continue;
            }
            int rowH = info.row.getHeight();
            int centerY = (rowScreen.y - companionY) + Math.max(0, (rowH - BUTTON_HEIGHT) / 2);
            // Skip if the row is not visible in the viewport at all.
            if (centerY + BUTTON_HEIGHT < 0 || centerY > companionH) continue;

            final int rank = info.rank;
            final Damage dmg = info.dmg;
            final long maxHp = info.entityMaxHp;

            JButton btn = OverlayButtonStyle.rowChatButton("Chat");
            btn.setBounds(RIGHT_INSET / 2, centerY, BUTTON_WIDTH, BUTTON_HEIGHT);
            btn.addActionListener(e -> {
                String line = DpsChatSender.buildPlayerLine(rank, dmg, maxHp);
                if (line != null) DpsChatSender.sendToGameChat(line);
            });
            OverlayTooltip.install(btn, frame,
                "Send this player's rank / damage / percent to game chat (clipboard fallback).");
            root.add(btn);
            placed++;
        }

        if (placed == 0) {
            hide();
            return;
        }

        frame.setBounds(companionX, companionY, STRIP_WIDTH, companionH);
        if (!frame.isVisible()) {
            frame.setVisible(true);
            SwingUtilities.invokeLater(this::orderFront);
        } else {
            orderFront();
        }
        root.revalidate();
        root.repaint();
    }

    void hide() {
        if (frame.isVisible()) frame.setVisible(false);
        root.removeAll();
    }

    void applyOpacity() {
        try {
            frame.setOpacity(OverlayOpacityController.alphaToOpacity(OverlayOpacityController.getAlpha()));
        } catch (Throwable ignored) {
        }
        if (frame.isVisible()) frame.repaint();
    }

    void reassertOnTop() {
        if (!frame.isVisible()) return;
        orderFront();
    }

    private void orderFront() {
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

    /** Snapshot of a single overlay row needed to build a mirroring Chat button. */
    static final class RowChatInfo {
        final JComponent row;
        final int rank;
        final Damage dmg;
        final long entityMaxHp;

        RowChatInfo(JComponent row, int rank, Damage dmg, long entityMaxHp) {
            this.row = row;
            this.rank = rank;
            this.dmg = dmg;
            this.entityMaxHp = entityMaxHp;
        }
    }
}
