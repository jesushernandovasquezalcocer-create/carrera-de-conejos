package rabbitrace.automation;

import org.junit.jupiter.api.Test;
import rabbitrace.game.Match;
import rabbitrace.render.Chrome;
import rabbitrace.render.Rps;
import rabbitrace.settings.Settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The opening throw's pointer path: clicking a hand
 * plate commits the same {@link Match#commitHand} choice the automation
 * {@code rps} command and the hand keys use, a committed column is disabled, and
 * the panel stops hit-testing the moment the throw resolves, so it never steals
 * input from {@code Partida} afterwards.
 */
class RpsPanelTest {

    private static Commands commands(Match match) throws Exception {
        return new Commands(match, Settings.defaults(), EventLog.open(null), () -> { });
    }

    private static Rps.Hand hand(String player, int hand) {
        for (Rps.Hand candidate : Rps.HANDS) {
            if (candidate.player().equals(player) && candidate.hand() == hand) {
                return candidate;
            }
        }
        throw new AssertionError("no " + player + " hand " + hand);
    }

    private static String clickHand(Commands commands, String player, int hand) {
        Chrome.Rect r = hand(player, hand).rect();
        return commands.click(r.x() + r.w() / 2, r.y() + r.h() / 2);
    }

    @Test
    void clickingAHandCommitsTheChoiceAndDisablesThatColumn() throws Exception {
        Match match = Match.start("ABC123");
        Commands commands = commands(match);

        assertEquals("ok committed", clickHand(commands, Match.BLUE, 1),
                "a click on P1's rock plate commits it");
        assertTrue(match.committed(Match.BLUE));
        assertNull(match.revealedHand(Match.BLUE),
                "the committed hand stays secret until both sides are in");

        // A committed column ignores further clicks; the ready card covers the
        // whole column, so a click there is consumed and changes nothing.
        assertEquals("ok", clickHand(commands, Match.BLUE, 2));
        assertNull(match.revealedHand(Match.BLUE));
        Chrome.Rect ready = Rps.columnRect(Match.BLUE);
        assertEquals("ok", commands.click(ready.x() + ready.w() / 2, ready.y() + 20));

        // The panel's heading area is not a hand and commits nothing.
        assertEquals("ok", commands.click(Rps.PANEL.x() + 20, Rps.PANEL.y() + 20));
        assertFalse(match.committed(Match.BROWN));

        // P2's paper beats P1's rock: brown is the first player.
        assertEquals("ok brown", clickHand(commands, Match.BROWN, 2));
        assertEquals(Match.AWAIT_ROLL, match.phase());
        assertEquals(Match.BROWN, match.active());
        assertEquals(1, match.revealedHand(Match.BLUE), "the click really committed rock");
        assertEquals(2, match.revealedHand(Match.BROWN));
    }

    @Test
    void keysAreLiveOnlyForThePendingPlayer() throws Exception {
        Match match = Match.start("ABC123");
        Commands commands = commands(match);

        assertEquals("ok committed", commands.handle("key 1"));
        // P1 is done: a second key must not replace the choice. Brown's paper
        // then reveals P1's hand, which must still be rock, not paper.
        assertEquals("ok", commands.handle("key 2"));
        assertEquals("ok brown", commands.handle("key 8"));
        assertEquals(1, match.revealedHand(Match.BLUE));
        assertEquals(2, match.revealedHand(Match.BROWN));

        // The same for P2: after brown commits, P2's digits are inert.
        Match second = Match.start("ABC123");
        Commands other = commands(second);
        assertEquals("ok committed", other.handle("key 7"));
        assertEquals("ok", other.handle("key 8"));
        assertEquals("ok blue", other.handle("key 2"));
        assertEquals(2, second.revealedHand(Match.BLUE));
        assertEquals(1, second.revealedHand(Match.BROWN),
                "brown's first press (7, rock) is the committed hand");
    }

    @Test
    void thePanelStopsTakingInputOnceTheThrowResolves() throws Exception {
        Match match = Match.start("ABC123");
        Commands commands = commands(match);
        commands.handle("rps 2 1"); // blue wins paper over rock, AwaitRoll

        int[] before = match.position(match.active());
        assertEquals("ok", clickHand(commands, Match.BLUE, 1),
                "a hand plate is inert after the throw");
        assertEquals(Match.AWAIT_ROLL, match.phase());
        assertEquals(Match.BLUE, match.active());
        assertEquals(before[0], match.position(match.active())[0]);
        assertEquals(before[1], match.position(match.active())[1]);

        // The HUD gear still wins over the panel region: it is outside the
        // panel, and the gear path runs before any hand hit-test.
        assertEquals("ok", commands.click(Chrome.GEAR.rect().x() + 10,
                Chrome.GEAR.rect().y() + 10));
        assertEquals(Match.AJUSTES, match.screen());
    }
}
