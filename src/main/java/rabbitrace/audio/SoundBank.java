package rabbitrace.audio;

import rabbitrace.assets.AudioAsset;
import rabbitrace.assets.AudioManifest;
import rabbitrace.assets.AudioSlot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The runtime cue bank: the slots this run can
 * actually play, resolved once from {@code assets/manifest.json}.
 *
 * <p>The failure rule lives here. A missing manifest, a
 * missing slot, an unreadable WAV or an entry that breaks a rule leaves that
 * slot silent with one log line, and never throws into the game. A silent slot
 * is simply absent from the bank, so firing it is a no-op, and a failure in one
 * entry does not disturb any other slot or any game state (audio is never
 * game state).
 */
public final class SoundBank {

    private final Map<String, AudioAsset> playable;

    private SoundBank(Map<String, AudioAsset> playable) {
        this.playable = playable;
    }

    /** Loads the manifest, logging failures to {@code System.err}. */
    public static SoundBank load(Path manifestFile) {
        return load(manifestFile, System.err::println);
    }

    /** Loads the manifest, sending each failure's one line to {@code log}. */
    public static SoundBank load(Path manifestFile, Consumer<String> log) {
        AudioManifest manifest = AudioManifest.loadForPlayback(manifestFile, log);
        Map<String, AudioAsset> resolved = new LinkedHashMap<>();
        for (AudioAsset asset : manifest.entries()) {
            resolved.put(asset.id(), asset);
        }
        return new SoundBank(resolved);
    }

    /** A bank with every slot silent, for a run that has no manifest to read. */
    public static SoundBank silent() {
        return new SoundBank(new LinkedHashMap<>());
    }

    /** True when this slot has no playable entry and firing it is a no-op. */
    public boolean isSilent(String slot) {
        return !playable.containsKey(slot);
    }

    /** The resolved slot, or empty when it is silent. */
    public Optional<AudioAsset> find(String slot) {
        return Optional.ofNullable(playable.get(slot));
    }

    /** The slots that are silent, in inventory order. */
    public List<String> silentSlots() {
        List<String> silent = new ArrayList<>();
        for (AudioSlot slot : AudioSlot.values()) {
            if (!playable.containsKey(slot.id())) {
                silent.add(slot.id());
            }
        }
        return List.copyOf(silent);
    }

    /** How many slots can be played; the rest are silent. */
    public int size() {
        return playable.size();
    }
}
