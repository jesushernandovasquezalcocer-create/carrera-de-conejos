package rabbitrace.game;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import rabbitrace.automation.Snapshot;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * a match opens in {@code RpsSelect} and
 * reaches {@code AwaitRoll} only through a decisive throw.
 *
 * <p>Both halves are asserted against the state a scripted session sees, not only
 * against fields. The opening half checks every fresh match, including one started
 * through {@code reseed}, and the automation {@code state} line, so a launch that
 * skipped the throw into {@code AwaitRoll} fails before any input is sent. The
 * refusal half performs every state action available before the throw — roll, end
 * turn, move — and compares the whole snapshot afterwards, so an action that
 * mutated anything while still reporting {@code RpsSelect} also fails.
 *
 * <p>The throw half plays all six decisive hand pairs and reads the phase after
 * each: the turn is reachable this way, whichever rabbit wins. The negative half
 * commits a tie, which by re-shakes rather than awards a turn, and asserts the
 * match is still in {@code RpsSelect} with no roll, no budget and turn 1. Finally,
 * a rematch before the match is won is refused: no-second-throw case is
 * offered from the win screen only, so it is not a second door into
 * {@code AwaitRoll} from the opening throw.
 */
class MatchOpeningTest {

    private static final List<String> SEEDS =
            List.of("000000", "ABC123", "123456", "999999", "ZZZZZZ");

    /** The six decisive hand pairs: blue wins the first three, brown the rest. */
    private static final int[][] DECISIVE = {{1, 3}, {2, 1}, {3, 2}, {3, 1}, {1, 2}, {2, 3}};
    private static final String[] WINNERS = {
        Match.BLUE, Match.BLUE, Match.BLUE, Match.BROWN, Match.BROWN, Match.BROWN
    };

    @Test
    void aMatchOpensInRpsSelectAndReachesAwaitRollOnlyThroughADecisiveThrow() {
        assertFreshMatchesOpenInRpsSelect();
        assertNoStateActionAdvancesTheOpening();
        assertEveryDecisiveThrowReachesAwaitRoll();
        assertATieNeverReachesAwaitRoll();
        assertARevanchaBeforeTheWinIsRefused();
    }

    // ------------------------------------------------------------------
    // A match opens in RpsSelect, live or restarted.
    // ------------------------------------------------------------------

    private static void assertFreshMatchesOpenInRpsSelect() {
        for (String seed : SEEDS) {
            Match match = Match.start(seed);
            String context = "fresh match on seed " + seed;

            assertEquals(Match.RPS_SELECT, match.phase(),
                    context + " opened in " + match.phase() + ", not RpsSelect");
            assertEquals(Match.PARTIDA, match.screen(),
                    context + " opened on screen " + match.screen() + ", not Partida");
            assertEquals(1, match.turn(),
                    context + " opened on turn " + match.turn() + ", not the first turn");
            assertNull(match.roll(),
                    context + " already has roll " + match.roll() + " before the throw");
            assertNull(match.budget(),
                    context + " already has budget " + match.budget() + " before the throw");
            assertTrue(match.legalTargets().isEmpty(),
                    context + " offers " + match.legalTargets().size()
                            + " legal targets before the throw");

            JsonObject state = stateOf(match);
            assertEquals(Match.RPS_SELECT, state.get("phase").getAsString(),
                    context + " reports phase " + state.get("phase").getAsString()
                            + " in the state line");
            assertEquals(Match.PARTIDA, state.get("screen").getAsString(),
                    context + " reports screen " + state.get("screen").getAsString()
                            + " in the state line");
            assertEquals(1, state.get("turn").getAsInt(),
                    context + " reports turn " + state.get("turn").getAsInt()
                            + " in the state line");
            assertTrue(state.get("roll").isJsonNull(),
                    context + " reports roll " + state.get("roll") + " in the state line");
            assertTrue(state.get("budget").isJsonNull(),
                    context + " reports budget " + state.get("budget") + " in the state line");
            assertEquals(0, state.getAsJsonArray("legalTargets").size(),
                    context + " reports legalTargets in the state line");
        }

        // Starting over by seed is a fresh match too, not a resumed one.
        Match reseeded = Match.start("ABC123");
        reseeded.throwHands(1, 3);
        reseeded.rollDie();
        reseeded.reseed("123456");
        assertEquals(Match.RPS_SELECT, reseeded.phase(),
                "a reseeded match opened in " + reseeded.phase() + ", not RpsSelect");
        assertEquals(1, reseeded.turn(),
                "a reseeded match opened on turn " + reseeded.turn());
        assertNull(reseeded.roll(), "a reseeded match kept the previous roll");
        assertNull(reseeded.budget(), "a reseeded match kept the previous budget");

        // No match is live before Nueva partida: that is a null phase, not a turn.
        Match idle = Match.idle("ABC123");
        assertNull(idle.phase(),
                "the idle launch reports phase " + idle.phase() + ", not null");
        assertNotEquals(Match.AWAIT_ROLL, idle.phase(),
                "the idle launch reached AwaitRoll without any throw");
    }

    // ------------------------------------------------------------------
    // Nothing else moves a fresh match out of RpsSelect.
    // ------------------------------------------------------------------

    private static void assertNoStateActionAdvancesTheOpening() {
        for (String seed : SEEDS) {
            Match match = Match.start(seed);
            String before = Snapshot.of(match);

            assertThrows(IllegalStateException.class, match::rollDie,
                    "seed " + seed + " accepted Tirar in RpsSelect");
            assertStillSelect(match, before, seed, "after a refused Tirar");

            assertThrows(IllegalStateException.class, match::endTurn,
                    "seed " + seed + " accepted Terminar turno in RpsSelect");
            assertStillSelect(match, before, seed, "after a refused Terminar turno");

            assertThrows(IllegalStateException.class,
                    () -> match.moveTo(BoardGenerator.CARROT_COL, BoardGenerator.CARROT_ROW),
                    "seed " + seed + " accepted a move in RpsSelect");
            assertStillSelect(match, before, seed, "after a refused move");

            match.endTurnIfSpent();
            assertStillSelect(match, before, seed, "after the spent-turn check");
        }
    }

    private static void assertStillSelect(Match match, String before, String seed, String when) {
        assertEquals(Match.RPS_SELECT, match.phase(),
                "seed " + seed + " left RpsSelect " + when + ", now " + match.phase());
        assertEquals(before, Snapshot.of(match),
                "seed " + seed + " changed the state " + when);
    }

    // ------------------------------------------------------------------
    // Every decisive throw reaches AwaitRoll, on all six hand pairs.
    // ------------------------------------------------------------------

    private static void assertEveryDecisiveThrowReachesAwaitRoll() {
        for (String seed : SEEDS) {
            for (int i = 0; i < DECISIVE.length; i++) {
                Match match = Match.start(seed);
                String context = "seed " + seed + " throw "
                        + DECISIVE[i][0] + " vs " + DECISIVE[i][1];

                assertEquals(Match.RPS_SELECT, match.phase(),
                        context + " did not start in RpsSelect");
                String winner = match.throwHands(DECISIVE[i][0], DECISIVE[i][1]);

                assertEquals(WINNERS[i], winner,
                        context + " was won by " + winner + ", not " + WINNERS[i]);
                assertEquals(Match.AWAIT_ROLL, match.phase(),
                        context + " left the match in " + match.phase() + ", not AwaitRoll");
                assertEquals(Match.AWAIT_ROLL, stateOf(match).get("phase").getAsString(),
                        context + " reports " + stateOf(match).get("phase").getAsString()
                                + " in the state line");
                assertNull(match.roll(),
                        context + " rolled before Tirar");
                assertNull(match.budget(),
                        context + " had a budget before Tirar");
            }
        }
    }

    // ------------------------------------------------------------------
    // A tie is not decisive and hands out no turn.
    // ------------------------------------------------------------------

    private static void assertATieNeverReachesAwaitRoll() {
        Match match = Match.start("ABC123");
        try {
            match.throwHands(2, 2);
        } catch (RuntimeException reShake) {
            // a tie re-shakes with no input. Whether that beat is observable or
            // not, the throw decides nothing and must not award the first turn.
        }
        assertEquals(Match.RPS_SELECT, match.phase(),
                "a tie left the match in " + match.phase() + ", not RpsSelect");
        assertNotEquals(Match.AWAIT_ROLL, match.phase(),
                "a tie reached AwaitRoll although it decides nothing");
        assertNull(match.roll(), "a tie rolled before Tirar");
        assertNull(match.budget(), "a tie produced a budget");
        assertEquals(1, match.turn(), "a tie advanced to turn " + match.turn());
    }

    // ------------------------------------------------------------------
    // Revancha is a win-screen control, not another way in.
    // ------------------------------------------------------------------

    private static void assertARevanchaBeforeTheWinIsRefused() {
        Match match = Match.start("ABC123");
        String boardHash = match.board().hash();

        assertThrows(IllegalStateException.class, match::restart,
                "Revancha before the opening throw reached AwaitRoll");
        assertEquals(Match.RPS_SELECT, match.phase(),
                "a refused Revancha left the match in " + match.phase());
        assertEquals(boardHash, match.board().hash(),
                "a refused Revancha changed the board");

        assertThrows(IllegalStateException.class, match::rematch,
                "a rematch before the opening throw reached AwaitRoll");
        assertEquals(Match.RPS_SELECT, match.phase(),
                "a refused rematch left the match in " + match.phase());

        // Mid-match is still not the win screen.
        match.throwHands(1, 3);
        assertThrows(IllegalStateException.class, match::rematch,
                "a rematch in AwaitRoll replaced the match");
        assertEquals(Match.AWAIT_ROLL, match.phase(),
                "a refused rematch left the match in " + match.phase());
        assertEquals(boardHash, match.board().hash(),
                "a refused rematch changed the board");
    }

    private static JsonObject stateOf(Match match) {
        return JsonParser.parseString(Snapshot.of(match)).getAsJsonObject();
    }
}
