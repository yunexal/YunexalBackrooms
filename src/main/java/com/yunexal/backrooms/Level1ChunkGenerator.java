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
 * Procedural Backrooms Level 1 ("Habitable Zone") chunk generator:
 * - Vast poured-concrete warehouses, heavy 2x2 hazard pillars, overhead pipe conduits,
 *   non-flowing condensation puddles, storage bays, and curated humanity outposts
 *   (M.E.G. Base Alpha, B.N.T.G. Trader's Keep, and Tom's Diner).
 */
public final class Level1ChunkGenerator extends ChunkGenerator {
    public static final int CELL_SIZE = 4;
    public static final int REGION_CELLS = 8;
    private static final int CACHE_LIMIT = 1024;

    public enum Level1ZoneType {
        WAREHOUSE_HALL,
        STORAGE_CORRIDOR,
        LOADING_BAY,
        MEG_BASE_ALPHA,
        BNTG_TRADER_KEEP,
        TOMS_DINER
    }

    private enum LightState {
        WORKING,
        FLICKERING,
        DEAD,
        NONE
    }

    private static final class RegionPlan {
        final Level1ZoneType zoneType;
        final boolean[][] westWall = new boolean[REGION_CELLS][REGION_CELLS];
        final boolean[][] northWall = new boolean[REGION_CELLS][REGION_CELLS];

        RegionPlan(Level1ZoneType zoneType) {
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

    public Level1ChunkGenerator(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    private BackroomsConfig cfg() {
        return plugin.getBackroomsConfig();
    }

    public void clearCache() {
        regionCache.clear();
    }

    private long effectiveSeed(WorldInfo worldInfo) {
        long s = cfg().getLevel1Seed();
        return s != 0L ? s : worldInfo.getSeed();
    }

    private long effectiveSeed(World world) {
        long s = cfg().getLevel1Seed();
        return s != 0L ? s : world.getSeed();
    }

    public static boolean isOutpostZone(Level1ZoneType zone) {
        return zone == Level1ZoneType.MEG_BASE_ALPHA
                || zone == Level1ZoneType.BNTG_TRADER_KEEP
                || zone == Level1ZoneType.TOMS_DINER;
    }

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random unused,
                              int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        BackroomsConfig config = cfg();
        long seed = effectiveSeed(worldInfo);
        int floorY = config.getLevel1FloorY();
        int wallHeight = config.getLevel1WallHeight();
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

                // Subfloor slab & roof bedrock seal
                chunkData.setBlock(lx, floorY - 3, lz, Material.BEDROCK);
                chunkData.setBlock(lx, floorY - 2, lz, Material.BEDROCK);
                chunkData.setBlock(lx, floorY - 1, lz, Material.GRAY_CONCRETE);
                chunkData.setBlock(lx, ceilingY + 1, lz, Material.BEDROCK);
                chunkData.setBlock(lx, ceilingY + 2, lz, Material.BEDROCK);

                Level1ZoneType zone = getZoneType(seed, cx, cz);
                int rlx = Math.floorMod(cx, REGION_CELLS);
                int rlz = Math.floorMod(cz, REGION_CELLS);
                boolean solidWall = isWallBlock(seed, cx, cz, inCellX, inCellZ, zone);
                boolean puddle = !solidWall && isPuddleBlock(seed, cx, cz, wx, wz, inCellX, inCellZ, zone);

                // 1. Concrete warehouse floor (or contained shallow condensation puddle in interior 2x2 cell core)
                Material floorMat;
                if (puddle) {
                    floorMat = Material.WATER;
                } else if (zone == Level1ZoneType.TOMS_DINER && rlx >= 2 && rlx <= 5 && rlz >= 2 && rlz <= 5) {
                    floorMat = ((wx + wz) & 1) == 0 ? Material.POLISHED_DIORITE : Material.DEEPSLATE_TILES;
                } else if (isOutpostZone(zone) && rlx >= 2 && rlx <= 5 && rlz >= 2 && rlz <= 5) {
                    floorMat = Material.STONE_BRICKS;
                } else {
                    double fRoll = toUnitDouble(mix64(seed ^ 0x2938475610293847L, wx, wz));
                    floorMat = (fRoll < 0.14) ? Material.STONE : (fRoll < 0.24 ? Material.LIGHT_GRAY_CONCRETE : Material.GRAY_CONCRETE);
                }
                chunkData.setBlock(lx, floorY, lz, floorMat);

                // 2. Concrete warehouse walls, heavy 2x2 columns, iron-bar storage cages & stepped loading curbs
                if (solidWall) {
                    boolean corner = (inCellX == 0 && inCellZ == 0);
                    buildWallColumn(chunkData, lx, lz, floorY, ceilingY, seed, wx, wz, cx, cz, inCellX, inCellZ, corner, zone);
                } else if (zone == Level1ZoneType.LOADING_BAY && rlx == 2 && rlz >= 2 && rlz <= 5 && inCellX == 1) {
                    // Stepped concrete loading bay platform curb
                    chunkData.setBlock(lx, floorY + 1, lz, Material.SMOOTH_STONE_SLAB);
                }

                // 3. Industrial ceiling + exposed overhead piping along structural ribs
                if (!solidWall && (inCellX == 0 || inCellZ == 0) && zone != Level1ZoneType.TOMS_DINER) {
                    long pipeSeed = mix64(seed ^ 0x517777123456789AL, inCellX == 0 ? cx : -999999, inCellZ == 0 ? cz : -999999);
                    if (toUnitDouble(pipeSeed) < 0.24) {
                        chunkData.setBlock(lx, ceilingY - 1, lz,
                                (inCellX == 0) ? Material.POLISHED_DEEPSLATE : Material.WAXED_EXPOSED_COPPER);
                    }
                }

                if (inCellX == 2 && inCellZ == 2 && !solidWall) {
                    LightState light = getLightState(seed, cx, cz, zone);
                    Material ceilMat = switch (light) {
                        case WORKING -> Material.OCHRE_FROGLIGHT;
                        case FLICKERING -> Material.SEA_LANTERN;
                        case DEAD -> Material.POLISHED_ANDESITE;
                        case NONE -> Material.STONE;
                    };
                    chunkData.setBlock(lx, ceilingY, lz, ceilMat);
                } else {
                    chunkData.setBlock(lx, ceilingY, lz,
                            ((wx + wz) & 7) == 0 ? Material.POLISHED_DEEPSLATE : Material.STONE);
                }
            }
        }
    }

    private void buildWallColumn(ChunkData chunkData, int lx, int lz, int floorY, int ceilingY,
                                 long seed, int wx, int wz, int cx, int cz,
                                 int inCellX, int inCellZ, boolean corner, Level1ZoneType zone) {
        int rlx = Math.floorMod(cx, REGION_CELLS);
        int rlz = Math.floorMod(cz, REGION_CELLS);
        boolean densePillar = (!isOutpostZone(zone)) && inCellX <= 1 && inCellZ <= 1
                && toUnitDouble(mix64(seed ^ 0xA24BAED4963EE407L, cx, cz)) > 0.72;

        if (densePillar) {
            for (int y = floorY + 1; y < ceilingY; y++) {
                chunkData.setBlock(lx, y, lz, Material.DEEPSLATE_BRICKS);
            }
            return;
        }

        // Steel mesh storage cages inside Storage Corridors & Trader Keep bays
        if (!corner && (zone == Level1ZoneType.STORAGE_CORRIDOR || zone == Level1ZoneType.LOADING_BAY)) {
            long pan = mix64(seed ^ 0x9988776655443322L, cx * 2 + (inCellX == 0 ? 0 : 1), cz);
            double roll = toUnitDouble(pan);
            int pos = (inCellX == 0) ? inCellZ : inCellX;
            if (roll < 0.16 && (pos == 2 || pos == 3)) {
                chunkData.setBlock(lx, floorY + 1, lz, Material.POLISHED_DEEPSLATE);
                chunkData.setBlock(lx, floorY + 2, lz, Material.IRON_BARS);
                chunkData.setBlock(lx, floorY + 3, lz, Material.IRON_BARS);
                chunkData.setBlock(lx, ceilingY - 1, lz, Material.GRAY_TERRACOTTA);
                return;
            }
        }

        // Open doorway entrances (rlx/rlz middle cells) cleanly punched through outpost walls
        if (isOutpostZone(zone) && ((inCellX == 0 && (rlx == 2 || rlx == 6) && rlz == 3)
                || (inCellZ == 0 && (rlz == 2 || rlz == 6) && rlx == 3))) {
            chunkData.setBlock(lx, ceilingY - 1, lz, Material.POLISHED_DEEPSLATE);
            return;
        }

        long colHash = mix64(seed ^ 0x517CC1B727220A95L, wx, wz);
        double detailRoll = toUnitDouble(colHash);

        for (int y = floorY + 1; y < ceilingY; y++) {
            Material mat;
            if (corner) {
                mat = Material.DEEPSLATE_BRICKS;
            } else if (zone == Level1ZoneType.TOMS_DINER && rlx >= 2 && rlx <= 5 && rlz >= 2 && rlz <= 5) {
                mat = (y == floorY + 1) ? Material.STONE_BRICKS : Material.WHITE_TERRACOTTA;
            } else if (y == floorY + 1) {
                mat = Material.POLISHED_DEEPSLATE;
            } else if (y == ceilingY - 1 && detailRoll > 0.94) {
                mat = Material.IRON_BLOCK;
            } else {
                mat = Material.GRAY_TERRACOTTA;
            }
            chunkData.setBlock(lx, y, lz, mat);
        }
    }

    private boolean isPuddleBlock(long seed, int cx, int cz, int wx, int wz, int inCellX, int inCellZ, Level1ZoneType zone) {
        if (isOutpostZone(zone)) {
            return false;
        }
        // Keep exact arrival junction clear of puddles
        if (Math.abs(cx) <= 4 && Math.abs(cz) <= 4 && cx >= 0 && cz >= 0) {
            return false;
        }
        // Only inside inner 2x2 of a 4x4 cell so water sits sealed inside concrete basins without ever overflowing
        if (inCellX != 1 && inCellX != 2) {
            return false;
        }
        if (inCellZ != 1 && inCellZ != 2) {
            return false;
        }
        double cellRoll = toUnitDouble(mix64(seed ^ 0x4C59102938475612L, cx, cz));
        if (cellRoll >= 0.085) {
            return false;
        }
        // Either single 1x1 puddle or small 1x2 / 2x2 puddle
        return toUnitDouble(mix64(seed ^ 0x8F12349876543210L, wx, wz)) < 0.72;
    }

    private boolean isWallBlock(long seed, int cx, int cz, int inCellX, int inCellZ, Level1ZoneType zone) {
        if (!isOutpostZone(zone) && inCellX <= 1 && inCellZ <= 1
                && toUnitDouble(mix64(seed ^ 0xA24BAED4963EE407L, cx, cz)) > 0.72) {
            return true;
        }
        if (inCellX > 0 && inCellZ > 0) {
            return false;
        }
        int rlx = Math.floorMod(cx, REGION_CELLS);
        int rlz = Math.floorMod(cz, REGION_CELLS);
        if (inCellX == 0 && inCellZ == 0) {
            if (isOutpostZone(zone) && rlx >= 3 && rlx <= 5 && rlz >= 3 && rlz <= 5) {
                return false;
            }
            if (zone == Level1ZoneType.WAREHOUSE_HALL) {
                return toUnitDouble(mix64(seed ^ 0xA24BAED4963EE407L, cx, cz)) > 0.25;
            }
            boolean w1 = hasWestWall(seed, cx, cz);
            boolean w2 = hasWestWall(seed, cx, cz - 1);
            boolean n1 = hasNorthWall(seed, cx, cz);
            boolean n2 = hasNorthWall(seed, cx - 1, cz);
            return w1 || w2 || n1 || n2 || toUnitDouble(mix64(seed, cx, cz)) < 0.55;
        }
        if (inCellX == 0) {
            return hasWestWall(seed, cx, cz);
        }
        return hasNorthWall(seed, cx, cz);
    }

    private boolean hasWestWall(long seed, int cx, int cz) {
        int rx = Math.floorDiv(cx, REGION_CELLS);
        int rz = Math.floorDiv(cz, REGION_CELLS);
        return getRegionPlan(seed, rx, rz).westWall[Math.floorMod(cx, REGION_CELLS)][Math.floorMod(cz, REGION_CELLS)];
    }

    private boolean hasNorthWall(long seed, int cx, int cz) {
        int rx = Math.floorDiv(cx, REGION_CELLS);
        int rz = Math.floorDiv(cz, REGION_CELLS);
        return getRegionPlan(seed, rx, rz).northWall[Math.floorMod(cx, REGION_CELLS)][Math.floorMod(cz, REGION_CELLS)];
    }

    public Level1ZoneType getZoneAtRegion(long seed, int rx, int rz) {
        return getRegionPlan(seed, rx, rz).zoneType;
    }

    public Level1ZoneType getZoneAtBlock(World world, int wx, int wz) {
        long seed = effectiveSeed(world);
        int cx = Math.floorDiv(wx, CELL_SIZE);
        int cz = Math.floorDiv(wz, CELL_SIZE);
        return getZoneType(seed, cx, cz);
    }

    private Level1ZoneType getZoneType(long seed, int cx, int cz) {
        return getRegionPlan(seed, Math.floorDiv(cx, REGION_CELLS), Math.floorDiv(cz, REGION_CELLS)).zoneType;
    }

    private RegionPlan getRegionPlan(long seed, int rx, int rz) {
        long key = (((long) rx) << 32) ^ (rz & 0xFFFFFFFFL) ^ (seed * 0x9E3779B97F4A7C15L);
        RegionPlan cached = regionCache.get(key);
        if (cached != null) {
            return cached;
        }
        RegionPlan built = buildRegionPlan(seed, rx, rz);
        regionCache.put(key, built);
        return built;
    }

    private RegionPlan buildRegionPlan(long seed, int rx, int rz) {
        Level1ZoneType zone = pickZoneType(seed, rx, rz);
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
            if (!carved) {
                stack.pop();
            }
        }

        double braidChance = switch (zone) {
            case WAREHOUSE_HALL -> 0.88;
            case LOADING_BAY -> 0.72;
            case STORAGE_CORRIDOR -> 0.35;
            case MEG_BASE_ALPHA, BNTG_TRADER_KEEP, TOMS_DINER -> 0.65;
        };

        for (int x = 1; x < REGION_CELLS; x++) {
            for (int z = 0; z < REGION_CELLS; z++) {
                if (plan.westWall[x][z] && rng.nextDouble() < braidChance) {
                    plan.westWall[x][z] = false;
                }
            }
        }
        for (int x = 0; x < REGION_CELLS; x++) {
            for (int z = 1; z < REGION_CELLS; z++) {
                if (plan.northWall[x][z] && rng.nextDouble() < braidChance) {
                    plan.northWall[x][z] = false;
                }
            }
        }

        // Enclosed outpost compound core (cells 2..5 x 2..5) with open doorway bays at cell index 3
        if (isOutpostZone(zone)) {
            for (int x = 3; x <= 5; x++) {
                for (int z = 2; z <= 5; z++) {
                    plan.westWall[x][z] = false;
                }
            }
            for (int x = 2; x <= 5; x++) {
                for (int z = 3; z <= 5; z++) {
                    plan.northWall[x][z] = false;
                }
            }
            for (int z = 2; z <= 5; z++) {
                plan.westWall[2][z] = (z != 3);
                plan.westWall[6][z] = (z != 3);
            }
            for (int x = 2; x <= 5; x++) {
                plan.northWall[x][2] = (x != 3);
                plan.northWall[x][6] = (x != 3);
            }
        }

        // Arrival warehouse hall at (0, 0) connects East directly toward Base Alpha (1, 0)
        if (rx == 0 && rz == 0) {
            for (int x = 3; x <= 6; x++) {
                for (int z = 3; z <= 5; z++) {
                    plan.westWall[x][z] = false;
                }
            }
            for (int x = 2; x <= 6; x++) {
                for (int z = 4; z <= 5; z++) {
                    plan.northWall[x][z] = false;
                }
            }
        }

        boolean[] westOpen = borderOpenings(seed ^ 0x1B873593CC9E2D51L, rx - 1, rz);
        for (int z = 0; z < REGION_CELLS; z++) {
            plan.westWall[0][z] = !westOpen[z];
        }
        // Guarantee open direct sightline doorway between Arrival (0,0) and Base Alpha (1,0) at z=3
        if (rx == 1 && rz == 0) {
            plan.westWall[0][3] = false;
        }
        boolean[] northOpen = borderOpenings(seed ^ 0x85EBCA6B12345679L, rx, rz - 1);
        for (int x = 0; x < REGION_CELLS; x++) {
            plan.northWall[x][0] = !northOpen[x];
        }

        return plan;
    }

    private boolean[] borderOpenings(long saltSeed, int fromRx, int fromRz) {
        boolean[] open = new boolean[REGION_CELLS];
        Random r = new Random(mix64(saltSeed, fromRx, fromRz));
        int count = 3 + r.nextInt(2);
        for (int i = 0; i < count; i++) {
            open[r.nextInt(REGION_CELLS)] = true;
        }
        return open;
    }

    private Level1ZoneType pickZoneType(long seed, int rx, int rz) {
        // Curated canonical humanity settlements near Level 1 arrival
        if (rx == 0 && rz == 0) return Level1ZoneType.WAREHOUSE_HALL;
        if (rx == 1 && rz == 0) return Level1ZoneType.MEG_BASE_ALPHA;
        if (rx == -2 && rz == 1) return Level1ZoneType.BNTG_TRADER_KEEP;
        if (rx == 0 && rz == -2) return Level1ZoneType.TOMS_DINER;

        double roll = toUnitDouble(mix64(seed ^ 0xE7037ED1A0B428DBL, rx, rz));
        if (roll < 0.005) return Level1ZoneType.MEG_BASE_ALPHA;
        if (roll < 0.010) return Level1ZoneType.BNTG_TRADER_KEEP;
        if (roll < 0.014) return Level1ZoneType.TOMS_DINER;
        if (roll < 0.22) return Level1ZoneType.LOADING_BAY;
        if (roll < 0.56) return Level1ZoneType.STORAGE_CORRIDOR;
        return Level1ZoneType.WAREHOUSE_HALL;
    }

    private LightState getLightState(long seed, int cx, int cz, Level1ZoneType zone) {
        if (isOutpostZone(zone)) {
            return LightState.WORKING;
        }
        double roll = toUnitDouble(mix64(seed ^ 0x94D049BB133111EBL, cx, cz));
        if (zone == Level1ZoneType.STORAGE_CORRIDOR) {
            if (roll < 0.14) return LightState.FLICKERING;
            if (roll < 0.28) return LightState.DEAD;
            if (roll < 0.36) return LightState.NONE;
            return LightState.WORKING;
        }
        if (roll < 0.10) return LightState.FLICKERING;
        if (roll < 0.18) return LightState.DEAD;
        if (roll < 0.24) return LightState.NONE;
        return LightState.WORKING;
    }

    public Location getArrivalLocation(World world) {
        int floorY = cfg().getLevel1FloorY();
        // Inside (0,0) Warehouse Hall looking straight East (-90 deg yaw) toward Base Alpha
        return new Location(world, 18.5, floorY + 1.0, 14.5, -90.0f, 0.0f);
    }

    public Location getOutpostSpawn(World world, Level1ZoneType target) {
        int floorY = cfg().getLevel1FloorY();
        return switch (target) {
            case MEG_BASE_ALPHA -> new Location(world, 32 + 16.5, floorY + 1.0, 16.5, 0f, 0f);
            case BNTG_TRADER_KEEP -> new Location(world, -64 + 16.5, floorY + 1.0, 32 + 16.5, 0f, 0f);
            case TOMS_DINER -> new Location(world, 16.5, floorY + 1.0, -64 + 16.5, 0f, 0f);
            default -> getArrivalLocation(world);
        };
    }

    public boolean isInProtectedOutpostCore(World world, int wx, int wz) {
        Level1ZoneType zone = getZoneAtBlock(world, wx, wz);
        if (!isOutpostZone(zone)) {
            return false;
        }
        int cx = Math.floorDiv(wx, CELL_SIZE);
        int cz = Math.floorDiv(wz, CELL_SIZE);
        int rlx = Math.floorMod(cx, REGION_CELLS);
        int rlz = Math.floorMod(cz, REGION_CELLS);
        return rlx >= 2 && rlx <= 5 && rlz >= 2 && rlz <= 5;
    }

    @Override
    public @NotNull BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return biomeProvider;
    }

    @Override
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return List.of(new Level1Populator(plugin));
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
