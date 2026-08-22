package tomato.gui.dps;

import tomato.backend.data.Damage;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;

import java.util.List;

/**
 * Watches the DPS tick stream and fires a top-5 chat message once the boss
 * fight the local player is engaged in concludes.
 *
 * "Concludes" means one thing only: the tracked boss's HP stat has dropped
 * to zero. No timer-based fallback: if the death packet is missed, we simply
 * do not chat.
 *
 * A single fire-latch per tracked boss prevents repeat sends while the boss
 * lingers in the hit list. Switching to a new boss resets the latch.
 *
 * Gated by {@link DpsDisplayOptions#chatDpsAfterBoss} — when the option is
 * off, tracking is torn down each tick so the first tick after re-enabling
 * doesn't misfire on a stale boss.
 */
final class BossFinishNotifier {

    /** Ignore trivial hits (accidental single stray shots on a boss). */
    private static final long MIN_FIGHT_MS = 3000;

    private static int trackedBossId = -1;
    private static boolean fired = false;

    private BossFinishNotifier() { }

    static synchronized void onTick(TomatoData data) {
        if (!DpsDisplayOptions.chatDpsAfterBoss) {
            reset();
            return;
        }
        if (data == null) return;
        Entity[] entities = data.getEntityHitList();
        if (entities == null) return;

        Entity currentBoss = findActiveBoss(entities);
        if (currentBoss != null && trackedBossId != currentBoss.id) {
            trackedBossId = currentBoss.id;
            fired = false;
        }

        if (trackedBossId == -1 || fired) return;

        Entity tracked = findEntityById(entities, trackedBossId);
        if (tracked == null) return; // vanished before HP=0 arrived — do not guess.

        if (tracked.maxHp() > 0 && tracked.hp() <= 0) {
            fireFor(tracked);
        }
    }

    private static void fireFor(Entity boss) {
        fired = true;
        if (boss == null) return;
        if (boss.getFightDuration() < MIN_FIGHT_MS) return;
        String msg = DpsChatSender.buildTopMessage(boss, 5);
        if (msg != null) DpsChatSender.sendToGameChat(msg);
    }

    private static Entity findActiveBoss(Entity[] entities) {
        Entity best = null;
        long bestTs = Long.MIN_VALUE;
        for (Entity e : entities) {
            if (e == null || e.maxHp() <= 0) continue;
            if (!e.isBossMob()) continue;
            if (!entityHasUserDamage(e)) continue;
            long ts = e.getLastDamageTaken();
            if (ts > bestTs) { bestTs = ts; best = e; }
        }
        return best;
    }

    private static Entity findEntityById(Entity[] entities, int id) {
        for (Entity e : entities) {
            if (e != null && e.id == id) return e;
        }
        return null;
    }

    private static boolean entityHasUserDamage(Entity entity) {
        List<Damage> damages = entity.getPlayerDamageList();
        if (damages == null) return false;
        for (Damage d : damages) {
            if (d != null && d.owner != null && d.owner.isUser()) return true;
        }
        return false;
    }

    private static void reset() {
        trackedBossId = -1;
        fired = false;
    }
}
