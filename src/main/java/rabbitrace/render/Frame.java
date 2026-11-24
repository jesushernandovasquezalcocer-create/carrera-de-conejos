package rabbitrace.render;

import rabbitrace.game.Board;
import rabbitrace.settings.Settings;

import java.util.List;

/**
 * Everything the painter is allowed to see. Pure data, no window, no clock.
 *
 * <p>{@code opener} is the screen Ajustes was entered from (
 * ): the settings panel is painted over it, and its veil depends on it.
 * It is {@code null} on every other screen.
 *
 * <p>The opening throw's four fields ({@code p1Hand}, {@code p2Hand} and the two
 * commitments) are what the RPS panel needs without the painter reaching into a
 * {@code Match}: a hand is {@code null} until both sides committed, so a hidden
 * choice cannot leak through the frame.
 *
 * <p>{@code endTurnEnabled} is {@code Terminar turno}'s rule as a
 * frame field: {@code AwaitMove} with at least one step taken. The painter dims
 * the button from this value, so it never asks a {@code Match}.
 */
public record Frame(
        Board board,
        int[] blue,
        int[] brown,
        int[] carrot,
        String screen,
        String phase,
        Integer p1Hand,
        Integer p2Hand,
        boolean p1Committed,
        boolean p2Committed,
        String active,
        int turn,
        Integer roll,
        boolean endTurnEnabled,
        String seed,
        long rngCursor,
        String boardHash,
        List<int[]> targets,
        String opener,
        Settings settings,
        String seedField,
        boolean seedValid,
        RenderPlan plan) {
}
