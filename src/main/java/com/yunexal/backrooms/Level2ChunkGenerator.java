package com.yunexal.backrooms;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Procedural Backrooms Level 2 ("Abandoned Utility Halls" / "Pipe Dreams") chunk generator:
 * - Tight claustrophobic utility tunnels (wallHeight=3) with chalky concrete (ANDESITE)
 *   and dirty industrial brick (BRICKS)
 * - Heavy horizontal copper/iron pipes along corridor sides & ceiling (leaving narrow passages)
 * - Superheated Boiler Zones (>43C) with hot cut-copper pipes and floor steam grates
 * - Machinery Bays with non-functional humming pumps/boilers & rare M.E.G. Survivor Office outpost
 */
public final class Level2ChunkGenerator extends ChunkGenerator {
    public static final int CELL_SIZE = 4;
    public static final int REGION_CELLS = 8;
    private static final int CACHE_LIMIT = 1024;

    public enum Level2ZoneType {
        PIPE_CORRIDOR,
        BOILER_HOT_ZONE,
        MACHINERY_BAY,
        SURVIVOR_OFFICE
    }

    private enum LightState {
        DIM_WORKING,
        FLICKERING,
        DEAD,
        NONE
    }

    private static final class RegionPlan {
        final Level2ZoneType zoneType;
        final boolean[][] westWall = new boolean[REGION_CELLS][REGION_CELLS];
        final boolean[][] northWall = new boolean[REGION_CELLS][REGION_CELLS];

        RegionPlan(Level2ZoneType zoneType) {
            this.zoneType = zoneType;
        }
    }

    private final YunexalBackroomsPlugin plugin;
    private final BiomeProvider biomeProvider = new BiomeProvider() {
        @Override
        public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
            return Biome.THE_VOID;
        }

        @Override
        public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
            return List.of(Biome.THE_VOID);
        }
    };

    private final Map<Long, RegionPlan> regionCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, RegionPlan> eldest) {
                    return size() > CACHE_LIMIT;
                }
            }
    );

    public Level2ChunkGenerator(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    private BackroomsConfig cfg() {
        return plugin.getBackroomsConfig();
    }

    public void clearCache() {
        regionCache.clear();
    }

    private long effectiveSeed(WorldInfo wi) {
        long s = cfg().getLevel2Seed();
        return s != 0L ? s : wi.getSeed();
    }

    private long effectiveSeed(World w) {
        long s = cfg().getLevel2Seed();
        return s != 0L ? s : w.getSeed();
    }

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random unused,
                              int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        BackroomsConfig config = cfg();
        long seed = effectiveSeed(worldInfo);
        int floorY = config.getLevel2FloorY();
        int wallHeight = config.getLevel2WallHeight();
        int ceilingY = floorY + wallHeight + 1;
        int worldBaseX = chunkX << 4;
        int worldBaseZ = chunkZ << 4;

        for (int lx = 0; lx < 16; lx++) {
            int wx = worldBaseX + lx;
            int cx = Math.floorDiv(wx, CELL_SIZE);
            int inCellX = Math.floorMod(wx, CELL_SIZE);

            for (int lz = 0; lz < 16; lz++) {
                int wz = worldBaseZ + lz;
                int cz = Math.floorDiv(wz, CELL_SIZE);
                int inCellZ = Math.floorMod(wz, CELL_SIZE);

                // Bedrock & soiled concrete slab
                chunkData.setBlock(lx, floorY - 3, lz, Material.BEDROCK);
                chunkData.setBlock(lx, floorY - 2, lz, Material.BEDROCK);
                chunkData.setBlock(lx, floorY - 1, lz, Material.GRAY_CONCRETE);
                chunkData.setBlock(lx, ceilingY + 1, lz, Material.BEDROCK);
                chunkData.setBlock(lx, ceilingY + 2, lz, Material.BEDROCK);

                Level2ZoneType zone = getZoneType(seed, cx, cz);
                int rlx = Math.floorMod(cx, REGION_CELLS);
                int rlz = Math.floorMod(cz, REGION_CELLS);
                boolean solidWall = isWallBlock(seed, cx, cz, inCellX, inCellZ, zone);

                // 1. Dusty concrete & dark utility tunnel floor (steam grates in Boiler Hot Zones)
                Material floorMat;
                if (zone == Level2ZoneType.BOILER_HOT_ZONE && inCellX == 1 && inCellZ == 1 && !solidWall) {
                    chunkData.setBlock(lx, floorY - 1, lz, Material.MAGMA_BLOCK);
                    floorMat = Material.IRON_TRAPDOOR;
                } else if (zone == Level2ZoneType.SURVIVOR_OFFICE && rlx >= 2 && rlx <= 4 && rlz >= 2 && rlz <= 4) {
                    floorMat = Material.STONE_BRICKS;
                } else {
                    double fr = toUnitDouble(mix64(seed ^ 0x3A2B1C0D4E5F6789L, wx, wz));
                    floorMat = fr < 0.25 ? Material.POLISHED_DEEPSLATE : (fr < 0.55 ? Material.ANDESITE : Material.GRAY_CONCRETE);
                }
                chunkData.setBlock(lx, floorY, lz, floorMat);

                // 2. Narrow dirty concrete/brick walls & flanking industrial pipe runs
                if (solidWall) {
                    boolean corner = (inCellX == 0 && inCellZ == 0);
                    buildWallColumn(chunkData, lx, lz, floorY, ceilingY, seed, wx, wz, cx, cz, inCellX, inCellZ, corner, zone);
                } else if (zone != Level2ZoneType.SURVIVOR_OFFICE) {
                    placeUtilityPipes(chunkData, lx, lz, floorY, ceilingY, seed, cx, cz, inCellX, inCellZ, zone);
                }

                // 3. Low ceiling with recessed industrial lighting & ceiling conduits
                if (inCellX == 2 && inCellZ == 2 && !solidWall) {
                    LightState ls = getLightState(seed, cx, cz, zone);
                    Material ceilMat = switch (ls) {
                        case DIM_WORKING -> Material.OCHRE_FROGLIGHT;
                        case FLICKERING -> Material.SEA_LANTERN;
                        case DEAD -> Material.POLISHED_ANDESITE;
                        case NONE -> Material.ANDESITE;
                    };
                    chunkData.setBlock(lx, ceilingY, lz, ceilMat);
                } else {
                    chunkData.setBlock(lx, ceilingY, lz,
                            ((wx + wz) & 3) == 0 ? Material.POLISHED_DEEPSLATE : Material.ANDESITE);
                }
            }
        }
    }

    private void buildWallColumn(ChunkData chunkData, int lx, int lz, int floorY, int ceilingY,
                                 long seed, int wx, int wz, int cx, int cz,
                                 int inCellX, int inCellZ, boolean corner, Level2ZoneType zone) {
        int rlx = Math.floorMod(cx, REGION_CELLS);
        int rlz = Math.floorMod(cz, REGION_CELLS);

        // Open doorway into Survivor Office enclave
        if (zone == Level2ZoneType.SURVIVOR_OFFICE
                && ((inCellX == 0 && (rlx == 2 || rlx == 5) && rlz == 3)
                || (inCellZ == 0 && (rlz == 2 || rlz == 5) && rlx == 3))) {
            chunkData.setBlock(lx, ceilingY - 1, lz, Material.POLISHED_DEEPSLATE);
            return;
        }

        // Rare Level 3 locked utility doorway easter egg / teaser
        if (!corner && zone != Level2ZoneType.SURVIVOR_OFFICE) {
            boolean northWall = (inCellZ == 0);
            int pos = northWall ? inCellX : inCellZ;
            if (isLevel3TeaserPanel(seed, cx, cz, northWall, zone) && pos == 2) {
                for (int y = floorY + 1; y < ceilingY; y++) {
                    chunkData.setBlock(lx, y, lz, y == floorY + 2 ? Material.WAXED_COPPER_BULB : Material.IRON_BLOCK);
                }
                return;
            }
        }

        boolean dirtyBrick = toUnitDouble(mix64(seed ^ 0x7711993355224466L, wx, wz)) < 0.42;
        for (int y = floorY + 1; y < ceilingY; y++) {
            Material mat;
            if (corner) {
                mat = Material.POLISHED_DEEPSLATE;
            } else if (y == floorY + 1) {
                mat = dirtyBrick ? Material.BRICKS : Material.POLISHED_DEEPSLATE;
            } else {
                mat = dirtyBrick ? Material.BRICKS : Material.ANDESITE;
            }
            chunkData.setBlock(lx, y, lz, mat);
        }
    }

    private void placeUtilityPipes(ChunkData chunkData, int lx, int lz, int floorY, int ceilingY,
                                   long seed, int cx, int cz, int inCellX, int inCellZ, Level2ZoneType zone) {
        // Side pipes narrowing corridor passages when hugging a solid North or West wall
        boolean westWall = hasWestWall(seed, cx, cz);
        boolean northWall = hasNorthWall(seed, cx, cz);
        boolean eastWall = hasWestWall(seed, cx + 1, cz);

        Material hotPipe = (zone == Level2ZoneType.BOILER_HOT_ZONE) ? Material.CUT_COPPER : Material.COPPER_BLOCK;

        // Horizontal low & high wall-mounted pipes along edges (never blocking middle 2 blocks 1..2 fully)
        if (westWall && inCellX == 1 && inCellZ != 0) {
            double r = toUnitDouble(mix64(seed ^ 0x1111222233334444L, cx, cz));
            if (r < 0.65) {
                chunkData.setBlock(lx, floorY + 2, lz, hotPipe);
                if (r < 0.35) {
                    chunkData.setBlock(lx, ceilingY - 1, lz, Material.IRON_BLOCK);
                }
            }
        } else if (eastWall && inCellX == 3 && inCellZ != 0) {
            double r = toUnitDouble(mix64(seed ^ 0x5555666677778888L, cx, cz));
            if (r < 0.58) {
                chunkData.setBlock(lx, ceilingY - 1, lz, hotPipe);
            }
        }

        // Overhead pipes running along North edge (y = ceilingY - 1)
        if (northWall && inCellZ == 1 && inCellX != 0) {
            chunkData.setBlock(lx, ceilingY - 1, lz, hotPipe);
        }

        // Center ceiling pipe bundle in select cells
        if (inCellX == 3 && (inCellZ == 1 || inCellZ == 2)) {
            if (toUnitDouble(mix64(seed ^ 0x9999AAAABBBBCCCCL, cx, cz)) < 0.35) {
                chunkData.setBlock(lx, ceilingY - 1, lz, Material.IRON_BLOCK);
            }
        }
    }

    public boolean isLevel3TeaserPanel(long seed, int cx, int cz, boolean northWall, Level2ZoneType zone) {
        if (zone == Level2ZoneType.SURVIVOR_OFFICE || (Math.abs(cx) <= 2 && Math.abs(cz) <= 2)) {
            return false;
        }
        return toUnitDouble(mix64(seed ^ 0x3333DEAD00033333L, cx * 2 + (northWall ? 1 : 0), cz)) >= 0.992;
    }

    public boolean isLevel3TeaserBlock(World w, int wx, int wy, int wz) {
        BackroomsConfig cfg = cfg();
        if (wy < cfg.getLevel2FloorY() + 1 || wy >= cfg.getLevel2CeilingY()) {
            return false;
        }
        int cx = Math.floorDiv(wx, CELL_SIZE);
        int cz = Math.floorDiv(wz, CELL_SIZE);
        int ix = Math.floorMod(wx, CELL_SIZE);
        int iz = Math.floorMod(wz, CELL_SIZE);
        if (ix > 0 && iz > 0) return false;
        boolean northWall = (iz == 0);
        int pos = northWall ? ix : iz;
        if (pos != 2) return false;
        long seed = effectiveSeed(w);
        Level2ZoneType zone = getZoneType(seed, cx, cz);
        return isLevel3TeaserPanel(seed, cx, cz, northWall, zone)
                && (northWall ? hasNorthWall(seed, cx, cz) : hasWestWall(seed, cx, cz));
    }

    private boolean isWallBlock(long seed, int cx, int cz, int inCellX, int inCellZ, Level2ZoneType zone) {
        if (inCellX > 0 && inCellZ > 0) {
            return false;
        }
        int rlx = Math.floorMod(cx, REGION_CELLS);
        int rlz = Math.floorMod(cz, REGION_CELLS);
        if (inCellX == 0 && inCellZ == 0) {
            if (zone == Level2ZoneType.SURVIVOR_OFFICE && rlx >= 3 && rlx <= 4 && rlz >= 3 && rlz <= 4) {
                return false;
            }
            return true;
        }
        if (inCellX == 0) {
            return hasWestWall(seed, cx, cz);
        }
        return hasNorthWall(seed, cx, cz);
    }

    private boolean hasWestWall(long seed, int cx, int cz) {
        return getRegionPlan(seed, Math.floorDiv(cx, REGION_CELLS), Math.floorDiv(cz, REGION_CELLS))
                .westWall[Math.floorMod(cx, REGION_CELLS)][Math.floorMod(cz, REGION_CELLS)];
    }

    private boolean hasNorthWall(long seed, int cx, int cz) {
        return getRegionPlan(seed, Math.floorDiv(cx, REGION_CELLS), Math.floorDiv(cz, REGION_CELLS))
                .northWall[Math.floorMod(cx, REGION_CELLS)][Math.floorMod(cz, REGION_CELLS)];
    }

    public Level2ZoneType getZoneAtRegion(long seed, int rx, int rz) {
        return getRegionPlan(seed, rx, rz).zoneType;
    }

    public Level2ZoneType getZoneAtBlock(World world, int wx, int wz) {
        long seed = effectiveSeed(world);
        return getZoneType(seed, Math.floorDiv(wx, CELL_SIZE), Math.floorDiv(wz, CELL_SIZE));
    }

    private Level2ZoneType getZoneType(long seed, int cx, int cz) {
        return getRegionPlan(seed, Math.floorDiv(cx, REGION_CELLS), Math.floorDiv(cz, REGION_CELLS)).zoneType;
    }

    private RegionPlan getRegionPlan(long seed, int rx, int rz) {
        long key = (((long) rx) << 32) ^ (rz & 0xFFFFFFFFL) ^ (seed * 0x9E3779B97F4A7C15L);
        RegionPlan cached = regionCache.get(key);
        if (cached != null) return cached;
        RegionPlan built = buildRegionPlan(seed, rx, rz);
        regionCache.put(key, built);
        return built;
    }

    private RegionPlan buildRegionPlan(long seed, int rx, int rz) {
        Level2ZoneType zone = pickZoneType(seed, rx, rz);
        RegionPlan plan = new RegionPlan(zone);
        for (int x = 0; x < REGION_CELLS; x++) {
            for (int z = 0; z < REGION_CELLS; z++) {
                plan.westWall[x][z] = true;
                plan.northWall[x][z] = true;
            }
        }

        Random rng = new Random(mix64(seed ^ 0x3C6EF372FE94F82AL, rx, rz));
        boolean[][] visited = new boolean[REGION_CELLS][REGION_CELLS];
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        int startX = rng.nextInt(REGION_CELLS);
        int startZ = rng.nextInt(REGION_CELLS);
        visited[startX][startZ] = true;
        stack.push(new int[]{startX, startZ});

        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        int[] order = {0, 1, 2, 3};

        while (!stack.isEmpty()) {
            int[] cur = stack.peek();
            int cx = cur[0];
            int cz = cur[1];
            for (int i = 3; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }
            boolean carved = false;
            for (int d : order) {
                int nx = cx + dx[d];
                int nz = cz + dz[d];
                if (nx >= 0 && nx < REGION_CELLS && nz >= 0 && nz < REGION_CELLS && !visited[nx][nz]) {
                    visited[nx][nz] = true;
                    if (nx == cx + 1) plan.westWall[nx][nz] = false;
                    else if (cx == nx + 1) plan.westWall[cx][cz] = false;
                    else if (nz == cz + 1) plan.northWall[nx][nz] = false;
                    else plan.northWall[cx][cz] = false;
                    stack.push(new int[]{nx, nz});
                    carved = true;
                    break;
                }
            }
            if (!carved) stack.pop();
        }

        // Keep Level 2 tunnels tight & linear (low braiding, except Machinery Bays & Office)
        double braid = switch (zone) {
            case PIPE_CORRIDOR -> 0.14;
            case BOILER_HOT_ZONE -> 0.18;
            case MACHINERY_BAY -> 0.45;
            case SURVIVOR_OFFICE -> 0.40;
        };
        for (int x = 1; x < REGION_CELLS; x++) {
            for (int z = 0; z < REGION_CELLS; z++) {
                if (plan.westWall[x][z] && rng.nextDouble() < braid) plan.westWall[x][z] = false;
            }
        }
        for (int x = 0; x < REGION_CELLS; x++) {
            for (int z = 1; z < REGION_CELLS; z++) {
                if (plan.northWall[x][z] && rng.nextDouble() < braid) plan.northWall[x][z] = false;
            }
        }

        if (zone == Level2ZoneType.MACHINERY_BAY) {
            for (int x = 3; x <= 4; x++) {
                for (int z = 2; z <= 4; z++) plan.westWall[x][z] = false;
            }
            for (int x = 2; x <= 4; x++) {
                for (int z = 3; z <= 4; z++) plan.northWall[x][z] = false;
            }
        }

        if (zone == Level2ZoneType.SURVIVOR_OFFICE) {
            for (int x = 3; x <= 4; x++) {
                for (int z = 2; z <= 4; z++) plan.westWall[x][z] = false;
            }
            for (int x = 2; x <= 4; x++) {
                for (int z = 3; z <= 4; z++) plan.northWall[x][z] = false;
            }
            for (int z = 2; z <= 4; z++) {
                plan.westWall[2][z] = (z != 3);
                plan.westWall[5][z] = (z != 3);
            }
            for (int x = 2; x <= 4; x++) {
                plan.northWall[x][2] = (x != 3);
                plan.northWall[x][5] = (x != 3);
            }
        }

        if (rx == 0 && rz == 0) {
            // Long signature entry pipe tunnel heading East toward Survivor Office (1, 0)
            for (int x = 1; x < REGION_CELLS; x++) {
                plan.westWall[x][3] = false;
            }
        }

        boolean[] westOpen = borderOpenings(seed ^ 0x1B873593CC9E2D51L, rx - 1, rz);
        for (int z = 0; z < REGION_CELLS; z++) plan.westWall[0][z] = !westOpen[z];
        if (rx == 1 && rz == 0) plan.westWall[0][3] = false;

        boolean[] northOpen = borderOpenings(seed ^ 0x85EBCA6B12345679L, rx, rz - 1);
        for (int x = 0; x < REGION_CELLS; x++) plan.northWall[x][0] = !northOpen[x];

        return plan;
    }

    private boolean[] borderOpenings(long salt, int rx, int rz) {
        boolean[] open = new boolean[REGION_CELLS];
        Random r = new Random(mix64(salt, rx, rz));
        int count = 2 + r.nextInt(2);
        for (int i = 0; i < count; i++) open[r.nextInt(REGION_CELLS)] = true;
        return open;
    }

    private Level2ZoneType pickZoneType(long seed, int rx, int rz) {
        if (rx == 0 && rz == 0) return Level2ZoneType.PIPE_CORRIDOR;
        if (rx == 1 && rz == 0) return Level2ZoneType.SURVIVOR_OFFICE;
        double roll = toUnitDouble(mix64(seed ^ 0xC123456789ABCDEFL, rx, rz));
        if (roll < 0.008) return Level2ZoneType.SURVIVOR_OFFICE;
        if (roll < 0.24) return Level2ZoneType.BOILER_HOT_ZONE;
        if (roll < 0.42) return Level2ZoneType.MACHINERY_BAY;
        return Level2ZoneType.PIPE_CORRIDOR;
    }

    private LightState getLightState(long seed, int cx, int cz, Level2ZoneType zone) {
        if (zone == Level2ZoneType.SURVIVOR_OFFICE) return LightState.DIM_WORKING;
        double roll = toUnitDouble(mix64(seed ^ 0x94D049BB133111EBL, cx, cz));
        if (roll < 0.14) return LightState.FLICKERING;
        if (roll < 0.34) return LightState.DEAD;
        if (roll < 0.48) return LightState.NONE;
        return LightState.DIM_WORKING;
    }

    public Location getArrivalLocation(World world) {
        int floorY = cfg().getLevel2FloorY();
        return new Location(world, 6.5, floorY + 1.0, 14.5, -90.0f, 0.0f);
    }

    public boolean isInProtectedOfficeCore(World world, int wx, int wz) {
        if (getZoneAtBlock(world, wx, wz) != Level2ZoneType.SURVIVOR_OFFICE) return false;
        int rlx = Math.floorMod(Math.floorDiv(wx, CELL_SIZE), REGION_CELLS);
        int rlz = Math.floorMod(Math.floorDiv(wz, CELL_SIZE), REGION_CELLS);
        return rlx >= 2 && rlx <= 4 && rlz >= 2 && rlz <= 4;
    }

    @Override
    public @NotNull BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) { return biomeProvider; }

    @Override
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return List.of(new Level2Populator(plugin));
    }

    @Override
    public boolean isParallelCapable() { return true; }

    @Override
    public boolean shouldGenerateNoise() { return false; }

    @Override
    public boolean shouldGenerateSurface() { return false; }

    @Override
    public boolean shouldGenerateBedrock() { return false; }

    @Override
    public boolean shouldGenerateCaves() { return false; }

    @Override
    public boolean shouldGenerateDecorations() { return false; }

    @Override
    public boolean shouldGenerateMobs() { return false; }

    @Override
    public boolean shouldGenerateStructures() { return false; }

    private static long mix64(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xBF58476D1CE4E5B9L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static double toUnitDouble(long h) {
        return ((h >>> 11) * 0x1.0p-53);
    }
}
