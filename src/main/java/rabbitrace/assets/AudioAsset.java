package rabbitrace.assets;

/**
 * One resolved audio slot.
 *
 * <p>Exactly one of {@link #file()} and {@link #synth()} is non-null: a file is
 * a path relative to {@code assets/} that exists and is a WAV, and a synth is a
 * {@link Synth} name from the boot kit. The group and loop flag are the
 * slot's own, so the manifest cannot reclassify a cue.
 *
 * <p>{@link #durationMs()} is the source's real length: the WAV header for a
 * file, the kit's synthesis length for a synth. A loop entry carries its
 * declared {@link #loopStartMs()} and {@link #loopEndMs()}, which the manifest
 * has already checked against {@code 0 <= start < end <= duration}; a non-loop
 * entry carries 0 and 0 because it has no loop points at all.
 */
public record AudioAsset(String id, String group, boolean loop,
                         String file, String synth, double gainDb,
                         long durationMs, long loopStartMs, long loopEndMs) {

    public AudioAsset {
        if ((file == null) == (synth == null)) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' needs exactly one of file and synth");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException(
                    "audio slot '" + id + "' has a negative duration: " + durationMs + " ms");
        }
    }

    /** True when the slot resolved to a committed WAV rather than the kit. */
    public boolean usesFile() {
        return file != null;
    }
}
