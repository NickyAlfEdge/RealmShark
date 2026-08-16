package tomato.gui.dps;

import util.PropertiesManager;

/**
 * Single source of truth for overlay opacity. All three overlays (DPS, Loot,
 * Quest) read their alpha from here so a single control in the menu bar can
 * drive them together.
 *
 * The alpha is in 0..255 space (mapped to a 0.15..1.0 window opacity by the
 * overlay classes themselves) and persists to the shared {@code overlayOpacity}
 * property. Legacy per-overlay {@code *OverlayOpacity} properties are ignored
 * from now on.
 */
public final class OverlayOpacityController {

    public static final int MIN = 40;
    public static final int MAX = 255;
    public static final int DEFAULT = 140;
    private static final String PROP = "overlayOpacity";

    private static int currentAlpha = DEFAULT;

    static {
        String v = PropertiesManager.getProperty(PROP);
        if (v != null) {
            try {
                int a = Integer.parseInt(v);
                if (a >= MIN && a <= MAX) currentAlpha = a;
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private OverlayOpacityController() { }

    public static int getAlpha() {
        return currentAlpha;
    }

    /** Update all overlays and persist. */
    public static void setAlpha(int alpha) {
        currentAlpha = Math.max(MIN, Math.min(MAX, alpha));
        PropertiesManager.setProperties(PROP, Integer.toString(currentAlpha));
        DpsOverlayGUI.refreshOpacity();
        LootOverlayGUI.refreshOpacity();
        QuestOverlayGUI.refreshOpacity();
    }

    /** Standard mapping used by every overlay so they stay visually consistent. */
    public static float alphaToOpacity(int alpha) {
        float span = MAX - MIN;
        float t = span <= 0 ? 1f : (alpha - MIN) / span;
        float minOpacity = 0.15f;
        return Math.max(0f, Math.min(1f, minOpacity + t * (1f - minOpacity)));
    }
}
