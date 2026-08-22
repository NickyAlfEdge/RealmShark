package tomato.gui.dps;

import assets.ImageBuffer;
import tomato.Tomato;
import tomato.backend.data.Entity;
import tomato.gui.stats.LootGUI;
import tomato.realmshark.enums.LootBags;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compact floating overlay that shows a running count of each loot bag colour
 * that has dropped. Sits by default just above the DPS overlay so both can be
 * seen at once over the game window.
 *
 * Icons come from {@link ImageBuffer#getOutlinedIcon(int, int)} using the bag's
 * {@code objectType} (same source as the main Loot tab), and the visible bag
 * types respect the "Filter Loot" checkboxes in {@link LootGUI}. Bags whose
 * filter is off are hidden from the overlay too.
 *
 * Uses the same z-order + macOS-Space promotion strategy as {@link DpsOverlayGUI}.
 */
public class LootOverlayGUI {

    // ---- persistence keys ----
    private static final String PROP_X = "lootOverlayX";
    private static final String PROP_Y = "lootOverlayY";
    private static final String PROP_W = "lootOverlayW";
    private static final String PROP_H = "lootOverlayH";
    private static final String PROP_VISIBLE = "lootOverlayVisible";
    private static final String PROP_LOCKED = "lootOverlayLocked";
    private static final String PROP_MINIMIZED = "lootOverlayMinimized";

    private static final int DEFAULT_W = 260;
    private static final int DEFAULT_H = 70;
    private static final int MIN_W = 160;
    private static final int MIN_H = 50;
    // Kept in sync with DpsOverlayGUI.TITLE_H so both toolbars render identically.
    private static final int TITLE_H = 26;
    private static final int GRIP_SIZE = 12;

    // Order in which bag colours are displayed. Matches the "Filter Loot" menu order.
    private static final int[][] BAG_ORDER = new int[][] {
        { LootBags.WHITE.getId(),  LootBags.BOOSTED_WHITE.getId()  },
        { LootBags.ORANGE.getId(), LootBags.BOOSTED_ORANGE.getId() },
        { LootBags.RED.getId(),    LootBags.BOOSTED_RED.getId()    },
        { LootBags.GOLD.getId(),   LootBags.BOOSTED_GOLD.getId()   },
        { LootBags.EGG.getId(),    LootBags.BOOSTED_EGG.getId()    },
        { LootBags.BLUE.getId(),   LootBags.BOOSTED_BLUE.getId()   },
        { LootBags.TEAL.getId(),   LootBags.BOOSTED_TEAL.getId()   },
        { LootBags.PURPLE.getId(), LootBags.BOOSTED_PURPLE.getId() },
        { LootBags.PINK.getId(),   LootBags.BOOSTED_PINK.getId()   },
        { LootBags.BROWN.getId(),  LootBags.BOOSTED_BROWN.getId()  },
    };

    private static LootOverlayGUI INSTANCE;

    private final JFrame frame;
    private final JPanel content;

    // Bag base-ID -> total dropped count (base and boosted counted together, keyed by base).
    private final Map<Integer, Integer> counts = new LinkedHashMap<>();

    private Font mainFont = new Font("Monospaced", Font.PLAIN, 12);
    private boolean locked = false;
    private boolean minimized = false;
    private int savedHeight = -1;
    private JToggleButton lockBtn;
    private JButton minBtn;
    private JPanel bottomBar;
    private JPanel titleBar;
    private JPanel titleBtns;
    private OverlayControlsWindow controlsWindow;
    // Extra vertical pixels the (chunkier) locked controls window uses beyond
    // TITLE_H. Applied as extra title-bar preferred height so the content row
    // ("No drops yet." etc.) shifts down and isn't overlapped by the buttons.
    private int lockedExtraPad = 0;
    private javax.swing.Timer topReassertTimer;

    private static final boolean IS_MAC =
        System.getProperty("os.name", "").toLowerCase().contains("mac");

    private LootOverlayGUI() {
        loadPrefs();

        frame = new JFrame("Tomato Loot Overlay");
        frame.setUndecorated(true);
        if (!IS_MAC) {
            frame.setAlwaysOnTop(true);
        }
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setFocusableWindowState(false);
        frame.setAutoRequestFocus(false);
        if (Tomato.imagePath != null) {
            frame.setIconImage(Toolkit.getDefaultToolkit().getImage(Tomato.imagePath));
        }
        try {
            frame.setOpacity(OverlayOpacityController.alphaToOpacity(OverlayOpacityController.getAlpha()));
        } catch (Throwable ignored) {
        }

        JPanel root = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(15, 15, 18));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
            }
        };
        root.setOpaque(false);
        root.add(buildTitleBar(), BorderLayout.NORTH);

        content = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(2, 6, 2, 6));
        root.add(content, BorderLayout.CENTER);

        bottomBar = new JPanel(new BorderLayout());
        bottomBar.setOpaque(false);
        bottomBar.add(new ResizeGrip(), BorderLayout.EAST);
        root.add(bottomBar, BorderLayout.SOUTH);

        frame.setContentPane(root);

        int x = getIntProp(PROP_X, 40);
        int y = getIntProp(PROP_Y, 4);
        int w = Math.max(MIN_W, getIntProp(PROP_W, DEFAULT_W));
        int h = Math.max(MIN_H, getIntProp(PROP_H, DEFAULT_H));
        frame.setBounds(x, y, w, h);

        if (minimized) {
            savedHeight = h;
            SwingUtilities.invokeLater(this::applyMinimizedLayout);
        }

        rebuild();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public static void init() {
        if (INSTANCE == null) INSTANCE = new LootOverlayGUI();
        String vis = PropertiesManager.getProperty(PROP_VISIBLE);
        if (vis != null && vis.equals("true")) {
            INSTANCE.setVisible(true);
        }
    }

    public static boolean isVisible() {
        return INSTANCE != null && INSTANCE.frame.isVisible();
    }

    public static void toggle() {
        if (INSTANCE == null) INSTANCE = new LootOverlayGUI();
        INSTANCE.setVisible(!INSTANCE.frame.isVisible());
    }

    public static boolean isLocked() {
        return INSTANCE != null && INSTANCE.locked;
    }

    public static void toggleLocked() {
        if (INSTANCE == null) return;
        INSTANCE.setLockedInternal(!INSTANCE.locked);
    }

    private static Runnable stateListener;

    public static void setStateListener(Runnable listener) {
        stateListener = listener;
    }

    private static void fireStateChanged() {
        Runnable r = stateListener;
        if (r != null) SwingUtilities.invokeLater(r);
    }

    private void setLockedInternal(boolean value) {
        locked = value;
        if (lockBtn != null) {
            lockBtn.setSelected(value);
            lockBtn.setText(value ? "\uD83D\uDD12" : "\uD83D\uDD13");
        }
        PropertiesManager.setProperties(PROP_LOCKED, value ? "true" : "false");
        applyLockState();
        fireStateChanged();
    }

    /**
     * Apply the current locked state: when locked, main frame becomes
     * click-through and the button strip moves into a small companion window
     * so the user can still hit Clear / lock / minimize / close. When
     * unlocked, the buttons return to the title bar and click-through is cleared.
     */
    private void applyLockState() {
        if (frame == null) return;
        if (locked) {
            detachButtonsToControlsWindow();
            MacOSOverlayHelper.setIgnoresMouseEvents(frame.getTitle(), true);
            WindowsOverlayHelper.setIgnoresMouseEvents(frame.getTitle(), true);
        } else {
            MacOSOverlayHelper.setIgnoresMouseEvents(frame.getTitle(), false);
            WindowsOverlayHelper.setIgnoresMouseEvents(frame.getTitle(), false);
            reattachButtonsToTitleBar();
        }
    }

    private void detachButtonsToControlsWindow() {
        if (titleBtns == null || frame == null) return;
        if (controlsWindow == null) {
            controlsWindow = new OverlayControlsWindow(
                "Tomato Loot Overlay Controls", TITLE_H, Tomato.imagePath);
        }
        int extra = controlsWindow.showFor(frame, titleBtns);
        applyLockPad(extra);
        if (titleBar != null) {
            titleBar.revalidate();
            titleBar.repaint();
        }
    }

    private void reattachButtonsToTitleBar() {
        if (controlsWindow == null) return;
        JPanel buttons = controlsWindow.hideAndReleaseButtons();
        if (buttons != null && titleBar != null && titleBtns != null && buttons == titleBtns) {
            titleBar.add(titleBtns, BorderLayout.EAST);
            applyLockPad(0);
            titleBar.revalidate();
            titleBar.repaint();
        }
    }

    /**
     * Grow the title bar's preferred height by {@code extra} pixels so a
     * chunkier locked {@link OverlayControlsWindow} doesn't paint over the
     * content area (e.g. the "No drops yet." placeholder).
     */
    private void applyLockPad(int extra) {
        lockedExtraPad = Math.max(0, extra);
        if (titleBar != null) {
            titleBar.setPreferredSize(new Dimension(10, TITLE_H + lockedExtraPad));
        }
        if (frame != null) {
            frame.revalidate();
            frame.repaint();
        }
    }

    /** Package-visible entry point used by {@link OverlayOpacityController#setAlpha(int)}. */
    static void refreshOpacity() {
        if (INSTANCE != null) INSTANCE.applyOpacity();
    }

    private void applyOpacity() {
        try {
            frame.setOpacity(OverlayOpacityController.alphaToOpacity(OverlayOpacityController.getAlpha()));
        } catch (Throwable ignored) {
        }
        frame.repaint();
        if (controlsWindow != null) controlsWindow.applyOpacity();
    }

    public static void editFont(Font font) {
        if (INSTANCE == null) return;
        int size = Math.min(font.getSize(), 14);
        INSTANCE.mainFont = font.deriveFont((float) size);
        if (INSTANCE.frame.isVisible()) INSTANCE.rebuild();
    }

    /** Called from {@link LootGUI} whenever a bag drops. */
    public static void recordBag(Entity bag) {
        if (INSTANCE == null || bag == null) return;
        int id = baseBagId(bag.objectType);
        if (id < 0) return;
        INSTANCE.counts.merge(id, 1, Integer::sum);
        if (INSTANCE.frame.isVisible()) INSTANCE.rebuild();
    }

    /** Clear the current running counts. */
    public static void resetCounts() {
        if (INSTANCE == null) return;
        INSTANCE.counts.clear();
        if (INSTANCE.frame.isVisible()) INSTANCE.rebuild();
    }

    /** Re-run visibility filters against the {@link LootGUI} filter flags. */
    public static void applyFilters() {
        if (INSTANCE == null || !INSTANCE.frame.isVisible()) return;
        INSTANCE.rebuild();
    }

    // ------------------------------------------------------------------
    // Title bar
    // ------------------------------------------------------------------

    private JPanel buildTitleBar() {
        titleBar = new JPanel(new BorderLayout());
        titleBar.setOpaque(false);
        titleBar.setBorder(new EmptyBorder(2, 8, 2, 4));
        titleBar.setPreferredSize(new Dimension(10, TITLE_H));

        JLabel title = new JLabel("Loot");
        title.setForeground(new Color(235, 235, 235));
        title.setFont(mainFont.deriveFont(Font.BOLD));
        titleBar.add(title, BorderLayout.WEST);

        titleBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        titleBtns.setOpaque(false);

        JButton resetBtn = OverlayButtonStyle.toolButton("Clear");
        OverlayTooltip.install(resetBtn, frame, "Reset all bag drop counts to zero");
        resetBtn.addActionListener(e -> resetCounts());
        titleBtns.add(resetBtn);

        lockBtn = OverlayButtonStyle.textToggle(locked ? "\uD83D\uDD12" : "\uD83D\uDD13");
        lockBtn.setSelected(locked);
        OverlayTooltip.install(lockBtn, frame, "Lock overlay: freezes position and passes clicks through to the game.");
        lockBtn.addActionListener(e -> setLockedInternal(lockBtn.isSelected()));
        titleBtns.add(lockBtn);

        minBtn = OverlayButtonStyle.toolButton(minimized ? "\u25A2" : "\u2212");
        OverlayTooltip.install(minBtn, frame, "Minimize / restore overlay contents");
        minBtn.addActionListener(e -> toggleMinimized());
        titleBtns.add(minBtn);

        JButton closeBtn = OverlayButtonStyle.toolButton("\u2715");
        OverlayTooltip.install(closeBtn, frame, "Hide overlay");
        closeBtn.addActionListener(e -> setVisible(false));
        titleBtns.add(closeBtn);

        titleBar.add(titleBtns, BorderLayout.EAST);

        DragHandler drag = new DragHandler();
        titleBar.addMouseListener(drag);
        titleBar.addMouseMotionListener(drag);
        title.addMouseListener(drag);
        title.addMouseMotionListener(drag);

        return titleBar;
    }

    private JButton makeToolButton(String text) {
        return OverlayButtonStyle.toolButton(text);
    }

    private JToggleButton makePillToggle(String text, Color activeColor) {
        return OverlayButtonStyle.pillToggle(text, activeColor);
    }

    // ------------------------------------------------------------------
    // Rebuild content
    // ------------------------------------------------------------------

    private void rebuild() {
        SwingUtilities.invokeLater(this::doRebuild);
    }

    private void doRebuild() {
        content.removeAll();

        int iconSize = Math.max(18, mainFont.getSize() + 8);
        boolean anyShown = false;
        for (int[] pair : BAG_ORDER) {
            int baseId = pair[0];
            if (!isBagEnabled(baseId)) continue;
            int count = counts.getOrDefault(baseId, 0);
            if (count <= 0) continue;

            content.add(buildBagCell(baseId, count, iconSize));
            anyShown = true;
        }
        if (!anyShown) {
            JLabel empty = new JLabel("No drops yet.");
            empty.setForeground(new Color(200, 200, 200));
            empty.setFont(mainFont);
            content.add(empty);
        }
        content.revalidate();
        content.repaint();
    }

    private JPanel buildBagCell(int baseId, int count, int iconSize) {
        JPanel cell = new JPanel();
        cell.setOpaque(false);
        cell.setLayout(new BoxLayout(cell, BoxLayout.X_AXIS));
        cell.setBorder(new EmptyBorder(0, 0, 0, 4));

        JLabel iconLabel = new JLabel(ImageBuffer.getOutlinedIcon(baseId, iconSize));
        iconLabel.setToolTipText(LootBags.lootBagName.getOrDefault(baseId, "Bag"));
        cell.add(iconLabel);
        cell.add(Box.createRigidArea(new Dimension(3, 0)));

        JLabel countLabel = new JLabel("x" + count);
        countLabel.setForeground(new Color(240, 240, 240));
        countLabel.setFont(mainFont.deriveFont(Font.BOLD));
        cell.add(countLabel);
        return cell;
    }

    private static int baseBagId(int objectType) {
        for (int[] pair : BAG_ORDER) {
            if (objectType == pair[0] || objectType == pair[1]) return pair[0];
        }
        return -1;
    }

    private static boolean isBagEnabled(int baseId) {
        if (baseId == LootBags.WHITE.getId())  return LootGUI.filterWhiteBag;
        if (baseId == LootBags.ORANGE.getId()) return LootGUI.filterOrangeBag;
        if (baseId == LootBags.RED.getId())    return LootGUI.filterRedBag;
        if (baseId == LootBags.GOLD.getId())   return LootGUI.filterGoldBag;
        if (baseId == LootBags.EGG.getId())    return LootGUI.filterEggBag;
        if (baseId == LootBags.BLUE.getId())   return LootGUI.filterBlueBag;
        if (baseId == LootBags.TEAL.getId())   return LootGUI.filterTealBag;
        if (baseId == LootBags.PURPLE.getId()) return LootGUI.filterPurpleBag;
        if (baseId == LootBags.PINK.getId())   return LootGUI.filterPinkBag;
        if (baseId == LootBags.BROWN.getId())  return LootGUI.filterBrownBag;
        return false;
    }

    // ------------------------------------------------------------------
    // Show / hide / z-order
    // ------------------------------------------------------------------

    private void setVisible(boolean visible) {
        frame.setVisible(visible);
        PropertiesManager.setProperties(PROP_VISIBLE, visible ? "true" : "false");
        if (visible) {
            SwingUtilities.invokeLater(() -> {
                MacOSOverlayHelper.promoteToAllSpacesFloating(frame.getTitle());
                applyLockState();
            });
            applyOpacity();
            rebuild();
            startTopReassertTimer();
        } else {
            stopTopReassertTimer();
            if (controlsWindow != null && controlsWindow.isVisible()) {
                reattachButtonsToTitleBar();
            }
        }
        fireStateChanged();
    }

    private void startTopReassertTimer() {
        stopTopReassertTimer();
        if (IS_MAC) {
            topReassertTimer = new javax.swing.Timer(500, e -> {
                if (!frame.isVisible()) return;
                try {
                    MacOSOverlayHelper.promoteToAllSpacesFloating(frame.getTitle());
                    MacOSOverlayHelper.setIgnoresMouseEvents(frame.getTitle(), locked);
                    if (controlsWindow != null) controlsWindow.reassertOnTop();
                } catch (Exception ignored) {
                }
            });
        } else {
            final int[] tick = {0};
            topReassertTimer = new javax.swing.Timer(500, e -> {
                if (!frame.isVisible()) return;
                try {
                    frame.toFront();
                    if ((++tick[0] % 10) == 0) {
                        if (frame.isAlwaysOnTop()) frame.setAlwaysOnTop(false);
                        frame.setAlwaysOnTop(true);
                    }
                    WindowsOverlayHelper.setIgnoresMouseEvents(frame.getTitle(), locked);
                    if (controlsWindow != null) controlsWindow.reassertOnTop();
                } catch (Exception ignored) {
                }
            });
        }
        topReassertTimer.setRepeats(true);
        topReassertTimer.start();
    }

    private void stopTopReassertTimer() {
        if (topReassertTimer != null) {
            topReassertTimer.stop();
            topReassertTimer = null;
        }
    }

    private void loadPrefs() {
        String l = PropertiesManager.getProperty(PROP_LOCKED);
        if (l != null) locked = l.equals("true");
        String m = PropertiesManager.getProperty(PROP_MINIMIZED);
        if (m != null) minimized = m.equals("true");
    }

    private void toggleMinimized() {
        minimized = !minimized;
        PropertiesManager.setProperties(PROP_MINIMIZED, minimized ? "true" : "false");
        if (minBtn != null) minBtn.setText(minimized ? "\u25A2" : "\u2212");
        applyMinimizedLayout();
    }

    private void applyMinimizedLayout() {
        if (frame == null) return;
        if (minimized) {
            if (savedHeight <= 0) savedHeight = frame.getHeight();
            content.setVisible(false);
            bottomBar.setVisible(false);
            int minHeight = frame.getInsets().top + frame.getInsets().bottom + TITLE_H + lockedExtraPad + 6;
            frame.setSize(frame.getWidth(), minHeight);
        } else {
            content.setVisible(true);
            bottomBar.setVisible(true);
            int restore = savedHeight > TITLE_H + 20 ? savedHeight : DEFAULT_H;
            frame.setSize(frame.getWidth(), restore);
        }
        frame.revalidate();
        frame.repaint();
    }

    // ------------------------------------------------------------------
    // Drag / resize
    // ------------------------------------------------------------------

    private class DragHandler extends MouseAdapter {
        private Point dragStart;
        private Point windowStart;

        @Override
        public void mousePressed(MouseEvent e) {
            if (locked) return;
            dragStart = e.getLocationOnScreen();
            windowStart = frame.getLocation();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (locked || dragStart == null) return;
            Point now = e.getLocationOnScreen();
            frame.setLocation(
                windowStart.x + (now.x - dragStart.x),
                windowStart.y + (now.y - dragStart.y));
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (locked) return;
            Point p = frame.getLocation();
            PropertiesManager.setProperties(PROP_X, Integer.toString(p.x));
            PropertiesManager.setProperties(PROP_Y, Integer.toString(p.y));
        }
    }

    private class ResizeGrip extends JComponent {
        private Point startMouse;
        private Dimension startSize;

        ResizeGrip() {
            setPreferredSize(new Dimension(GRIP_SIZE, GRIP_SIZE));
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (locked) return;
                    startMouse = e.getLocationOnScreen();
                    startSize = frame.getSize();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (locked) return;
                    Dimension d = frame.getSize();
                    PropertiesManager.setProperties(PROP_W, Integer.toString(d.width));
                    if (!minimized) {
                        savedHeight = d.height;
                        PropertiesManager.setProperties(PROP_H, Integer.toString(d.height));
                    }
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (locked || startMouse == null || startSize == null) return;
                    Point now = e.getLocationOnScreen();
                    int nw = Math.max(MIN_W, startSize.width + (now.x - startMouse.x));
                    int nh = Math.max(MIN_H, startSize.height + (now.y - startMouse.y));
                    frame.setSize(nw, nh);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(new Color(180, 180, 180, 160));
            int w = getWidth();
            int h = getHeight();
            for (int i = 0; i < 3; i++) {
                int off = 3 + i * 3;
                g2.drawLine(w - off, h - 2, w - 2, h - off);
            }
            g2.dispose();
        }
    }

    private static int getIntProp(String key, int fallback) {
        String v = PropertiesManager.getProperty(key);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
