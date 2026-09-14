package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import com.google.gson.JsonObject;

/**
 * How cross an entity is, and with whom it has stopped being patient.
 *
 * `minecraft:anger_level` is what a Bedrock warden counts while somebody keeps making noise near it:
 * every provocation adds, time takes away, and past the threshold the entity is angry. Java has an
 * anger timer on a few of its own mobs and no level at all, so the count lives here and the entity
 * publishes it as `anger_level`, which is what a pack's own sounds and filters branch on.
 */
public final class Anger {
    private final int angryThreshold;
    private final int angryBoost;
    private final int decrementInterval;
    private final int annoyingness;
    private final int projectileAnnoyingness;
    private final int maxAnger;
    private final Filter nuisances;
    private int level;
    private int sinceDecrement;

    private Anger(JsonObject component) {
        this.angryThreshold = (int) Goals.number(component, "angry_threshold", 80);
        this.angryBoost = (int) Goals.number(component, "angry_boost", 20);
        this.decrementInterval = Goals.ticks(component, "anger_decrement_interval", 1);
        this.annoyingness = (int) Goals.number(component, "default_annoyingness", 0);
        this.projectileAnnoyingness =
            (int) Goals.number(component, "default_projectile_annoyingness", 0);
        this.maxAnger = (int) Goals.number(component, "max_anger", 100);
        this.nuisances = Goals.filter(component, "nuisance_filter");
    }

    /** Null for an entity whose pack never said it keeps track of being annoyed. */
    public static Anger tryFrom(ServerEntityDefinition definition) {
        JsonObject component = definition.components().get("minecraft:anger_level");
        return component == null ? null : new Anger(component);
    }

    public int level() {
        return level;
    }

    public boolean angry() {
        return level >= angryThreshold;
    }

    /** Whether this is somebody the entity is willing to be annoyed by. */
    public boolean annoyedBy(Filter.Context context) {
        return nuisances.matches(context);
    }

    /**
     * Raises the level by what the provocation is worth, up to the ceiling the pack named.
     *
     * Crossing the threshold costs the boost on top, which is what keeps an entity that has just
     * lost its temper angry for a while rather than for the one tick the last provocation bought.
     */
    public void provoke(boolean fromDistance) {
        boolean was = angry();
        int worth = fromDistance ? projectileAnnoyingness : annoyingness;
        level = Math.min(maxAnger, level + Math.max(1, worth));
        if (!was && angry()) level = Math.min(maxAnger, level + angryBoost);
    }

    /** Lets time take one off, and says whether the level moved so the entity can publish it. */
    public boolean decay(int ticks) {
        if (level <= 0 || decrementInterval <= 0) return false;

        sinceDecrement += ticks;
        if (sinceDecrement < decrementInterval) return false;

        sinceDecrement = 0;
        level--;
        return true;
    }
}
