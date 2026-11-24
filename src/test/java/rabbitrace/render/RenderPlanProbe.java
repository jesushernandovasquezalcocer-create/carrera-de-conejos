package rabbitrace.render;

import rabbitrace.assets.AssetSource;
import rabbitrace.game.BoardGenerator;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Prints the choices a run makes for one assets tree and
 * one seed. 's test runs this in separate JVMs; the transcript is
 * the evidence the two runs agree.
 */
public final class RenderPlanProbe {

    private RenderPlanProbe() {
    }

    public static void main(String[] args) {
        Path assetsRoot = Path.of(args[0]);
        String seed = args.length > 1 ? args[1] : "ABC123";
        System.out.print(describe(assetsRoot, seed));
    }

    /** The plan for a manifest directory, as the shipped boot path would build it. */
    public static String describe(Path assetsRoot, String seed) {
        AssetSource source = AssetSource.load(assetsRoot, message -> { });
        return describe(RenderPlan.of(source.slotSetVersion(), BoardGenerator.VERSION, seed,
                source.slots()));
    }

    /** One line per choice: the version, every field crop, every variant and every tile's decoration. */
    public static String describe(RenderPlan plan) {
        StringBuilder out = new StringBuilder();
        out.append("version ").append(plan.slotSetVersion()).append('\n');
        for (Map.Entry<String, int[]> crop : plan.cropOrigins().entrySet()) {
            out.append("crop ").append(crop.getKey())
                    .append(' ').append(crop.getValue()[0])
                    .append(' ').append(crop.getValue()[1]).append('\n');
        }
        for (Map.Entry<String, List<String>> group : plan.groups().entrySet()) {
            if (!group.getKey().startsWith("deco.") || group.getValue().size() < 2) {
                continue;
            }
            for (int row = 0; row < TileMap.ROWS; row++) {
                for (int col = 0; col < TileMap.COLS; col++) {
                    out.append("variant ").append(group.getKey())
                            .append(' ').append(col)
                            .append(' ').append(row)
                            .append(' ').append(plan.variant(group.getKey(), col, row))
                            .append('\n');
                }
            }
        }
        for (int row = 0; row < TileMap.ROWS; row++) {
            for (int col = 0; col < TileMap.COLS; col++) {
                String slot = plan.decorationAt(col, row);
                if (slot != null) {
                    out.append("deco ").append(col).append(' ').append(row)
                            .append(' ').append(slot).append('\n');
                }
            }
        }
        return out.toString();
    }
}
