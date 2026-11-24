package rabbitrace.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * The SFX voice pool: four voices, oldest dropped
 * first, and two slots that retrigger a single voice instead of stacking.
 *
 * <p>A voice is one opened cue. {@link AudioDirector#cue(String)} asks this pool
 * before a cue reaches the mixer, so the rule is enforced on the line that
 * actually plays rather than on a counter a test could satisfy alone. A voice
 * that has finished is dropped without stopping anything; a fifth live cue
 * stops the oldest one first.
 */
final class SfxVoices implements AutoCloseable {

    /** The pool holds four SFX voices. */
    static final int MAX_VOICES = 4;

    /** These two are single-voice retriggers, so a fast path never stacks. */
    static final Set<String> SINGLE_VOICE = Set.of("sfx.hop", "sfx.bump.blocked");

    /** One cue as the pool sees it: a real {@code Clip}, or a test double. */
    interface Voice {
        void start();

        void stop();

        boolean isRunning();

        /**
         * The cue's length in sample frames, as the real {@code Clip} says
         * ({@link ClipVoice#frames()}). A voice that never reached a device
         * reports {@code 0}, which is what keeps the dev probe's cue log a
         * record of played clips rather than a counter next to them.
         */
        long frames();
    }

    private record Held(String slot, Voice voice, long sequence) {
    }

    /** One cue that reached a voice and was started, in play order. */
    record Fired(String slot, long frames) {
    }

    private final Deque<Held> active = new ArrayDeque<>();
    private final List<Fired> fired = new ArrayList<>();
    private long nextSequence;

    /**
     * Takes a voice for {@code slot} and starts it: a single-voice slot stops
     * its own running cue first, and a fifth live cue stops the oldest cue in
     * the pool before it is added.
     */
    synchronized void play(String slot, Voice voice) {
        dropFinished();
        Deque<Held> kept = new ArrayDeque<>();
        boolean singleVoice = SINGLE_VOICE.contains(slot);
        for (Held held : active) {
            if (singleVoice && held.slot().equals(slot)) {
                // a retrigger replaces the slot's voice, never adds one.
                held.voice().stop();
            } else {
                kept.addLast(held);
            }
        }
        while (kept.size() >= MAX_VOICES) {
            // the fifth concurrent SFX drops the oldest.
            kept.removeFirst().voice().stop();
        }
        kept.addLast(new Held(slot, voice, nextSequence++));
        active.clear();
        active.addAll(kept);
        voice.start();
        // The dev probe reads this: the clip was started, and its frame length
        // is the clip's, so a cue that never opened one records nothing or 0.
        fired.add(new Fired(slot, voice.frames()));
    }

    /** Every cue this run started, oldest first. The dev-only probe prints it. */
    synchronized List<Fired> fired() {
        return List.copyOf(fired);
    }

    /** The live slots, oldest first. The dev-only {@code audio} probe prints them. */
    synchronized List<String> activeSlots() {
        List<String> slots = new ArrayList<>();
        for (Held held : active) {
            slots.add(held.slot());
        }
        return List.copyOf(slots);
    }

    /** How many voices are live right now; never more than {@link #MAX_VOICES}. */
    synchronized int size() {
        return active.size();
    }

    /** A finished cue frees its voice without stopping anything else. */
    private void dropFinished() {
        active.removeIf(held -> !held.voice().isRunning());
    }

    @Override
    public synchronized void close() {
        for (Held held : active) {
            held.voice().stop();
        }
        active.clear();
    }
}
