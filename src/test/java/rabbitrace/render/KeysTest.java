package rabbitrace.render;

import org.junit.jupiter.api.Test;
import rabbitrace.game.Match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The pure keyboard map (
 * ). No window, no match and no Swing type is involved: the map is a
 * function of {@code (screen, phase, key)} alone, so every state of the table
 * is asserted here rather than through a frame or a scripted session.
 */
class KeysTest {

    private static Keys.Action press(String screen, String phase, Keys.Key key) {
        return Keys.action(screen, phase, key);
    }

    private static Keys.Action typed(String screen, String phase, char character) {
        return press(screen, phase, Keys.Key.typed(character));
    }

    private static Keys.Action named(String screen, String phase, Keys.Kind kind) {
        return press(screen, phase, Keys.Key.named(kind));
    }

    // ------------------------------------------------------------------
    // RpsSelect: P1 1/2/3, P2 7/8/9.
    // ------------------------------------------------------------------

    @Test
    void theOpeningThrowSplitsTheHandKeysByPlayer() {
        for (char hand = '1'; hand <= '3'; hand++) {
            assertEquals(Keys.Action.RPS_BLUE,
                    typed(Match.PARTIDA, Match.RPS_SELECT, hand),
                    "P1's hand key " + hand + " must commit blue's choice");
        }
        for (char hand = '7'; hand <= '9'; hand++) {
            assertEquals(Keys.Action.RPS_BROWN,
                    typed(Match.PARTIDA, Match.RPS_SELECT, hand),
                    "P2's hand key " + hand + " must commit brown's choice");
        }
        for (char other : new char[]{'0', '4', '5', '6'}) {
            assertEquals(Keys.Action.NONE,
                    typed(Match.PARTIDA, Match.RPS_SELECT, other),
                    "'" + other + "' is not a hand key in RpsSelect");
        }
        assertEquals(Keys.Action.NONE,
                named(Match.PARTIDA, Match.RPS_SELECT, Keys.Kind.NORTH),
                "an arrow is not a hand key in RpsSelect");
        assertEquals(Keys.Action.NONE,
                named(Match.PARTIDA, Match.RPS_SELECT, Keys.Kind.SPACE),
                "Space has no meaning in RpsSelect");
    }

    // ------------------------------------------------------------------
    // Skippable beats: any key skips, and only Esc unwinds instead.
    // ------------------------------------------------------------------

    @Test
    void anyKeySkipsTheShakeAndEveryOtherSkippableBeat() {
        Keys.Key[] keys = {
                Keys.Key.typed('1'), Keys.Key.typed('w'), Keys.Key.space(),
                Keys.Key.named(Keys.Kind.ENTER), Keys.Key.named(Keys.Kind.BACKSPACE),
                Keys.Key.named(Keys.Kind.NORTH)};
        for (String phase : new String[]{
                Match.RPS_REVEAL, Match.ROLLING, Match.HAND_OFF, Match.NO_LEGAL_MOVE,
                Match.MATCH_WON}) {
            for (Keys.Key key : keys) {
                assertEquals(Keys.Action.SKIP_ANIMATION, press(Match.PARTIDA, phase, key),
                        "any key must be the skip in " + phase + ", including " + key.kind());
            }
        }
        // Esc is the layer unwind, not a skip, even while the shake runs: the
        // controller finishes the beat as part of pausing.
        assertEquals(Keys.Action.PAUSE,
                named(Match.PARTIDA, Match.RPS_REVEAL, Keys.Kind.ESCAPE),
                "Esc in RpsReveal pauses, and the pause skips the shake to completion");
    }

    // ------------------------------------------------------------------
    // AwaitRoll: Space rolls.
    // ------------------------------------------------------------------

    @Test
    void spaceRollsInAwaitRollAndNothingElseDoes() {
        assertEquals(Keys.Action.ROLL, named(Match.PARTIDA, Match.AWAIT_ROLL, Keys.Kind.SPACE));
        assertEquals(Keys.Action.NONE, named(Match.PARTIDA, Match.AWAIT_ROLL, Keys.Kind.ENTER),
                "names the Tirar button or Space, not Enter, as the roll");
        assertEquals(Keys.Action.NONE, typed(Match.PARTIDA, Match.AWAIT_ROLL, 'w'),
                "a movement key cannot roll");
        assertEquals(Keys.Action.NONE, typed(Match.PARTIDA, Match.AWAIT_ROLL, '1'),
                "a hand key cannot roll once the throw is resolved");
    }

    // ------------------------------------------------------------------
    // AwaitMove: WASD/arrows step, Space ends the turn.
    // ------------------------------------------------------------------

    @Test
    void wasdAndTheArrowsTakeOneStepAndSpaceEndsTheTurn() {
        assertEquals(Keys.Action.MOVE_NORTH, typed(Match.PARTIDA, Match.AWAIT_MOVE, 'w'));
        assertEquals(Keys.Action.MOVE_NORTH, typed(Match.PARTIDA, Match.AWAIT_MOVE, 'W'));
        assertEquals(Keys.Action.MOVE_NORTH, named(Match.PARTIDA, Match.AWAIT_MOVE, Keys.Kind.NORTH));
        assertEquals(Keys.Action.MOVE_SOUTH, typed(Match.PARTIDA, Match.AWAIT_MOVE, 's'));
        assertEquals(Keys.Action.MOVE_SOUTH, named(Match.PARTIDA, Match.AWAIT_MOVE, Keys.Kind.SOUTH));
        assertEquals(Keys.Action.MOVE_WEST, typed(Match.PARTIDA, Match.AWAIT_MOVE, 'a'));
        assertEquals(Keys.Action.MOVE_WEST, named(Match.PARTIDA, Match.AWAIT_MOVE, Keys.Kind.WEST));
        assertEquals(Keys.Action.MOVE_EAST, typed(Match.PARTIDA, Match.AWAIT_MOVE, 'd'));
        assertEquals(Keys.Action.MOVE_EAST, named(Match.PARTIDA, Match.AWAIT_MOVE, Keys.Kind.EAST));

        assertEquals(Keys.Action.END_TURN, named(Match.PARTIDA, Match.AWAIT_MOVE, Keys.Kind.SPACE));
        assertEquals(Keys.Action.NONE, typed(Match.PARTIDA, Match.AWAIT_MOVE, 'q'),
                "a letter that is not a movement key does nothing");
        assertEquals(Keys.Action.NONE, typed(Match.PARTIDA, Match.AWAIT_MOVE, '2'),
                "a hand key does nothing during movement");
    }

    // ------------------------------------------------------------------
    // Esc: one layer per press.
    // ------------------------------------------------------------------

    @Test
    void escapeUnwindsExactlyOneLayer() {
        assertEquals(Keys.Action.PAUSE, named(Match.PARTIDA, Match.AWAIT_MOVE, Keys.Kind.ESCAPE));
        assertEquals(Keys.Action.PAUSE, named(Match.PARTIDA, Match.RPS_SELECT, Keys.Kind.ESCAPE));
        assertEquals(Keys.Action.RESUME, named(Match.PAUSA, Match.AWAIT_MOVE, Keys.Kind.ESCAPE));
        assertEquals(Keys.Action.CLOSE_AJUSTES, named(Match.AJUSTES, Match.AWAIT_MOVE, Keys.Kind.ESCAPE));
        assertEquals(Keys.Action.CANCEL_CONFIRM, named(Match.CONFIRMACION, null, Keys.Kind.ESCAPE));
        assertEquals(Keys.Action.NONE, named(Match.TITULO, null, Keys.Kind.ESCAPE));
        assertEquals(Keys.Action.NONE, named(Match.VICTORIA, Match.MATCH_WON, Keys.Kind.ESCAPE));
    }

    // ------------------------------------------------------------------
    // Título seed field: typing, Backspace, Enter.
    // ------------------------------------------------------------------

    @Test
    void theTituloSeedFieldOwnsThePrintableKeys() {
        assertEquals(Keys.Action.TYPE_CHARACTER, typed(Match.TITULO, null, 'a'));
        assertEquals(Keys.Action.TYPE_CHARACTER, typed(Match.TITULO, null, 'U'));
        assertEquals(Keys.Action.TYPE_CHARACTER, typed(Match.TITULO, null, '7'));
        assertEquals(Keys.Action.TYPE_CHARACTER,
                named(Match.TITULO, null, Keys.Kind.SPACE),
                "Space is a field character on Título, not Partida's contextual key");
        assertEquals(Keys.Action.BACKSPACE, named(Match.TITULO, null, Keys.Kind.BACKSPACE));
        assertEquals(Keys.Action.NEW_MATCH, named(Match.TITULO, null, Keys.Kind.ENTER),
                "Enter in the field activates Nueva partida");
        assertEquals(Keys.Action.NONE, named(Match.TITULO, null, Keys.Kind.NORTH),
                "an arrow is not a seed character");
    }

    // ------------------------------------------------------------------
    // Victoria and the global F11.
    // ------------------------------------------------------------------

    @Test
    void victoriaTakesEnterAsRematch() {
        assertEquals(Keys.Action.REMATCH, named(Match.VICTORIA, Match.MATCH_WON, Keys.Kind.ENTER));
        assertEquals(Keys.Action.NONE, named(Match.VICTORIA, Match.MATCH_WON, Keys.Kind.SPACE),
                "names Enter as Revancha; Space is not a second binding");
        assertEquals(Keys.Action.NONE, typed(Match.VICTORIA, Match.MATCH_WON, 'r'));
    }

    @Test
    void f11TogglesFullscreenFromAnyScreen() {
        String[] screens = {Match.TITULO, Match.PARTIDA, Match.PAUSA, Match.AJUSTES,
                Match.VICTORIA, Match.CONFIRMACION};
        for (String screen : screens) {
            assertEquals(Keys.Action.FULLSCREEN, named(screen, Match.AWAIT_ROLL, Keys.Kind.F11),
                    "F11 must toggle from " + screen);
        }
    }

    // ------------------------------------------------------------------
    // Automation chords parse into the same key type Shell produces.
    // ------------------------------------------------------------------

    @Test
    void chordsParseIntoTheSameKeyTypeTheShellProduces() {
        assertEquals(Keys.Kind.SPACE, Keys.chord("SPACE").kind());
        assertEquals(' ', Keys.chord("SPACE").character(), "Space carries the field character");
        assertEquals(Keys.Kind.ENTER, Keys.chord("ENTER").kind());
        assertEquals(Keys.Kind.ENTER, Keys.chord("RETURN").kind());
        assertEquals(Keys.Kind.ESCAPE, Keys.chord("ESC").kind());
        assertEquals(Keys.Kind.BACKSPACE, Keys.chord("BACKSPACE").kind());
        assertEquals(Keys.Kind.F11, Keys.chord("F11").kind());
        assertEquals(Keys.Kind.NORTH, Keys.chord("UP").kind());
        assertEquals(Keys.Kind.SOUTH, Keys.chord("DOWN").kind());
        assertEquals(Keys.Kind.WEST, Keys.chord("LEFT").kind());
        assertEquals(Keys.Kind.EAST, Keys.chord("RIGHT").kind());

        Keys.Key w = Keys.chord("w");
        assertEquals(Keys.Kind.CHARACTER, w.kind(),
                "a letter stays a character: the screen decides whether it moves or types");
        assertEquals('w', w.character());
        assertEquals(Keys.Action.MOVE_NORTH, press(Match.PARTIDA, Match.AWAIT_MOVE, w));
        assertEquals(Keys.Action.TYPE_CHARACTER, press(Match.TITULO, null, w));
        assertEquals('7', Keys.chord("7").character());
        assertNull(Keys.chord("not-a-key"), "an unknown multi-character chord is refused");
        assertNull(Keys.chord(""), "an empty chord is refused");
    }

    @Test
    void aMissingKeyIsAlwaysNoneNeverAStep() {
        assertEquals(Keys.Action.NONE, press(Match.PARTIDA, Match.AWAIT_MOVE, null));
        assertEquals(Keys.Action.NONE, press(Match.PARTIDA, null, Keys.Key.typed('w')));
        assertEquals(Keys.Action.NONE, typed(Match.PAUSA, Match.AWAIT_MOVE, 'w'),
                "a key consumed on Pausa never falls through to the match");
    }
}
