package rabbitrace.automation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import rabbitrace.assets.AssetSource;
import rabbitrace.game.Board;
import rabbitrace.game.BoardGenerator;
import rabbitrace.game.Match;
import rabbitrace.i18n.Strings;
import rabbitrace.render.Animations;
import rabbitrace.settings.Settings;

/**
 * The state dump, schema {@value #VERSION}.
 *
 * <p>Built as an ordered JSON object, so a state line is byte-stable for the same
 * match and single-line by construction: no pretty-printing, no embedded newlines,
 * no NaN.
 */
public final class Snapshot {

    public static final int VERSION = 2;

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private Snapshot() {
    }

    public static String of(Match match) {
        return of(match, false, Settings.defaults());
    }

    public static String of(Match match, boolean includeBoard) {
        return of(match, includeBoard, Settings.defaults());
    }

    public static String of(Match match, boolean includeBoard, Settings settings) {
        return of(match, includeBoard, settings, Animations.defaults());
    }

    public static String of(Match match, boolean includeBoard, Settings settings, Animations animations) {
        return of(match, includeBoard, settings, animations, null);
    }

    /**
     * @param runningBeat a beat the automation runs outside the phase machine
     *     (the hop and the turn-end beat), or {@code null}
     */
    public static String of(Match match, boolean includeBoard, Settings settings, Animations animations,
                            String runningBeat) {
        return of(match, includeBoard, settings, animations, runningBeat, AssetSource.placeholder());
    }

    /**
     * The full dump, including two additive fields:
     * {@code assets} ({@code placeholder} or {@code file}) and
     * {@code slotSetVersion}.
     */
    public static String of(Match match, boolean includeBoard, Settings settings, Animations animations,
                            String runningBeat, AssetSource assets) {
        Board board = match.board();
        JsonObject o = new JsonObject();
        o.addProperty("v", VERSION);
        o.addProperty("screen", match.screen());
        o.addProperty("phase", match.phase());
        // whether the run draws the kit or the final files, so an
        // scripted run can tell a stand-in frame from a final one without reading pixels.
        o.addProperty("assets", assets.kind());
        o.addProperty("slotSetVersion", assets.slotSetVersion());
        o.addProperty("active", match.active());
        o.addProperty("turn", match.turn());
        o.addProperty("roll", match.roll());
        o.addProperty("budget", match.budget());

        // Readiness is public, the chosen hand is not. Hands
        // appear here only after both players committed, and a tie clears them.
        JsonObject opening = new JsonObject();
        opening.addProperty("p1", match.revealedHand(Match.BLUE));
        opening.addProperty("p2", match.revealedHand(Match.BROWN));
        opening.addProperty("winner", match.openingWinner());
        JsonObject committed = new JsonObject();
        committed.addProperty("p1", match.committed(Match.BLUE));
        committed.addProperty("p2", match.committed(Match.BROWN));
        opening.add("committed", committed);
        o.add("throw", opening);

        o.addProperty("seed", board.seed());
        o.addProperty("genVersion", BoardGenerator.VERSION);
        // the Título seed field is not match state, so it sits
        // beside `seed` rather than replacing it. A field that is not a valid
        // seed carries the exact copy the painter shows.
        o.addProperty("seedField", match.seedField());
        o.addProperty("seedValid", match.seedFieldValid());
        o.addProperty("seedMessage",
                match.seedFieldValid() ? null : Strings.text("title.seedInvalid"));
        o.addProperty("rngCursor", match.rngCursor());

        JsonObject rabbits = new JsonObject();
        rabbits.add("blue", cell(match.blue()));
        rabbits.add("brown", cell(match.brown()));
        o.add("rabbits", rabbits);
        o.add("carrot", cell(new int[]{BoardGenerator.CARROT_COL, BoardGenerator.CARROT_ROW}));
        o.addProperty("boardHash", board.hash());

        JsonArray targets = new JsonArray();
        for (int[] target : match.legalTargets()) {
            targets.add(cell(target));
        }
        o.add("legalTargets", targets);
        // true while a skippable animation is running. The die tumble and the
        // no-move/hand-off banners are all skippable, and a
        // beat whose duration is 0 does not run.
        o.addProperty("animating", running(match, animations, runningBeat));
        // The one tunable table behind every beat, so a
        // scripted session can read back what the dev hook set.
        JsonObject durations = new JsonObject();
        animations.asMap().forEach(durations::addProperty);
        o.add("durations", durations);
        o.add("settings", settings.toJson());

        if (includeBoard) {
            JsonArray rows = new JsonArray();
            for (String row : board.rowsAsStrings()) {
                rows.add(row);
            }
            o.add("rows", rows);
        }
        return GSON.toJson(o);
    }

    private static boolean running(Match match, Animations animations, String beat) {
        if (beat != null) {
            return animations.get(beat) > 0;
        }
        if (Match.RPS_REVEAL.equals(match.phase())) {
            return animations.get(Animations.RPS_SHAKE) > 0;
        }
        if (Match.ROLLING.equals(match.phase())) {
            return animations.get(Animations.DIE_TUMBLE) > 0;
        }
        if (Match.NO_LEGAL_MOVE.equals(match.phase())) {
            return animations.get(Animations.NO_MOVE) > 0;
        }
        if (Match.HAND_OFF.equals(match.phase())) {
            return animations.get(Animations.HAND_OFF) > 0;
        }
        return false;
    }

    private static JsonArray cell(int[] cell) {
        JsonArray a = new JsonArray();
        a.add(cell[0]);
        a.add(cell[1]);
        return a;
    }
}
