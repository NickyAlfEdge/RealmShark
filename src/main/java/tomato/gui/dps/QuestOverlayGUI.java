package tomato.gui.dps;

import assets.IdToAsset;
import assets.ImageBuffer;
import packets.data.QuestData;
import tomato.Tomato;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;

/**
 * Floating overlay listing active daily quests. Uses the same layout, drag,
 * lock, opacity, and minimize primitives as {@link DpsOverlayGUI} /
 * {@link LootOverlayGUI}.
 *
 * Data source: {@link tomato.gui.TomatoGUI#updateQuests(QuestData[])} pushes
 * the latest quest array here whenever the game emits it.
 */
public class QuestOverlayGUI {

    private static final String PROP_X = "questOverlayX";
    private static final String PROP_Y = "questOverlayY";
    private static final String PROP_W = "questOverlayW";
    private static final String PROP_H = "questOverlayH";
    private static final String PROP_VISIBLE = "questOverlayVisible";
    private static final String PROP_LOCKED = "questOverlayLocked";
    private static final String PROP_MINIMIZED = "questOverlayMinimized";
    private static final String PROP_MARKS_ONLY = "questOverlayMarksOnly";

    private static final int DEFAULT_W = 320;
    private static final int DEFAULT_H = 220;
    private static final int MIN_W = 200;
    private static final int MIN_H = 90;
    private static final int TITLE_H = 20;
    private static final int GRIP_SIZE = 14;

    private static QuestOverlayGUI INSTANCE;

    private JFrame frame;
    private JPanel content;
    private JScrollPane scrollPane;
    private JPanel bottomBar;
    private JPanel titleBar;
    private JPanel titleBtns;
    private JLabel titleLabel;
    private JToggleButton lockBtn;
    private JToggleButton marksBtn;
    private JButton minBtn;
    private OverlayControlsWindow controlsWindow;
    // Extra vertical pixels the (chunkier) locked controls window uses beyond
    // TITLE_H. Applied as extra title-bar preferred height so the content
    // (quest list) shifts down and isn't overlapped by the buttons.
    private int lockedExtraPad = 0;

    private Font mainFont = new Font("Monospaced", Font.PLAIN, 12);
    private boolean locked = false;
    private boolean minimized = false;
    private boolean marksOnly = false;
    private int savedHeight = -1;

    // Most recent quest data, cached so a font change / open triggers a rebuild.
    private QuestData[] lastQuests = new QuestData[0];

    private javax.swing.Timer topReassertTimer;

    private QuestOverlayGUI() {
        loadPrefs();
        buildFrame();
    }

    private static final boolean IS_MAC =
        System.getProperty("os.name", "").toLowerCase().contains("mac");

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public static void init() {
        if (INSTANCE == null) INSTANCE = new QuestOverlayGUI();
        String vis = PropertiesManager.getProperty(PROP_VISIBLE);
        if (vis != null && vis.equals("true")) {
            INSTANCE.setVisible(true);
        }
    }

    public static boolean isVisible() {
        return INSTANCE != null && INSTANCE.frame != null && INSTANCE.frame.isVisible();
    }

    public static boolean isLocked() {
        return INSTANCE != null && INSTANCE.locked;
    }

    public static void toggle() {
        if (INSTANCE == null) INSTANCE = new QuestOverlayGUI();
        INSTANCE.setVisible(!INSTANCE.frame.isVisible());
    }

    public static void toggleLocked() {
        if (INSTANCE == null) return;
        INSTANCE.setLockedInternal(!INSTANCE.locked);
    }

    /** Called from {@link tomato.gui.TomatoGUI#updateQuests(QuestData[])}. */
    public static void update(QuestData[] quests) {
        if (INSTANCE == null) return;
        INSTANCE.lastQuests = quests != null ? quests : new QuestData[0];
        if (INSTANCE.frame.isVisible()) INSTANCE.rebuild();
    }

    public static void editFont(Font font) {
        if (INSTANCE == null) return;
        int size = Math.min(font.getSize(), 14);
        INSTANCE.mainFont = font.deriveFont((float) size);
        if (INSTANCE.titleLabel != null) INSTANCE.titleLabel.setFont(INSTANCE.mainFont.deriveFont(Font.BOLD));
        if (INSTANCE.frame.isVisible()) INSTANCE.rebuild();
    }

    private static Runnable stateListener;

    public static void setStateListener(Runnable listener) {
        stateListener = listener;
    }

    private static void fireStateChanged() {
        Runnable r = stateListener;
        if (r != null) SwingUtilities.invokeLater(r);
    }

    // ------------------------------------------------------------------
    // Frame construction
    // ------------------------------------------------------------------

    private void buildFrame() {
        frame = new JFrame("Tomato Quest Overlay");
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

        content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(2, 6, 2, 6));

        scrollPane = new JScrollPane(content);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setBorder(new EmptyBorder(0, 0, 0, 0));
        scrollPane.getVerticalScrollBar().setUnitIncrement(20);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        root.add(scrollPane, BorderLayout.CENTER);

        bottomBar = new JPanel(new BorderLayout());
        bottomBar.setOpaque(false);
        bottomBar.add(new ResizeGrip(), BorderLayout.EAST);
        root.add(bottomBar, BorderLayout.SOUTH);

        frame.setContentPane(root);

        int x = getIntProp(PROP_X, 360);
        int y = getIntProp(PROP_Y, 40);
        int w = Math.max(MIN_W, getIntProp(PROP_W, DEFAULT_W));
        int h = Math.max(MIN_H, getIntProp(PROP_H, DEFAULT_H));
        frame.setBounds(x, y, w, h);

        if (minimized) {
            savedHeight = h;
            SwingUtilities.invokeLater(this::applyMinimizedLayout);
        }

        rebuild();
    }

    private JPanel buildTitleBar() {
        titleBar = new JPanel(new BorderLayout());
        titleBar.setOpaque(false);
        titleBar.setBorder(new EmptyBorder(2, 8, 2, 4));
        titleBar.setPreferredSize(new Dimension(10, TITLE_H));

        titleLabel = new JLabel("Quests");
        titleLabel.setForeground(new Color(235, 235, 235));
        titleLabel.setFont(mainFont.deriveFont(Font.BOLD));
        titleBar.add(titleLabel, BorderLayout.WEST);

        titleBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        titleBtns.setOpaque(false);

        marksBtn = OverlayButtonStyle.pillToggle("Marks", new Color(70, 170, 80));
        marksBtn.setSelected(marksOnly);
        OverlayTooltip.install(marksBtn, frame, "When enabled, only show quests from the Quests tab (mark rewards).");
        marksBtn.addActionListener(e -> {
            marksOnly = marksBtn.isSelected();
            PropertiesManager.setProperties(PROP_MARKS_ONLY, marksOnly ? "true" : "false");
            rebuild();
        });
        titleBtns.add(marksBtn);

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
        titleLabel.addMouseListener(drag);
        titleLabel.addMouseMotionListener(drag);

        return titleBar;
    }

    private JButton makeToolButton(String text) {
        return OverlayButtonStyle.toolButton(text);
    }

    private JToggleButton makePillToggle(String text, Color activeColor) {
        return OverlayButtonStyle.pillToggle(text, activeColor);
    }

    // ------------------------------------------------------------------
    // Rebuild
    // ------------------------------------------------------------------

    private void rebuild() {
        SwingUtilities.invokeLater(this::doRebuild);
    }

    private void doRebuild() {
        content.removeAll();

        int count = 0;
        for (QuestData q : lastQuests) {
            if (q == null) continue;
            if (!q.repeatable && q.completed) continue;
            if (marksOnly && !isMarksQuest(q)) continue;
            content.add(buildQuestRow(q));
            content.add(Box.createRigidArea(new Dimension(0, 2)));
            count++;
        }
        if (count == 0) {
            String msg = marksOnly
                ? "No marks quests active."
                : "Visit the Daily Quest room to see quests.";
            JLabel empty = new JLabel(msg);
            empty.setForeground(new Color(200, 200, 200));
            empty.setFont(mainFont);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(empty);
        }
        content.revalidate();
        content.repaint();
    }

    /** True if any reward's object name contains "mark of" — the Quests-tab tell. */
    private static boolean isMarksQuest(QuestData q) {
        if (q == null || q.rewards == null) return false;
        for (int id : q.rewards) {
            try {
                String name = IdToAsset.objectName(id);
                if (name != null && name.toLowerCase().contains("mark of")) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private JPanel buildQuestRow(QuestData q) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(1, 0, 1, 0));

        JLabel name = new JLabel(q.name != null ? q.name : "?");
        name.setForeground(new Color(230, 230, 230));
        name.setFont(mainFont);
        row.add(name);
        row.add(Box.createRigidArea(new Dimension(6, 0)));

        try {
            addIcons(row, q.requirements, 16);
        } catch (IOException ignored) {
        }
        row.add(Box.createRigidArea(new Dimension(4, 0)));
        JLabel arrow = new JLabel("\u2192"); // right arrow
        arrow.setForeground(new Color(200, 200, 200));
        arrow.setFont(mainFont);
        row.add(arrow);
        row.add(Box.createRigidArea(new Dimension(4, 0)));
        try {
            addIcons(row, q.rewards, 16);
        } catch (IOException ignored) {
        }
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private void addIcons(JPanel parent, int[] ids, int size) throws IOException {
        if (ids == null) return;
        for (int id : ids) {
            JLabel item = new JLabel(ImageBuffer.getOutlinedIcon(id, size));
            try {
                item.setToolTipText(IdToAsset.objectName(id));
            } catch (Exception ignored) {
            }
            parent.add(item);
            parent.add(Box.createRigidArea(new Dimension(1, 0)));
        }
    }

    // ------------------------------------------------------------------
    // Show / hide / z-order / opacity / lock / minimize
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
     * so the user can still hit Marks / lock / minimize / close. When
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
                "Tomato Quest Overlay Controls", TITLE_H, Tomato.imagePath);
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
     * quest content underneath.
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
            scrollPane.setVisible(false);
            bottomBar.setVisible(false);
            int minHeight = frame.getInsets().top + frame.getInsets().bottom + TITLE_H + lockedExtraPad + 6;
            frame.setSize(frame.getWidth(), minHeight);
        } else {
            scrollPane.setVisible(true);
            bottomBar.setVisible(true);
            int restore = savedHeight > TITLE_H + 20 ? savedHeight : DEFAULT_H;
            frame.setSize(frame.getWidth(), restore);
        }
        frame.revalidate();
        frame.repaint();
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
        String mo = PropertiesManager.getProperty(PROP_MARKS_ONLY);
        if (mo != null) marksOnly = mo.equals("true");
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
