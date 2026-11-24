package rabbitrace.automation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import rabbitrace.game.Board;
import rabbitrace.game.BoardGenerator;
import rabbitrace.game.Match;
import rabbitrace.render.Animations;
import rabbitrace.settings.Settings;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The presentation beat clock: every beat
 * with a positive duration must end on its own, not only when a key or click
 * skips it. The shipped default table used to leave the die tumbling forever
 * and made a budget-0 turn unescapable, because the clock thread only advanced
 * the victory and reveal beats.
 *
 * <p>Each test drives a controllable {@link LongSupplier} instead of
 * {@code System.nanoTime}, so "600 ms later" is exact and running the suite
 * takes no wall-clock time. The same table with every row zeroed must keep the
 * one-shot transitions the scripted scenarios rely on.
 */
class BeatClockTest {

    @Test
    void aTumblingDieSettlesWhenItsDurationElapses() throws Exception {
        Animations animations = only(Animations.DIE_TUMBLE, 600);
        TestClock clock = new TestClock();
        Match match = openingMatch();
        try (EventLog log = EventLog.open(null)) {
            Commands commands = controller(match, log, animations, clock);
            assertEquals("ok", commands.handle("press tirar"));
            assertEquals(Match.ROLLING, match.phase(), "a positive tumble starts Rolling");

            clock.advanceMs(599);
            commands.tick();
            assertEquals(Match.ROLLING, match.phase(), "599 ms must not settle the die");

            clock.advanceMs(1);
            commands.tick();
            assertEquals(Match.AWAIT_MOVE, match.phase(),
                    "600 ms must settle the tumble with no input at all");
            assertEquals(match.roll(), match.budget(),
                    "the settled die's pips become the budget on the clock path too");
            assertFalse(state(commands).get("animating").getAsBoolean(),
                    "the tumble is over once the clock settles it");
        }
    }

    /**
     * The tumble fraction the window paints is the same
     * beat's clock, not a second one: half the duration is 0.5, and the settle
     * ends the fraction as well as the phase.
     */
    @Test
    void theLiveTumbleFractionRunsOnTheSameBeatClock() throws Exception {
        Animations animations = only(Animations.DIE_TUMBLE, 600);
        TestClock clock = new TestClock();
        Match match = openingMatch();
        try (EventLog log = EventLog.open(null)) {
            Commands commands = controller(match, log, animations, clock);
            assertEquals(-1, commands.dieTumbleFraction(), 1e-9,
                    "no tumble is live before Tirar");
            commands.handle("press tirar");
            assertEquals(0, commands.dieTumbleFraction(), 1e-9,
                    "the tumble starts at 0 on its own beat clock");

            clock.advanceMs(300);
            commands.tick();
            assertEquals(0.5, commands.dieTumbleFraction(), 1e-6,
                    "half of the 600 ms beat is half the fraction");
            assertEquals(Match.ROLLING, match.phase());

            clock.advanceMs(300);
            commands.tick();
            assertEquals(Match.AWAIT_MOVE, match.phase());
            assertEquals(-1, commands.dieTumbleFraction(), 1e-9,
                    "a settled die has no live tumble fraction");
        }
    }

    @Test
    void aHopEndsWhenItsDurationElapses() throws Exception {
        Animations animations = only(Animations.HOP, 180);
        TestClock clock = new TestClock();
        Match match = openingMatch();
        try (EventLog log = EventLog.open(null)) {
            Commands commands = controller(match, log, animations, clock);
            commands.handle("press tirar");
            int budget = match.budget();
            int[] target = adjacentTarget(match);
            commands.handle("tile " + target[0] + " " + target[1]);
            assertEquals(Match.AWAIT_MOVE, match.phase());
            assertTrue(state(commands).get("animating").getAsBoolean(),
                    "the hop is live right after the step");

            clock.advanceMs(179);
            commands.tick();
            assertTrue(state(commands).get("animating").getAsBoolean(),
                    "179 ms must not end the hop");

            clock.advanceMs(1);
            commands.tick();
            assertEquals(Match.AWAIT_MOVE, match.phase());
            assertFalse(state(commands).get("animating").getAsBoolean(),
                    "180 ms must end the hop with no input");
            assertEquals(budget - 1, match.budget(), "the auto-release spends no extra pip");
        }
    }

    /**
     * The hard lock from the playtest: a move that spends the last pip starts
     * the non-skippable turn-end beat, and the old release only fired when the
     * duration was zero, so the shipped 400 ms made the beat permanent.
     */
    @Test
    void theTurnEndBeatHandsOffAtBudgetZeroWhenItsDurationElapses() throws Exception {
        Animations animations = only(Animations.TURN_END_BEAT, 400);
        TestClock clock = new TestClock();
        Match match = openingMatch();
        try (EventLog log = EventLog.open(null)) {
            Commands commands = controller(match, log, animations, clock);
            commands.handle("press tirar");
            spendEveryPip(commands, match);

            assertEquals(Match.AWAIT_MOVE, match.phase(), "the turn-end beat keeps AwaitMove");
            assertEquals(0, match.budget().intValue());
            assertTrue(state(commands).get("animating").getAsBoolean(),
                    "the turn-end beat is the animation at budget 0");

            clock.advanceMs(399);
            commands.tick();
            assertEquals(Match.AWAIT_MOVE, match.phase(),
                    "399 ms must not hand off yet; the beat is not skippable");
            assertEquals(0, match.budget().intValue());

            clock.advanceMs(1);
            commands.tick();
            assertEquals(Match.AWAIT_ROLL, match.phase(),
                    "400 ms must hand off on its own; this is the budget-0 lock regression");
            assertEquals(Match.BROWN, match.active(), "the next player is on turn");
            assertEquals(2, match.turn());
        }
    }

    @Test
    void theNoMoveBannerOpensTheHandOffWhenItsDurationElapses() throws Exception {
        Animations animations = only(Animations.NO_MOVE, 1200);
        animations.set(Animations.HAND_OFF, 1200);
        TestClock clock = new TestClock();
        Match match = boxedBlue();
        try (EventLog log = EventLog.open(null)) {
            Commands commands = controller(match, log, animations, clock);
            commands.handle("press tirar");
            assertEquals(Match.NO_LEGAL_MOVE, match.phase());

            clock.advanceMs(1199);
            commands.tick();
            assertEquals(Match.NO_LEGAL_MOVE, match.phase(), "1199 ms must not pass the banner");

            clock.advanceMs(1);
            commands.tick();
            assertEquals(Match.HAND_OFF, match.phase(),
                    "1200 ms must open the hand-off banner with no input");
            assertEquals(Match.BROWN, match.active());
            assertEquals(2, match.turn());
        }
    }

    @Test
    void theHandOffBannerReachesAwaitRollWhenItsDurationElapses() throws Exception {
        Animations animations = only(Animations.HAND_OFF, 1200);
        TestClock clock = new TestClock();
        Match match = boxedBlue();
        try (EventLog log = EventLog.open(null)) {
            Commands commands = controller(match, log, animations, clock);
            commands.handle("press tirar");
            assertEquals(Match.HAND_OFF, match.phase(),
                    "a zero no-move duration still leaves the positive hand-off banner");

            clock.advanceMs(1199);
            commands.tick();
            assertEquals(Match.HAND_OFF, match.phase(), "1199 ms must not end the banner");

            clock.advanceMs(1);
            commands.tick();
            assertEquals(Match.AWAIT_ROLL, match.phase(),
                    "1200 ms must hand the turn to the next player with no input");
            assertEquals(Match.BROWN, match.active());
            assertEquals(2, match.turn());
        }
    }

    /** Every row zeroed is still the one-shot table the scripted sessions use. */
    @Test
    void zeroDurationsStillTransitionInTheSameCommand() throws Exception {
        Animations animations = Animations.fromFlag("0");
        TestClock clock = new TestClock();
        Match match = openingMatch();
        try (EventLog log = EventLog.open(null)) {
            Commands commands = controller(match, log, animations, clock);
            commands.handle("press tirar");
            assertEquals(Match.AWAIT_MOVE, match.phase(),
                    "a zero tumble settles inside the Tirar command");
            assertEquals(match.roll(), match.budget());
            assertFalse(state(commands).get("animating").getAsBoolean());

            int[] target = adjacentTarget(match);
            commands.handle("tile " + target[0] + " " + target[1]);
            assertEquals(Match.AWAIT_MOVE, match.phase());
            assertFalse(state(commands).get("animating").getAsBoolean(),
                    "a zero hop never reports an animation");
        }

        Match spent = openingMatch();
        try (EventLog log = EventLog.open(null)) {
            Commands commands = controller(spent, log, animations, clock);
            commands.handle("press tirar");
            spendEveryPip(commands, spent);
            assertEquals(Match.AWAIT_ROLL, spent.phase(),
                    "a zero turn-end beat ends the turn inside the spending command");
        }

        Match boxed = boxedBlue();
        try (EventLog log = EventLog.open(null)) {
            Commands commands = controller(boxed, log, animations, clock);
            commands.handle("press tirar");
            assertEquals(Match.AWAIT_ROLL, boxed.phase(),
                    "zero no-move and hand-off banners still hand off in the roll command");
            assertEquals(Match.BROWN, boxed.active());
        }
    }

    // ------------------------------------------------------------------
    // Fixtures.
    // ------------------------------------------------------------------

    private static Match openingMatch() {
        Match match = Match.start("ABC123");
        assertEquals(Match.BLUE, match.throwHands(2, 1),
                "the fixture's opening throw is decisive for blue");
        return match;
    }

    /** Blue's start is boxed in by three blocking tiles, as the rules script it. */
    private static Match boxedBlue() {
        Match match = Match.start("ABC123");
        match.setTile(1, 8, Board.ROCK);
        match.setTile(2, 7, Board.BUSH);
        match.setTile(3, 8, Board.FENCE);
        assertEquals(Match.BLUE, match.throwHands(2, 1),
                "the fixture's opening throw is decisive for blue");
        return match;
    }

    private static Commands controller(Match match, EventLog log, Animations animations, TestClock clock) {
        return new Commands(match, Settings.defaults(), log, () -> { }, animations, clock);
    }

    /** The default table with every row zeroed except the one under test. */
    private static Animations only(String beat, int millis) {
        Animations animations = Animations.defaults();
        animations.zeroAll();
        animations.set(beat, millis);
        return animations;
    }

    /** Steps one tile at a time until the turn's budget reaches zero. */
    private static void spendEveryPip(Commands commands, Match match) throws Exception {
        while (match.budget() != null && match.budget() > 0) {
            int[] target = adjacentTarget(match);
            assertTrue(commands.handle("tile " + target[0] + " " + target[1]).startsWith("ok"));
        }
    }

    /** A legal target exactly one orthogonal tile away, never the carrot. */
    private static int[] adjacentTarget(Match match) {
        int[] from = match.position(match.active());
        for (int[] target : match.legalTargets()) {
            if (target[0] == BoardGenerator.CARROT_COL && target[1] == BoardGenerator.CARROT_ROW) {
                continue;
            }
            if (Math.abs(target[0] - from[0]) + Math.abs(target[1] - from[1]) == 1) {
                return target;
            }
        }
        throw new AssertionError("the active rabbit has no adjacent legal step");
    }

    private static JsonObject state(Commands commands) throws Exception {
        String reply = commands.handle("state");
        assertTrue(reply.startsWith("ok "), "state replied " + reply);
        return JsonParser.parseString(reply.substring(3)).getAsJsonObject();
    }

    /** A clock the test moves by hand, in milliseconds, independent of wall time. */
    private static final class TestClock implements LongSupplier {

        private long nanos = 1_000_000_000L;

        @Override
        public long getAsLong() {
            return nanos;
        }

        void advanceMs(long millis) {
            nanos += millis * 1_000_000L;
        }
    }
}
