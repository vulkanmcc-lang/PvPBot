package com.pvpbot;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;

public final class FormationManager {
    public enum Shape {
        GRID,
        CIRCLE,
        ARC,
        LINE,
        COLUMN,
        WEDGE,
        SQUARE,

        DIAMOND,
        TRIANGLE,
        CROSS,
        SALTIRE,
        STAR,
        SPIRAL,
        ECHELON,
        DOUBLE_LINE,
        CHEVRON,
        SHIELD_WALL,
        SKIRMISH,
        VANGUARD;

        public static Shape parse(String raw) {
            if (raw == null) return null;
            String v = raw.toUpperCase(java.util.Locale.ROOT);
            if (v.equals("RING")) return CIRCLE;
            if (v.equals("ARCH")) return ARC;
            if (v.equals("ROW") || v.equals("RANK")) return LINE;
            if (v.equals("FILE")) return COLUMN;
            if (v.equals("V") || v.equals("ARROW")) return WEDGE;
            if (v.equals("BOX") || v.equals("HOLLOW")) return SQUARE;
            if (v.equals("RHOMBUS")) return DIAMOND;
            if (v.equals("TRI") || v.equals("DELTA")) return TRIANGLE;
            if (v.equals("PLUS")) return CROSS;
            if (v.equals("X")) return SALTIRE;
            if (v.equals("BURST")) return STAR;
            if (v.equals("SWIRL") || v.equals("HELIX")) return SPIRAL;
            if (v.equals("STAGGER")) return ECHELON;
            if (v.equals("RANKS") || v.equals("TWOLINE")) return DOUBLE_LINE;
            if (v.equals("ARROWHEAD")) return CHEVRON;
            if (v.equals("WALL") || v.equals("PHALANX")) return SHIELD_WALL;
            if (v.equals("LOOSE") || v.equals("SCATTER")) return SKIRMISH;
            if (v.equals("SPEAR") || v.equals("TIP")) return VANGUARD;
            for (Shape s : values()) if (s.name().equals(v)) return s;
            return null;
        }

        public static java.util.List<String> names() {
            java.util.List<String> out = new ArrayList<>();
            for (Shape s : values()) out.add(s.name().toLowerCase(java.util.Locale.ROOT));
            return out;
        }
    }

    public static final double DEFAULT_SPACING = 2.0;

    private static final double FIRST_ROW_DISTANCE = 3.0;

    private static final int GROUND_SEARCH_RANGE = 8;

    private FormationManager() {}

    public static List<Location> computeGrid(Location anchor, int count, double spacing) {
        List<Location> slots = new ArrayList<>(Math.max(count, 0));
        if (count <= 0 || anchor.getWorld() == null) return slots;
        if (spacing <= 0.25) spacing = DEFAULT_SPACING;

        int cols = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil((double) count / cols);

        double yawRad = Math.toRadians(anchor.getYaw());
        double fwdX = -Math.sin(yawRad);
        double fwdZ =  Math.cos(yawRad);
        double rightX = -fwdZ;
        double rightZ =  fwdX;

        float facingYaw = normalizeYaw(anchor.getYaw() + 180.0f);

        int placed = 0;
        for (int row = 0; row < rows && placed < count; row++) {
            int inThisRow = Math.min(cols, count - placed);

            double firstColOffset = -((inThisRow - 1) / 2.0) * spacing;
            double rowDistance = FIRST_ROW_DISTANCE + row * spacing;

            for (int col = 0; col < inThisRow; col++, placed++) {
                double lateral = firstColOffset + col * spacing;
                double x = anchor.getX() + fwdX * rowDistance + rightX * lateral;
                double z = anchor.getZ() + fwdZ * rowDistance + rightZ * lateral;
                double y = snapToGround(anchor.getWorld(), x, anchor.getY(), z);
                slots.add(new Location(anchor.getWorld(), x, y, z, facingYaw, 0.0f));
            }
        }
        return slots;
    }

    public static List<Location> compute(Location anchor, Shape shape, int count, double spacing) {
        if (shape == null || shape == Shape.GRID) return computeGrid(anchor, count, spacing);
        List<Location> slots = new ArrayList<>(Math.max(count, 0));
        if (count <= 0 || anchor.getWorld() == null) return slots;
        if (spacing <= 0.25) spacing = DEFAULT_SPACING;

        List<double[]> offsets = switch (shape) {
            case CIRCLE -> ringOffsets(count, spacing, 360.0);
            case ARC    -> ringOffsets(count, spacing, 180.0);
            case LINE   -> lineOffsets(count, spacing, false);
            case COLUMN -> lineOffsets(count, spacing, true);
            case WEDGE  -> wedgeOffsets(count, spacing);
            case SQUARE -> squareOffsets(count, spacing);
            case DIAMOND     -> diamondOffsets(count, spacing);
            case TRIANGLE    -> triangleOffsets(count, spacing);
            case CROSS       -> armOffsets(count, spacing, false);
            case SALTIRE     -> armOffsets(count, spacing, true);
            case STAR        -> starOffsets(count, spacing);
            case SPIRAL      -> spiralOffsets(count, spacing);
            case ECHELON     -> echelonOffsets(count, spacing);
            case DOUBLE_LINE -> doubleLineOffsets(count, spacing);
            case CHEVRON     -> chevronOffsets(count, spacing);
            case SHIELD_WALL -> shieldWallOffsets(count, spacing);
            case SKIRMISH    -> skirmishOffsets(count, spacing);
            case VANGUARD    -> vanguardOffsets(count, spacing);
            default     -> java.util.Collections.emptyList();
        };
        return materialize(anchor, offsets);
    }

    private static List<double[]> ringOffsets(int count, double spacing, double sweepDegrees) {
        List<double[]> out = new ArrayList<>(count);
        if (count == 1) {
            out.add(new double[]{FIRST_ROW_DISTANCE + spacing, 0});
            return out;
        }
        boolean closed = sweepDegrees >= 359.0;
        int gaps = closed ? count : count - 1;

        double sweepRad = Math.toRadians(sweepDegrees);
        double radius = Math.max(spacing, (spacing * gaps) / sweepRad);
        double centreForward = FIRST_ROW_DISTANCE + radius;

        for (int i = 0; i < count; i++) {
            double t = (double) i / gaps;
            double angle = -sweepRad / 2.0 + sweepRad * t;
            if (closed) angle = sweepRad * t;
            double forward = centreForward - Math.cos(angle) * radius;
            double lateral = Math.sin(angle) * radius;
            out.add(new double[]{forward, lateral});
        }
        return out;
    }

    private static List<double[]> lineOffsets(int count, double spacing, boolean single) {
        List<double[]> out = new ArrayList<>(count);
        double first = -((count - 1) / 2.0) * spacing;
        for (int i = 0; i < count; i++) {
            if (single) out.add(new double[]{FIRST_ROW_DISTANCE + i * spacing, 0});
            else        out.add(new double[]{FIRST_ROW_DISTANCE, first + i * spacing});
        }
        return out;
    }

    private static List<double[]> wedgeOffsets(int count, double spacing) {
        List<double[]> out = new ArrayList<>(count);
        out.add(new double[]{FIRST_ROW_DISTANCE, 0});
        for (int i = 1; i < count; i++) {
            int rank = (i + 1) / 2;
            double side = (i % 2 == 1) ? -1 : 1;
            out.add(new double[]{
                    FIRST_ROW_DISTANCE + rank * spacing * 0.75,
                    side * rank * spacing});
        }
        return out;
    }

    private static List<double[]> squareOffsets(int count, double spacing) {
        List<double[]> out = new ArrayList<>(count);
        if (count <= 0) return out;

        int side = Math.max(2, (int) Math.ceil((count + 4) / 4.0));
        double half = ((side - 1) / 2.0) * spacing;
        double centreForward = FIRST_ROW_DISTANCE + half;

        List<double[]> perimeter = new ArrayList<>();
        for (int row = 0; row < side; row++) {
            for (int col = 0; col < side; col++) {
                boolean edge = row == 0 || col == 0 || row == side - 1 || col == side - 1;
                if (!edge) continue;
                perimeter.add(new double[]{
                        centreForward - half + row * spacing,
                        -half + col * spacing});
            }
        }
        for (int i = 0; i < count; i++) out.add(perimeter.get(i % perimeter.size()));
        return out;
    }

    private static List<Location> materialize(Location anchor, List<double[]> offsets) {
        List<Location> slots = new ArrayList<>(offsets.size());
        double yawRad = Math.toRadians(anchor.getYaw());
        double fwdX = -Math.sin(yawRad);
        double fwdZ =  Math.cos(yawRad);
        double rightX = -fwdZ;
        double rightZ =  fwdX;
        float facingYaw = normalizeYaw(anchor.getYaw() + 180.0f);

        for (double[] o : offsets) {
            double x = anchor.getX() + fwdX * o[0] + rightX * o[1];
            double z = anchor.getZ() + fwdZ * o[0] + rightZ * o[1];
            double y = snapToGround(anchor.getWorld(), x, anchor.getY(), z);
            slots.add(new Location(anchor.getWorld(), x, y, z, facingYaw, 0.0f));
        }
        return slots;
    }

    public static int arrange(List<PvPBot> bots, Location anchor, Shape shape,
                              double spacing, boolean instant) {
        List<Location> slots = compute(anchor, shape, bots.size(), spacing);
        int moved = 0;
        for (int i = 0; i < bots.size() && i < slots.size(); i++) {
            PvPBot bot = bots.get(i);
            if (bot == null || !bot.isAlive()) continue;
            if (instant) bot.teleportTo(slots.get(i));
            else bot.orderToFormationSlot(slots.get(i));
            moved++;
        }
        return moved;
    }

    public static int arrangeGrid(List<PvPBot> bots, Location anchor, double spacing) {
        List<Location> slots = computeGrid(anchor, bots.size(), spacing);
        int moved = 0;
        for (int i = 0; i < bots.size() && i < slots.size(); i++) {
            PvPBot bot = bots.get(i);
            if (bot == null || !bot.isAlive()) continue;
            bot.teleportTo(slots.get(i));
            moved++;
        }
        return moved;
    }

    private static double snapToGround(World world, double x, double aroundY, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int startY = (int) Math.floor(aroundY);

        for (int dy = 0; dy <= GROUND_SEARCH_RANGE; dy++) {
            if (isStandable(world, bx, startY - dy, bz)) return startY - dy;
            if (dy != 0 && isStandable(world, bx, startY + dy, bz)) return startY + dy;
        }
        return aroundY;
    }

    private static boolean isStandable(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) return false;
        Block below = world.getBlockAt(x, y - 1, z);
        Block feet  = world.getBlockAt(x, y, z);
        Block head  = world.getBlockAt(x, y + 1, z);
        return below.getType().isSolid() && feet.isPassable() && head.isPassable();
    }

    private static float normalizeYaw(float yaw) {
        yaw %= 360.0f;
        if (yaw > 180.0f) yaw -= 360.0f;
        if (yaw < -180.0f) yaw += 360.0f;
        return yaw;
    }

    private static List<double[]> diamondOffsets(int count, double spacing) {
        List<double[]> out = new ArrayList<>(count);

        double radius = Math.max(spacing, count * spacing / 8.0);
        double centre = FIRST_ROW_DISTANCE + radius;
        for (int i = 0; i < count; i++) {
            double t = 4.0 * i / count;
            int edge = (int) t;
            double f = t - edge;

            double[][] corner = {{-radius, 0}, {0, radius}, {radius, 0}, {0, -radius}};
            double[] a = corner[edge % 4];
            double[] b = corner[(edge + 1) % 4];
            out.add(new double[]{
                    centre + a[0] + (b[0] - a[0]) * f,
                    a[1] + (b[1] - a[1]) * f});
        }
        return out;
    }

    private static List<double[]> triangleOffsets(int count, double spacing) {
        List<double[]> out = new ArrayList<>(count);
        int placed = 0;
        int row = 0;
        while (placed < count) {
            int inRow = Math.min(row + 1, count - placed);
            double first = -((inRow - 1) / 2.0) * spacing;
            for (int i = 0; i < inRow; i++) {
                out.add(new double[]{
                        FIRST_ROW_DISTANCE + row * spacing * 0.85,
                        first + i * spacing});
            }
            placed += inRow;
            row++;
        }
        return out;
    }

    private static List<double[]> armOffsets(int count, double spacing, boolean diagonal) {
        List<double[]> out = new ArrayList<>(count);
        double centre = FIRST_ROW_DISTANCE + Math.max(spacing, count * spacing / 8.0);
        double base = diagonal ? 45.0 : 0.0;

        int ring = 1;
        while (out.size() < count) {
            for (int arm = 0; arm < 4 && out.size() < count; arm++) {
                double ang = Math.toRadians(base + arm * 90.0);
                double dist = ring * spacing;
                out.add(new double[]{
                        centre + Math.cos(ang) * dist,
                        Math.sin(ang) * dist});
            }
            ring++;
        }
        return out;
    }

    private static List<double[]> starOffsets(int count, double spacing) {
        List<double[]> out = new ArrayList<>(count);
        double centre = FIRST_ROW_DISTANCE + Math.max(spacing, count * spacing / 10.0);
        if (count > 0) out.add(new double[]{centre, 0});
        int ring = 1;
        while (out.size() < count) {
            for (int arm = 0; arm < 6 && out.size() < count; arm++) {
                double ang = Math.toRadians(arm * 60.0);
                double dist = ring * spacing;
                out.add(new double[]{
                        centre + Math.cos(ang) * dist,
                        Math.sin(ang) * dist});
            }
            ring++;
        }
        return out;
    }

    private static List<double[]> spiralOffsets(int count, double spacing) {
        List<double[]> out = new ArrayList<>(count);
        double radius = spacing;
        double angle = 0.0;
        double centre = FIRST_ROW_DISTANCE + spacing;
        for (int i = 0; i < count; i++) {
            out.add(new double[]{
                    centre + Math.cos(angle) * radius,
                    Math.sin(angle) * radius});
            angle += spacing / Math.max(radius, 0.5);
            radius += spacing * 0.35;
        }
        return out;
    }

    private static List<double[]> echelonOffsets(int count, double spacing) {
        List<double[]> out = new ArrayList<>(count);
        double firstLat = -((count - 1) / 2.0) * spacing;
        for (int i = 0; i < count; i++) {
            out.add(new double[]{
                    FIRST_ROW_DISTANCE + i * spacing * 0.6,
                    firstLat + i * spacing});
        }
        return out;
    }

    private static List<double[]> doubleLineOffsets(int count, double spacing) {
        List<double[]> out = new ArrayList<>(count);
        int front = (count + 1) / 2;
        int rear = count - front;
        double firstFront = -((front - 1) / 2.0) * spacing;
        for (int i = 0; i < front; i++) {
            out.add(new double[]{FIRST_ROW_DISTANCE, firstFront + i * spacing});
        }
        double firstRear = -((rear - 1) / 2.0) * spacing;
        for (int i = 0; i < rear; i++) {
            out.add(new double[]{
                    FIRST_ROW_DISTANCE + spacing * 1.2,
                    firstRear + i * spacing + spacing * 0.5});
        }
        return out;
    }

    private static List<double[]> chevronOffsets(int count, double spacing) {
        List<double[]> out = new ArrayList<>(count);
        double depth = FIRST_ROW_DISTANCE + (count / 2.0) * spacing * 0.75;
        out.add(new double[]{depth, 0});
        for (int i = 1; i < count; i++) {
            int rank = (i + 1) / 2;
            double side = (i % 2 == 1) ? -1 : 1;
            out.add(new double[]{
                    depth - rank * spacing * 0.75,
                    side * rank * spacing});
        }
        return out;
    }

    private static List<double[]> shieldWallOffsets(int count, double spacing) {
        List<double[]> out = new ArrayList<>(count);

        int front = Math.max(1, (count * 2) / 3);
        int rear = count - front;
        double tight = spacing * 0.8;
        double firstFront = -((front - 1) / 2.0) * tight;
        for (int i = 0; i < front; i++) {
            out.add(new double[]{FIRST_ROW_DISTANCE, firstFront + i * tight});
        }
        double firstRear = -((rear - 1) / 2.0) * spacing * 1.5;
        for (int i = 0; i < rear; i++) {
            out.add(new double[]{
                    FIRST_ROW_DISTANCE + spacing * 2.0,
                    firstRear + i * spacing * 1.5});
        }
        return out;
    }

    private static List<double[]> skirmishOffsets(int count, double spacing) {
        List<double[]> out = new ArrayList<>(count);
        double wide = spacing * 1.8;
        int perRow = Math.max(2, (int) Math.ceil(Math.sqrt(count)));
        for (int i = 0; i < count; i++) {
            int row = i / perRow;
            int col = i % perRow;
            int inRow = Math.min(perRow, count - row * perRow);
            double firstLat = -((inRow - 1) / 2.0) * wide;

            double jf = (((i * 2654435761L) >>> 8) % 1000) / 1000.0 - 0.5;
            double jl = (((i * 40503L + 17) >>> 5) % 1000) / 1000.0 - 0.5;
            out.add(new double[]{
                    FIRST_ROW_DISTANCE + row * wide + jf * spacing * 0.8,
                    firstLat + col * wide + jl * spacing * 0.8});
        }
        return out;
    }

    private static List<double[]> vanguardOffsets(int count, double spacing) {
        List<double[]> out = new ArrayList<>(count);
        int core = Math.max(1, count / 3);
        for (int i = 0; i < core && out.size() < count; i++) {
            int rank = i / 2;
            double side = (i % 2 == 0) ? -0.5 : 0.5;
            out.add(new double[]{
                    FIRST_ROW_DISTANCE + rank * spacing * 0.7,
                    side * spacing});
        }
        int wing = count - out.size();
        for (int i = 0; i < wing; i++) {
            int rank = (i / 2) + 1;
            double side = (i % 2 == 0) ? -1 : 1;
            out.add(new double[]{
                    FIRST_ROW_DISTANCE + spacing * 1.5 + rank * spacing * 1.1,
                    side * (spacing * 1.5 + rank * spacing * 0.6)});
        }
        return out;
    }
}
