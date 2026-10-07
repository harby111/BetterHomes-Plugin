package dev.basehider;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/** Immutable, validated snapshot of config.yml. A new instance is created on every reload. */
public final class HiderConfig {

    public final Set<String> enabledWorlds;
    public final int hideDistanceY;
    public final int revealDistanceY;
    public final int updateIntervalTicks;
    public final int transitionBudget;
    public final boolean hideEntities;
    public final boolean filterEffects;
    public final boolean debug;

    private HiderConfig(Set<String> enabledWorlds, int hide, int reveal, int interval,
                        int budget, boolean hideEntities, boolean filterEffects, boolean debug) {
        this.enabledWorlds = enabledWorlds;
        this.hideDistanceY = hide;
        this.revealDistanceY = reveal;
        this.updateIntervalTicks = interval;
        this.transitionBudget = budget;
        this.hideEntities = hideEntities;
        this.filterEffects = filterEffects;
        this.debug = debug;
    }

    public static HiderConfig load(FileConfiguration c, Logger log) {
        List<String> worlds = c.getStringList("enabled-worlds");
        Set<String> enabled = new HashSet<>(worlds);

        int hide = Math.max(1, c.getInt("hide-distance-y", 30));
        int reveal = Math.max(0, c.getInt("reveal-distance-y", 16));
        if (reveal > hide) {
            log.warning("reveal-distance-y (" + reveal + ") must be <= hide-distance-y (" + hide
                    + "); using " + hide + ".");
            reveal = hide;
        }

        int horizontal = c.getInt("horizontal-size", 16);
        if (horizontal != 16) {
            log.warning("horizontal-size must be 16 (hiding is per chunk column); ignoring " + horizontal + ".");
        }

        int interval = Math.max(1, c.getInt("update-interval-ticks", 20));
        int budget = Math.max(1, c.getInt("transition-budget", 8));

        return new HiderConfig(enabled, hide, reveal, interval, budget,
                c.getBoolean("hide-entities", true),
                c.getBoolean("filter-effects", true),
                c.getBoolean("debug", false));
    }
}
