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
        return fitted(anchor, Shape.GRID, count, spacing);
    }

    private static List<Location> computeGridRaw(Location anchor, int count, double spacing) {
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
        return fitted(anchor, shape == null ? Shape.GRID : shape, count, spacing);
    }

    private static List<Location> computeRaw(Location anchor, Shape shape, int count, double spacing) {
        if (shape == null || shape == Shape.GRID) return computeGridRaw(anchor, count, spacing);
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

    // ---------------------------------------------------------------------
    // Fitting a formation into the real terrain.
    //
    // The shapes are laid out as if the ground were flat and open; in a
    // corridor, a cave or a cramped base most of those slots land inside a
    // wall or on the far side of one, and the bot sent there never arrives.
    // Every slot is moved to the nearest free cell a bot can actually stand
    // in AND walk to from the anchor (a flood fill over standable cells),
    // so in tight spaces the formation squeezes into whatever room there is.
    // Escorts recompute their slots every tick for every member, so the
    // result is cached for half a second per anchor/shape/size.
    // ---------------------------------------------------------------------

    private static final int FIT_CACHE_TICKS = 10;
    private static final int FIT_MAX_CELLS = 6000;
    private static final java.util.Map<String, Object[]> FIT_CACHE = new java.util.HashMap<>();

    private static List<Location> fitted(Location anchor, Shape shape, int count, double spacing) {
        List<Location> out = new ArrayList<>();
        if (count <= 0 || anchor == null || anchor.getWorld() == null) return out;
        int now = org.bukkit.Bukkit.getCurrentTick();
        String key = anchor.getWorld().getName() + ':' + anchor.getBlockX() + ':' + anchor.getBlockY() + ':'
                + anchor.getBlockZ() + ':' + Math.round(normalizeYaw(anchor.getYaw()) / 15f) + ':' + shape + ':'
                + count + ':' + Math.round(spacing * 10);
        Object[] hit = FIT_CACHE.get(key);
        if (hit != null && now - (int) hit[0] <= FIT_CACHE_TICKS) {
            @SuppressWarnings("unchecked")
            List<Location> cached = (List<Location>) hit[1];
            for (Location l : cached) out.add(l.clone());
            return out;
        }
        if (FIT_CACHE.size() > 256) FIT_CACHE.entrySet().removeIf(e -> now - (int) e.getValue()[0] > FIT_CACHE_TICKS);

        List<Location> raw = computeRaw(anchor, shape, count, spacing);
        List<Location> fit = fitToTerrain(anchor, raw);
        FIT_CACHE.put(key, new Object[]{now, fit});
        for (Location l : fit) out.add(l.clone());
        return out;
    }

    // ---------------------------------------------------------------------
    // Which way is "behind" the leader.
    //
    // Using the leader's head yaw made the whole formation spin every time
    // they looked around: say "stand behind me" and turn round to watch the
    // army, and "behind" is now where you're looking - so the bots run
    // through you to the far side, you turn again, and they end up in front
    // of you most of the time. The heading is set from where the leader is
    // facing when the order is given, and after that only follows the way
    // they actually walk (a couple of blocks of travel roughly the way
    // they're facing). Looking around in place never moves the formation.
    // ---------------------------------------------------------------------

    private static final double HEADING_STEP = 2.0;
    private static final double HEADING_TELEPORT = 32.0;
    private static final double HEADING_MAX_TURN = 100.0;
    private static final int HEADING_STALE_TICKS = 100;

    private static final class Heading {
        float yaw;
        double x, z;
        java.util.UUID world;
        int tick;
    }

    private static final java.util.Map<java.util.UUID, Heading> HEADINGS = new java.util.HashMap<>();

    // Point the formation the way the leader is facing right now - called
    // when the order is given, so "behind me" means behind where I'm looking.
    public static void resetHeading(org.bukkit.entity.Player leader) {
        if (leader == null) return;
        Location l = leader.getLocation();
        Heading h = new Heading();
        h.yaw = normalizeYaw(l.getYaw());
        h.x = l.getX();
        h.z = l.getZ();
        h.world = l.getWorld() == null ? null : l.getWorld().getUID();
        h.tick = org.bukkit.Bukkit.getCurrentTick();
        HEADINGS.put(leader.getUniqueId(), h);
    }

    public static float headingOf(org.bukkit.entity.Player leader) {
        Location l = leader.getLocation();
        int now = org.bukkit.Bukkit.getCurrentTick();
        java.util.UUID world = l.getWorld() == null ? null : l.getWorld().getUID();
        Heading h = HEADINGS.get(leader.getUniqueId());
        if (h == null || now - h.tick > HEADING_STALE_TICKS || !java.util.Objects.equals(h.world, world)) {
            resetHeading(leader);
            return HEADINGS.get(leader.getUniqueId()).yaw;
        }
        if (h.tick == now) return h.yaw;
        h.tick = now;
        double dx = l.getX() - h.x, dz = l.getZ() - h.z;
        double d2 = dx * dx + dz * dz;
        if (d2 > HEADING_TELEPORT * HEADING_TELEPORT) {
            h.yaw = normalizeYaw(l.getYaw());
            h.x = l.getX();
            h.z = l.getZ();
        } else if (d2 >= HEADING_STEP * HEADING_STEP) {
            float travel = normalizeYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            // Walking backwards / strafing past the army keeps the old
            // heading - "behind" is still behind where they're looking.
            if (Math.abs(normalizeYaw(travel - l.getYaw())) <= HEADING_MAX_TURN) h.yaw = travel;
            h.x = l.getX();
            h.z = l.getZ();
        }
        if (HEADINGS.size() > 64) HEADINGS.values().removeIf(o -> now - o.tick > HEADING_STALE_TICKS);
        return h.yaw;
    }

    private static long cellKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static List<Location> fitToTerrain(Location anchor, List<Location> raw) {
        World w = anchor.getWorld();
        int ax = anchor.getBlockX(), az = anchor.getBlockZ();
        int ay = Integer.MIN_VALUE;
        for (int d = 0; d <= 3 && ay == Integer.MIN_VALUE; d++) {
            if (isStandable(w, ax, anchor.getBlockY() - d, az)) ay = anchor.getBlockY() - d;
            else if (d > 0 && isStandable(w, ax, anchor.getBlockY() + d, az)) ay = anchor.getBlockY() + d;
        }
        if (ay == Integer.MIN_VALUE) return raw; // anchor in the air / in a block: leave it be

        double reach = 6;
        for (Location l : raw) reach = Math.max(reach, Math.hypot(l.getX() - anchor.getX(), l.getZ() - anchor.getZ()) + 4);
        double reachSq = Math.min(reach, 40) * Math.min(reach, 40);

        // Every shape is laid out on the anchor's forward side (for an escort
        // that's behind the leader). Cells on the other side - in front of
        // the leader - are only explored once the formation side is used up,
        // so the budget isn't spent on ground nobody should stand on and a
        // slot that has to move never jumps round in front of the leader
        // while there's room behind them.
        double yawRad = Math.toRadians(anchor.getYaw());
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);

        // Flood fill: every standable cell walkable from the anchor.
        java.util.Set<Long> reachable = new java.util.HashSet<>();
        List<int[]> cells = new ArrayList<>();
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<int[]> wrongSide = new java.util.ArrayDeque<>();
        queue.add(new int[]{ax, ay, az});
        reachable.add(cellKey(ax, ay, az));
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        // Nearest cells come first (breadth-first). The budget has to cover
        // the whole half-disc the shape spans, or the back rows of a big army
        // never get a real cell and pile up on the edge of what was searched.
        double fillR = Math.min(reach, 40);
        int cellBudget = Math.min(FIT_MAX_CELLS,
                Math.max(Math.max(96, raw.size() * 10), (int) (Math.PI * fillR * fillR * 0.55)));
        while ((!queue.isEmpty() || !wrongSide.isEmpty()) && cells.size() < cellBudget) {
            int[] c = !queue.isEmpty() ? queue.poll() : wrongSide.poll();
            cells.add(c);
            for (int[] d : dirs) {
                int nx = c[0] + d[0], nz = c[2] + d[1];
                double ddx = nx + 0.5 - anchor.getX(), ddz = nz + 0.5 - anchor.getZ();
                if (ddx * ddx + ddz * ddz > reachSq) continue;
                // Same level, a step up (needs head room), or a drop of up to 3.
                for (int ny : new int[]{c[1], c[1] + 1, c[1] - 1, c[1] - 2, c[1] - 3}) {
                    if (ny > c[1] && !w.getBlockAt(c[0], c[1] + 2, c[2]).isPassable()) continue;
                    if (!isStandable(w, nx, ny, nz)) continue;
                    if (reachable.add(cellKey(nx, ny, nz))) {
                        boolean front = ddx * fx + ddz * fz < -1.5;
                        (front ? wrongSide : queue).add(new int[]{nx, ny, nz});
                    }
                    break;
                }
            }
        }

        java.util.Set<Long> taken = new java.util.HashSet<>();
        taken.add(cellKey(ax, ay, az)); // nobody stands in the leader
        List<Location> out = new ArrayList<>(raw.size());
        for (Location slot : raw) {
            int sx = slot.getBlockX(), sz = slot.getBlockZ(), sy = slot.getBlockY();
            int[] pick = null;
            // Nearest free reachable cell around where the slot wanted to be.
            outer:
            for (int r = 0; r <= 4; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        for (int dy : new int[]{0, -1, 1, -2, 2, -3, 3}) {
                            long k = cellKey(sx + dx, sy + dy, sz + dz);
                            if (reachable.contains(k) && !taken.contains(k)) {
                                pick = new int[]{sx + dx, sy + dy, sz + dz};
                                break outer;
                            }
                        }
                    }
                }
            }
            if (pick == null) {
                // Nothing near it (inside a wall, past the end of a tunnel):
                // the closest free reachable cell anywhere.
                double best = Double.MAX_VALUE;
                for (int[] c : cells) {
                    long k = cellKey(c[0], c[1], c[2]);
                    if (taken.contains(k)) continue;
                    double dx = c[0] + 0.5 - slot.getX(), dz = c[2] + 0.5 - slot.getZ(), dy = c[1] - slot.getY();
                    double dd = dx * dx + dz * dz + dy * dy * 4;
                    // In front of the leader only when there's nothing else.
                    if ((c[0] + 0.5 - anchor.getX()) * fx + (c[2] + 0.5 - anchor.getZ()) * fz < -1.5) dd += 1.0e6;
                    if (dd < best) {
                        best = dd;
                        pick = c;
                    }
                }
            }
            if (pick == null) {
                out.add(slot.clone()); // more bots than room: share the spot
                continue;
            }
            taken.add(cellKey(pick[0], pick[1], pick[2]));
            out.add(new Location(w, pick[0] + 0.5, pick[1], pick[2] + 0.5, slot.getYaw(), slot.getPitch()));
        }
        return out;
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

    // Bots walk to their grid slots (no teleporting).
    public static int arrangeGrid(List<PvPBot> bots, Location anchor, double spacing) {
        List<Location> slots = computeGrid(anchor, bots.size(), spacing);
        int moved = 0;
        for (int i = 0; i < bots.size() && i < slots.size(); i++) {
            PvPBot bot = bots.get(i);
            if (bot == null || !bot.isAlive()) continue;
            bot.orderToFormationSlot(slots.get(i));
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
