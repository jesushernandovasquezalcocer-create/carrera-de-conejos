package rabbitrace.render;

import rabbitrace.assets.AssetSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The renderer's per-match choices: which decoration
 * variant each tile carries and which crop origin each ground field gets.
 *
 * <p>Both are render-only and deterministic for a fixed {@code (slotSetVersion,
 * genVersion, seed, slots)}; no choice touches the match-seed stream, so
 * {@code rngCursor}, {@code boardHash} and replays are unaffected.
 *
 * <p>A variant group is the id stem of ({@code prop.rock.a} and
 * {@code prop.rock.b} are {@code prop.rock}). requires a manifest reorder
 * to be inert, so members are ordered by id rather than by their position in the
 * JSON, and the hash carries the slot-set version; reordering alone cannot
 * re-map a variant, and a version bump is what moves the choices.
 */
public final class RenderPlan {

    /** each field is the board plus one tile of margin, 2688×1600. */
    public static final int FIELD_WIDTH = 2688;
    public static final int FIELD_HEIGHT = 1600;

    /** decorations cover roughly 35% of the eligible tiles. */
    private static final int DECORATION_PERCENT = 35;

    private static final RenderPlan NONE = new RenderPlan(0, 0, "", List.of(), false, true, 0, 0);

    private final int slotSetVersion;
    private final int genVersion;
    private final String seed;
    private final Map<String, List<String>> groups;
    private final List<String> decorationGroups;
    private final Map<String, int[]> cropOrigins;
    private final Map<String, AssetSource.Slot> slotsById;
    private final String[][] decoration;
    private final boolean decorations;
    private final boolean sprites;
    private final int groundShiftX;
    private final int groundShiftY;

    private RenderPlan(int slotSetVersion, int genVersion, String seed, List<AssetSource.Slot> slots,
                       boolean decorations, boolean sprites, int groundShiftX, int groundShiftY) {
        this.slotSetVersion = slotSetVersion;
        this.genVersion = genVersion;
        this.seed = seed;
        this.decorations = decorations;
        this.sprites = sprites;
        this.groundShiftX = groundShiftX;
        this.groundShiftY = groundShiftY;

        Map<String, List<String>> byGroup = new java.util.TreeMap<>();
        Map<String, int[]> fields = new java.util.TreeMap<>();
        Map<String, AssetSource.Slot> byId = new LinkedHashMap<>();
        for (AssetSource.Slot slot : slots) {
            String id = slot.id();
            if (id == null || id.isBlank()) {
                continue;
            }
            if (!byId.containsKey(id)) {
                byId.put(id, slot);
            }
            if ("field".equals(slot.placement()) && slot.width() > 0 && slot.height() > 0) {
                fields.put(id, new int[]{slot.width(), slot.height()});
            }
            int dot = id.lastIndexOf('.');
            if (dot > 0) {
                byGroup.computeIfAbsent(id.substring(0, dot), key -> new ArrayList<>()).add(id);
            }
        }
        this.slotsById = Collections.unmodifiableMap(byId);
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        List<String> decoGroups = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byGroup.entrySet()) {
            List<String> members = new ArrayList<>(entry.getValue());
            // manifest order must not re-map a variant, so the group's
            // members are read in id order, not in the order the JSON lists them.
            Collections.sort(members);
            frozen.put(entry.getKey(), List.copyOf(members));
            if (entry.getKey().startsWith("deco.") && members.size() > 1) {
                decoGroups.add(entry.getKey());
            }
        }
        this.groups = Collections.unmodifiableMap(frozen);
        this.decorationGroups = List.copyOf(decoGroups);

        Map<String, int[]> crops = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> field : fields.entrySet()) {
            crops.put(field.getKey(), RenderHash.cropOrigin(slotSetVersion, genVersion, seed,
                    field.getKey(), field.getValue()[0], field.getValue()[1]));
        }
        this.cropOrigins = Collections.unmodifiableMap(crops);

        this.decoration = new String[TileMap.ROWS][TileMap.COLS];
        if (!decorations) {
            // the layer is render-only, so a run can turn it off without
            // changing any rule, any hash or the match-seed stream.
            return;
        }
        for (int row = 0; row < TileMap.ROWS; row++) {
            for (int col = 0; col < TileMap.COLS; col++) {
                decoration[row][col] = chooseDecoration(col, row);
            }
        }
    }

    /** The plan for one match; {@code slots} is the manifest's sprite list. */
    public static RenderPlan of(int slotSetVersion, int genVersion, String seed,
                                List<AssetSource.Slot> slots) {
        return of(slotSetVersion, genVersion, seed, slots, true);
    }

    /**
     * The same plan with the decoration layer on or off. Turning it off
     * chooses no decoration at all, so the painter draws the bare board; the
     * variant groups and ground crops stay identical, because the layer changes
     * pixels only.
     */
    public static RenderPlan of(int slotSetVersion, int genVersion, String seed,
                                List<AssetSource.Slot> slots, boolean decorations) {
        return of(slotSetVersion, genVersion, seed, slots, decorations, true, 0, 0);
    }

    /**
     * The same plan with the two dev-only probes: the non-border sprite
     * layer on or off, and a source-pixel offset on every ground crop origin.
     * Both are render-only, like the decoration switch: no rule, no hash and no
     * match-seed draw moves. The offset exists so a scenario can render the same
     * board twice and see the declared 168x160 stride in the painted pixels.
     */
    public static RenderPlan of(int slotSetVersion, int genVersion, String seed,
                                List<AssetSource.Slot> slots, boolean decorations, boolean sprites,
                                int groundShiftX, int groundShiftY) {
        return new RenderPlan(slotSetVersion, genVersion, seed == null ? "" : seed,
                slots == null ? List.of() : slots, decorations, sprites, groundShiftX, groundShiftY);
    }

    /** The plan of a run with no manifest: no decorations, no declared fields. */
    public static RenderPlan none() {
        return NONE;
    }

    public int slotSetVersion() {
        return slotSetVersion;
    }

    public int genVersion() {
        return genVersion;
    }

    /** Whether the non-border sprite layer is painted (dev-only probe). */
    public boolean sprites() {
        return sprites;
    }

    /** The dev-only source-pixel offset on every ground crop origin. */
    public int groundShiftX() {
        return groundShiftX;
    }

    /** The dev-only source-pixel offset on every ground crop origin. */
    public int groundShiftY() {
        return groundShiftY;
    }

    public String seed() {
        return seed;
    }

    /** Every variant group, its members in the stable id order. */
    public Map<String, List<String>> groups() {
        return groups;
    }

    /** The one crop origin per declared ground field, or an empty map. */
    public Map<String, int[]> cropOrigins() {
        return cropOrigins;
    }

    /**
     * The crop origin for a field, or {@code null} when the manifest declares no
     * field of that id. The painter falls back to {@link RenderHash#cropOrigin}
     * with geometry, so a kit-less run still has one continuous plate.
     */
    public int[] cropOrigin(String fieldId) {
        return cropOrigins.get(fieldId);
    }

    /** The manifest entry for a slot id, or {@code null} in a kit-less run. */
    public AssetSource.Slot slot(String id) {
        return slotsById.get(id);
    }

    /**
     * A sprite's declared source size as {@code [w, h]}, or {@code null} when the
     * slot declares none. makes it exactly 2× the
     * footprint; the painter's cap is read from it.
     */
    public int[] size(String id) {
        AssetSource.Slot slot = slotsById.get(id);
        if (slot == null || slot.width() <= 0 || slot.height() <= 0) {
            return null;
        }
        return new int[]{slot.width(), slot.height()};
    }

    /**
     * A sprite's virtual footprint as {@code [w, h]}, or {@code null} when the
     * slot declares none. This is the largest rectangle the painter may draw the
     * sprite into.
     */
    public int[] footprint(String id) {
        AssetSource.Slot slot = slotsById.get(id);
        if (slot == null || slot.footprintWidth() <= 0 || slot.footprintHeight() <= 0) {
            return null;
        }
        return new int[]{slot.footprintWidth(), slot.footprintHeight()};
    }

    /**
     * A field slot's declared per-tile sample window as {@code [w, h]}, or
     * {@code null} when the slot declares none. and require exactly
     * {@code 2 × 84×80}.
     */
    public int[] sample(String fieldId) {
        AssetSource.Slot slot = slotsById.get(fieldId);
        if (slot == null || slot.sampleWidth() <= 0 || slot.sampleHeight() <= 0) {
            return null;
        }
        return new int[]{slot.sampleWidth(), slot.sampleHeight()};
    }

    /**
     * A slot's declared 9-slice as {@code [l, t, r, b]} source pixels, or
     * {@code null} when it declares none. The
     * painter draws such a slot into a layout rectangle with corners at 1:1.
     */
    public int[] nineSlice(String id) {
        AssetSource.Slot slot = slotsById.get(id);
        if (slot == null || slot.sliceLeft() <= 0 || slot.sliceTop() <= 0
                || slot.sliceRight() <= 0 || slot.sliceBottom() <= 0) {
            return null;
        }
        return new int[]{slot.sliceLeft(), slot.sliceTop(), slot.sliceRight(), slot.sliceBottom()};
    }

    /**
     * A slot's declared transforms (allowed subset) in
     * manifest order, or an empty list when it declares none. lets the
     * painter apply only these, and only in 90° steps and mirrors.
     */
    public List<String> transforms(String id) {
        AssetSource.Slot slot = slotsById.get(id);
        return slot == null || slot.transforms() == null ? List.of() : slot.transforms();
    }

    /** The variant chosen for {@code group} at a tile, or null for an unknown group. */
    public String variant(String group, int col, int row) {
        List<String> members = groups.get(group);
        if (members == null || members.isEmpty() || col < 0 || row < 0
                || col >= TileMap.COLS || row >= TileMap.ROWS) {
            return null;
        }
        return members.get(RenderHash.variantIndex(slotSetVersion, genVersion, seed, group,
                col, row, members.size()));
    }

    /** The decoration slot chosen for a tile, or {@code null} when it carries none. */
    public String decorationAt(int col, int row) {
        if (col < 0 || row < 0 || col >= TileMap.COLS || row >= TileMap.ROWS) {
            return null;
        }
        return decoration[row][col];
    }

    /**
     * chooses a variant inside a group. The same render hash decides which
     * tiles carry a decoration at all, so a
     * reorder or a seed moves the whole layer together.
     */
    private String chooseDecoration(int col, int row) {
        if (decorationGroups.isEmpty()) {
            return null;
        }
        long h = RenderHash.variantHash(slotSetVersion, genVersion, seed, "deco", col, row);
        if (Long.remainderUnsigned(h, 100) >= DECORATION_PERCENT) {
            return null;
        }
        String group = decorationGroups.get(
                (int) Long.remainderUnsigned(h >>> 16, decorationGroups.size()));
        return variant(group, col, row);
    }
}
