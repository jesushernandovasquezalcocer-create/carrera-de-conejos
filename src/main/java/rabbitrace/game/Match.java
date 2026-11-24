package rabbitrace.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The match state seam: board, rabbits and the turn machine.
 *
 * <p>The rules implemented here are the ones the automation needs to script a whole
 * match: the opening throw, the roll, one step at a time along a
 * shortest legal path, the turn hand-off, the winning step and the rematch. The
 * finer legality guarantees and the screen surfaces are later
 * work; this class keeps only the state they read.
 *
 * <p>Match is AWT-free and holds no wall clock
 * Every draw goes through the match-seed stream.
 */
public final class Match {

    /** Phase names, /. */
    public static final String RPS_SELECT = "RpsSelect";
    public static final String RPS_REVEAL = "RpsReveal";
    public static final String AWAIT_ROLL = "AwaitRoll";
    public static final String ROLLING = "Rolling";
    public static final String AWAIT_MOVE = "AwaitMove";
    public static final String NO_LEGAL_MOVE = "NoLegalMove";
    public static final String HAND_OFF = "HandOff";
    public static final String MATCH_WON = "MatchWon";

    /** Screen names. */
    public static final String TITULO = "Titulo";
    public static final String PARTIDA = "Partida";
    public static final String PAUSA = "Pausa";
    public static final String AJUSTES = "Ajustes";
    public static final String VICTORIA = "Victoria";
    public static final String CONFIRMACION = "Confirmacion";

    public static final String BLUE = "blue";
    public static final String BROWN = "brown";

    /** The fixed neighbour order N, E, S, W. */
    private static final int[][] NEIGHBOURS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private Board board;
    private String screen;
    /** The screen before {@link #screen}; Ajustes paints over it. */
    private String previousScreen;
    /**
     * The Título seed field's live text. It is not match
     * state: it may be invalid (five characters, a stray {@code U}) while the
     * board it previews stays the last valid one, so a required seed never
     * becomes an exception at the keyboard.
     */
    private String seedField;
    private String phase;
    private String active;
    private int turn;
    private Integer roll;
    private Integer budget;
    private int stepsThisTurn;
    private int[] blue;
    private int[] brown;
    private String winner;

    /** The opening throw: committed choices stay private until both are in. */
    private Integer p1Choice;
    private Integer p2Choice;
    private boolean throwRevealed;
    private String openingWinner;

    /** Rolls consumed from the die stream; part of {@code rngCursor}. */
    private long rollCount;

    Match(Board board) {
        this.board = board;
        this.screen = PARTIDA;
        this.phase = RPS_SELECT;
        this.active = BLUE;
        this.turn = 1;
        this.blue = BoardGenerator.BLUE_START.clone();
        this.brown = BoardGenerator.BROWN_START.clone();
        this.seedField = board.seed();
    }

    /** A fresh match; it opens on the rock-paper-scissors throw. */
    public static Match start(String rawSeed) {
        return Rules.begin(rawSeed);
    }

    /**
     * The launch state when no match is live: the
     * board for the seed field is preselected, but {@code phase} is {@code null}
     * until {@code Nueva partida} or the {@code seed} command starts one.
     */
    public static Match idle(String rawSeed) {
        Match match = new Match(BoardGenerator.generate(Seed.normalise(rawSeed)));
        match.screen = TITULO;
        match.phase = null;
        return match;
    }

    /**
     * Fills the Título seed field without starting a
     * match: the field is the preselected board of an idle match, so
     * {@code Nueva partida} later starts the opening throw on this seed. A
     * paused match underneath is left untouched: the field names the next
     * match, not the one in memory.
     */
    public void preselect(String rawSeed) {
        this.seedField = Seed.normalise(rawSeed);
        if (phase == null) {
            reset(BoardGenerator.generate(seedField), BLUE, null);
            this.screen = TITULO;
        }
    }

    /** The Título seed field's live text. */
    public String seedField() {
        return seedField;
    }

    /** True while the field names a valid seed, so {@code Nueva partida} is live. */
    public boolean seedFieldValid() {
        return Seed.isValid(seedField);
    }

    /**
     * One keystroke's worth of typing in the Título seed field (
     * ): normalised live and never rejected, so an invalid intermediate
     * value stays visible. A valid value previews the board it names, unless a
     * paused match is in memory; then only the field moves.
     */
    public void typeSeed(String typed) {
        this.seedField = Seed.typing(seedField + typed);
        if (phase == null && seedFieldValid()) {
            reset(BoardGenerator.generate(seedField), BLUE, null);
            this.screen = TITULO;
        }
    }

    /** {@code Backspace} in the Título seed field: drop the last character. */
    public void backspaceSeed() {
        if (!seedField.isEmpty()) {
            this.seedField = seedField.substring(0, seedField.length() - 1);
        }
    }

    /**
     * A fresh match on a new seed, previous loser first, no second throw
     * The seed draw is the only random source outside the
     * match stream and it never enters game state as a random value.
     *
     * <p>{@code Revancha} is offered only on the win screen (
     * ), so a rematch before the match is won is refused. That is what keeps
     * {@code AwaitRoll} reachable from {@code RpsSelect} only through a decisive
     * opening throw; the rematch's own {@code AwaitRoll} is the
     * no-second-throw case defines.
     */
    public void rematch() {
        if (!MATCH_WON.equals(phase)) {
            throw new IllegalStateException(
                    "Revancha is only offered after a won match, not " + phase);
        }
        String loser = BROWN.equals(winner) ? BLUE : BROWN;
        String previous = board.seed();
        String next = Seed.random();
        // The rematch seed must differ; the draw is secure, not a proof.
        while (next.equals(previous)) {
            next = Seed.random();
        }
        reset(BoardGenerator.generate(next), loser, AWAIT_ROLL);
    }

    /** {@code seed <6 char>}: a fresh match on that seed, throw included. */
    public void reseed(String rawSeed) {
        reset(BoardGenerator.generate(rawSeed), BLUE, RPS_SELECT);
    }

    /** The {@code restart} command: same as {@code Revancha}. */
    public void restart() {
        rematch();
    }

    /**
     * {@code Menú} from Victoria ends the match: the board
     * stays preselected for the seed field, but no phase is live.
     */
    public void endMatch() {
        this.screen = TITULO;
        this.phase = null;
        this.roll = null;
        this.budget = null;
        this.stepsThisTurn = 0;
        this.winner = null;
    }

    private void reset(Board board, String firstPlayer, String startPhase) {
        this.board = board;
        this.seedField = board.seed();
        this.screen = PARTIDA;
        this.phase = startPhase;
        this.active = firstPlayer;
        this.turn = 1;
        this.roll = null;
        this.budget = null;
        this.stepsThisTurn = 0;
        this.blue = BoardGenerator.BLUE_START.clone();
        this.brown = BoardGenerator.BROWN_START.clone();
        this.winner = null;
        this.p1Choice = null;
        this.p2Choice = null;
        this.throwRevealed = false;
        this.openingWinner = null;
        this.rollCount = 0;
    }

    // ------------------------------------------------------------------
    // Opening throw. Choices are 1 rock, 2 paper, 3 scissors.
    // ------------------------------------------------------------------

    /** One resolved throw: both revealed hands and the winner, or {@code null} on a tie. */
    public record ThrowResult(int p1, int p2, String winner) {
    }

    /**
     * Commits one player's throw choice. The chosen hand is
     * kept private until the other player has committed too: the only reader is
     * {@link #revealedHand(String)}, and it returns {@code null} while the choice
     * is still secret. Returns the reveal when this commit completes the throw,
     * and {@code null} while the other side is still choosing.
     *
     * <p>A tie re-shakes automatically: the throw resolves to nothing, both
     * commitments are cleared, and the match stays in {@code RpsSelect} with no
     * first player selected. The caller supplies no further input for that.
     */
    public ThrowResult commitHand(String player, int hand) {
        if (!RPS_SELECT.equals(phase)) {
            throw new IllegalStateException("the opening throw is already resolved");
        }
        requireHand(hand);
        boolean first = BLUE.equals(player);
        if (!first && !BROWN.equals(player)) {
            throw new IllegalArgumentException("a throw choice belongs to blue or brown: " + player);
        }
        if (first ? p1Choice != null : p2Choice != null) {
            throw new IllegalStateException(player + " has already committed a throw choice");
        }
        if (first) {
            p1Choice = hand;
        } else {
            p2Choice = hand;
        }
        if (p1Choice == null || p2Choice == null) {
            return null;
        }

        int p1 = p1Choice;
        int p2 = p2Choice;
        if (p1 == p2) {
            // ties re-shake with no input and select no first player.
            p1Choice = null;
            p2Choice = null;
            throwRevealed = false;
            openingWinner = null;
            return new ThrowResult(p1, p2, null);
        }
        active = beats(p1, p2) ? BLUE : BROWN;
        throwRevealed = true;
        openingWinner = active;
        phase = AWAIT_ROLL;
        return new ThrowResult(p1, p2, active);
    }

    /** Commits both choices and resolves the throw. Returns the winner, or null on a tie. */
    public String throwHands(int p1, int p2) {
        commitHand(BLUE, p1);
        ThrowResult result = commitHand(BROWN, p2);
        return result.winner();
    }

    /**
     * Holds a decisive throw in {@code RpsReveal} while the shake plays
     * A zero {@code rpsShake} duration never enters
     * this phase, so the winner reaches {@code AwaitRoll} at once.
     */
    public void beginReveal() {
        if (!AWAIT_ROLL.equals(phase)) {
            throw new IllegalStateException("only a decisive throw can be revealed, not " + phase);
        }
        phase = RPS_REVEAL;
    }

    /** Ends the shake; the reveal stands and the winner is in {@code AwaitRoll}. */
    public void finishReveal() {
        if (!RPS_REVEAL.equals(phase)) {
            throw new IllegalStateException("no throw is being revealed, but " + phase);
        }
        phase = AWAIT_ROLL;
    }

    /** A committed hand, or {@code null} while it is still secret or after a tie. */
    public Integer revealedHand(String player) {
        if (!throwRevealed) {
            return null;
        }
        return BLUE.equals(player) ? p1Choice : BROWN.equals(player) ? p2Choice : null;
    }

    /** True once that player has committed, without exposing what was chosen. */
    public boolean committed(String player) {
        if (BLUE.equals(player)) {
            return p1Choice != null;
        }
        if (BROWN.equals(player)) {
            return p2Choice != null;
        }
        throw new IllegalArgumentException("a throw choice belongs to blue or brown: " + player);
    }

    /** The opening throw's winner, or {@code null} before a decisive reveal. */
    public String openingWinner() {
        return openingWinner;
    }

    private static void requireHand(int hand) {
        if (hand < 1 || hand > 3) {
            throw new IllegalArgumentException("a throw choice is 1, 2 or 3: " + hand);
        }
    }

    private static boolean beats(int mine, int theirs) {
        return (mine == 1 && theirs == 3) || (mine == 2 && theirs == 1) || (mine == 3 && theirs == 2);
    }

    // ------------------------------------------------------------------
    // Roll. The k-th roll is a pure function of the match seed and k.
    // ------------------------------------------------------------------

    /**
     * Throws the die for the active player: the value is
     * drawn once and becomes public, but no budget exists until it settles.
     * Accepted only in {@code AwaitRoll}, so a press anywhere else is refused.
     */
    public int startRoll() {
        if (!AWAIT_ROLL.equals(phase)) {
            throw new IllegalStateException("Tirar is only accepted in AwaitRoll, not " + phase);
        }
        rollCount++;
        int value = die(board.seed(), rollCount);
        roll = value;
        budget = null;
        stepsThisTurn = 0;
        phase = ROLLING;
        return value;
    }

    /**
     * Settles a tumbling die: the pips become the budget and movement unlocks
     * Movement is refused until this happens. When no
     * orthogonal neighbour of the active rabbit is free, the turn does not wait for
     * input in {@code AwaitMove}: it enters {@code NoLegalMove} instead
     *
     */
    public int settleRoll() {
        if (!ROLLING.equals(phase)) {
            throw new IllegalStateException("a die can only settle in Rolling, not " + phase);
        }
        budget = roll;
        stepsThisTurn = 0;
        phase = hasFreeNeighbour() ? AWAIT_MOVE : NO_LEGAL_MOVE;
        return roll;
    }

    /**
     * whether the active rabbit has at least one orthogonal
     * neighbour that is not blocking. The other rabbit is traversable,
     * so it never closes a neighbour; a board edge is not a neighbour.
     */
    private boolean hasFreeNeighbour() {
        int[] from = position(active);
        for (int[] step : NEIGHBOURS) {
            int col = from[0] + step[0];
            int row = from[1] + step[1];
            if (board.inside(col, row) && !board.blocking(col, row)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Throws and settles in one step: a zero-duration animation table
     * has no observable tumble.
     */
    public int rollDie() {
        int value = startRoll();
        return settleRoll();
    }

    /** the k-th roll of a match is a pure function of seed and k. */
    static int die(String seed, long k) {
        SplitMix64 stream = new SplitMix64(
                Fnv.hash64("rabbit-race/die/" + BoardGenerator.VERSION + "/" + seed));
        for (long i = 1; i < k; i++) {
            stream.nextLong();
        }
        return 1 + stream.nextInt(6);
    }

    // ------------------------------------------------------------------
    // Movement.
    // ------------------------------------------------------------------

    /**
     * Commits the shortest legal path to {@code (col, row)} and returns its steps as
     * [fromCol, fromRow, toCol, toRow]. Ties break N, E, S, W; the carrot is a
     * terminal tile only.
     */
    public List<int[]> moveTo(int col, int row) {
        if (!AWAIT_MOVE.equals(phase)) {
            throw new IllegalStateException("movement is only accepted in AwaitMove, not " + phase);
        }
        int[] from = position(active);
        List<int[]> path = shortestPath(from, col, row);
        if (path == null) {
            throw new IllegalArgumentException("no legal path to " + col + "," + row);
        }
        if (path.size() > budget) {
            throw new IllegalArgumentException("the path costs " + path.size()
                    + ", the remaining budget is " + budget);
        }
        List<int[]> steps = new ArrayList<>();
        for (int[] next : path) {
            int[] previous = position(active);
            place(active, next);
            budget--;
            stepsThisTurn++;
            steps.add(new int[]{previous[0], previous[1], next[0], next[1]});
        }
        boolean won = col == BoardGenerator.CARROT_COL && row == BoardGenerator.CARROT_ROW;
        if (won) {
            phase = MATCH_WON;
            winner = active;
        }
        return steps;
    }

    /**
     * Whether {@code Terminar turno} would be accepted now (
     * ): the match is in {@code AwaitMove} and the active rabbit has
     * taken at least one step. This is the same predicate the HUD button paints
     * enabled and the pointer path hit-tests, so a dim button is an inert one.
     */
    public boolean canEndTurn() {
        return AWAIT_MOVE.equals(phase) && stepsThisTurn > 0;
    }

    /** Ends the active player's turn ({@code Terminar turno}) and hands off. */
    public void endTurn() {
        if (!AWAIT_MOVE.equals(phase)) {
            throw new IllegalStateException("Terminar turno is only accepted in AwaitMove, not " + phase);
        }
        if (stepsThisTurn == 0) {
            throw new IllegalStateException("a turn needs at least one step before it can end");
        }
        handOff();
    }

    /** At budget 0 the turn ends by itself. */
    public void endTurnIfSpent() {
        if (AWAIT_MOVE.equals(phase) && budget != null && budget == 0) {
            handOff();
        }
    }

    /**
     * The no-move banner ends: the die was
     * already rolled and is spent, so the turn hands off without a step.
     */
    public void passNoLegalMove() {
        if (!NO_LEGAL_MOVE.equals(phase)) {
            throw new IllegalStateException("NoLegalMove is not the current phase, but " + phase);
        }
        advanceToHandOff();
    }

    /** The hand-off banner ends: the next player is in {@code AwaitRoll}. */
    public void finishHandOff() {
        if (!HAND_OFF.equals(phase)) {
            throw new IllegalStateException("HandOff is not the current phase, but " + phase);
        }
        phase = AWAIT_ROLL;
    }

    private void handOff() {
        advanceToHandOff();
        phase = AWAIT_ROLL;
    }

    private void advanceToHandOff() {
        active = other(active);
        turn++;
        roll = null;
        budget = null;
        stepsThisTurn = 0;
        phase = HAND_OFF;
    }

    private void place(String rabbit, int[] cell) {
        if (BLUE.equals(rabbit)) {
            blue = cell.clone();
        } else {
            brown = cell.clone();
        }
    }

    public int[] position(String rabbit) {
        return BLUE.equals(rabbit) ? blue.clone() : brown.clone();
    }

    private int[] otherPosition(String rabbit) {
        return BLUE.equals(rabbit) ? brown : blue;
    }

    public static String other(String rabbit) {
        return BLUE.equals(rabbit) ? BROWN : BLUE;
    }

    /**
     * Whether a click on {@code (col, row)} would commit a legal path
     * in {@code AwaitMove}, the tile is free, a shortest
     * path exists, and it costs no more than the remaining budget. A tile that is
     * not targetable is rendered greyed and its click is ignored, so this is the
     * one predicate both the painter and the pointer path read.
     */
    public boolean targetable(int col, int row) {
        if (!AWAIT_MOVE.equals(phase) || budget == null) {
            return false;
        }
        int[] from = position(active);
        if (col == from[0] && row == from[1]) {
            return false;
        }
        List<int[]> path = shortestPath(from, col, row);
        return path != null && path.size() <= budget;
    }

    /**
     * The tiles the active rabbit may legally end this turn on
     * reachable within the remaining budget, the
     * carrot as a terminal only, and never the other rabbit's tile.
     */
    public List<int[]> legalTargets() {
        if (!AWAIT_MOVE.equals(phase) || budget == null) {
            return List.of();
        }
        int[] from = position(active);
        int[] blocked = otherPosition(active);
        List<int[]> targets = new ArrayList<>();
        int remaining = budget;
        for (int row = 0; row < board.rows(); row++) {
            for (int col = 0; col < board.cols(); col++) {
                if (col == from[0] && row == from[1]) {
                    continue;
                }
                if (col == blocked[0] && row == blocked[1]) {
                    continue;
                }
                List<int[]> path = shortestPath(from, col, row);
                if (path != null && path.size() <= remaining) {
                    targets.add(new int[]{col, row});
                }
            }
        }
        return targets;
    }

    /**
     * BFS over free tiles with the fixed neighbour order N, E, S, W. The carrot is
     * recorded as a target but never expanded, so no path traverses it. The
     * other rabbit may be traversed but not occupied.
     */
    private List<int[]> shortestPath(int[] from, int goalCol, int goalRow) {
        if (!board.inside(goalCol, goalRow) || board.blocking(goalCol, goalRow)) {
            return null;
        }
        if (goalCol == from[0] && goalRow == from[1]) {
            return null;
        }
        int[] blocked = otherPosition(active);
        if (goalCol == blocked[0] && goalRow == blocked[1]) {
            return null;
        }
        int cols = board.cols();
        int rows = board.rows();
        int[][] previous = new int[rows * cols][];
        boolean[][] seen = new boolean[rows][cols];
        Deque<int[]> queue = new ArrayDeque<>();
        seen[from[1]][from[0]] = true;
        queue.add(from);
        while (!queue.isEmpty()) {
            int[] cell = queue.removeFirst();
            for (int[] delta : NEIGHBOURS) {
                int c = cell[0] + delta[0];
                int r = cell[1] + delta[1];
                if (!board.inside(c, r) || seen[r][c] || board.blocking(c, r)) {
                    continue;
                }
                seen[r][c] = true;
                previous[r * cols + c] = cell;
                if (c == BoardGenerator.CARROT_COL && r == BoardGenerator.CARROT_ROW) {
                    // Terminal only: remember the tile, never walk through it.
                    continue;
                }
                queue.addLast(new int[]{c, r});
            }
        }
        if (!seen[goalRow][goalCol]) {
            return null;
        }
        List<int[]> path = new ArrayList<>();
        int[] walk = {goalCol, goalRow};
        while (walk[0] != from[0] || walk[1] != from[1]) {
            path.add(0, walk);
            walk = previous[walk[1] * cols + walk[0]];
        }
        return path;
    }

    // ------------------------------------------------------------------
    // Reads.
    // ------------------------------------------------------------------

    public Board board() {
        return board;
    }

    /**
     * Dev-only scene setup ({@code Commands}'s {@code obstacle}): replaces one tile
     * on the current board. Used to reach the safety net, which a valid
     * board can never produce.
     */
    public void setTile(int col, int row, char kind) {
        this.board = board.withTile(col, row, kind);
    }

    public String screen() {
        return screen;
    }

    /** The screen this one was entered from, or {@code null} at launch. */
    public String opener() {
        return previousScreen;
    }

    public void screen(String next) {
        this.previousScreen = this.screen;
        this.screen = next;
    }

    public String phase() {
        return phase;
    }

    public String active() {
        return active;
    }

    public int turn() {
        return turn;
    }

    /** This turn's roll, or {@code null} before it. */
    public Integer roll() {
        return roll;
    }

    /** Points not yet spent, or {@code null} before the roll. */
    public Integer budget() {
        return budget;
    }

    public int stepsThisTurn() {
        return stepsThisTurn;
    }

    public String winner() {
        return winner;
    }

    public int[] blue() {
        return blue.clone();
    }

    public int[] brown() {
        return brown.clone();
    }

    /** The match-seed stream position: board draws plus rolls consumed. */
    public long rngCursor() {
        return board.rngCursor() + rollCount;
    }
}
