package rabbitrace.automation;

import org.junit.jupiter.api.Test;
import rabbitrace.game.Match;
import rabbitrace.render.Keys;
import rabbitrace.settings.Settings;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keyboard path through the controller (
 * ): the hand keys commit a secret choice, a tie re-shakes with no input,
 * Space rolls and ends the turn, a movement key takes one step, and Esc unwinds
 * one screen layer. The pure map is {@link rabbitrace.render.Keys}; this test
 * proves the actions reach the same {@link Match} methods a pointer press does.
 */
class KeyCommandTest {

    private static Commands commands(Match match) throws Exception {
        return new Commands(match, Settings.defaults(), EventLog.open(null), () -> { });
    }

    @Test
    void theHandKeysCommitASecretChoiceAndATieReshakes() throws Exception {
        Match match = Match.start("ABC123");
        Commands commands = commands(match);

        assertEquals("ok committed", commands.handle("key 1"),
                "P1's hand key must commit blue's choice");
        assertTrue(match.committed(Match.BLUE), "blue must be committed");
        assertNull(match.revealedHand(Match.BLUE),
                "a committed hand stays secret until both sides are in");
        assertEquals("ok", commands.handle("key 2"),
                "a second press for a committed side changes nothing");

        assertEquals("ok tie", commands.handle("key 7"),
                "blue rock and brown's 7 (rock) are a tie");
        assertEquals(Match.RPS_SELECT, match.phase(), "a tie re-selects with no input");
        assertFalse(match.committed(Match.BLUE) || match.committed(Match.BROWN),
                "a tie clears both commitments");
        assertNull(match.openingWinner(), "a tie selects no first player");

        assertEquals("ok committed", commands.handle("key 2"));
        assertEquals("ok tie", commands.handle("key 8"));

        // Scissors against rock: brown wins and takes the first turn.
        assertEquals("ok committed", commands.handle("key 3"));
        assertEquals("ok brown", commands.handle("key 7"));
        assertEquals(Match.AWAIT_ROLL, match.phase());
        assertEquals(Match.BROWN, match.active());
    }

    @Test
    void spaceRollsAndWasdStepsAndSpaceEndsTheTurn() throws Exception {
        Match match = Match.start("ABC123");
        match.throwHands(2, 1); // blue paper beats brown rock; blue is first
        Commands commands = commands(match);

        // `key 1` is not a hand key outside RpsSelect and must not roll.
        assertEquals("ok", commands.handle("key 1"));
        assertEquals(Match.AWAIT_ROLL, match.phase(), "a hand key cannot roll");

        assertTrue(commands.handle("key SPACE").startsWith("ok"));
        assertEquals(Match.AWAIT_MOVE, match.phase(), "Space must roll and settle");
        assertNotNull(match.roll());
        assertEquals(match.roll(), match.budget(), "the pips are the budget");

        int[] start = match.position(match.active());
        assertTrue(commands.handle("key W").startsWith("ok"));
        assertArrayEquals(new int[]{start[0], start[1] - 1}, match.position(match.active()),
                "W takes exactly one step north");
        assertEquals(match.roll() - 1, match.budget(), "a step spends one pip");

        assertTrue(commands.handle("key SPACE").startsWith("ok"));
        assertEquals(Match.AWAIT_ROLL, match.phase(),
                "Space after a step ends the turn and hands off");
        assertEquals(Match.BROWN, match.active());
    }

    @Test
    void escapeUnwindsOneLayerAndTheTituloFieldTakesKeys() throws Exception {
        Match match = Match.start("ABC123");
        Commands commands = commands(match);
        assertEquals("ok", commands.handle("key ESC"));
        assertEquals(Match.PAUSA, match.screen(), "Esc in Partida pauses");
        assertEquals(Match.RPS_SELECT, match.phase(), "Pausa is a screen, not a phase");
        assertEquals("ok", commands.handle("key ESC"));
        assertEquals(Match.PARTIDA, match.screen(), "Esc in Pausa resumes");

        Match idle = Match.idle("ABC123");
        Commands title = commands(idle);
        assertEquals("ok", title.handle("key BACKSPACE"));
        assertEquals("ABC12", idle.seedField(), "Backspace drops the last character");
        assertEquals("ok", title.handle("key o"));
        assertEquals("ABC120", idle.seedField(), "typing normalises O to 0");
        assertEquals("ok", title.handle("key SPACE"));
        assertEquals("ABC120", idle.seedField(), "whitespace is dropped live");
        assertTrue(idle.seedFieldValid());

        // Enter in the field activates Nueva partida.
        assertEquals("ok", title.handle("key ENTER"));
        assertEquals(Match.PARTIDA, idle.screen());
        assertEquals(Match.RPS_SELECT, idle.phase());
        assertEquals("ABC120", idle.board().seed());
    }
}
