package tomato.gui.dps;

import tomato.backend.data.Damage;
import tomato.backend.data.Entity;
import tomato.gui.dps.shared.DpsTextFormat;

import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * Shared plumbing for turning DPS data into chat-friendly strings and pushing
 * them into the RotMG Exalt window (falling back to the system clipboard).
 *
 * The overlay's "send top-5" button, the per-row chat buttons in the overlay
 * and main GUI, and the "Chat DPS After Boss" auto-notifier all go through
 * this class so they share formatting rules and focus/injection behaviour.
 *
 * Key-injection contract:
 *  - The message is always written to the clipboard first as a fallback.
 *  - We only synthesize keystrokes AFTER successfully focusing the game window
 *    (Windows: {@code FindWindowW}+{@code SetForegroundWindow};
 *    macOS: {@code osascript}). If focus fails, we do NOT fire keys — they'd
 *    otherwise land in whatever window happens to be foregrounded.
 */
final class DpsChatSender {

    // Windows top-level window title candidates. Tried in order via exact
    // FindWindowW match, then as case-insensitive substring matches against
    // every visible top-level window (EnumWindows). The game's window title
    // varies by build ("RotMGExalt" vs "Realm of the Mad God Exalt" vs
    // versioned strings) — matching multiple candidates lets us locate the
    // running instance without ever launching a fresh one.
    static final String[] WINDOWS_TITLE_CANDIDATES = new String[] {
        "RotMGExalt",
        "RotMG Exalt",
        "Realm of the Mad God Exalt",
        "Realm of the Mad God"
    };

    // Retained for callers that want the primary title string.
    static final String GAME_WINDOW_TITLE = WINDOWS_TITLE_CANDIDATES[0];

    // macOS: LaunchServices application names to try in order. First one that
    // `open -a` recognises wins. Add more entries here if a user's install
    // shows up under a different name. The final entry is used as a
    // fuzzy-match seed for the pgrep fallback.
    static final String[] MAC_APP_CANDIDATES = new String[] {
        "RotMGExalt",
        "RotMG Exalt",
        "Exalt",
        "Realm of the Mad God Exalt"
    };

    private static final boolean IS_MAC =
        System.getProperty("os.name", "").toLowerCase().contains("mac");

    private DpsChatSender() { }

    /**
     * Build a comma-separated single-line summary of the top {@code limit}
     * player damages on {@code entity}. Returns {@code null} when there is
     * nothing meaningful to say.
     *
     * Format: {@code 1: PlayerA - 42.35%, 2: PlayerB - 21.10%, ...}.
     */
    static String buildTopMessage(Entity entity, int limit) {
        if (entity == null) return null;
        List<Damage> damages = entity.getPlayerDamageList();
        if (damages == null || damages.isEmpty()) return null;

        long maxHp = entity.maxHp();
        StringBuilder sb = new StringBuilder();
        int rank = 0;
        for (Damage dmg : damages) {
            if (dmg == null || dmg.owner == null) continue;
            rank++;
            if (rank > limit) break;
            float percent = maxHp > 0 ? ((float) dmg.damage * 100f) / (float) maxHp : 0f;
            if (sb.length() > 0) sb.append(", ");
            sb.append(rank).append(": ")
                .append(dmg.owner.name())
                .append(" - ")
                .append(String.format("%.2f%%", percent));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Build a single-player chat line: {@code {rank}: {name} {damage} - {percent}%}.
     */
    static String buildPlayerLine(int rank, Damage dmg, long maxHp) {
        if (dmg == null || dmg.owner == null) return null;
        float percent = maxHp > 0 ? ((float) dmg.damage * 100f) / (float) maxHp : 0f;
        return rank + ": " + dmg.owner.name()
            + " " + DpsTextFormat.grouped(dmg.damage)
            + " - " + String.format("%.2f%%", percent);
    }

    static void writeToClipboard(String text) {
        if (text == null) return;
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            cb.setContents(new StringSelection(text), null);
        } catch (Throwable ignored) {
            // Clipboard access can fail on headless / restricted environments; silent no-op.
        }
    }

    /**
     * Place {@code text} on the clipboard, then attempt to focus the RotMG
     * Exalt window and synthesize {@code Enter} → paste → {@code Enter}. If
     * focus fails the clipboard is still populated so the user can paste
     * manually. Runs the key-injection on a daemon thread.
     */
    static void sendToGameChat(String text) {
        if (text == null || text.isEmpty()) return;
        writeToClipboard(text);

        final Robot robot;
        try {
            robot = new Robot();
        } catch (Throwable t) {
            return; // clipboard fallback already populated
        }
        // Deliberately slower than the Robot default: many games (RotMG
        // Exalt included) drop the paste modifier when V follows too quickly,
        // producing a stray "v" typed into chat instead of the message.
        robot.setAutoDelay(35);

        Thread t = new Thread(() -> {
            try {
                boolean focused = IS_MAC
                    ? MacOSOverlayHelper.activateAnyApp(MAC_APP_CANDIDATES)
                    : WindowsOverlayHelper.focusAnyWindow(WINDOWS_TITLE_CANDIDATES);
                if (!focused) return;

                // Let the target window actually accept focus before typing.
                Thread.sleep(260);

                int pasteMod = IS_MAC ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;

                // Open chat.
                robot.keyPress(KeyEvent.VK_ENTER);
                robot.keyRelease(KeyEvent.VK_ENTER);
                // Wait long enough for the game to actually show the chat
                // caret; if we start pasting before this the game eats the
                // modifier press and only V ends up in chat.
                robot.delay(220);

                // Paste. Insert explicit delays around the modifier so the
                // key-down for V lands inside the modifier's press window.
                robot.keyPress(pasteMod);
                robot.delay(60);
                robot.keyPress(KeyEvent.VK_V);
                robot.delay(45);
                robot.keyRelease(KeyEvent.VK_V);
                robot.delay(45);
                robot.keyRelease(pasteMod);
                robot.delay(220);

                // Send.
                robot.keyPress(KeyEvent.VK_ENTER);
                robot.keyRelease(KeyEvent.VK_ENTER);
            } catch (Throwable ignored) {
                // Best-effort; clipboard is already populated as a fallback.
            }
        }, "tomato-dps-chat-send");
        t.setDaemon(true);
        t.start();
    }
}
