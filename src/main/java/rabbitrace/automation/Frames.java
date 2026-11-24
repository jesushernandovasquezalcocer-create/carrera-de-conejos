package rabbitrace.automation;

import rabbitrace.assets.AssetSource;
import rabbitrace.game.BoardGenerator;
import rabbitrace.game.Match;
import rabbitrace.render.BoardPainter;
import rabbitrace.render.Frame;
import rabbitrace.render.RenderPlan;
import rabbitrace.settings.Settings;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Glue between a match and the pure painter: builds the painter input from state. */
public final class Frames {

    private Frames() {
    }

    public static Frame of(Match match) {
        return of(match, Settings.defaults(), AssetSource.placeholder());
    }

    /**
     * The painter input for the live screen. {@code settings} is what the Ajustes
     * panel renders, and {@code opener} is the
     * screen it covers, named only while Ajustes is up.
     */
    public static Frame of(Match match, Settings settings) {
        return of(match, settings, AssetSource.placeholder());
    }

    /**
     * The same frame with the manifest's render plan: the decoration variants
     * and ground crop this match's seed picks.
     */
    public static Frame of(Match match, Settings settings, AssetSource assets) {
        return of(match, settings, assets, true);
    }

    /**
     * The same frame with the decoration layer on or off. Off is the bare
     * board: same match state, same variant groups and ground crop, no
     * {@code deco.*} sprites.
     */
    public static Frame of(Match match, Settings settings, AssetSource assets, boolean decorations) {
        return of(match, settings, assets, decorations, true, 0, 0);
    }

    /**
     * The same frame with two dev-only render probes: the non-border
     * sprite layer on or off and a source-pixel offset on the ground crop. Both
     * move pixels only, never match state.
     */
    public static Frame of(Match match, Settings settings, AssetSource assets, boolean decorations,
                           boolean sprites, int groundShiftX, int groundShiftY) {
        String opener = Match.AJUSTES.equals(match.screen()) ? match.opener() : null;
        return new Frame(
                match.board(),
                match.blue(),
                match.brown(),
                new int[]{BoardGenerator.CARROT_COL, BoardGenerator.CARROT_ROW},
                match.screen(),
                match.phase(),
                match.revealedHand(Match.BLUE),
                match.revealedHand(Match.BROWN),
                match.committed(Match.BLUE),
                match.committed(Match.BROWN),
                match.active(),
                match.turn(),
                match.roll(),
                match.canEndTurn(),
                match.board().seed(),
                match.rngCursor(),
                match.board().hash(),
                match.legalTargets(),
                opener,
                settings,
                match.seedField(),
                match.seedFieldValid(),
                RenderPlan.of(assets.slotSetVersion(), BoardGenerator.VERSION,
                        match.board().seed(), assets.slots(), decorations, sprites,
                        groundShiftX, groundShiftY));
    }

    /** Renders the current frame to a 1600x900 PNG and returns its absolute path. */
    public static Path writePng(Match match, Settings settings, String rawPath) throws Exception {
        return writePng(match, settings, AssetSource.placeholder(), rawPath);
    }

    /** The shipped frame command: the run's asset source supplies the render plan. */
    public static Path writePng(Match match, Settings settings, AssetSource assets, String rawPath)
            throws Exception {
        return writePng(match, settings, assets, true, rawPath);
    }

    /**
     * The frame command with the layer on or off, so a
     * scenario can render the same match both ways.
     */
    public static Path writePng(Match match, Settings settings, AssetSource assets,
                                boolean decorations, String rawPath) throws Exception {
        return writePng(match, settings, assets, decorations, true, 0, 0, rawPath);
    }

    /**
     * The frame command with dev-only render probes (non-border sprites
     * on or off, and a source-pixel ground offset). The PNG is the same painter
     * path as the shipped frame; only the two switches differ.
     */
    public static Path writePng(Match match, Settings settings, AssetSource assets,
                                boolean decorations, boolean sprites, int groundShiftX,
                                int groundShiftY, String rawPath) throws Exception {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("frame needs a path");
        }
        Path file = Path.of(rawPath).toAbsolutePath();
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        BufferedImage image = BoardPainter.render(
                of(match, settings, assets, decorations, sprites, groundShiftX, groundShiftY));
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IllegalStateException("no PNG writer available");
        }
        return file;
    }

    /** The fields a lifecycle event carries. */
    public static Map<String, Object> fields(Match match) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("seed", match.board().seed());
        fields.put("genVersion", BoardGenerator.VERSION);
        fields.put("boardHash", match.board().hash());
        return fields;
    }
}
