package rabbitrace.assets;

import java.util.Arrays;

/**
 * The ten audio slots, in inventory order.
 *
 * <p>The table is the source of truth, not the manifest: an entry may say whatever it
 * likes, but only these ids are slots, each belongs to one group and each says
 * whether it loops. The manifest's {@code audio} array is resolved
 * against this list, so a missing id and an invented one are both visible
 * rather than silently accepted.
 */
public enum AudioSlot {

    UI_CLICK("ui.click", "sfx", false),
    TURN_CHANGE("sfx.turn.change", "sfx", false),
    HOP("sfx.hop", "sfx", false),
    BUMP_BLOCKED("sfx.bump.blocked", "sfx", false),
    DICE_ROLL("sfx.dice.roll", "sfx", false),
    DICE_SETTLE("sfx.dice.settle", "sfx", false),
    RPS_REVEAL("sfx.rps.reveal", "sfx", false),
    WIN("sfx.win", "sfx", false),
    NATURE_LOOP("amb.nature.loop", "ambience", true),
    MENU_MUSIC("music.menu", "music", true);

    private final String id;
    private final String group;
    private final boolean loop;

    AudioSlot(String id, String group, boolean loop) {
        this.id = id;
        this.group = group;
        this.loop = loop;
    }

    /** The dotted slot id, exactly as the manifest writes it. */
    public String id() {
        return id;
    }

    /** {@code sfx}, {@code ambience} or {@code music}. */
    public String group() {
        return group;
    }

    /** True for the two loop slots only. */
    public boolean loop() {
        return loop;
    }

    /** The slot with this id, or {@code null} when the table does not define it. */
    public static AudioSlot byId(String id) {
        return Arrays.stream(values())
                .filter(slot -> slot.id.equals(id))
                .findFirst()
                .orElse(null);
    }
}
