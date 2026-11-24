package rabbitrace.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import rabbitrace.game.Match;
import rabbitrace.automation.Commands;
import rabbitrace.automation.EventLog;
import rabbitrace.settings.Settings;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The wiring half of the keyboard fix: a real Swing {@link KeyEvent} delivered
 * to the shell's canvas reaches the match through the same controller the
 * automation uses. The pure map is covered without Swing
 * in {@link KeysTest}; this smoke proves the window is connected to it, which is
 * exactly the defect an automation-only suite could not see.
 *
 * <p>It needs a display (the canvas is a Swing peer) and is skipped in a
 * headless run, where no window exists by definition.
 */
class ShellKeyTest {

    private Shell shell;

    @AfterEach
    void dispose() throws Exception {
        if (shell != null) {
            onEdt(() -> {
                shell.close();
                return null;
            });
            shell = null;
        }
    }

    @Test
    void aSpaceKeyEventRollsTheDie() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "no window exists in a headless run, so there is no key target");

        Match match = Match.start("ABC123");
        match.throwHands(2, 1); // blue wins the throw and is in AwaitRoll
        assertEquals(Match.AWAIT_ROLL, match.phase());

        try (EventLog log = EventLog.open(null)) {
            Commands commands = new Commands(match, Settings.defaults(), log, () -> { });
            shell = onEdt(() -> {
                Shell created = new Shell("key-smoke", () -> null, (x, y) -> { },
                        key -> commands.key(key));
                // A key event is only dispatched to a displayable component, so
                // the smoke shows the real shell briefly at a small size. Focus
                // is not required: the event goes straight to the canvas.
                created.frame().setSize(320, 180);
                created.show();
                return created;
            });

            onEdt(() -> {
                pressKey(KeyEvent.VK_SPACE, ' ');
                return null;
            });
            assertEquals(Match.AWAIT_MOVE, match.phase(),
                    "a real Space KeyEvent must roll and settle the die");
            assertNotNull(match.budget(), "the settled die must have become a budget");

            int[] before = match.position(match.active());
            int budget = match.budget();
            onEdt(() -> {
                // 'w' is a character event on the shell; the screen decides that
                // it moves one tile north in AwaitMove.
                pressKey(KeyEvent.VK_W, 'w');
                return null;
            });
            assertArrayEquals(new int[]{before[0], before[1] - 1}, match.position(match.active()),
                    "a real W KeyEvent must take exactly one step north");
            assertEquals(budget - 1, match.budget(), "the step must spend exactly one pip");
        }
    }

    private void pressKey(int code, char character) {
        KeyEvent event = new KeyEvent(shell.frame().getContentPane(), KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, code, character);
        shell.frame().getContentPane().dispatchEvent(event);
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(task.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }
}
