package rabbitrace.automation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import rabbitrace.audio.AudioDirector;
import rabbitrace.audio.LoopBeds;
import rabbitrace.audio.Mixing;
import rabbitrace.assets.AssetSource;
import rabbitrace.assets.AudioSlot;
import rabbitrace.game.BoardGenerator;
import rabbitrace.game.Match;
import rabbitrace.game.Seed;
import rabbitrace.i18n.Strings;
import rabbitrace.render.Animations;
import rabbitrace.render.BoardPainter;
import rabbitrace.render.Chrome;
import rabbitrace.render.Frame;
import rabbitrace.render.Keys;
import rabbitrace.render.NineSlice;
import rabbitrace.render.RenderPlan;
import rabbitrace.render.Rps;
import rabbitrace.render.Screens;
import rabbitrace.render.TileMap;
import rabbitrace.render.Transforms;
import rabbitrace.settings.Settings;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The automation command surface, extracted from
 * {@code Main} so a test can drive the same dispatch the socket does.
 *
 * <p>{@code click <x> <y>} takes virtual pixels and {@code tile <col> <row>} is
 * sugar for a click at that tile's centre: both resolve through
 * {@link TileMap#tileAt}, which is the coordinate law, and then feed one
 * pointer path.
 *
 * <p>The match commands emit events as they change
 * state, so {@code -Dautomation.log} is a complete reproduction recipe. A scripted
 * match uses {@code rps}, {@code press tirar}, {@code tile} and
 * {@code press terminar} with {@code animations 0}; no command waits on a clock.
 */
public final class Commands {

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    /** One pointer click after the virtual-to-board conversion. */
    public record Click(int virtualX, int virtualY, int col, int row) {

        /** True when the click fell outside the grid and was ignored. */
        public boolean outside() {
            return col < 0 || row < 0;
        }
    }

    /**
          * The window the shipped app owns, as seen by the controller. A headless run
     * has none, so {@code fullscreen} only changes the setting there; the window
     * shell is the only implementation
     * and it marshals to the EDT itself.
     */
    public interface WindowHandle {

        /** The window's outer bounds as {@code [x, y, w, h]}. */
        int[] bounds();

        /** The area a maximized window covers on the current monitor. */
        int[] screenBounds();

        boolean fullscreen();

        void setFullscreen(boolean fullscreen);
    }

    private final Match match;
    private final Settings settings;
    private final EventLog log;
    private final Runnable repaint;
    /** The live window, or {@code null} in a headless run. */
    private WindowHandle window;
    /** The tunable table; a zero duration makes that beat unobservable. */
    private final Animations animations;
    /**
     * every settings change is a gain change on the lines
     * the audio director holds. A scripted controller may leave it a no-op.
     */
    private final Consumer<Mixing.Levels> audio;
    /** The beds, told which screen is current; may be a no-op in tests. */
    private final Consumer<String> screenAudio;
    /** The attached director, so the dev-only {@code audio} probe can read its beds. */
    private final AudioDirector sound;
    /** What the run draws: the kit or the final files. */
    private final AssetSource assets;
    /** The decoration layer; the dev-only {@code deco} switch toggles it. */
    private boolean decorations = true;
    /** The non-border sprite layer; the dev-only {@code sprites on|off} switch. */
    private boolean sprites = true;
    /** dev-only ground crop offset, in source pixels; {@code ground shift} sets it. */
    private int groundShiftX;
    private int groundShiftY;
    private Click lastClick;
    /** The screen Ajustes was opened from, so {@code Volver} returns there. */
    private String ajustesOpener = Match.TITULO;
    /** A match frozen under Pausa or carried to Título by {@code Menú}. */
    private boolean pausedMatch;
    /** The hop beat is playing after a step; a key/click or the clock finishes it. */
    private boolean hopRunning;
    /** When the current hop beat started, in {@link #clock} units. */
    private long hopStartedNanos;
    /**
     * The turn-end beat is playing at budget 0. It is not skippable, so no
     * input ends it: it ends when its duration has elapsed on the clock
     * ({@link #releaseTurnEndBeat()}).
     */
    private boolean turnEndRunning;
    /** When the current turn-end beat started, in {@link #clock} units. */
    private long turnEndStartedNanos;
    /**
     * The die tumble is playing after {@code Tirar}; a key/click or the clock
     * settles it. Volatile because the window's paint path reads it on the EDT
     * while the command and beat-clock threads write it under the controller's
     * lock: a repaint must never block on that lock, or a fullscreen toggle that
     * holds it and waits on the EDT deadlocks.
     */
    private volatile boolean rollRunning;
    /** When the current tumble started, in {@link #clock} units. */
    private volatile long rollStartedNanos;
    /** The current tumble's duration in nanoseconds, cached when it starts. */
    private volatile long rollDurationNanos;
    /** The no-move banner is playing after a settled die; a key/click or the clock ends it. */
    private boolean noMoveRunning;
    /** When the no-move banner appeared, in {@link #clock} units. */
    private long noMoveStartedNanos;
    /** The hand-off banner is playing; a key/click or the clock finishes it. */
    private boolean handOffRunning;
    /** When the hand-off banner appeared, in {@link #clock} units. */
    private long handOffStartedNanos;
    /**
     * The beat after the winning hop, before Victoria appears. Like the
     * turn-end beat it is not skippable: it ends when its duration reaches zero
     * or when the clock says it has elapsed ({@link #tick()}).
     */
    private boolean victoryRunning;
    /**
     * The opening throw's shake: it runs for
     * {@code rpsShake} ms on the same clock, and any key or click finishes it
     * early ({@link #skipAnimation()}). A zero duration never enters
     * {@code RpsReveal}, so a scripted session reaches {@code AwaitRoll} at once.
     */
    private boolean revealRunning;
    /** True once the current `HandOff` banner has fired its turn-change cue. */
    private boolean handOffCued;
    /** Monotonic source for the presentation beats; system nanosecond clock. */
    private final LongSupplier clock;
    /** When the victory beat started, in {@link #clock} units. */
    private long victoryStartedNanos;
    /** When the opening throw's shake started, in {@link #clock} units. */
    private long revealStartedNanos;
    /**
     * The live tumble's paint cadence: the beat clock ticks every 10 ms, but a
     * 1600×900 frame does not need 100 of them a second. 33 ms is ~30 fps, enough
     * for a 75 ms face cycle to read and far below the settle beat.
     */
    private static final long TUMBLE_PAINT_NANOS = 33_000_000L;
    /** When the live tumble last asked for a repaint, in {@link #clock} units. */
    private long lastTumblePaintNanos;

    public Commands(Match match, Settings settings, EventLog log, Runnable repaint) {
        // A scripted session is on a zero-duration table unless it opts in.
        this(match, settings, log, repaint, Animations.fromFlag("0"));
    }

    public Commands(Match match, Settings settings, EventLog log, Runnable repaint,
                    Animations animations) {
        this(match, settings, log, repaint, animations, System::nanoTime);
    }

    public Commands(Match match, Settings settings, EventLog log, Runnable repaint,
                    Animations animations, LongSupplier clock) {
        this(match, settings, log, repaint, animations, clock, levels -> { });
    }

    public Commands(Match match, Settings settings, EventLog log, Runnable repaint,
                    Animations animations, LongSupplier clock, Consumer<Mixing.Levels> audio) {
        this(match, settings, log, repaint, animations, clock, audio, screen -> { }, null);
    }

    public Commands(Match match, Settings settings, EventLog log, Runnable repaint,
                    Animations animations, LongSupplier clock, Consumer<Mixing.Levels> audio,
                    Consumer<String> screenAudio, AudioDirector sound) {
        this(match, settings, log, repaint, animations, clock, audio, screenAudio, sound,
                AssetSource.placeholder());
    }

    /** The shipped constructors default to the kit; Main passes the loaded source. */
    public Commands(Match match, Settings settings, EventLog log, Runnable repaint,
                    Animations animations, LongSupplier clock, Consumer<Mixing.Levels> audio,
                    Consumer<String> screenAudio, AudioDirector sound, AssetSource assets) {
        this.match = match;
        this.settings = settings;
        this.log = log;
        this.repaint = repaint;
        this.animations = animations;
        this.clock = clock;
        this.audio = audio;
        this.screenAudio = screenAudio;
        this.sound = sound;
        this.assets = assets;
    }

    /**
     * Runs the presentation beats on their own clock. A real launch has no command traffic between a win and the win
     * screen, so something has to advance the non-skippable beats; this is that
     * ticker. It changes only the screen surface, never a rule, so the rules
     * core stays free of wall-clock.
     */
    public void startClock() {
        Thread ticker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                tick();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "beat-clock");
        ticker.setDaemon(true);
        ticker.start();
    }

    /** Advances the presentation beats on their own clocks; the clock thread's unit of work. */
    public synchronized void tick() {
        releaseRollBeat();
        releaseNoMoveBeat();
        releaseHandOffBeat();
        releaseHopBeat();
        releaseTurnEndBeat();
        releaseVictoryBeat();
        releaseRevealBeat();
        repaintTumble();
    }

    /**
     * The live tumble's elapsed fraction in {@code [0, 1)}, or negative when no
     * tumble is playing. It is read from the same {@code dieTumble} beat clock
     * that releases the die, so the painter's face cycle has no timer of its own.
     * Presentation only: no rule reads it.
     *
     * <p>Deliberately unsynchronized: the window's paint path calls this, and a
     * fullscreen toggle holds the controller lock across an EDT wait, so taking
     * that lock here could deadlock a repaint. The three fields it reads are
     * volatile and the duration is cached when the tumble starts, so the paint
     * path never touches the animation table.
     */
    public double dieTumbleFraction() {
        long duration = rollDurationNanos;
        if (!rollRunning || duration <= 0 || !Match.ROLLING.equals(match.phase())) {
            return -1;
        }
        long elapsed = clock.getAsLong() - rollStartedNanos;
        return Math.max(0, Math.min(0.999, elapsed / (double) duration));
    }

    /**
     * Keeps the window repainting while the die tumbles.
     * The face cycle is meaningless without fresh frames, and the beat clock is
     * already the only ticker this class owns; a settle or a skip has its own
     * {@code repaint} call, so this stops with {@code rollRunning}.
     */
    private void repaintTumble() {
        if (!rollRunning || !Match.ROLLING.equals(match.phase())) {
            return;
        }
        long now = clock.getAsLong();
        if (now - lastTumblePaintNanos < TUMBLE_PAINT_NANOS) {
            return;
        }
        lastTumblePaintNanos = now;
        repaint.run();
    }

    /** The most recent pointer click, or {@code null} before the first one. */
    public Click lastClick() {
        return lastClick;
    }

    /**
     * Gives the controller the live window. {@code Main} does this after the
     * shell exists; a headless run never attaches one, so a fullscreen change
     * stays a setting.
     */
    public void attachWindow(WindowHandle handle) {
        this.window = handle;
    }

    /**
     * Whether the decoration layer is drawn. The window shell asks this on
     * every paint, so the dev-only {@code deco} switch moves the live window and
     * the offscreen {@code frame} command together.
     */
    public boolean decorations() {
        return decorations;
    }

    /** Whether the non-border sprite layer is painted (dev-only switch). */
    public boolean sprites() {
        return sprites;
    }

    public int groundShiftX() {
        return groundShiftX;
    }

    public int groundShiftY() {
        return groundShiftY;
    }

    /**
     * One pointer click in virtual pixels, the path the window shell feeds after
     * window → virtual step. The socket's {@code click} command reaches
     * the same method.
     */
    public synchronized String click(int x, int y) {
        return pointerAt(x, y);
    }

    public synchronized String handle(String line) throws Exception {
        // a running turn-end beat is not skippable. It ends when the
        // dev hook zeroes its duration, so the next command sees the hand-off.
        releaseTurnEndBeat();
        // the beat between the winning hop and Victoria is not
        // skippable either, so zeroing its duration is what reveals the screen.
        releaseVictoryBeat();
        // the opening throw's shake advances on the clock, so a
        // command arriving after its duration sees the winner in AwaitRoll.
        releaseRevealBeat();
        String[] parts = line.trim().split("\\s+", 2);
        String verb = parts[0];
        String rest = parts.length > 1 ? parts[1].trim() : "";
        switch (verb) {
            case "state":
                return "ok " + Snapshot.of(match, "board".equals(rest), settings, animations,
                        runningBeat(), assets);
            case "layout":
                // Dev-only geometry: the chrome the painter draws,
                // so a scenario can check that no HUD panel or border prop covers
                // a tile centre. Not part of state: it carries no game
                // state and no match command changes it.
                return "ok " + layoutJson();
            case "panels":
                // Dev-only geometry: the panels and their controls, the
                // same model the painter draws and the pointer path hit-tests
                // Like `layout`, it carries no game state.
                return "ok " + panelsJson();
            case "bounds":
                // Dev-only window geometry: the real outer bounds and the
                // maximized area of the current monitor, so a
                // scenario can prove F11 restores what it saved. Not
                // part of state: headless reports windowed:false.
                return "ok " + boundsJson();
            case "audio":
                // Dev-only bed model: which loop is chosen, the
                // 400 ms crossfade, and how often each bed was started. Audio is
                // never game state, so this is a probe and not a state field.
                return "ok " + audioJson();
            case "obstacle": {
                // Dev-only scene setup: replace one tile on the current board, so a
                                // scenario can exercise the rules' safety net, which a valid board
                // never produces. Not in state's command
                // table because it is not play; it changes no rule, it only builds
                // the board the rule runs on.
                String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");
                if (args.length != 3) {
                    throw new IllegalArgumentException("obstacle needs col row kind");
                }
                int[] cell = ints(args[0] + " " + args[1], 2, "obstacle needs col row kind");
                if (cell[0] < 0 || cell[1] < 0 || cell[0] >= TileMap.COLS || cell[1] >= TileMap.ROWS) {
                    throw new IllegalArgumentException("tile outside the grid: " + cell[0] + " " + cell[1]);
                }
                match.setTile(cell[0], cell[1], kind(args[2]));
                repaint.run();
                return "ok";
            }
            case "click": {
                int[] point = ints(rest, 2, "click needs x y");
                return pointerAt(point[0], point[1]);
            }
            case "tile": {
                int[] cell = ints(rest, 2, "tile needs col row");
                if (cell[0] < 0 || cell[1] < 0 || cell[0] >= TileMap.COLS || cell[1] >= TileMap.ROWS) {
                    throw new IllegalArgumentException("tile outside the grid: " + cell[0] + " " + cell[1]);
                }
                return pointerAt(TileMap.tileCentreX(cell[0]), TileMap.tileCentreY(cell[1]));
            }
            case "frame":
                return "ok " + Frames.writePng(match, settings, assets, decorations, sprites,
                        groundShiftX, groundShiftY, rest);
            case "slice": {
                // Dev-only render probe: draw a
                // ui.button.* slot at its native size and at 96x40, 96x64 and
                // 96x120 through the same 9-slice path the HUD buttons use, and
                // write the 1600x900 probe image. The reply reports the source
                // size, the declared slices and every destination rectangle, so
                // a scenario can compare the four corner blocks in the pixels.
                String trimmed = rest.trim();
                int space = trimmed.indexOf(' ');
                if (space < 0) {
                    throw new IllegalArgumentException("slice needs a slot and a path");
                }
                String slot = trimmed.substring(0, space).trim();
                String path = trimmed.substring(space + 1).trim();
                return "ok " + writeSliceProbe(path, slot);
            }
            case "fence": {
                // Dev-only render probe: draw the fence
                // segment through the painter's own transform path at each
                // declared combination, in one 1600x900 image. The reply names
                // the declared and applied transforms and every destination
                // rectangle, so the scenario can read the rotation from pixels.
                String path = rest.trim();
                if (path.isEmpty()) {
                    throw new IllegalArgumentException("fence needs a path");
                }
                return "ok " + writeFenceProbe(path);
            }
            case "deco": {
                // Dev-only render switch: the decoration
                // layer on or off, and how many deco.* sprites it would draw. A
                // render choice only — no rule, no match state, no PRNG draw.
                String arg = rest.trim().toLowerCase(Locale.ROOT);
                if (!arg.isEmpty()) {
                    if (!"on".equals(arg) && !"off".equals(arg)) {
                        throw new IllegalArgumentException("deco takes on or off");
                    }
                    decorations = "on".equals(arg);
                    repaint.run();
                }
                return "ok " + decorationJson();
            }
            case "sprites":
                // Dev-only geometry: every sprite the
                // painter draws for this frame with its source size, footprint
                // and the destination rectangle it is clipped to, plus each
                // ground field's declared sample window. The `off` argument is
                // a render switch like `deco`, so a scenario can capture the
                // same board with the non-border sprite layer omitted and see
                // in the pixels that no sprite painted outside its rectangle.
                {
                    String arg = rest.trim().toLowerCase(Locale.ROOT);
                    if (!arg.isEmpty()) {
                        if (!"on".equals(arg) && !"off".equals(arg)) {
                            throw new IllegalArgumentException("sprites takes on or off");
                        }
                        sprites = "on".equals(arg);
                        repaint.run();
                    }
                    return "ok " + spritesJson();
                }
            case "ground": {
                // Dev-only render probe: move every
                // ground field's crop origin by a source-pixel offset. Rendering
                // the same board with and without the offset makes the declared
                // 168x160 stride visible in the ground pixels.
                String trimmed = rest.trim();
                if (!trimmed.isEmpty()) {
                    if (trimmed.toLowerCase(Locale.ROOT).startsWith("shift")) {
                        trimmed = trimmed.substring("shift".length()).trim();
                    }
                    int[] shift = ints(trimmed, 2, "ground shift needs dx dy");
                    groundShiftX = shift[0];
                    groundShiftY = shift[1];
                    repaint.run();
                }
                JsonObject o = new JsonObject();
                o.addProperty("v", 1);
                o.add("shift", pair(groundShiftX, groundShiftY));
                return "ok " + GSON.toJson(o);
            }
            case "rps": {
                String[] hands = rest.isEmpty() ? new String[0] : rest.split("\\s+");
                Match.ThrowResult result;
                if (hands.length == 2 && isPlayer(hands[0])) {
                    // The dev hook commits one side directly, so a scene can stop
                    // between the two commitments and prove the choice is secret.
                    result = match.commitHand(player(hands[0]), hand(hands[1]));
                } else if (hands.length == 2) {
                    int p1 = hand(hands[0]);
                    int p2 = hand(hands[1]);
                    if (!Match.RPS_SELECT.equals(match.phase())
                            || match.committed(Match.BLUE) || match.committed(Match.BROWN)) {
                        throw new IllegalStateException(
                                "rps with two hands needs both sides still choosing");
                    }
                    match.commitHand(Match.BLUE, p1);
                    result = match.commitHand(Match.BROWN, p2);
                } else {
                    throw new IllegalArgumentException(
                            "rps needs p1|p2 and one hand, or two hands: 1, 2 or 3");
                }
                if (result != null) {
                    afterThrow(result);
                }
                repaint.run();
                return "ok " + (result == null ? "committed"
                        : result.winner() == null ? "tie" : result.winner());
            }
            case "press": {
                return press(rest);
            }
            case "key": {
                return key(rest);
            }
            case "set": {
                String[] pair = rest.split("\\s+", 2);
                if (pair.length != 2) {
                    throw new IllegalArgumentException("set needs a key and a value");
                }
                String key = pair[0];
                settings.set(key, pair[1]);
                applyAudio();
                if ("fullscreen".equals(key)) {
                    applyWindowFullscreen();
                }
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("key", key);
                fields.put("value", settings.value(key));
                log.event("settingsChanged", match.rngCursor(), fields);
                repaint.run();
                return "ok " + settings.value(key);
            }
            case "field": {
                // Dev-only: fill the Título seed field without starting a
                // match, so a scenario can drive `Nueva partida` from a known
                // field value. It is the field, not the match command: `seed`
                // starts a match, this leaves the app on Título with no phase.
                if (!Match.TITULO.equals(match.screen()) || match.phase() != null) {
                    throw new IllegalStateException(
                            "the seed field is only on Título with no live match");
                }
                if (rest.isBlank()) {
                    throw new IllegalArgumentException("field needs a 6-character seed");
                }
                String normalised = Seed.normalise(rest);
                match.preselect(normalised);
                repaint.run();
                return "ok " + normalised;
            }
            case "seed": {
                if (rest.isBlank()) {
                    throw new IllegalArgumentException("seed needs a 6-character seed");
                }
                String normalised = Seed.normalise(rest);
                String from = match.screen();
                resetBeats();
                match.reseed(normalised);
                pausedMatch = false;
                log.event("restarted", match.rngCursor(), restartedFields());
                screenTo(from, Match.PARTIDA);
                repaint.run();
                return "ok " + normalised;
            }
            case "restart": {
                rematch();
                return "ok " + match.board().seed();
            }
            case "animations": {
                // The one tunable table. `0` zeroes every
                // duration and `1` restores the defaults; `<name> <ms>` sets
                // one beat, which is how a scenario proves each entry is a value
                // of its own rather than a global switch.
                String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");
                if (args.length == 1) {
                    if (!"0".equals(args[0]) && !"1".equals(args[0])) {
                        throw new IllegalArgumentException(
                                "animations takes 0, 1, or <name> <ms>");
                    }
                    if ("1".equals(args[0])) {
                        animations.restoreDefaults();
                    } else {
                        animations.zeroAll();
                    }
                    return "ok";
                }
                if (args.length == 2) {
                    int millis;
                    try {
                        millis = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "animations <name> <ms> needs an integer: " + args[1]);
                    }
                    animations.set(args[0], millis);
                    return "ok " + animations.get(args[0]);
                }
                throw new IllegalArgumentException("animations takes 0, 1, or <name> <ms>");
            }
            case "quit":
                // Settings save on app close; the connection is
                // still open, so the reply reaches the client.
                settings.saveQuietly();
                return "ok bye";
            default:
                throw new IllegalArgumentException("unknown command: " + verb);
        }
    }

    /**
     * {@code Tirar}: live only in {@code AwaitRoll}, so a
     * press anywhere else changes nothing and cannot re-roll or bank a die
     * A positive tumble duration reaches {@code Rolling}, where
     * the die is public but the budget is not; a zero duration settles at once.
     */
    private String tirar() {
        if (!Match.AWAIT_ROLL.equals(match.phase())) {
            return "ok";
        }
        if (animations.get(Animations.DIE_TUMBLE) > 0) {
            match.startRoll();
            // the roll cue belongs to `Rolling` starting; it is
            // truncated when the die settles, so one cue per Tirar.
            cue(AudioSlot.DICE_ROLL.id());
            // the tumble holds `Rolling` for its own duration; the
            // clock settles it, and any key/click settles it early. The clock
            // starts after the cue so a slow audio open cannot eat the beat.
            rollRunning = true;
            rollStartedNanos = clock.getAsLong();
            rollDurationNanos = animations.get(Animations.DIE_TUMBLE) * 1_000_000L;
            // The die is tumbling: no event and no budget until it settles.
            repaint.run();
            return "ok";
        }
        // A zero tumble never shows `Rolling`: the roll and its settle are the
        // same instant, and both cues still belong to that press.
        cue(AudioSlot.DICE_ROLL.id());
        int value = match.rollDie();
        logRolled(value);
        afterRollSettled();
        repaint.run();
        return "ok " + value;
    }

    /**
     * {@code Terminar turno}: live only in {@code AwaitMove} after at least one
     * step, so a zero-step press is consumed and changes
     * nothing.
     */
    private String terminar() {
        if (!match.canEndTurn()) {
            return "ok";
        }
        endTurn();
        repaint.run();
        return "ok " + match.active();
    }

    /**
          * The one post-commit path of the opening throw, shared by the {@code rps}
     * command and the hand keys: log the reveal,
     * shake a decisive throw while {@code rpsShake} has a duration, and fire the
     * reveal cue at the instant the hands show.
     */
    private void afterThrow(Match.ThrowResult result) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("p1", result.p1());
        fields.put("p2", result.p2());
        if (result.winner() != null) {
            fields.put("winner", result.winner());
        }
        log.event("throw", match.rngCursor(), fields);
        // a decisive throw shakes before it reveals while
        // rpsShake has a duration; zeroing it reveals at once.
        // the reveal cue belongs to the hands showing, so a tie
        // (which has no shake phase) and a zero-shake throw fire it here, and a
        // shaking throw fires it when the shake ends.
        if (result.winner() != null && animations.get(Animations.RPS_SHAKE) > 0) {
            match.beginReveal();
            // the shake holds RpsReveal for its own duration; the
            // clock releases it, and any key/click skips it early.
            revealRunning = true;
            revealStartedNanos = clock.getAsLong();
        } else {
            cue(AudioSlot.RPS_REVEAL.id());
        }
    }

    /**
     * One committed hand from the opening throw's keys:
     * P1's {@code 1}/{@code 2}/{@code 3} or P2's {@code 7}/{@code 8}/{@code 9}.
     * The choice is committed through the same {@link Match#commitHand} the
     * {@code rps} command and a hand click use, so it stays secret until both
     * sides are in. A press or click for a side that already
     * committed changes nothing, which is how a column is disabled.
     */
    private String chooseHand(String player, int hand) {
        if (!Rps.accepts(match.phase(), match.committed(player))) {
            return "ok";
        }
        Match.ThrowResult result = match.commitHand(player, hand);
        repaint.run();
        if (result == null) {
            return "ok committed";
        }
        afterThrow(result);
        repaint.run();
        return "ok " + (result.winner() == null ? "tie" : result.winner());
    }

    /**
     * The hand a player's key names: P1 presses
     * {@code 1}/{@code 2}/{@code 3}, P2 presses {@code 7}/{@code 8}/{@code 9} for
     * the same three hands.
     */
    private static int handOf(String player, Keys.Key press) {
        int digit = press.character() - '0';
        return Match.BROWN.equals(player) ? digit - 6 : digit;
    }

    private String press(String name) {
        switch (name) {
            case "tirar":
                return tirar();
            case "terminar":
                return terminar();
            case "revancha":
                rematch();
                return "ok " + match.board().seed();
            case "pausa": {
                if (!Match.PARTIDA.equals(match.screen())) {
                    throw new IllegalStateException("Pausa is only available in Partida, not "
                            + match.screen());
                }
                pausedMatch = true;
                screenTo(Match.PARTIDA, Match.PAUSA);
                repaint.run();
                return "ok";
            }
            case "reanudar": {
                if (!Match.PAUSA.equals(match.screen())) {
                    throw new IllegalStateException("Reanudar is only available in Pausa, not "
                            + match.screen());
                }
                pausedMatch = false;
                screenTo(Match.PAUSA, Match.PARTIDA);
                repaint.run();
                return "ok";
            }
            case "jugar": {
                if (!Match.TITULO.equals(match.screen())) {
                    throw new IllegalStateException("Nueva partida is only on Titulo, not "
                            + match.screen());
                }
                // Nueva partida is disabled until the field is a
                // valid seed, so an invalid value changes nothing at all.
                if (!match.seedFieldValid()) {
                    return "ok";
                }
                // With a paused match in memory, Nueva partida asks first.
                if (pausedMatch) {
                    screenTo(Match.TITULO, Match.CONFIRMACION);
                } else {
                    startFromField();
                }
                repaint.run();
                return "ok";
            }
            case "continuar": {
                if (!Match.TITULO.equals(match.screen())) {
                    throw new IllegalStateException("Continuar is only on Titulo, not "
                            + match.screen());
                }
                // Disabled unless a paused match exists: pressing it does nothing.
                if (pausedMatch && match.phase() != null) {
                    pausedMatch = false;
                    screenTo(Match.TITULO, Match.PARTIDA);
                    repaint.run();
                }
                return "ok";
            }
            case "menu": {
                if (Match.PAUSA.equals(match.screen())) {
                    // The match stays in memory for Continuar.
                    screenTo(Match.PAUSA, Match.TITULO);
                } else if (Match.VICTORIA.equals(match.screen())) {
                    // A finished match is never paused: Menú ends it.
                    String from = Match.VICTORIA;
                    match.endMatch();
                    pausedMatch = false;
                    resetBeats();
                    screenTo(from, Match.TITULO);
                } else {
                    throw new IllegalStateException("Menú is only available in Pausa or Victoria, not "
                            + match.screen());
                }
                repaint.run();
                return "ok";
            }
            case "cancelar": {
                if (!Match.CONFIRMACION.equals(match.screen())) {
                    throw new IllegalStateException("Cancelar is only available in Confirmacion, not "
                            + match.screen());
                }
                screenTo(Match.CONFIRMACION, Match.TITULO);
                repaint.run();
                return "ok";
            }
            case "confirmar": {
                if (!Match.CONFIRMACION.equals(match.screen())) {
                    throw new IllegalStateException("Confirmar is only available in Confirmacion, not "
                            + match.screen());
                }
                startFromField();
                repaint.run();
                return "ok";
            }
            case "ajustes": {
                String from = match.screen();
                if (!Match.AJUSTES.equals(from)) {
                    ajustesOpener = from;
                    screenTo(from, Match.AJUSTES);
                }
                repaint.run();
                return "ok";
            }
            case "restablecer": {
                if (!Match.AJUSTES.equals(match.screen())) {
                    throw new IllegalStateException("Restablecer is only available in Ajustes, not "
                            + match.screen());
                }
                settings.reset();
                applyAudio();
                // Restablecer also returns the window to windowed.
                applyWindowFullscreen();
                Map<String, Object> fields = new LinkedHashMap<>();
                for (String key : List.of("master", "music", "sfx", "muted", "fullscreen")) {
                    fields.put(key, settings.value(key));
                }
                log.event("settingsChanged", match.rngCursor(), fields);
                repaint.run();
                return "ok";
            }
            case "volver": {
                if (!Match.AJUSTES.equals(match.screen())) {
                    throw new IllegalStateException("Volver is only available in Ajustes, not "
                            + match.screen());
                }
                // Leaving Ajustes is when settings are written.
                settings.saveQuietly();
                screenTo(Match.AJUSTES, ajustesOpener);
                repaint.run();
                return "ok";
            }
            case "salir":
                // settings save on app close. DevHook treats the
                // `ok bye` reply as the app's exit, exactly as `quit` does.
                settings.saveQuietly();
                return "ok bye";
            default:
                throw new IllegalArgumentException("press does not accept '" + name + "' yet");
        }
    }

    /**
     * {@code key <chord>}: a synthetic key to the focused game surface
     * The chord is parsed into the pure
     * {@link Keys} map's own key type and then executed by {@link #key(Keys.Key)},
     * so the automation and the window shell share one keyboard path. Unknown
     * chords are refused rather than mistaken for a character.
     */
    private String key(String chord) {
        Keys.Key press = Keys.chord(chord);
        if (press == null) {
            throw new IllegalArgumentException("key does not accept '" + chord + "' yet");
        }
        return key(press);
    }

    /**
     * One key press, from the window shell or the automation. {@link Keys#action}
     * decides what the press means from the screen, the phase and the key alone;
     * every action then runs the same controller method the pointer path and
     * {@code press} use, so a keyboard roll, step and screen change are not a
     * second rules path. A key consumed by a screen or by a running beat never
     * falls through to the match.
     */
    public synchronized String key(Keys.Key press) {
        Keys.Action action = Keys.action(match.screen(), match.phase(), press);
        switch (action) {
            case FULLSCREEN: {
                // F11 toggles fullscreen from any screen, and the
                // `Pantalla completa` row reflects the result because the setting
                // is the single source the surface paints. The window shell remembers the
                // windowed bounds for the session.
                boolean value = !settings.fullscreen();
                settings.set("fullscreen", Boolean.toString(value));
                applyWindowFullscreen();
                logSetting("fullscreen");
                repaint.run();
                return "ok " + value;
            }
            case PAUSE:
                // Esc finishes a running beat rather than freezing
                // it mid-frame, then pauses. Pausing changes no rule outcome.
                skipAnimation();
                return press("pausa");
            case RESUME:
                return press("reanudar");
            case CLOSE_AJUSTES:
                return press("volver");
            case CANCEL_CONFIRM:
                return press("cancelar");
            case REMATCH:
                rematch();
                return "ok " + match.board().seed();
            case TYPE_CHARACTER:
                // the field normalises live and never refuses a
                // keystroke, so an invalid intermediate stays visible. Space is
                // a field character, never Partida's contextual key.
                match.typeSeed(String.valueOf(press.kind() == Keys.Kind.SPACE
                        ? ' ' : press.character()));
                repaint.run();
                return "ok";
            case BACKSPACE:
                match.backspaceSeed();
                repaint.run();
                return "ok";
            case NEW_MATCH:
                // Enter in the seed field activates Nueva partida,
                // which is the same guarded action as pressing the button.
                return press("jugar");
            case RPS_BLUE:
                return chooseHand(Match.BLUE, handOf(Match.BLUE, press));
            case RPS_BROWN:
                return chooseHand(Match.BROWN, handOf(Match.BROWN, press));
            default:
                break;
        }
        // From here every action is a match action. A key consumed on a screen
        // or by a running beat never falls through to one.
        if (!Match.PARTIDA.equals(match.screen())) {
            return "ok";
        }
        if (skipAnimation()) {
            return "ok";
        }
        if (turnEndRunning) {
            // the turn-end beat is not skippable: no key ends it.
            return "ok";
        }
        return switch (action) {
            case ROLL -> tirar();
            case END_TURN -> terminar();
            case MOVE_NORTH -> step(0, -1);
            case MOVE_SOUTH -> step(0, 1);
            case MOVE_WEST -> step(-1, 0);
            case MOVE_EAST -> step(1, 0);
            default -> "ok";
        };
    }

    /**
     * One orthogonal step from a movement key: the same
     * {@link Match#targetable}/{@link Match#moveTo} pair a click uses, so a key
     * that would leave the board, enter a blocking tile or land on the other
     * rabbit changes nothing and plays the bump cue. A committed step spends one
     * pip and is final; a step that spends the last one hands the turn off.
     */
    private String step(int dc, int dr) {
        int[] from = match.position(match.active());
        int col = from[0] + dc;
        int row = from[1] + dr;
        if (!match.targetable(col, row)) {
            // a directional key into a blocking tile, the board
            // edge or the other rabbit is the bump cue. Clicks on untargetable
            // tiles stay silent, so the pointer path fires nothing.
            cue(AudioSlot.BUMP_BLOCKED.id());
            return "ok";
        }
        List<int[]> steps = match.moveTo(col, row);
        logSteps(steps);
        afterMove(col, row);
        repaint.run();
        return "ok";
    }

    /**
     * Consumes an input that arrives while a skippable animation is up
     * It finishes the beat and returns {@code true};
     * the caller must then stop, so the same key or click never also performs a
     * state action.
     */
    private boolean skipAnimation() {
        if (hopRunning) {
            if (!Match.AWAIT_MOVE.equals(match.phase()) && !Match.MATCH_WON.equals(match.phase())) {
                // The turn ended by another path; the hop is no longer live.
                hopRunning = false;
            } else {
                // a hop is skippable, and the skip finishes every
                // remaining hop at once. No step is hidden behind the key.
                // a winning hop is followed by the win beat, not
                // by Victoria, so the skip only advances past the hop.
                hopRunning = false;
                if (Match.MATCH_WON.equals(match.phase())) {
                    afterWinningHop();
                } else {
                    finishMoveBeats();
                }
                repaint.run();
                return true;
            }
        }
        if (Match.RPS_REVEAL.equals(match.phase())) {
            // The shake ends on any key/click; the reveal is already decided, so
            // the skip never also rolls for the winner.
            revealRunning = false;
            match.finishReveal();
            // the reveal cue belongs to the hands showing, which
            // is the instant the shake ends.
            cue(AudioSlot.RPS_REVEAL.id());
            repaint.run();
            return true;
        }
        if (Match.ROLLING.equals(match.phase())) {
            rollRunning = false;
            int value = match.settleRoll();
            logRolled(value);
            afterRollSettled();
            repaint.run();
            return true;
        }
        if (Match.NO_LEGAL_MOVE.equals(match.phase())) {
            // The no-move banner is skippable: the die is spent and
            // the turn hands off without a step.
            noMoveRunning = false;
            String ended = match.active();
            match.passNoLegalMove();
            log.event("turnEnded", match.rngCursor(), turnEndedFields(ended));
            finishHandOffBeat();
            cueHandOffBanner();
            repaint.run();
            return true;
        }
        if (Match.HAND_OFF.equals(match.phase())) {
            // The hand-off banner only ends: the next player has to press Tirar.
            handOffRunning = false;
            match.finishHandOff();
            cueHandOffBanner();
            repaint.run();
            return true;
        }
        return false;
    }

    /**
     * The beats that follow a settled die: the
     * no-move banner, then the hand-off banner. A zero duration makes that
     * banner unobservable, so zeroing the row changes the phase the next command
     * sees, exactly as zeroing the tumble or the shake does.
     */
    private void afterRollSettled() {
        // the settle cue belongs to the pips showing, which is
        // exactly this moment — after a tumble or the zero-tumble one-shot.
        cue(AudioSlot.DICE_SETTLE.id());
        if (Match.NO_LEGAL_MOVE.equals(match.phase())) {
            if (animations.get(Animations.NO_MOVE) > 0) {
                // the banner holds `NoLegalMove` for its own
                // duration; the clock ends it, and any key/click ends it early.
                noMoveRunning = true;
                noMoveStartedNanos = clock.getAsLong();
                return;
            }
            noMoveRunning = false;
            String ended = match.active();
            match.passNoLegalMove();
            log.event("turnEnded", match.rngCursor(), turnEndedFields(ended));
        }
        noMoveRunning = false;
        finishHandOffBeat();
        cueHandOffBanner();
    }

    /**
     * A zero hand-off duration leaves no banner: the next player is in
     * {@code AwaitRoll}. A positive one is armed by {@link #cueHandOffBanner()}
     * once its cue has fired, so a slow audio open cannot eat the beat.
     */
    private void finishHandOffBeat() {
        if (!Match.HAND_OFF.equals(match.phase())) {
            handOffRunning = false;
            return;
        }
        if (animations.get(Animations.HAND_OFF) == 0) {
            handOffRunning = false;
            match.finishHandOff();
        }
    }

    /**
     * The turn-change cue belongs to
     * the `HandOff` banner appearing — not to the die settling, the step that
     * spends the last pip, or the banner's skip. The flag makes one banner one
     * cue however many commands observe it, and a phase that is not `HandOff`
     * arms the next banner.
     */
    private void cueHandOffBanner() {
        if (!Match.HAND_OFF.equals(match.phase())) {
            handOffCued = false;
            handOffRunning = false;
            return;
        }
        if (!handOffCued) {
            handOffCued = true;
            cue(AudioSlot.TURN_CHANGE.id());
        }
        // the banner's clock starts when the banner is up, after
        // its cue, so a slow audio open cannot eat the banner's duration.
        if (animations.get(Animations.HAND_OFF) > 0 && !handOffRunning) {
            handOffRunning = true;
            handOffStartedNanos = clock.getAsLong();
        }
    }

    /**
     * After a committed step: the win ends the match, a
     * positive {@code hop} duration plays the hop beat, and otherwise the turn
     * either runs its {@code turnEndBeat} or hands off.
     */
    private void afterMove(int col, int row) {
        if (Match.MATCH_WON.equals(match.phase())) {
            logWin(col, row);
            if (animations.get(Animations.HOP) > 0) {
                beginHopBeat();
                return;
            }
            afterWinningHop();
            return;
        }
        if (animations.get(Animations.HOP) > 0) {
            beginHopBeat();
            return;
        }
        finishMoveBeats();
    }

    /** Starts the hop beat on its own clock; a key/click or the clock ends it. */
    private void beginHopBeat() {
        hopRunning = true;
        hopStartedNanos = clock.getAsLong();
    }

    /**
     * The match is won when the rabbit enters the
     * carrot, but Victoria is painted only after the winning hop and this beat. A zero
     * duration makes the beat unobservable, so a scripted session reaches the win
     * screen in the same command.
     */
    private void afterWinningHop() {
        if (!Match.MATCH_WON.equals(match.phase())) {
            return;
        }
        if (animations.get(Animations.VICTORY_BEAT) > 0) {
            victoryRunning = true;
            victoryStartedNanos = clock.getAsLong();
            return;
        }
        victoryRunning = false;
        screenTo(Match.PARTIDA, Match.VICTORIA);
        // the win cue belongs to Victoria appearing, after the
        // winning hop and its beat, not to the hop that took the carrot.
        cue(AudioSlot.WIN.id());
    }

    /**
     * The beat is not skippable: it ends when its duration has elapsed on
     * the clock, or at once when the dev hook zeroes the row. Then, and only
     * then, does {@code screen} become Victoria.
     */
    private void releaseVictoryBeat() {
        if (!victoryRunning) {
            return;
        }
        long elapsed = clock.getAsLong() - victoryStartedNanos;
        if (animations.get(Animations.VICTORY_BEAT) > elapsed / 1_000_000L) {
            return;
        }
        victoryRunning = false;
        screenTo(Match.PARTIDA, Match.VICTORIA);
        cue(AudioSlot.WIN.id());
        repaint.run();
    }

    /**
     * The opening throw's shake ends on its own clock:
     * when {@code rpsShake} has elapsed, the winner enters {@code AwaitRoll} and
     * the reveal cue fires — the same two things a skip does. A zero duration
     * never started the beat, so a scripted session stays deterministic.
     */
    private void releaseRevealBeat() {
        if (!revealRunning) {
            return;
        }
        if (!Match.RPS_REVEAL.equals(match.phase())) {
            revealRunning = false;
            return;
        }
        long elapsed = clock.getAsLong() - revealStartedNanos;
        if (animations.get(Animations.RPS_SHAKE) > elapsed / 1_000_000L) {
            return;
        }
        revealRunning = false;
        match.finishReveal();
        // the reveal cue belongs to the hands showing, which is
        // this instant whether the shake elapsed or was skipped.
        cue(AudioSlot.RPS_REVEAL.id());
        repaint.run();
    }

    /** Ends the beats that follow a settled step: the turn-end beat, or the hand-off. */
    private void finishMoveBeats() {
        if (Match.AWAIT_MOVE.equals(match.phase()) && match.budget() != null && match.budget() == 0
                && animations.get(Animations.TURN_END_BEAT) > 0) {
            turnEndRunning = true;
            turnEndStartedNanos = clock.getAsLong();
            return;
        }
        turnEndRunning = false;
        endTurnIfSpent();
    }

    /**
     * The hop beat ends on its own clock: when {@code hop} has elapsed this
     * runs the same aftermath a skip runs — the victory beat after a winning hop,
     * otherwise the turn-end/hand-off beats.
     */
    private void releaseHopBeat() {
        if (!hopRunning) {
            return;
        }
        if (!Match.AWAIT_MOVE.equals(match.phase()) && !Match.MATCH_WON.equals(match.phase())) {
            // The turn ended by another path; the hop is no longer live.
            hopRunning = false;
            return;
        }
        long elapsed = clock.getAsLong() - hopStartedNanos;
        if (animations.get(Animations.HOP) > elapsed / 1_000_000L) {
            return;
        }
        hopRunning = false;
        if (Match.MATCH_WON.equals(match.phase())) {
            afterWinningHop();
        } else {
            finishMoveBeats();
        }
        repaint.run();
    }

    /**
     * The tumble ends on its own clock: when
     * {@code dieTumble} has elapsed the die settles and the same
     * event/aftermath path runs as a skip. A zero duration never started the
     * beat, so a scripted session settles in the {@code Tirar} command.
     */
    private void releaseRollBeat() {
        if (!rollRunning) {
            return;
        }
        if (!Match.ROLLING.equals(match.phase())) {
            rollRunning = false;
            return;
        }
        long elapsed = clock.getAsLong() - rollStartedNanos;
        if (animations.get(Animations.DIE_TUMBLE) > elapsed / 1_000_000L) {
            return;
        }
        rollRunning = false;
        int value = match.settleRoll();
        logRolled(value);
        afterRollSettled();
        repaint.run();
    }

    /**
     * The no-move banner ends on its own clock: when {@code noMove} has
     * elapsed the turn hands off without a step, exactly as a skip does.
     */
    private void releaseNoMoveBeat() {
        if (!noMoveRunning) {
            return;
        }
        if (!Match.NO_LEGAL_MOVE.equals(match.phase())) {
            noMoveRunning = false;
            return;
        }
        long elapsed = clock.getAsLong() - noMoveStartedNanos;
        if (animations.get(Animations.NO_MOVE) > elapsed / 1_000_000L) {
            return;
        }
        noMoveRunning = false;
        String ended = match.active();
        match.passNoLegalMove();
        log.event("turnEnded", match.rngCursor(), turnEndedFields(ended));
        finishHandOffBeat();
        cueHandOffBanner();
        repaint.run();
    }

    /**
     * The hand-off banner ends on its own clock: when {@code handOff} has
     * elapsed the next player is in {@code AwaitRoll}, the same end a skip gives.
     */
    private void releaseHandOffBeat() {
        if (!handOffRunning) {
            return;
        }
        if (!Match.HAND_OFF.equals(match.phase())) {
            handOffRunning = false;
            return;
        }
        long elapsed = clock.getAsLong() - handOffStartedNanos;
        if (animations.get(Animations.HAND_OFF) > elapsed / 1_000_000L) {
            return;
        }
        handOffRunning = false;
        match.finishHandOff();
        cueHandOffBanner();
        repaint.run();
    }

    /**
     * the turn-end beat is not skippable, so it ends when
     * its duration has elapsed on the clock, or at once when the dev hook zeroes
     * the row.
     */
    private void releaseTurnEndBeat() {
        if (!turnEndRunning) {
            return;
        }
        if (!Match.AWAIT_MOVE.equals(match.phase())) {
            turnEndRunning = false;
            return;
        }
        long elapsed = clock.getAsLong() - turnEndStartedNanos;
        if (animations.get(Animations.TURN_END_BEAT) > elapsed / 1_000_000L) {
            return;
        }
        turnEndRunning = false;
        String ended = match.active();
        match.endTurnIfSpent();
        log.event("turnEnded", match.rngCursor(), turnEndedFields(ended));
        cueHandOffBanner();
        repaint.run();
    }

    /** The beat the state dump reports as animating, beyond the phase's own beat. */
    private String runningBeat() {
        if (hopRunning) {
            return Animations.HOP;
        }
        if (turnEndRunning) {
            return Animations.TURN_END_BEAT;
        }
        return null;
    }

    /** Drops the automation's own beats when the match they belonged to goes away. */
    private void resetBeats() {
        hopRunning = false;
        turnEndRunning = false;
        rollRunning = false;
        noMoveRunning = false;
        handOffRunning = false;
        victoryRunning = false;
        revealRunning = false;
        handOffCued = false;
    }

    private void logSteps(List<int[]> steps) {
        for (int[] step : steps) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("from", List.of(step[0], step[1]));
            fields.put("to", List.of(step[2], step[3]));
            fields.put("active", match.active());
            fields.put("budget", match.budget());
            fields.put("step", match.stepsThisTurn());
            log.event("moved", match.rngCursor(), fields);
            // one hop cue per step. A fast path fires them in
            // quick succession, which is exactly where single-voice
            // retrigger keeps them from stacking.
            cue(AudioSlot.HOP.id());
        }
    }

    /** Fires an SFX cue through the director's voice pool, if attached. */
    private void cue(String slot) {
        if (sound != null) {
            sound.cue(slot);
        }
    }

    private void logWin(int col, int row) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("winner", match.winner());
        fields.put("rabbit", List.of(col, row));
        fields.put("turn", match.turn());
        log.event("won", match.rngCursor(), fields);
    }

    private void rematch() {
        String previous = match.screen();
        match.restart();
        pausedMatch = false;
        resetBeats();
        log.event("restarted", match.rngCursor(), restartedFields());
        screenTo(previous, Match.PARTIDA);
        repaint.run();
    }

    /**
     * {@code Nueva partida} on the Título seed field: a
     * fresh match, opening throw included, on the value the field carries.
     */
    private void startFromField() {
        String from = match.screen();
        String seed = match.seedField();
        match.reseed(seed);
        pausedMatch = false;
        resetBeats();
        log.event("restarted", match.rngCursor(), restartedFields());
        screenTo(from, Match.PARTIDA);
    }

    /**
     * The single pointer path both commands feed ("against the same
     * controllers the UI uses"). A click outside the grid rectangle is recorded
     * and ignored, never turned into a board cell. During {@code AwaitMove}
     * a click commits the shortest legal path to the tile.
     */
    private String pointerAt(int x, int y) {
        int[] cell = TileMap.tileAt(x, y);
        lastClick = cell == null ? new Click(x, y, -1, -1) : new Click(x, y, cell[0], cell[1]);
        // a pointer click both focuses and activates. A click on a
        // panel reaches the same action its named press does; a click
        // outside every control of an overlay is consumed by that screen and
        // never falls through to the match underneath.
        String control = Screens.controlAt(match.screen(), x, y);
        if (control != null) {
            // activating a button, toggle or slider plays
            // `ui.click` once; focusing the seed field and hovering play nothing.
            if (!"field".equals(control)) {
                cue(AudioSlot.UI_CLICK.id());
            }
            return activate(control, x);
        }
        if (!Match.PARTIDA.equals(match.screen())) {
            return "ok";
        }
        // the gear is enabled in every Partida phase and opens
        // Ajustes directly, skipping Pausa.
        if (Chrome.GEAR.rect().contains(x, y)) {
            cue(AudioSlot.UI_CLICK.id());
            return press("ajustes");
        }
        // during the opening throw the panel's six hand plates
        // are the pointer's choice. A plate commits through the same
        // Match.commitHand the `rps` command and the hand keys use; a plate in a
        // column that already committed is disabled and swallows the click, and
        // any other click inside the panel changes nothing. Outside RpsSelect the
        // panel is not hit-tested, so Partida is untouched afterwards.
        if (Match.RPS_SELECT.equals(match.phase())) {
            Rps.Hand hand = Rps.handAt(x, y);
            if (hand != null) {
                if (Rps.accepts(match.phase(), match.committed(hand.player()))) {
                    cue(AudioSlot.UI_CLICK.id());
                    return chooseHand(hand.player(), hand.hand());
                }
                return "ok";
            }
            if (Rps.PANEL.contains(x, y)) {
                return "ok";
            }
        }
        if (skipAnimation()) {
            // Any key/click settles a tumbling die early or ends a banner
            // and the click is consumed as the skip: it never
            // also moves the rabbit, rolls or ends the next turn.
            return "ok";
        }
        if (turnEndRunning) {
            // the turn-end beat is not skippable: no click ends it.
            return "ok";
        }
        // the HUD Tirar button is the pointer's roll action,
        // live only in AwaitRoll and disabled by every other phase. It sits in
        // the top margin and resolves through the same rect the painter draws.
        if (Chrome.TIRAR.rect().contains(x, y)) {
            if (Match.AWAIT_ROLL.equals(match.phase())) {
                cue(AudioSlot.UI_CLICK.id());
                return tirar();
            }
            return "ok";
        }
        // Terminar turno sits in the bottom band and is live
        // only after at least one step. The same predicate paints it, so a dim
        // button swallows the click and the turn cannot end with zero steps.
        if (Chrome.END_TURN.rect().contains(x, y)) {
            if (match.canEndTurn()) {
                cue(AudioSlot.UI_CLICK.id());
                return terminar();
            }
            return "ok";
        }
        if (cell == null || !Match.AWAIT_MOVE.equals(match.phase())) {
            return "ok";
        }
        if (!match.targetable(cell[0], cell[1])) {
            // an untargetable tile is greyed and ignores clicks. The click is
            // still recorded as the last pointer position, but no path, budget,
            // phase or cursor changes, so a script sees the same state before and
            // after.
            return "ok";
        }
        List<int[]> steps = match.moveTo(cell[0], cell[1]);
        logSteps(steps);
        afterMove(cell[0], cell[1]);
        repaint.run();
        return "ok";
    }

    /**
     * The action behind a control: buttons are the
     * named {@code press} action their label carries, while the field, the two
     * switches and the slider track have pointer meanings of their own. Nothing
     * here is a second rules path — a button click and a named press call the
     * same controller.
     */
    private String activate(String name, int x) {
        switch (name) {
            case "field":
                // Focus only: typing, Backspace and the clipboard chords are the
                // seed field's own input.
                return "ok";
            case "aleatoria": {
                if (!Match.TITULO.equals(match.screen()) || match.phase() != null) {
                    return "ok";
                }
                // a fresh random seed from the secure source, then the
                // field shows it and the next Nueva partida builds that board.
                match.preselect(Seed.random());
                repaint.run();
                return "ok";
            }
            case "master", "music", "sfx": {
                // a pointer click on the track sets the value; the number
                // beside it is always rendered next to it.
                Chrome.Rect track = Screens.of(match.screen()).control(name).rect();
                int value = Math.round((x - track.x()) * 100f / track.w());
                value = Math.max(0, Math.min(100, Math.round(value / 5f) * 5));
                settings.set(name, Integer.toString(value));
                applyAudio();
                logSetting(name);
                repaint.run();
                return "ok " + value;
            }
            case "muted", "fullscreen": {
                boolean value = !((Boolean) settings.value(name));
                settings.set(name, Boolean.toString(value));
                applyAudio();
                if ("fullscreen".equals(name)) {
                    applyWindowFullscreen();
                }
                logSetting(name);
                repaint.run();
                return "ok " + value;
            }
            case "copiar":
                copySeed();
                return "ok";
            default:
                return press(name);
        }
    }

    /** Copiar writes the seed and its generator version to the clipboard. */
    private void copySeed() {
        try {
            String text = match.board().seed() + "   ·   "
                    + Strings.text("hud.generation") + " " + BoardGenerator.VERSION;
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
        } catch (RuntimeException e) {
            // A headless run has no system clipboard; the click still lands and
            // the state is unchanged, so a frame and a script stay deterministic.
        }
    }

    private void logSetting(String key) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("key", key);
        fields.put("value", settings.value(key));
        log.event("settingsChanged", match.rngCursor(), fields);
    }

    /** push the stored sliders and mute onto the lines already open. */
    private void applyAudio() {
        audio.accept(new Mixing.Levels(settings.master(), settings.music(),
                settings.sfx(), settings.muted()));
    }

    /**
     * apply the stored fullscreen setting to the live
     * window. A headless run attached no window, and the setting alone is correct
     * there.
     */
    private void applyWindowFullscreen() {
        if (window != null) {
            window.setFullscreen(settings.fullscreen());
        }
    }

    private void endTurn() {
        String ended = match.active();
        hopRunning = false;
        match.endTurn();
        log.event("turnEnded", match.rngCursor(), turnEndedFields(ended));
        cueHandOffBanner();
        repaint.run();
    }

    /** The die's pips are known once it settles. */
    private void logRolled(int value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("active", match.active());
        fields.put("turn", match.turn());
        fields.put("roll", value);
        fields.put("budget", match.budget());
        log.event("rolled", match.rngCursor(), fields);
    }

    private void endTurnIfSpent() {
        if (!Match.AWAIT_MOVE.equals(match.phase()) || match.budget() == null || match.budget() > 0) {
            return;
        }
        String ended = match.active();
        match.endTurnIfSpent();
        log.event("turnEnded", match.rngCursor(), turnEndedFields(ended));
        cueHandOffBanner();
    }

    private Map<String, Object> turnEndedFields(String ended) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ended);
        fields.put("active", match.active());
        fields.put("turn", match.turn());
        return fields;
    }

    private Map<String, Object> restartedFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("seed", match.board().seed());
        fields.put("genVersion", rabbitrace.game.BoardGenerator.VERSION);
        fields.put("boardHash", match.board().hash());
        fields.put("active", match.active());
        fields.put("turn", match.turn());
        return fields;
    }

    /** Logs a screen transition and changes the surface. */
    private void screenTo(String from, String next) {
        if (from.equals(next)) {
            return;
        }
        match.screen(next);
        // the screen chooses the bed. Only Título and Partida
        // change one, so this is what crossfades in and back out.
        screenAudio.accept(next);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("from", from);
        fields.put("to", next);
        log.event("screen", match.rngCursor(), fields);
    }

    /** The chrome model as one JSON line: grid, named HUD parts, border props. */
    private static String layoutJson() {
        JsonObject layout = new JsonObject();
        layout.add("grid", rect(Chrome.GRID));
        JsonArray hud = new JsonArray();
        for (Chrome.Part part : Chrome.HUD) {
            JsonObject element = rect(part.rect());
            element.addProperty("name", part.name());
            hud.add(element);
        }
        layout.add("hud", hud);
        JsonArray border = new JsonArray();
        for (Chrome.Rect prop : Chrome.BORDER_PROPS) {
            border.add(rect(prop));
        }
        layout.add("border", border);
        return GSON.toJson(layout);
    }

    private static JsonObject rect(Chrome.Rect r) {
        JsonObject o = new JsonObject();
        o.addProperty("x", r.x());
        o.addProperty("y", r.y());
        o.addProperty("w", r.w());
        o.addProperty("h", r.h());
        return o;
    }

    private static JsonObject rect(int[] r) {
        JsonObject o = new JsonObject();
        o.addProperty("x", r[0]);
        o.addProperty("y", r[1]);
        o.addProperty("w", r[2]);
        o.addProperty("h", r[3]);
        return o;
    }

    /**
     * The window as one JSON line: its outer bounds, the maximized area of the
     * current monitor, and both the applied and the stored fullscreen value. A
     * headless run has no window, so it reports {@code windowed:false} and the
     * setting only — the same thing {@code state} says.
     */
    private String boundsJson() {
        JsonObject o = new JsonObject();
        int[] bounds = window == null ? null : window.bounds();
        o.addProperty("windowed", bounds != null);
        if (bounds != null) {
            o.add("window", rect(bounds));
            o.add("screen", rect(window.screenBounds()));
            o.addProperty("fullscreen", window.fullscreen());
        }
        o.addProperty("setting", settings.fullscreen());
        return GSON.toJson(o);
    }

    /**
     * The decoration layer as one JSON line:
     * whether it is on, and how many {@code deco.*} sprites the painter would
     * draw for the current match. The count comes from the same frame the painter
     * consumes, so a probe that says zero and a frame that still shows flowers
     * cannot both be true.
     */
    private String decorationJson() {
        Frame frame = Frames.of(match, settings, assets, decorations);
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("on", decorations);
        o.addProperty("decorations", BoardPainter.decorationCount(frame));
        return GSON.toJson(o);
    }

    /**
     * The sprite geometry as one JSON line: every
     * sprite the painter draws for the current frame, with the declared source
     * size, the virtual footprint and the destination rectangle the painter clips
     * it to, and each ground field's declared source size and {@code sample}
     * window. It reads {@link BoardPainter#spriteDraws} and
     * {@link BoardPainter#groundSamples}, the same lists the paint path consumes,
     * so a probe that reports the cap cannot disagree with the pixels. The
     * {@code dirt} tiles are reported too, so a pixel check can compare two
     * grass tiles of the same field.
     */
    private String spritesJson() {
        Frame frame = Frames.of(match, settings, assets, decorations, sprites,
                groundShiftX, groundShiftY);
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.addProperty("spritesOn", sprites);
        root.add("shift", pair(groundShiftX, groundShiftY));
        JsonArray sprites = new JsonArray();
        for (BoardPainter.SpriteDraw sprite : BoardPainter.spriteDraws(frame)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", sprite.slot());
            entry.add("size", pair(sprite.sizeW(), sprite.sizeH()));
            entry.add("footprint", pair(sprite.footprintW(), sprite.footprintH()));
            JsonObject draw = new JsonObject();
            draw.addProperty("x", sprite.x());
            draw.addProperty("y", sprite.y());
            draw.addProperty("w", sprite.w());
            draw.addProperty("h", sprite.h());
            entry.add("draw", draw);
            entry.addProperty("scaleX", sprite.scaleX());
            entry.addProperty("scaleY", sprite.scaleY());
            entry.add("declared", strings(sprite.declared()));
            entry.add("applied", strings(sprite.applied()));
            entry.addProperty("angle", sprite.angle());
            sprites.add(entry);
        }
        root.add("sprites", sprites);
        JsonArray dirt = new JsonArray();
        for (int row = 0; row < frame.board().rows(); row++) {
            for (int col = 0; col < frame.board().cols(); col++) {
                if (frame.board().dirtAt(col, row)) {
                    dirt.add(pair(col, row));
                }
            }
        }
        root.add("dirt", dirt);
        JsonArray fields = new JsonArray();
        for (BoardPainter.GroundSample field : BoardPainter.groundSamples(frame)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", field.slot());
            entry.add("size", pair(field.sizeW(), field.sizeH()));
            entry.add("sample", pair(field.sampleW(), field.sampleH()));
            entry.add("origin", pair(field.originX(), field.originY()));
            entry.addProperty("tiles", field.tiles());
            entry.add("last", pair(field.lastX(), field.lastY()));
            fields.add(entry);
        }
        root.add("fields", fields);
        return GSON.toJson(root);
    }

    /**
     * dev-only probe: draw {@code
     * prop.fence.segment} at its footprint in each declared transform
     * combination, into one 1600×900 image, through
     * {@link BoardPainter#paintFence}, the same path a fence prop takes on the
     * board. The reply carries the declared transforms and each draw's rectangle
     * and applied list, so the scenario can compare the drawn masks: the
     * {@code rot90} block must be the base block turned a quarter turn, not a
     * report of one.
     */
    private String writeFenceProbe(String rawPath) throws Exception {
        String slot = "prop.fence.segment";
        RenderPlan plan = RenderPlan.of(assets.slotSetVersion(), BoardGenerator.VERSION,
                "", assets.slots());
        int[] footprint = plan.footprint(slot);
        if (footprint == null) {
            footprint = new int[]{84, 88};
        }
        List<List<String>> combinations = List.of(
                List.of(),
                List.of(Transforms.FLIP_X),
                List.of(Transforms.ROT90),
                List.of(Transforms.ROT90, Transforms.FLIP_X));

        int background = 0x24331d;
        BufferedImage image = new BufferedImage(BoardPainter.WIDTH, BoardPainter.HEIGHT,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(background));
            g.fillRect(0, 0, BoardPainter.WIDTH, BoardPainter.HEIGHT);
            int y = 300;
            for (int i = 0; i < combinations.size(); i++) {
                BoardPainter.paintFence(g, 160 + i * 200, y,
                        footprint[0], footprint[1], combinations.get(i));
            }
        } finally {
            g.dispose();
        }
        Path file = Path.of(rawPath).toAbsolutePath();
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IllegalStateException("no PNG writer available");
        }

        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.addProperty("slot", slot);
        root.addProperty("background", background);
        root.add("declared", strings(plan.transforms(slot)));
        JsonArray draws = new JsonArray();
        int y = 300;
        for (int i = 0; i < combinations.size(); i++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("x", 160 + i * 200);
            entry.addProperty("y", y);
            entry.addProperty("w", footprint[0]);
            entry.addProperty("h", footprint[1]);
            entry.add("applied", strings(combinations.get(i)));
            entry.addProperty("angle", Transforms.angle(combinations.get(i)));
            draws.add(entry);
        }
        root.add("draws", draws);
        return GSON.toJson(root);
    }

    /**
     * dev-only probe: render a {@code ui.button.*}
     * slot at its native size and at 96×40, 96×64 and 96×120 into a 1600×900
     * image, through {@link NineSlice#drawButton}, the same path the HUD buttons
     * use. The reply names the source size, the declared {@code nineSlice} and
     * every destination rectangle, so the scenario's pixel check can compare the
     * four corner blocks at each size against the native draw.
     */
    private String writeSliceProbe(String rawPath, String slot) throws Exception {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("slice needs a path");
        }
        RenderPlan plan = RenderPlan.of(assets.slotSetVersion(), BoardGenerator.VERSION,
                "", assets.slots());
        BufferedImage image = new BufferedImage(BoardPainter.WIDTH, BoardPainter.HEIGHT,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x24331d));
            g.fillRect(0, 0, BoardPainter.WIDTH, BoardPainter.HEIGHT);
            for (NineSlice.ProbeDraw draw : NineSlice.probeDraws(plan, slot)) {
                NineSlice.drawButton(g, plan, slot, draw.x(), draw.y(), draw.w(), draw.h());
            }
        } finally {
            g.dispose();
        }
        Path file = Path.of(rawPath).toAbsolutePath();
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IllegalStateException("no PNG writer available");
        }

        BufferedImage source = NineSlice.source(plan, slot);
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.addProperty("slot", slot);
        root.add("source", pair(source.getWidth(), source.getHeight()));
        root.add("nineSlice", array(NineSlice.slices(plan, slot)));
        root.addProperty("corner", NineSlice.CORNER);
        JsonArray draws = new JsonArray();
        for (NineSlice.ProbeDraw draw : NineSlice.probeDraws(plan, slot)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("x", draw.x());
            entry.addProperty("y", draw.y());
            entry.addProperty("w", draw.w());
            entry.addProperty("h", draw.h());
            draws.add(entry);
        }
        root.add("draws", draws);
        return GSON.toJson(root);
    }

    private static JsonArray array(int[] values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (int value : values) {
                array.add(value);
            }
        }
        return array;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (String value : values) {
                array.add(value);
            }
        }
        return array;
    }

    private static JsonArray pair(int x, int y) {
        JsonArray pair = new JsonArray();
        pair.add(x);
        pair.add(y);
        return pair;
    }

    /**
     * The bed model as one JSON line: the chosen bed,
     * whether a fade is running, its duration, how many fades have started, and
     * each loop's playback. Keys are {@code music} and {@code ambience} rather
     * than slot ids because a scenario addresses them by dotted path.
     *
     * <p>{@code gain}, {@code gainDb}, {@code muted}, {@code open}, {@code opens}
     * and {@code frames} are read from the bed's real {@code SourceDataLine}: its
     * {@code MASTER_GAIN} and {@code MUTE} controls, the number of times the bed
     * created a line, and its frame position, with {@code gain} the envelope those
     * controls carry above formula. A model that never reached a line would
     * report {@code open:false} and no frames, so the scenario cannot pass on
     * bookkeeping alone.
     */
    private String audioJson() {
        if (sound == null) {
            throw new IllegalStateException("audio is not attached to this session");
        }
        LoopBeds.Snapshot state = sound.beds();
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.addProperty("status", sound.token());
        root.addProperty("bed", state.bed());
        root.addProperty("crossfading", state.crossfading());
        root.addProperty("crossfadeMs", state.crossfadeMs());
        root.addProperty("crossfades", state.crossfades());
        root.add("music", loopJson(state.music()));
        root.add("ambience", loopJson(state.ambience()));
        // The voice pool: the slots currently sounding, oldest first.
        JsonArray sfx = new JsonArray();
        for (String slot : sound.activeCues()) {
            sfx.add(slot);
        }
        root.add("sfx", sfx);
        // The cues this run actually started, in play order. Each started
        // clip's frame length is carried, so this is a record of clips that
        // reached the mixer rather than a counter beside it.
        AudioDirector.CueEvidence cues = sound.cues();
        JsonObject cueJson = new JsonObject();
        cueJson.addProperty("count", cues.count());
        cueJson.addProperty("last", cues.last());
        cueJson.addProperty("lastFrames", cues.lastFrames());
        cueJson.addProperty("history", cues.history());
        // One counter per SFX slot, keyed by its last name segment, so a
        // scenario can say "one reveal, two hops" without counting the whole log.
        JsonObject counts = new JsonObject();
        for (AudioSlot slot : AudioSlot.values()) {
            if (slot.loop()) {
                continue;
            }
            String key = slot.id().substring(slot.id().lastIndexOf('.') + 1);
            counts.addProperty(key, cues.counts().getOrDefault(slot.id(), 0));
        }
        cueJson.add("counts", counts);
        root.add("cues", cueJson);
        return GSON.toJson(root);
    }

    private JsonObject loopJson(LoopBeds.Bed bed) {
        AudioDirector.LineEvidence line = sound.line(bed.slot());
        JsonObject o = new JsonObject();
        o.addProperty("slot", bed.slot());
        o.addProperty("open", line.open());
        o.addProperty("playing", bed.playing());
        o.addProperty("starts", bed.starts());
        o.addProperty("gain", line.gain());
        o.addProperty("gainDb", line.gainDb());
        o.addProperty("muted", line.muted());
        o.addProperty("frames", line.frames());
        o.addProperty("opens", line.opens());
        o.addProperty("sampleRate", line.sampleRate());
        // The playback position is the line's frame counter, not the model's
        // clock: a bed that was never streamed reports zero frames.
        o.addProperty("positionMs", line.open()
                ? line.frames() * 1000L / Math.max(1, line.sampleRate())
                : bed.positionMs());
        o.addProperty("modelPositionMs", bed.positionMs());
        return o;
    }

    /**
     * The panel model as one JSON line: every screen's panel and its
     * controls, plus the HUD gear. The painter draws these rectangles and the
     * pointer path hit-tests them, so a scenario can check the geometry the
     * frames are painted from rather than measure pixels alone.
     */
    private static String panelsJson() {
        JsonObject root = new JsonObject();
        JsonArray screens = new JsonArray();
        for (Screens.Panel panel : Screens.PANELS) {
            JsonObject entry = new JsonObject();
            entry.addProperty("screen", panel.screen());
            entry.add("panel", rect(panel.rect()));
            JsonArray controls = new JsonArray();
            for (Screens.Control control : panel.controls()) {
                JsonObject element = rect(control.rect());
                element.addProperty("name", control.name());
                controls.add(element);
            }
            entry.add("controls", controls);
            screens.add(entry);
        }
        root.add("screens", screens);
        root.add("gear", rect(Chrome.GEAR.rect()));
        return GSON.toJson(root);
    }

    private static boolean isPlayer(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "p1", "blue", "p2", "brown" -> true;
            default -> false;
        };
    }

    private static String player(String text) {
        return "p1".equalsIgnoreCase(text) || "blue".equalsIgnoreCase(text)
                ? Match.BLUE : Match.BROWN;
    }

    private static int hand(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "1", "rock", "piedra" -> 1;
            case "2", "paper", "papel" -> 2;
            case "3", "scissors", "tijera" -> 3;
            default -> throw new IllegalArgumentException("a hand is 1, 2 or 3: " + text);
        };
    }

    /** A tile kind for the dev-only {@code obstacle} command. */
    private static char kind(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case ".", "grass" -> rabbitrace.game.Board.GRASS;
            case "o", "rock" -> rabbitrace.game.Board.ROCK;
            case "b", "bush" -> rabbitrace.game.Board.BUSH;
            case "t", "stump" -> rabbitrace.game.Board.STUMP;
            case "f", "fence" -> rabbitrace.game.Board.FENCE;
            default -> throw new IllegalArgumentException(
                    "a tile kind is rock, bush, stump, fence or grass: " + text);
        };
    }

    private static int[] ints(String rest, int count, String usage) {
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        if (parts.length != count) {
            throw new IllegalArgumentException(usage);
        }
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            try {
                values[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(usage + "; '" + parts[i] + "' is not an integer");
            }
        }
        return values;
    }
}
