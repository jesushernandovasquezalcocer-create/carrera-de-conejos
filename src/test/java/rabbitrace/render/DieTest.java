package rabbitrace.render;

import org.junit.jupiter.api.Test;
import rabbitrace.game.Match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The die's presentation model without a window: every pose
 * is a pure function of the phase, the rolled value and the tumble fraction, so
 * the face cycle is testable at 75 ms granularity and the offline frame probe
 * can pin the rest face.
 */
class DieTest {

    /** 600 ms / 8 faces = 75 ms; 0.11 of the tumble is 66 ms, 0.14 is 84 ms. */
    @Test
    void theFaceCyclesEvery75MsAndStartsOnTheRolledValue() {
        for (int roll = 1; roll <= 6; roll++) {
            assertEquals(roll, Die.pose(Match.ROLLING, roll, 0).face(),
                    "the tumble opens on the value the match already drew");
            assertEquals(roll, Die.pose(Match.ROLLING, roll, 0.11).face(),
                    "at 66 ms the first face is still up");
            assertNotEquals(roll, Die.pose(Match.ROLLING, roll, 0.14).face(),
                    "at 84 ms the next face is up: the cycle is 75 ms at the 600 ms beat");
        }
    }

    /** Every step of the tumble shows a different face, and all are 1–6. */
    @Test
    void everyTumbleStepChangesTheFaceAndStaysInRange() {
        for (int roll = 1; roll <= 6; roll++) {
            int previous = Die.pose(Match.ROLLING, roll, 0).face();
            for (int step = 1; step < Die.STEPS; step++) {
                Die.Pose pose = Die.pose(Match.ROLLING, roll, (step + 0.5) / Die.STEPS);
                assertTrue(pose.face() >= 1 && pose.face() <= 6,
                        "face outside the six: " + pose);
                assertNotEquals(previous, pose.face(),
                        "step " + step + " of roll " + roll + " repeated the previous face");
                previous = pose.face();
            }
        }
    }

    /** Tilt, bob and pop are small, start at rest and are bounded. */
    @Test
    void theTumbleStaysSmallAndBounded() {
        for (int i = 0; i <= 100; i++) {
            Die.Pose pose = Die.pose(Match.ROLLING, 4, i / 100.0);
            assertTrue(Math.abs(pose.tilt()) <= Die.MAX_TILT,
                    "tilt leaves the small range: " + pose);
            assertTrue(Math.abs(pose.offsetY()) <= Die.MAX_BOB,
                    "bob leaves the small range: " + pose);
            assertTrue(pose.scale() >= 1 && pose.scale() <= 1 + Die.POP,
                    "pop leaves the small range: " + pose);
        }
        Die.Pose start = Die.pose(Match.ROLLING, 4, 0);
        Die.Pose end = Die.pose(Match.ROLLING, 4, 1);
        assertEquals(0, start.tilt(), 1e-9, "the tumble starts upright");
        assertEquals(0, start.offsetY(), 1e-9, "the tumble starts at rest height");
        assertEquals(0, end.tilt(), 1e-9, "the tumble settles upright");
        assertEquals(1, end.scale(), 1e-9, "the pop returns to the rest size as it settles");
        // The pop peaks mid-way through its last fifth, not at the settle.
        assertEquals(1 + Die.POP, Die.pose(Match.ROLLING, 4, 0.9).scale(), 1e-9,
                "the settle pop peaks about 60 ms before the die rests");
    }

    /** The rest pose: no live fraction, or a phase with no tumble, is upright and still. */
    @Test
    void withoutALiveTumbleTheDieRestsOnTheRoll() {
        Die.Pose offline = Die.pose(Match.ROLLING, 5, -1);
        assertEquals(5, offline.face(), "the offline probe renders the rolled value");
        assertEquals(0, offline.tilt(), 1e-9);
        assertEquals(0, offline.offsetY(), 1e-9);
        assertEquals(1, offline.scale(), 1e-9);

        Die.Pose settled = Die.pose(Match.AWAIT_MOVE, 6, -1);
        assertEquals(6, settled.face());
        assertEquals(0, settled.tilt(), 1e-9);
        assertEquals(1, settled.scale(), 1e-9);

        Die.Pose waiting = Die.pose(Match.AWAIT_ROLL, null, -1);
        assertEquals(1, waiting.face(), "no die in hand shows the neutral face");
    }

    /** Same inputs, same pose: the face carries no hidden state or random source. */
    @Test
    void thePoseIsDeterministic() {
        for (int i = 0; i < 20; i++) {
            assertEquals(Die.pose(Match.ROLLING, 3, 0.37), Die.pose(Match.ROLLING, 3, 0.37));
        }
    }
}
