package com.yunexal.backrooms;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Completely custom procedural Backrooms Level 0 ("The Lobby") chunk generator.
 *
 * <p>Architecture guarantees:
 * <ul>
 *     <li>Each 4x4 block cell maps seamlessly inside 16x16 chunks (4x4 cells/chunk) with zero border seams.</li>
 *     <li>Each 8x8 cell macro-region (32x32 blocks = 2x2 chunks) runs a randomized DFS spanning tree plus
 *         deterministic East/South inter-region doorways, guaranteeing 100% reachability everywhere.</li>
 *     <li>Macro zones vary naturally between classic twisting yellow corridors, open pillared halls,
 *         tight labyrinth sectors, and flickering blackout areas.</li>
 * </ul>
 */
public final class Level0ChunkGenerator extends ChunkGenerator {
    public static final int CELL_SIZE = 4;
    private static final int REGION_CELLS = 8;
    private static final int CACHE_LIMIT = 1024;

    public enum ZoneType {
        CLASSIC_MAZE,
        PILLAR_HALL,
        NARROW_MAZE,
        BLACKOUT_ZONE
    }

    private enum LightState {
        WORKING,
        FLICKERING,
        DEAD,
        NONE
    }

    private static final class RegionPlan {
        final ZoneType zoneType;
        // walls[lx][lz]: true = solid wall along West (lx=0) or North (lz=0) boundary of cell
        final boolean[][] westWall = new boolean[REGION_CELLS][REGION_CELLS];
        final boolean[][] northWall = new boolean[REGION_CELLS][REGION_CELLS];

        RegionPlan(ZoneType zoneType) {
            this.zoneType = zoneType;
        }
    }

    private final BackroomsConfig config;
    private final BiomeProvider biomeProvider = new Level0BiomeProvider();
    private final Map<Long, RegionPlan> regionCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, RegionPlan> eldest) {
                    return size() > CACHE_LIMIT;
                }
            }
    );

    public Level0ChunkGenerator(BackroomsConfig config) {
        this.config = config;
    }

    private long effectiveSeed(WorldInfo worldInfo) {
        return config.getLevel0Seed() != 0L ? config.getLevel0Seed() : worldInfo.getSeed();
    }

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random unused,
                              int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        long seed = effectiveSeed(worldInfo);
        int floorY = config.getFloorY();
        int wallHeight = config.getWallHeight();
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

                // 1. Subfloor & roof bedrock seal so sky/void is completely invisible and unreachable.
                chunkData.setBlock(lx, floorY - 2, lz, Material.BEDROCK);
                chunkData.setBlock(lx, floorY - 1, lz, Material.BEDROCK);
                chunkData.setBlock(lx, ceilingY + 1, lz, Material.BEDROCK);
                chunkData.setBlock(lx, ceilingY + 2, lz, Material.BEDROCK);

                // 2. Damp commercial office carpet floor.
                chunkData.setBlock(lx, floorY, lz, Material.BROWN_WOOL);

                ZoneType zone = getZoneType(seed, cx, cz);
                boolean solidWall = isWallBlock(seed, cx, cz, inCellX, inCellZ, zone);

                if (solidWall) {
                    boolean corner = (inCellX == 0 && inCellZ == 0);
                    buildWallColumn(chunkData, lx, lz, floorY, ceilingY, seed, wx, wz, corner);
                }

                // 3. Drop ceiling & ceiling fixtures.
                if (inCellX == 2 && inCellZ == 2 && !solidWall) {
                    LightState light = getLightState(seed, cx, cz, zone);
                    Material ceilingMat = switch (light) {
                        case WORKING -> Material.OCHRE_FROGLIGHT;
                        case FLICKERING -> Material.SEA_LANTERN;
                        case DEAD -> Material.POLISHED_ANDESITE;
                        case NONE -> Material.CALCITE;
                    };
                    chunkData.setBlock(lx, ceilingY, lz, ceilingMat);
                } else {
                    chunkData.setBlock(lx, ceilingY, lz, Material.CALCITE);
                }
            }
        }
    }

    private void buildWallColumn(ChunkData chunkData, int lx, int lz, int floorY, int ceilingY,
                                 long seed, int wx, int wz, boolean corner) {
        long colHash = mix64(seed ^ 0x517CC1B727220A95L, wx, wz);
        double dampRoll = toUnitDouble(colHash);

        boolean stainedBase = dampRoll < config.getDampPillarChance() * (corner ? 1.35 : 0.45);
        boolean fullMoldPillar = corner && dampRoll < (config.getDampPillarChance() * 0.28);
        boolean hvacVentTop = !corner && !fullMoldPillar && dampRoll > (1.0 - config.getVentChance());

        for (int y = floorY + 1; y < ceilingY; y++) {
            Material mat;
            if (fullMoldPillar) {
                mat = Material.BROWN_TERRACOTTA;
            } else if (y == floorY + 1) {
                mat = stainedBase ? Material.BROWN_TERRACOTTA : Material.ORANGE_TERRACOTTA;
            } else if (y == ceilingY - 1 && hvacVentTop) {
                mat = Material.IRON_BLOCK;
            } else if (y == floorY + 2 && stainedBase && ((colHash >>> 12) & 3) == 0) {
                mat = Material.BROWN_TERRACOTTA;
            } else {
                mat = Material.YELLOW_TERRACOTTA;
            }
            chunkData.setBlock(lx, y, lz, mat);
        }
    }

    private boolean isWallBlock(long seed, int cx, int cz, int inCellX, int inCellZ, ZoneType zone) {
        if (inCellX > 0 && inCellZ > 0) {
            return false;
        }
        if (inCellX == 0 && inCellZ == 0) {
            if (zone == ZoneType.PILLAR_HALL) {
                // Keep structural 1x1 columns across open halls, occasionally opening a wider bay
                long h = mix64(seed ^ 0xA24BAED4963EE407L, cx, cz);
                return toUnitDouble(h) > 0.16;
            }
            // Around spawn corridor make corners clean if all 4 neighboring walls are open
            boolean w1 = hasWestWall(seed, cx, cz);
            boolean w2 = hasWestWall(seed, cx, cz - 1);
            boolean n1 = hasNorthWall(seed, cx, cz);
            boolean n2 = hasNorthWall(seed, cx - 1, cz);
            if (!w1 && !w2 && !n1 && !n2) {
                return (cx != 0 && cz != 0) && toUnitDouble(mix64(seed, cx, cz)) < 0.72;
            }
            return true;
        }
        if (inCellX == 0) {
            return hasWestWall(seed, cx, cz);
        }
        return hasNorthWall(seed, cx, cz);
    }

    private boolean hasWestWall(long seed, int cx, int cz) {
        int rx = Math.floorDiv(cx, REGION_CELLS);
        int rz = Math.floorDiv(cz, REGION_CELLS);
        int lx = Math.floorMod(cx, REGION_CELLS);
        int lz = Math.floorMod(cz, REGION_CELLS);
        return getRegionPlan(seed, rx, rz).westWall[lx][lz];
    }

    private boolean hasNorthWall(long seed, int cx, int cz) {
        int rx = Math.floorDiv(cx, REGION_CELLS);
        int rz = Math.floorDiv(cz, REGION_CELLS);
        int lx = Math.floorMod(cx, REGION_CELLS);
        int lz = Math.floorMod(cz, REGION_CELLS);
        return getRegionPlan(seed, rx, rz).northWall[lx][lz];
    }

    private ZoneType getZoneType(long seed, int cx, int cz) {
        int rx = Math.floorDiv(cx, REGION_CELLS);
        int rz = Math.floorDiv(cz, REGION_CELLS);
        return getRegionPlan(seed, rx, rz).zoneType;
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
        ZoneType zone = pickZoneType(seed, rx, rz);
        RegionPlan plan = new RegionPlan(zone);
        for (int x = 0; x < REGION_CELLS; x++) {
            for (int z = 0; z < REGION_CELLS; z++) {
                plan.westWall[x][z] = true;
                plan.northWall[x][z] = true;
            }
        }

        // Randomized DFS spanning tree inside the 8x8 macro-region for zero diagonal bias and 100% connectivity.
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

            // Fisher-Yates shuffle on 4 directions
            for (int i = 3; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }

            boolean carved = false;
            for (int dirIdx : order) {
                int nx = cx + dx[dirIdx];
                int nz = cz + dz[dirIdx];
                if (nx >= 0 && nx < REGION_CELLS && nz >= 0 && nz < REGION_CELLS && !visited[nx][nz]) {
                    visited[nx][nz] = true;
                    carveInternalEdge(plan, cx, cz, nx, nz);
                    stack.push(new int[]{nx, nz});
                    carved = true;
                    break;
                }
            }
            if (!carved) {
                stack.pop();
            }
        }

        // Braiding & architectural shaping by macro-zone
        double braidChance = switch (zone) {
            case PILLAR_HALL -> 0.84;
            case NARROW_MAZE -> config.getExtraBraidingChance() * 0.45;
            case BLACKOUT_ZONE -> config.getExtraBraidingChance() * 1.10;
            case CLASSIC_MAZE -> config.getExtraBraidingChance();
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

        // Carve occasional 2x2 alcove rooms in Classic / Blackout zones
        if (zone == ZoneType.CLASSIC_MAZE || zone == ZoneType.BLACKOUT_ZONE) {
            int roomCount = 1 + rng.nextInt(3);
            for (int r = 0; r < roomCount; r++) {
                int ax = 1 + rng.nextInt(REGION_CELLS - 2);
                int az = 1 + rng.nextInt(REGION_CELLS - 2);
                plan.westWall[ax + 1][az] = false;
                plan.westWall[ax + 1][az + 1] = false;
                plan.northWall[ax][az + 1] = false;
                plan.northWall[ax + 1][az + 1] = false;
            }
        }

        // Keep spawn region (0, 0) cells (0..1, 0..1) open & inviting
        if (rx == 0 && rz == 0) {
            plan.westWall[1][0] = false;
            plan.westWall[1][1] = false;
            plan.northWall[0][1] = false;
            plan.northWall[1][1] = false;
        }

        // Deterministic inter-region border doorways: every region connects to West (rx-1, rz) and North (rx, rz-1).
        boolean[] westOpen = borderOpenings(seed ^ 0x1B873593CC9E2D51L, rx - 1, rz);
        for (int z = 0; z < REGION_CELLS; z++) {
            plan.westWall[0][z] = !westOpen[z];
        }
        boolean[] northOpen = borderOpenings(seed ^ 0x85EBCA6B12345679L, rx, rz - 1);
        for (int x = 0; x < REGION_CELLS; x++) {
            plan.northWall[x][0] = !northOpen[x];
        }

        return plan;
    }

    private void carveInternalEdge(RegionPlan plan, int x1, int z1, int x2, int z2) {
        if (x2 == x1 + 1) {
            plan.westWall[x2][z2] = false;
        } else if (x1 == x2 + 1) {
            plan.westWall[x1][z1] = false;
        } else if (z2 == z1 + 1) {
            plan.northWall[x2][z2] = false;
        } else if (z1 == z2 + 1) {
            plan.northWall[x1][z1] = false;
        }
    }

    private boolean[] borderOpenings(long saltSeed, int fromRx, int fromRz) {
        boolean[] open = new boolean[REGION_CELLS];
        Random r = new Random(mix64(saltSeed, fromRx, fromRz));
        int count = 2 + r.nextInt(2); // 2 or 3 doorways along each 8-cell edge
        for (int i = 0; i < count; i++) {
            open[r.nextInt(REGION_CELLS)] = true;
        }
        return open;
    }

    private ZoneType pickZoneType(long seed, int rx, int rz) {
        if (rx == 0 && rz == 0) {
            return ZoneType.CLASSIC_MAZE;
        }
        double roll = toUnitDouble(mix64(seed ^ 0xD6E8FEB86659FD93L, rx, rz));
        double pBlackout = config.getBlackoutZoneChance();
        double pPillar = config.getPillarHallChance();
        double pNarrow = config.getNarrowMazeChance();

        if (roll < pBlackout) {
            return ZoneType.BLACKOUT_ZONE;
        }
        if (roll < pBlackout + pPillar) {
            return ZoneType.PILLAR_HALL;
        }
        if (roll < pBlackout + pPillar + pNarrow) {
            return ZoneType.NARROW_MAZE;
        }
        return ZoneType.CLASSIC_MAZE;
    }

    private LightState getLightState(long seed, int cx, int cz, ZoneType zone) {
        // Keep spawn area brightly and steadily illuminated on first noclip entry.
        if (Math.abs(cx) <= 2 && Math.abs(cz) <= 2) {
            if ((cx & 1) == 0 || (cz & 1) == 0) {
                return LightState.WORKING;
            }
            return (cx == 1 && cz == 1) ? LightState.WORKING : LightState.FLICKERING;
        }

        double roll = toUnitDouble(mix64(seed ^ 0x94D049BB133111EBL, cx, cz));
        if (zone == ZoneType.BLACKOUT_ZONE) {
            if (roll < 0.08) {
                return LightState.WORKING;
            }
            if (roll < 0.28) {
                return LightState.FLICKERING;
            }
            if (roll < 0.85) {
                return LightState.DEAD;
            }
            return LightState.NONE;
        }

        if (zone == ZoneType.PILLAR_HALL) {
            if (roll < config.getFlickerLightChance()) {
                return LightState.FLICKERING;
            }
            if (roll < config.getFlickerLightChance() + config.getDeadLightChance()) {
                return LightState.DEAD;
            }
            return LightState.WORKING;
        }

        // Classic / Narrow corridors
        if (roll < config.getFlickerLightChance()) {
            return LightState.FLICKERING;
        }
        if (roll < config.getFlickerLightChance() + config.getDeadLightChance()) {
            return LightState.DEAD;
        }
        if (roll < config.getFlickerLightChance() + config.getDeadLightChance() + 0.16) {
            return LightState.NONE;
        }
        return LightState.WORKING;
    }

    public Location findSafeSpawn(World world) {
        long seed = world.getSeed();
        if (config.getLevel0Seed() != 0L) {
            seed = config.getLevel0Seed();
        }
        // Cell (0,0)..(1,1) center (lx=2, lz=2) is always open floor at y=floorY+1
        double x = 0 * CELL_SIZE + 2.5;
        double z = 0 * CELL_SIZE + 2.5;
        int floorY = config.getFloorY();
        // Verify cell center isn't blocked even if config changed dynamically
        for (int r = 0; r <= 4; r++) {
            for (int cx = -r; cx <= r; cx++) {
                for (int cz = -r; cz <= r; cz++) {
                    if (!isWallBlock(seed, cx, cz, 2, 2, getZoneType(seed, cx, cz))) {
                        x = cx * CELL_SIZE + 2.5;
                        z = cz * CELL_SIZE + 2.5;
                        return new Location(world, x, floorY + 1.0, z, -45f, 0f);
                    }
                }
            }
        }
        return new Location(world, x, floorY + 1.0, z, -45f, 0f);
    }

    public ZoneType getZoneAtBlock(World world, int blockX, int blockZ) {
        long seed = config.getLevel0Seed() != 0L ? config.getLevel0Seed() : world.getSeed();
        int cx = Math.floorDiv(blockX, CELL_SIZE);
        int cz = Math.floorDiv(blockZ, CELL_SIZE);
        return getZoneType(seed, cx, cz);
    }

    private static long mix64(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xBF58476D1CE4E5B9L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static double toUnitDouble(long hash) {
        return ((hash >>> 11) * 0x1.0p-53);
    }

    @Override
    public @NotNull BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return biomeProvider;
    }

    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return findSafeSpawn(world);
    }

    @Override
    public boolean isParallelCapable() {
        return true;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
