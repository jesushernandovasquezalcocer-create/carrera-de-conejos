package rabbitrace.audio;

import org.junit.jupiter.api.Test;
import rabbitrace.game.Match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bed model: one 400 ms
 * crossfade into the ambience, no restart under Pausa, Ajustes or a rematch, and
 * one back to the menu loop. A fake clock pins the envelope, so this cannot pass
 * by measuring a stale snapshot.
 */
class LoopBedsTest {

    private long now = 1_000;

    @Test
    void oneFadeInOneFadeBackAndNoRestartUnderOverlays() {
        LoopBeds beds = new LoopBeds(() -> now);

        // Título: the menu loop, at full gain, started once.
        beds.enter(Match.TITULO);
        LoopBeds.Snapshot title = beds.snapshot();
        assertEquals(LoopBeds.MUSIC_SLOT, title.bed());
        assertFalse(title.crossfading());
        assertEquals(0, title.crossfades());
        assertTrue(title.music().playing());
        assertEquals(1, title.music().starts());
        assertEquals(1.0, title.music().gain(), 0.0);
        assertFalse(title.ambience().playing());
        assertEquals(0, title.ambience().starts());

        // Título → Partida: one fade, ambience started once, envelope at zero.
        beds.enter(Match.PARTIDA);
        LoopBeds.Snapshot first = beds.snapshot();
        assertEquals(LoopBeds.AMBIENCE_SLOT, first.bed());
        assertTrue(first.crossfading());
        assertEquals(LoopBeds.CROSSFADE_MS, first.crossfadeMs());
        assertEquals(1, first.crossfades());
        assertEquals(1, first.ambience().starts());
        assertEquals(1.0, first.music().gain(), 1e-9);
        assertEquals(0.0, first.ambience().gain(), 1e-9);

        // Halfway: both beds are audible, each at half the envelope.
        now += LoopBeds.CROSSFADE_MS / 2;
        LoopBeds.Snapshot middle = beds.snapshot();
        assertEquals(0.5, middle.music().gain(), 1e-9, "the menu loop must still be fading out");
        assertEquals(0.5, middle.ambience().gain(), 1e-9, "the ambience must be fading in");

        // Exactly 400 ms after the start, only the ambience runs.
        now += LoopBeds.CROSSFADE_MS / 2;
        LoopBeds.Snapshot settled = beds.snapshot();
        assertFalse(settled.crossfading());
        assertFalse(settled.music().playing());
        assertEquals(0.0, settled.music().gain(), 0.0);
        assertTrue(settled.ambience().playing());
        assertEquals(1.0, settled.ambience().gain(), 0.0);
        long position = settled.ambience().positionMs();

        // Pausa, Ajustes and a rematch over the board change no bed.
        now += 1_000;
        beds.enter(Match.PAUSA);
        beds.enter(Match.AJUSTES);
        beds.enter(Match.PARTIDA);
        LoopBeds.Snapshot overlaid = beds.snapshot();
        assertEquals(1, overlaid.crossfades(), "no overlay may start a second fade");
        assertEquals(1, overlaid.ambience().starts(), "no overlay may restart the ambience");
        assertTrue(overlaid.ambience().playing());
        assertTrue(overlaid.ambience().positionMs() > position,
                "the ambience must keep playing while the overlays are up");

        // Returning to Título: the ambience fades out and the menu loop is
        // started again, exactly once.
        beds.enter(Match.TITULO);
        LoopBeds.Snapshot back = beds.snapshot();
        assertEquals(LoopBeds.MUSIC_SLOT, back.bed());
        assertTrue(back.crossfading());
        assertEquals(2, back.crossfades());
        assertEquals(2, back.music().starts());

        now += LoopBeds.CROSSFADE_MS;
        LoopBeds.Snapshot ended = beds.snapshot();
        assertFalse(ended.crossfading());
        assertTrue(ended.music().playing());
        assertEquals(1.0, ended.music().gain(), 0.0);
        assertFalse(ended.ambience().playing());
        assertEquals(0.0, ended.ambience().gain(), 0.0);
        assertEquals(1, ended.ambience().starts(), "the ambience was started only once");
    }

    @Test
    void ajustesOpenedFromTituloKeepsTheMenuLoop() {
        LoopBeds beds = new LoopBeds(() -> now);
        beds.enter(Match.TITULO);
        beds.enter(Match.AJUSTES);

        LoopBeds.Snapshot state = beds.snapshot();
        assertEquals(LoopBeds.MUSIC_SLOT, state.bed());
        assertEquals(1, state.music().starts());
        assertTrue(state.music().playing());
        assertFalse(state.crossfading());
        assertEquals(0, state.crossfades());
    }
}
