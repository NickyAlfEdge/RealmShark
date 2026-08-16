package tomato.gui.dps;

import assets.ImageBuffer;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import tomato.Tomato;
import tomato.backend.data.Damage;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.dps.shared.DpsTextFormat;
import tomato.realmshark.enums.CharacterClass;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Floating, always-on-top overlay showing live DPS. Designed to sit over the
 * RotMG Exalt window so players can watch DPS without alt-tabbing.
 *
 * Key UX behaviours:
 * - Translucent background so bullets/enemies remain visible underneath.
 * - Non-focusable so clicking it never steals input from the game.
 * - Periodic {@code toFront()} keeps it above the game window when the game
 *   is clicked (windowed / borderless-fullscreen). See {@link #startTopReassertTimer()}.
 * - Follow-me toggle: when enabled, auto-scrolls to keep the local player's
 *   row in view rather than showing the top of the DPS list.
 *
 * macOS caveat: true "Full Screen" mode (green button, which puts the game in
 * its own Space) hides any always-on-top window from other apps at the OS
 * level. Java-only overlays cannot appear on that Space without native code.
 * Use "Windowed" or "Borderless / Windowed Fullscreen" in the RotMG Exalt
 * client instead.
 */
public class DpsOverlayGUI {

    // ---- persistence keys ----
    private static final String PROP_X = "dpsOverlayX";
    private static final String PROP_Y = "dpsOverlayY";
    private static final String PROP_W = "dpsOverlayW";
    private static final String PROP_H = "dpsOverlayH";
    private static final String PROP_VISIBLE = "dpsOverlayVisible";
    private static final String PROP_FOLLOW = "dpsOverlayFollowMe";
    private static final String PROP_LOCKED = "dpsOverlayLocked";
    private static final String PROP_MINIMIZED = "dpsOverlayMinimized";

    // ---- defaults ----
    private static final int DEFAULT_W = 300;
    private static final int DEFAULT_H = 220;
    private static final int MIN_W = 200;
    private static final int MIN_H = 100;
    private static final int TITLE_H = 20;
    private static final int GRIP_SIZE = 14;

    // Overlay opacity is centralised via OverlayOpacityController; no per-overlay range here.

    private static DpsOverlayGUI INSTANCE;

    private final TomatoData data;
    private JFrame frame;
    private JPanel contentPanel;
    private JScrollPane scrollPane;
    private JPanel bottomBar;
    private JLabel titleLabel;
    private JToggleButton followBtn;
    private JToggleButton lockBtn;
    private JButton minBtn;

    private Font mainFont = new Font("Monospaced", Font.PLAIN, 12);
    private boolean followMe = false;
    private boolean locked = false;
    private boolean minimized = false;
    private int savedHeight = -1; // full-content height while minimized
    private JComponent selfRowRef; // most recently rendered "you" row (may be null)

    private javax.swing.Timer topReassertTimer;

    private static final boolean IS_MAC =
        System.getProperty("os.name", "").toLowerCase().contains("mac");

    private DpsOverlayGUI(TomatoData data) {
        this.data = data;
        loadPrefs();
        buildFrame();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public static void init(TomatoData data) {
        if (INSTANCE == null) INSTANCE = new DpsOverlayGUI(data);
        String vis = PropertiesManager.getProperty(PROP_VISIBLE);
        if (vis != null && vis.equals("true")) {
            INSTANCE.setVisible(true);
        }
    }

    public static boolean isVisible() {
        return INSTANCE != null && INSTANCE.frame != null && INSTANCE.frame.isVisible();
    }

    public static void toggle() {
        if (INSTANCE == null) return;
        INSTANCE.setVisible(!INSTANCE.frame.isVisible());
    }

    public static boolean isLocked() {
        return INSTANCE != null && INSTANCE.locked;
    }

    /** Toggle from outside (menu bar). On macOS with click-through this is the escape hatch. */
    public static void toggleLocked() {
        if (INSTANCE == null) return;
        INSTANCE.setLockedInternal(!INSTANCE.locked);
    }

    // External subscribers (e.g. the main menu bar) invoked whenever
    // lock/visibility state changes, so their checkbox mirrors the overlay button.
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

    /** Called from {@link DpsGUI#updateNewTickPacket(TomatoData)}. */
    public static void updateLive(TomatoData data) {
        if (INSTANCE == null || INSTANCE.frame == null || !INSTANCE.frame.isVisible()) return;
        INSTANCE.rebuild(data);
    }

    /** Called when the main UI font changes. */
    public static void editFont(Font font) {
        if (INSTANCE == null) return;
        // Cap overlay font so a 48pt main-UI font doesn't blow up the overlay.
        int size = Math.min(font.getSize(), 14);
        INSTANCE.mainFont = font.deriveFont((float) size);
        if (INSTANCE.titleLabel != null) INSTANCE.titleLabel.setFont(INSTANCE.mainFont.deriveFont(Font.BOLD));
        if (INSTANCE.frame != null && INSTANCE.frame.isVisible()) INSTANCE.rebuild(INSTANCE.data);
    }

    // ------------------------------------------------------------------
    // Frame construction
    // ------------------------------------------------------------------

    private void buildFrame() {
        frame = new JFrame("Tomato DPS Overlay");
        frame.setUndecorated(true);
        // On macOS we do NOT set Window.Type.UTILITY (maps to NSPanel, restricted
        // Space visibility) and we do NOT use Java's setAlwaysOnTop — both reset
        // the NSWindow level and undo our native NSScreenSaverWindowLevel promotion.
        if (!IS_MAC) {
            frame.setAlwaysOnTop(true);
        }
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        // Non-focusable so we never steal input from the game.
        frame.setFocusableWindowState(false);
        frame.setAutoRequestFocus(false);
        if (Tomato.imagePath != null) {
            frame.setIconImage(Toolkit.getDefaultToolkit().getImage(Tomato.imagePath));
        }

        // Whole-window opacity is the master transparency control. It plays nicely with
        // JFrame decorations and, unlike per-pixel translucency, lets the game show through
        // the entire overlay (background AND text).
        try {
            frame.setOpacity(OverlayOpacityController.alphaToOpacity(OverlayOpacityController.getAlpha()));
        } catch (Throwable ignored) {
        }

        // Best-effort macOS hint to allow the window to appear on other Spaces.
        applyMacFloatingHint();

        JPanel root = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(15, 15, 18));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setColor(new Color(140, 140, 150));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.dispose();
            }
        };
        root.setOpaque(false);

        root.add(buildTitleBar(), BorderLayout.NORTH);

        contentPanel = new JPanel();
        contentPanel.setOpaque(false);
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

        scrollPane = new JScrollPane(contentPanel);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setBorder(new EmptyBorder(2, 6, 2, 6));
        scrollPane.getVerticalScrollBar().setUnitIncrement(20);
        scrollPane.getVerticalScrollBar().setOpaque(false);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        root.add(scrollPane, BorderLayout.CENTER);

        bottomBar = buildBottomBar();
        root.add(bottomBar, BorderLayout.SOUTH);

        frame.setContentPane(root);

        int x = getIntProp(PROP_X, 40);
        int y = getIntProp(PROP_Y, 40);
        int w = Math.max(MIN_W, getIntProp(PROP_W, DEFAULT_W));
        int h = Math.max(MIN_H, getIntProp(PROP_H, DEFAULT_H));
        frame.setBounds(x, y, w, h);

        if (minimized) {
            // Persisted minimized state: hide content immediately after build.
            savedHeight = h;
            SwingUtilities.invokeLater(this::applyMinimizedLayout);
        }
    }

    private JPanel buildTitleBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(2, 8, 2, 4));
        bar.setPreferredSize(new Dimension(10, TITLE_H));

        titleLabel = new JLabel("DPS");
        titleLabel.setForeground(new Color(235, 235, 235));
        titleLabel.setFont(mainFont.deriveFont(Font.BOLD));
        bar.add(titleLabel, BorderLayout.WEST);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        btns.setOpaque(false);

        followBtn = makePillToggle("me", new Color(70, 170, 80));
        followBtn.setSelected(followMe);
        OverlayTooltip.install(followBtn, frame, "Follow me: keep your DPS row in view even as the list scrolls");
        followBtn.addActionListener(e -> {
            followMe = followBtn.isSelected();
            PropertiesManager.setProperties(PROP_FOLLOW, followMe ? "true" : "false");
            if (followMe) scrollToSelfRow();
        });
        btns.add(followBtn);

        lockBtn = new JToggleButton(locked ? "\uD83D\uDD12" : "\uD83D\uDD13");
        lockBtn.setSelected(locked);
        lockBtn.setFocusable(false);
        lockBtn.setMargin(new Insets(0, 4, 0, 4));
        lockBtn.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
        lockBtn.setForeground(new Color(235, 235, 235));
        lockBtn.setContentAreaFilled(false);
        lockBtn.setOpaque(false);
        OverlayTooltip.install(lockBtn, frame, "Lock overlay: freezes position and passes clicks through to the game.");
        lockBtn.addActionListener(e -> {
            locked = lockBtn.isSelected();
            lockBtn.setText(locked ? "\uD83D\uDD12" : "\uD83D\uDD13");
            PropertiesManager.setProperties(PROP_LOCKED, locked ? "true" : "false");
            applyLockState();
            fireStateChanged();
        });
        btns.add(lockBtn);

        minBtn = makeToolButton(minimized ? "\u25A2" : "\u2212"); // filled square = restore, minus = minimize
        OverlayTooltip.install(minBtn, frame, "Minimize / restore overlay contents");
        minBtn.addActionListener(e -> toggleMinimized());
        btns.add(minBtn);

        JButton closeBtn = makeToolButton("\u2715"); // x
        OverlayTooltip.install(closeBtn, frame, "Hide overlay");
        closeBtn.addActionListener(e -> setVisible(false));
        btns.add(closeBtn);

        bar.add(btns, BorderLayout.EAST);

        DragHandler drag = new DragHandler();
        bar.addMouseListener(drag);
        bar.addMouseMotionListener(drag);
        titleLabel.addMouseListener(drag);
        titleLabel.addMouseMotionListener(drag);

        return bar;
    }

    private JPanel buildBottomBar() {
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.setBorder(new EmptyBorder(0, 0, 0, 0));
        bottom.add(new ResizeGrip(), BorderLayout.EAST);
        return bottom;
    }

    /** Collapse the overlay to its title bar, or restore it to the last full size. */
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
            int minHeight = frame.getInsets().top + frame.getInsets().bottom + TITLE_H + 6;
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

    private JButton makeToolButton(String text) {
        JButton b = new JButton(text);
        b.setMargin(new Insets(0, 4, 0, 4));
        b.setFocusable(false);
        b.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
        b.setForeground(new Color(235, 235, 235));
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        return b;
    }

    /**
     * Toggle button styled as a pill: transparent when off, colored rounded
     * background when on, so the on/off state is unambiguous at a glance.
     */
    private JToggleButton makePillToggle(String text, Color activeColor) {
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
        b.setFocusable(false);
        b.setMargin(new Insets(0, 6, 0, 6));
        b.setBorder(BorderFactory.createEmptyBorder(1, 7, 1, 7));
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setRolloverEnabled(false);
        b.setForeground(new Color(235, 235, 235));
        return b;
    }

    // ------------------------------------------------------------------
    // Show / hide / z-order
    // ------------------------------------------------------------------

    private void setVisible(boolean visible) {
        frame.setVisible(visible);
        PropertiesManager.setProperties(PROP_VISIBLE, visible ? "true" : "false");
        if (visible) {
            // Once the NSWindow exists, promote it to a floating panel that shows on all Spaces
            // (including another app's native Full-Screen Space). No-op on non-macOS.
            SwingUtilities.invokeLater(() -> {
                MacOSOverlayHelper.promoteToAllSpacesFloating(frame.getTitle());
                applyLockState();
            });
            applyOpacity();
            rebuild(data);
            startTopReassertTimer();
        } else {
            stopTopReassertTimer();
        }
        fireStateChanged();
    }

    private static float alphaToOpacity(int alpha) {
        return OverlayOpacityController.alphaToOpacity(alpha);
    }

    /** Package-visible entry point used by {@link OverlayOpacityController#setAlpha(int)}. */
    static void refreshOpacity() {
        if (INSTANCE != null) INSTANCE.applyOpacity();
    }

    private void applyOpacity() {
        if (frame == null) return;
        try {
            frame.setOpacity(OverlayOpacityController.alphaToOpacity(OverlayOpacityController.getAlpha()));
        } catch (Throwable ignored) {
        }
        frame.repaint();
    }

    /** Toggle click-through so game input isn't captured when the overlay is locked. */
    private void applyLockState() {
        if (frame == null) return;
        MacOSOverlayHelper.setIgnoresMouseEvents(frame.getTitle(), locked);
        WindowsOverlayHelper.setIgnoresMouseEvents(frame.getTitle(), locked);
    }

    private void startTopReassertTimer() {
        stopTopReassertTimer();
        // Split by platform: on macOS, Java's toFront/alwaysOnTop demote the NSWindow
        // level back to Java's default and clobber our custom NSScreenSaverWindowLevel.
        // Re-apply the native promotion every tick so the overlay stays pinned on top
        // even on the game's own fullscreen Space.
        if (IS_MAC) {
            topReassertTimer = new javax.swing.Timer(500, e -> {
                if (frame == null || !frame.isVisible()) return;
                try {
                    MacOSOverlayHelper.promoteToAllSpacesFloating(frame.getTitle());
                    MacOSOverlayHelper.setIgnoresMouseEvents(frame.getTitle(), locked);
                } catch (Exception ignored) {
                }
            });
        } else {
            // Windows / Linux: toFront + alwaysOnTop is the working pattern.
            final int[] tick = {0};
            topReassertTimer = new javax.swing.Timer(500, e -> {
                if (frame == null || !frame.isVisible()) return;
                try {
                    frame.toFront();
                    if ((++tick[0] % 10) == 0) {
                        if (frame.isAlwaysOnTop()) frame.setAlwaysOnTop(false);
                        frame.setAlwaysOnTop(true);
                    }
                    WindowsOverlayHelper.setIgnoresMouseEvents(frame.getTitle(), locked);
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

    /**
     * Client-property hints honored by the Aqua LAF on macOS. Safe elsewhere.
     */
    private void applyMacFloatingHint() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) return;
        try {
            JRootPane rp = frame.getRootPane();
            rp.putClientProperty("apple.awt.windowShadow", Boolean.FALSE);
            rp.putClientProperty("apple.awt.transparentTitleBar", Boolean.TRUE);
        } catch (Exception ignored) {
        }
    }

    private void loadPrefs() {
        String follow = PropertiesManager.getProperty(PROP_FOLLOW);
        if (follow != null) followMe = follow.equals("true");
        String lock = PropertiesManager.getProperty(PROP_LOCKED);
        if (lock != null) locked = lock.equals("true");
        String min = PropertiesManager.getProperty(PROP_MINIMIZED);
        if (min != null) minimized = min.equals("true");
    }

    // ------------------------------------------------------------------
    // Content rebuild
    // ------------------------------------------------------------------

    private void rebuild(TomatoData data) {
        if (data == null) return;
        SwingUtilities.invokeLater(() -> doRebuild(data));
    }

    private void doRebuild(TomatoData data) {
        selfRowRef = null;
        contentPanel.removeAll();

        MapInfoPacket map = data.map;
        Entity[] hitList = data.getEntityHitList();
        List<Entity> sorted = getSortedEntityList(hitList);
        long dungeonTime = data.dungeonTime();

        String dungeonName = (map != null && map.name != null) ? map.name : "-";
        titleLabel.setText("DPS \u2013 " + dungeonName + DpsGUI.systemTimeToString(dungeonTime));

        Entity player = data.player;
        boolean any = false;

        // "Follow me" should track the currently-active boss (largest getLastDamageTaken),
        // not whichever block happens to be rendered last. Compute the target up front so
        // the row-building code only captures selfRowRef inside that specific entity block.
        Entity mostRecent = findMostRecentlyDamagedEntity(hitList, player);

        for (Entity e : sorted) {
            if (e.maxHp() <= 0) continue;
            if (CharacterClass.isPlayerCharacter(e.objectType)) continue;

            boolean captureSelf = (e == mostRecent);
            JPanel block = buildEntityBlock(e, player, captureSelf);
            if (block != null) {
                contentPanel.add(block);
                contentPanel.add(Box.createRigidArea(new Dimension(0, 2)));
                any = true;
            }
        }

        if (!any) {
            JLabel empty = new JLabel("Waiting for DPS...");
            empty.setForeground(new Color(200, 200, 200));
            empty.setFont(mainFont);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            contentPanel.add(empty);
        }

        contentPanel.revalidate();
        contentPanel.repaint();

        if (followMe) scrollToSelfRow();
    }

    /** Bring the local player's row into view once the layout has settled. */
    private void scrollToSelfRow() {
        SwingUtilities.invokeLater(() -> {
            if (selfRowRef == null) return;
            Rectangle r = SwingUtilities.convertRectangle(
                selfRowRef.getParent(), selfRowRef.getBounds(), contentPanel);
            if (r == null) return;
            // Pad a bit so the row isn't flush against the viewport edge.
            r.grow(0, 16);
            contentPanel.scrollRectToVisible(r);
        });
    }

    private List<Entity> getSortedEntityList(Entity[] entityHitList) {
        if (entityHitList == null) return java.util.Collections.emptyList();
        switch (DpsDisplayOptions.sortOption) {
            case 1:
                return Arrays.stream(entityHitList)
                    .sorted(Comparator.comparingLong(Entity::getFirstDamageTaken).reversed())
                    .collect(Collectors.toList());
            case 2:
                return Arrays.stream(entityHitList)
                    .sorted(Comparator.comparingLong(Entity::maxHp).reversed())
                    .collect(Collectors.toList());
            case 3:
                return Arrays.stream(entityHitList)
                    .sorted(Comparator.comparingLong(Entity::getFightTimer).reversed())
                    .collect(Collectors.toList());
            case 4:
                return Arrays.stream(entityHitList)
                    .filter(Entity::isBossMob)
                    .sorted(Comparator.comparingLong(Entity::maxHp).reversed())
                    .collect(Collectors.toList());
            default:
                return Arrays.stream(entityHitList)
                    .sorted(Comparator.comparingLong(Entity::getLastDamageTaken).reversed())
                    .collect(Collectors.toList());
        }
    }

    private static Entity findMostRecentlyDamagedEntity(Entity[] entities, Entity player) {
        if (entities == null || entities.length == 0) return null;
        Entity best = null;
        long bestTs = Long.MIN_VALUE;
        for (Entity e : entities) {
            if (e == null || e.maxHp() <= 0) continue;
            if (CharacterClass.isPlayerCharacter(e.objectType)) continue;
            // Only consider entities where the local player has dealt damage — otherwise
            // "follow me" would happily chase a boss the user isn't fighting.
            if (player != null && !entityHasUserDamage(e)) continue;
            long ts = e.getLastDamageTaken();
            if (ts > bestTs) {
                bestTs = ts;
                best = e;
            }
        }
        return best;
    }

    private static boolean entityHasUserDamage(Entity entity) {
        List<Damage> damages = entity.getPlayerDamageList();
        if (damages == null) return false;
        for (Damage d : damages) {
            if (d != null && d.owner != null && d.owner.isUser()) return true;
        }
        return false;
    }

    private JPanel buildEntityBlock(Entity entity, Entity player, boolean captureSelfRow) {
        List<Damage> damages = entity.getPlayerDamageList();
        if (damages == null || damages.isEmpty()) return null;

        JPanel block = new JPanel();
        block.setOpaque(false);
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
        block.setAlignmentX(Component.LEFT_ALIGNMENT);
        block.setBorder(new EmptyBorder(2, 2, 4, 2));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        int mobIconSize = Math.max(18, mainFont.getSize() + 8);
        JLabel mobIcon = new JLabel(ImageBuffer.getOutlinedIcon(entity.objectType, mobIconSize));
        header.add(mobIcon);
        header.add(Box.createRigidArea(new Dimension(6, 0)));

        JLabel mobName = new JLabel(entity.name()
            + "  HP:" + DpsTextFormat.grouped(entity.maxHp())
            + "  " + entity.getFightTimerString());
        mobName.setForeground(new Color(245, 220, 180));
        mobName.setFont(mainFont.deriveFont(Font.BOLD));
        header.add(mobName);
        header.add(Box.createHorizontalGlue());
        block.add(header);

        int playerIconSize = Math.max(14, mainFont.getSize() + 2);
        int rank = 0;
        boolean addedRow = false;
        for (Damage dmg : damages) {
            if (dmg == null || dmg.owner == null) continue;
            int filterDecision = Filter.filter(dmg.owner, player);
            if (Filter.shouldFilter() && filterDecision != 1) continue;
            rank++;

            JPanel row = buildPlayerRow(entity, dmg, rank, filterDecision == 2, playerIconSize);
            if (row != null) {
                block.add(row);
                addedRow = true;
                if (captureSelfRow && dmg.owner.isUser()) selfRowRef = row;
            }
        }
        return addedRow ? block : null;
    }

    private JPanel buildPlayerRow(Entity entity, Damage dmg, int rank,
                                  boolean highlight, int iconSize) {
        final boolean isSelf = dmg.owner.isUser();

        JPanel row = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                if (isSelf && followMe) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(new Color(80, 180, 90, 55));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                    g2.dispose();
                }
            }
        };
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(1, 2, 1, 2));

        int iconId = resolveIconId(dmg);
        row.add(new JLabel(ImageBuffer.getOutlinedIcon(iconId, iconSize)));
        row.add(Box.createRigidArea(new Dimension(4, 0)));

        String marker = isSelf ? "->" : (highlight ? ">>" : "  ");
        JLabel rankLabel = new JLabel(String.format("%s%d", marker, rank));
        rankLabel.setForeground(highlight
            ? new Color(255, 215, 90)
            : (isSelf ? new Color(140, 230, 140) : new Color(200, 200, 200)));
        rankLabel.setFont(mainFont);
        row.add(rankLabel);
        row.add(Box.createRigidArea(new Dimension(6, 0)));

        JLabel name = new JLabel(dmg.owner.name());
        name.setForeground(isSelf
            ? new Color(160, 240, 160)
            : (highlight ? new Color(255, 215, 90) : new Color(230, 230, 230)));
        name.setFont(isSelf ? mainFont.deriveFont(Font.BOLD) : mainFont);
        row.add(name);
        row.add(Box.createRigidArea(new Dimension(8, 0)));
        row.add(Box.createHorizontalGlue());

        float percent = entity.maxHp() > 0
            ? ((float) dmg.damage * 100f) / (float) entity.maxHp()
            : 0f;
        float fightMinutes = entity.getFightDuration() / 60000f;
        float dpm = fightMinutes > 0 ? (float) dmg.damage / fightMinutes : 0f;

        JLabel dmgLabel = new JLabel(String.format("%s  %5.2f%%",
            DpsTextFormat.grouped(dmg.damage), percent));
        dmgLabel.setForeground(new Color(240, 240, 240));
        dmgLabel.setFont(mainFont);
        row.add(dmgLabel);

        row.setToolTipText(String.format(
            "<html>%s<br>Damage: %s<br>DPM: %s<br>%% of HP: %.3f%%</html>",
            dmg.owner.name(),
            DpsTextFormat.grouped(dmg.damage),
            DpsTextFormat.fixed2GroupedComma(dpm),
            percent));

        return row;
    }

    private static int resolveIconId(Damage dmg) {
        if (dmg.owner == null) return 0;
        if (dmg.owner.stat != null && dmg.owner.stat.get(StatType.SKIN_ID) != null) {
            int skinId = dmg.owner.stat.get(StatType.SKIN_ID).statValue;
            if (skinId != 0) return skinId;
        }
        return dmg.owner.objectType;
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
            if (locked || frame == null) return;
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
                    // Only remember height while expanded; minimized height is transient.
                    if (!minimized) {
                        savedHeight = d.height;
                        PropertiesManager.setProperties(PROP_H, Integer.toString(d.height));
                    }
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
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

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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
