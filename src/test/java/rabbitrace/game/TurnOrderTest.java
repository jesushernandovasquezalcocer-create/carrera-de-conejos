package rabbitrace.game;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import rabbitrace.automation.Snapshot;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first turn goes to the opening
 * throw's winner, and turns alternate until the match ends.
 *
 * <p>Three surfaces, because each half of the sentence can fail alone. The throw
 * half plays all six decisive hand pairs, so a game that always starts with blue
 * (the nominal first rabbit) fails on the three pairs brown wins; it also rolls
 * once before reading the turn, because a hand-off that fired at the throw would
 * otherwise pass. The alternation half plays seeded matches a single step per
 * turn — the same schedule uses to reach the carrot — and reads the owner
 * and the turn counter before the roll, after the roll and after every hand-off
 * on both sides of the throw. A hand-off that skipped, repeated or failed to flip
 * a player fails on the first turn it happens. The ending half asserts that the
 * winning turn is the last one: the owner and counter do not move again, and
 * every state action is refused.
 *
 * <p>Owner and counter are read back through the automation {@code state} line
 * ({@link Snapshot#of(Match)}), not only from the fields, because that is the
 * turn a scripted session sees. A control requires every driven match to end on
 * the carrot and both players to have taken turns, so neither an empty
 * implementation nor a game that never hands off can pass by absence.
 */
class TurnOrderTest {

    private static final int MATCHES = 40;
    private static final int PLY_LIMIT = 200;
    private static final int BROWN_FIRST_TURNS = 8;
    private static final int[][] STEPS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    @Test
    void theFirstTurnGoesToTheThrowsWinnerAndTurnsAlternateUntilTheMatchEnds() {
        assertEveryDecisiveThrowGivesTheFirstTurnToItsWinner();
        assertTurnsAlternateThroughAWholeMatch();
        assertAlternationHoldsWhenBrownWinsTheThrow();
    }

    // ------------------------------------------------------------------
    // The throw: the winner owns turn 1, whichever rabbit it is.
    // ------------------------------------------------------------------

    /**
     * All six decisive pairs, each on three seeds. The expected winner follows
     * rock-paper-scissors, so three cases are blue and three brown; the loser is
     * asserted to be the other rabbit, not merely "not the expected one".
     */
    private static void assertEveryDecisiveThrowGivesTheFirstTurnToItsWinner() {
        int[][] hands = {{1, 3}, {2, 1}, {3, 2}, {3, 1}, {1, 2}, {2, 3}};
        String[] winners = {Match.BLUE, Match.BLUE, Match.BLUE, Match.BROWN, Match.BROWN, Match.BROWN};

        for (int i = 0; i < hands.length; i++) {
            for (String seed : List.of("000000", "123456", "999999")) {
                Match match = Match.start(seed);
                assertEquals(Match.RPS_SELECT, match.phase(),
                        "seed " + seed + " did not open in RpsSelect");

                String winner = match.throwHands(hands[i][0], hands[i][1]);
                String context = "seed " + seed + " after " + hands[i][0] + " vs " + hands[i][1];

                assertEquals(winners[i], winner,
                        " " + context + " the throw was won by " + winner
                                + ", not the hand that beats the other");
                assertEquals(winners[i], match.active(),
                        " " + context + " the first turn belongs to " + match.active()
                                + ", not the throw winner " + winner);
                assertEquals(Match.other(winners[i]), loser(winners[i]),
                        " " + context + " the throw has no single loser");
                assertNotEquals(match.active(), loser(winners[i]),
                        " " + context + " the loser of the throw took the first turn");
                assertEquals(1, match.turn(),
                        " " + context + " the first turn is numbered " + match.turn());
                assertEquals(Match.AWAIT_ROLL, match.phase(),
                        " " + context + " the throw left the match in " + match.phase());
                assertTurnOwner(match, winners[i], 1, seed, "at the opening throw");

                int roll = match.rollDie();
                assertTrue(roll >= 1 && roll <= 6,
                        " " + context + " rolled " + roll + ", outside 1..6");
                assertTurnOwner(match, winners[i], 1, seed, "after the first roll");
                assertEquals(Match.AWAIT_MOVE, match.phase(),
                        " " + context + " the first roll left the match in " + match.phase());
            }
        }
    }

    // ------------------------------------------------------------------
    // The alternation: a whole match, a single step per turn.
    // ------------------------------------------------------------------

    /**
     * Drives seeded matches, blue first, one orthogonal step per turn toward the
     * carrot. Every turn reads the owner and counter before the roll, after it,
     * and after the hand-off, and expects the other rabbit and the next number.
     * The winning turn is then read a last time: the match ends on the mover's
     * term, so neither the owner nor the counter changes again.
     */
    private static void assertTurnsAlternateThroughAWholeMatch() {
        int completedTurns = 0;
        int wins = 0;

        for (int i = 0; i < MATCHES; i++) {
            String seed = String.format("%06d", i);
            Match match = Match.start(seed);
            String first = match.throwHands(1, 3);
            assertEquals(Match.BLUE, first,
                    "the scripted opening throw of seed " + seed + " must be blue's");

            String expected = first;
            int expectedTurn = 1;
            int plies = 0;
            boolean won = false;

            while (!Match.MATCH_WON.equals(match.phase())) {
                assertTrue(plies++ < PLY_LIMIT,
                        "seed " + seed + " did not reach the carrot in " + PLY_LIMIT
                                + " turns, so the alternating schedule was not followed to the end");
                assertTurnOwner(match, expected, expectedTurn, seed, "before turn " + expectedTurn);
                assertEquals(Match.AWAIT_ROLL, match.phase(),
                        "seed " + seed + " is " + match.phase() + " before turn "
                                + expectedTurn + ", not AwaitRoll");

                int roll = match.rollDie();
                assertTrue(roll >= 1 && roll <= 6,
                        "seed " + seed + " rolled " + roll + ", outside 1..6");
                assertTurnOwner(match, expected, expectedTurn, seed,
                        "after the roll of turn " + expectedTurn);
                assertEquals(Match.AWAIT_MOVE, match.phase(),
                        "seed " + seed + " left AwaitRoll into " + match.phase());

                int[] next = nextStepTowardCarrot(match, seed);
                match.moveTo(next[0], next[1]);
                if (Match.MATCH_WON.equals(match.phase())) {
                    won = true;
                    break;
                }

                assertEquals(Match.AWAIT_MOVE, match.phase(),
                        "seed " + seed + " left AwaitMove on a non-winning step");
                match.endTurn();
                completedTurns++;
                expected = Match.other(expected);
                expectedTurn++;
            }

            assertTrue(won, "seed " + seed + " never reached the carrot, so the match"
                    + " never tested that alternation stops at the end");
            assertTurnOwner(match, expected, expectedTurn, seed, "when the match ended");
            assertEquals(expected, match.winner(),
                    "seed " + seed + " ended with winner " + match.winner()
                            + " instead of the rabbit on turn " + expectedTurn + ", " + expected);

            assertThrows(IllegalStateException.class, match::rollDie,
                    "seed " + seed + " still rolls after the match ended");
            assertThrows(IllegalStateException.class, match::endTurn,
                    "seed " + seed + " still hands off after the match ended");
            match.endTurnIfSpent();
            assertEquals(Match.MATCH_WON, match.phase(),
                    "seed " + seed + " left MatchWon after the match ended");
            assertTurnOwner(match, expected, expectedTurn, seed, "after the match ended");
            wins++;
        }

        assertEquals(MATCHES, wins,
                "only " + wins + " of " + MATCHES + " matches ended on the carrot");
        assertTrue(completedTurns >= MATCHES * 8,
                "only " + completedTurns + " turns were handed off across " + MATCHES
                        + " matches, so alternation was barely exercised");
    }

    // ------------------------------------------------------------------
    // The other first player: brown wins the throw and still alternates.
    // ------------------------------------------------------------------

    /**
     * The same schedule with brown as the throw winner, for a bounded number of
     * turns. The first half would pass a game that alternates only when blue
     * starts; these turns are owned by brown, then blue, then brown again.
     */
    private static void assertAlternationHoldsWhenBrownWinsTheThrow() {
        int completedTurns = 0;

        for (int i = 0; i < MATCHES; i++) {
            String seed = String.format("%06d", i);
            Match match = Match.start(seed);
            String first = match.throwHands(3, 1);
            assertEquals(Match.BROWN, first,
                    "the opening throw of seed " + seed + " must be brown's");

            String expected = first;
            int expectedTurn = 1;
            for (int t = 0; t < BROWN_FIRST_TURNS && !Match.MATCH_WON.equals(match.phase()); t++) {
                assertTurnOwner(match, expected, expectedTurn, seed,
                        "brown-first before turn " + expectedTurn);
                match.rollDie();
                assertTurnOwner(match, expected, expectedTurn, seed,
                        "brown-first after the roll of turn " + expectedTurn);

                int[] next = nextStepTowardCarrot(match, seed);
                match.moveTo(next[0], next[1]);
                if (Match.MATCH_WON.equals(match.phase())) {
                    assertTurnOwner(match, expected, expectedTurn, seed,
                            "brown-first when the match ended");
                    break;
                }
                match.endTurn();
                completedTurns++;
                expected = Match.other(expected);
                expectedTurn++;
            }
        }

        assertTrue(completedTurns >= MATCHES * 6,
                "only " + completedTurns + " brown-first turns were handed off across "
                        + MATCHES + " matches, so the brown-first schedule is barely exercised");
    }

    // ------------------------------------------------------------------
    // Observation and movement helpers.
    // ------------------------------------------------------------------

    /**
     * The turn contract at one instant: the engine fields, the turn counter and
     * the automation {@code state} line must name the same owner and turn.
     */
    private static void assertTurnOwner(Match match, String expectedActive, int expectedTurn,
            String seed, String when) {
        assertEquals(expectedActive, match.active(),
                "seed " + seed + " " + when + " is " + match.active()
                        + "'s turn, expected " + expectedActive);
        assertEquals(expectedTurn, match.turn(),
                "seed " + seed + " " + when + " is turn " + match.turn()
                        + ", expected " + expectedTurn);

        JsonObject state = JsonParser.parseString(Snapshot.of(match)).getAsJsonObject();
        assertEquals(expectedActive, state.get("active").getAsString(),
                "seed " + seed + " " + when + " reports active "
                        + state.get("active").getAsString() + " in state, expected "
                        + expectedActive);
        assertEquals(expectedTurn, state.get("turn").getAsInt(),
                "seed " + seed + " " + when + " reports turn "
                        + state.get("turn").getAsInt() + " in state, expected " + expectedTurn);
        assertEquals(match.phase(), state.get("phase").getAsString(),
                "seed " + seed + " " + when + " reports " + state.get("phase").getAsString()
                        + " in state but the engine is in " + match.phase());
    }

    private static String loser(String winner) {
        return Match.other(winner);
    }

    /**
     * The first step on the shortest route from the active rabbit to the carrot,
     * computed independently of {@link Match}: a 4-way BFS over passable tiles
     * that treats the other rabbit's tile as unlandable and the carrot as
     * terminal. When the other rabbit cuts the only route, any legal adjacent step
     * is taken instead; the other rabbit moves on its own turn, so the route
     * reopens.
     */
    private static int[] nextStepTowardCarrot(Match match, String seed) {
        int[] blocked = match.position(Match.other(match.active()));
        int cols = match.board().cols();
        int rows = match.board().rows();
        int[] start = match.position(match.active());
        int[][] previous = new int[rows * cols][];
        boolean[][] seen = new boolean[rows][cols];
        Deque<int[]> queue = new ArrayDeque<>();
        seen[start[1]][start[0]] = true;
        queue.add(start);
        boolean found = false;
        while (!queue.isEmpty() && !found) {
            int[] cell = queue.removeFirst();
            for (int[] delta : STEPS) {
                int col = cell[0] + delta[0];
                int row = cell[1] + delta[1];
                if (!match.board().passable(col, row) || seen[row][col]) {
                    continue;
                }
                if (col == blocked[0] && row == blocked[1]) {
                    continue;
                }
                seen[row][col] = true;
                previous[row * cols + col] = cell;
                if (isCarrot(col, row)) {
                    found = true;
                    break;
                }
                queue.addLast(new int[]{col, row});
            }
        }
        if (found) {
            LinkedList<int[]> route = new LinkedList<>();
            int[] walk = carrot();
            while (walk[0] != start[0] || walk[1] != start[1]) {
                route.addFirst(walk);
                walk = previous[walk[1] * cols + walk[0]];
            }
            return route.get(0);
        }
        int[] fallback = legalAdjacentStep(match);
        assertNotNull(fallback, "seed " + seed
                + " has no step available, which say a generated board cannot produce");
        return fallback;
    }

    /** Any adjacent passable tile that is not the other rabbit's tile. */
    private static int[] legalAdjacentStep(Match match) {
        int[] from = match.position(match.active());
        int[] other = match.position(Match.other(match.active()));
        for (int[] delta : STEPS) {
            int col = from[0] + delta[0];
            int row = from[1] + delta[1];
            if (!match.board().passable(col, row)) {
                continue;
            }
            if (col == other[0] && row == other[1]) {
                continue;
            }
            return new int[]{col, row};
        }
        return null;
    }

    private static int[] carrot() {
        return new int[]{BoardGenerator.CARROT_COL, BoardGenerator.CARROT_ROW};
    }

    private static boolean isCarrot(int col, int row) {
        return col == BoardGenerator.CARROT_COL && row == BoardGenerator.CARROT_ROW;
    }
}
