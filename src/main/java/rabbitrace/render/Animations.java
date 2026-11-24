package rabbitrace.render;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The animation tunable table: one duration per beat, in
 * milliseconds, and the only source of them.
 *
 * <p>The table starts on the shipped defaults. A zero duration means the beat is
 * not observable, which is how a scripted session waits on no clock:
 * {@code -Dautomation.animations=0} zeroes every entry, and the dev hook's
 * {@code animations} command zeroes or restores them.
 */
public final class Animations {

    /** RPS shake before reveal, 900 ms, skippable. */
    public static final String RPS_SHAKE = "rpsShake";
    /** Die tumble, 600 ms, skippable. */
    public static final String DIE_TUMBLE = "dieTumble";
    /** Hop, per tile, 180 ms, skippable. */
    public static final String HOP = "hop";
    /** Turn-end beat at budget 0, 400 ms, not skippable. */
    public static final String TURN_END_BEAT = "turnEndBeat";
    /** Hand-off banner, 1200 ms, skippable. */
    public static final String HAND_OFF = "handOff";
    /** No-move banner, 1200 ms, skippable. */
    public static final String NO_MOVE = "noMove";
    /**
     * Beat after the winning hop, before Victoria appears, 400 ms, not
     * skippable. It is outside table but still a
     * duration of the one tunable table, so a scripted session can zero it.
     */
    public static final String VICTORY_BEAT = "victoryBeat";

    private static final Map<String, Integer> DEFAULTS;

    static {
        Map<String, Integer> defaults = new LinkedHashMap<>();
        defaults.put(RPS_SHAKE, 900);
        defaults.put(DIE_TUMBLE, 600);
        defaults.put(HOP, 180);
        defaults.put(TURN_END_BEAT, 400);
        defaults.put(HAND_OFF, 1200);
        defaults.put(NO_MOVE, 1200);
        defaults.put(VICTORY_BEAT, 400);
        DEFAULTS = Collections.unmodifiableMap(defaults);
    }

    private final Map<String, Integer> durations = new LinkedHashMap<>(DEFAULTS);

    /** The table on the defaults. */
    public static Animations defaults() {
        return new Animations();
    }

    /**
     * The table a launch starts on: every entry zeroed when
     * {@code -Dautomation.animations=0}, otherwise the defaults.
     */
    public static Animations fromFlag(String value) {
        Animations table = new Animations();
        if ("0".equals(value)) {
            table.zeroAll();
        }
        return table;
    }

    /** One duration in milliseconds. */
    public int get(String name) {
        Integer value = durations.get(canonical(name));
        if (value == null) {
            throw new IllegalArgumentException("no such animation: " + name);
        }
        return value;
    }

    /** Sets one duration; the dev hook's per-beat form of {@code animations}. */
    public void set(String name, int millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("an animation duration cannot be negative: " + millis);
        }
        String key = canonical(name);
        if (!durations.containsKey(key)) {
            throw new IllegalArgumentException("no such animation: " + name);
        }
        durations.put(key, millis);
    }

    /** Zeroes every duration ({@code animations 0}, {@code -Dautomation.animations=0}). */
    public void zeroAll() {
        durations.replaceAll((name, millis) -> 0);
    }

    /** Restores every default ({@code animations 1}). */
    public void restoreDefaults() {
        durations.putAll(DEFAULTS);
    }

    /** True while at least one beat still has a duration, so a sounding run may play. */
    public boolean enabled() {
        return durations.values().stream().anyMatch(millis -> millis > 0);
    }

    /** The table in order, for the state dump. */
    public Map<String, Integer> asMap() {
        return Collections.unmodifiableMap(durations);
    }

    private static String canonical(String name) {
        String trimmed = name == null ? "" : name.trim();
        for (String key : DEFAULTS.keySet()) {
            if (key.equalsIgnoreCase(trimmed)) {
                return key;
            }
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
