package com.yunexal.backrooms;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Procedural Backrooms Level 0 ("The Lobby") chunk generator.
 *
 * <p>Architecture guarantees:
 * <ul>
 *     <li>Each 4x4 block cell maps seamlessly inside 16x16 chunks (4x4 cells/chunk) with zero border seams.</li>
 *     <li>Each 8x8 cell macro-region (32x32 blocks = 2x2 chunks) runs a randomized DFS spanning tree plus
 *         deterministic East/South inter-region doorways, guaranteeing reachability everywhere.</li>
 *     <li>Includes classic yellow corridors, pillared halls, tight labyrinths, blackout sectors,
 *         warm Manila sanctuary rooms, crimson Red Rooms, and rare soft-carpet noclip anomalies.</li>
 * </ul>
 */
public final class Level0ChunkGenerator extends ChunkGenerator {
    public static final int CELL_SIZE = 4;
    private static final int REGION_CELLS = 8;
    private static final int CACHE_LIMIT = 1024;

    public enum ZoneType {
        CLASSIC_MAZE,
        PILLAR_HALL,
        PITFALL_HALL,
        NARROW_MAZE,
        BLACKOUT_ZONE,
        MANILA_ROOM,
        RED_ROOM
    }

    private enum LightState {
        WORKING,
        FLICKERING,
        DEAD,
        NONE
    }

    private static final class RegionPlan {
        final ZoneType zoneType;
        final boolean[][] westWall = new boolean[REGION_CELLS][REGION_CELLS];
        final boolean[][] northWall = new boolean[REGION_CELLS][REGION_CELLS];

        RegionPlan(ZoneType zoneType) {
            this.zoneType = zoneType;
        }
    }

    private final YunexalBackroomsPlugin plugin;
    private final BiomeProvider biomeProvider = new Level0BiomeProvider();
    private final Map<Long, RegionPlan> regionCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, RegionPlan> eldest) {
                    return size() > CACHE_LIMIT;
                }
            }
    );

    public Level0ChunkGenerator(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    private BackroomsConfig cfg() {
        return plugin.getBackroomsConfig();
    }

    public void clearCache() {
        regionCache.clear();
    }

    private long effectiveSeed(WorldInfo worldInfo) {
        long configured = cfg().getLevel0Seed();
        return configured != 0L ? configured : worldInfo.getSeed();
    }

    private long effectiveSeed(World world) {
        long configured = cfg().getLevel0Seed();
        return configured != 0L ? configured : world.getSeed();
    }

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random unused,
                              int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        BackroomsConfig config = cfg();
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

                ZoneType zone = getZoneType(seed, cx, cz);
                boolean solidWall = isWallBlock(seed, cx, cz, inCellX, inCellZ, zone);
                int rlx = Math.floorMod(cx, REGION_CELLS);
                int rlz = Math.floorMod(cz, REGION_CELLS);
                boolean pitfallHallInterior = (zone == ZoneType.PITFALL_HALL
                        && rlx >= 1 && rlx <= 6 && rlz >= 1 && rlz <= 6);
                boolean pitfallShaft = pitfallHallInterior
                        && rlx >= 2 && rlx <= 5 && rlz >= 2 && rlz <= 5
                        && (inCellX == 1 || inCellX == 2) && (inCellZ == 1 || inCellZ == 2);

                // 1. Subfloor & roof bedrock seal (or Pitfall Hall under-floor crawlspace & shafts).
                if (pitfallHallInterior) {
                    chunkData.setBlock(lx, floorY - 11, lz, Material.BEDROCK);
                    chunkData.setBlock(lx, floorY - 10, lz, Material.BLACK_CONCRETE);
                    for (int y = floorY - 6; y <= floorY - 1; y++) {
                        if (!pitfallShaft) {
                            chunkData.setBlock(lx, y, lz, Material.BROWN_TERRACOTTA);
                        }
                    }
                } else {
                    chunkData.setBlock(lx, floorY - 2, lz, Material.BEDROCK);
                    chunkData.setBlock(lx, floorY - 1, lz, Material.BEDROCK);
                }
                chunkData.setBlock(lx, ceilingY + 1, lz, Material.BEDROCK);
                chunkData.setBlock(lx, ceilingY + 2, lz, Material.BEDROCK);

                // 2. Floor material (damp wool carpet, crimson carpet in Red Rooms, pitfall opening, or soft carpet).
                if (!pitfallShaft) {
                    Material floorMat;
                    if (zone == ZoneType.RED_ROOM) {
                        floorMat = Material.RED_WOOL;
                    } else if (!solidWall && !pitfallHallInterior && isNaturalNoclipSpot(seed, config, cx, cz, wx, wz, zone)) {
                        floorMat = Material.GRAY_WOOL;
                    } else {
                        floorMat = Material.BROWN_WOOL;
                    }
                    chunkData.setBlock(lx, floorY, lz, floorMat);
                }

                if (solidWall) {
                    boolean corner = (inCellX == 0 && inCellZ == 0);
                    buildWallColumn(chunkData, config, lx, lz, floorY, ceilingY, seed, wx, wz, cx, cz, inCellX, inCellZ, corner, zone);
                }

                // 3. Solid drop ceiling & recessed fluorescent fixtures (no broken ceiling holes).
                if (inCellX == 2 && inCellZ == 2 && !solidWall) {
                    LightState light = getLightState(seed, config, cx, cz, zone);
                    Material ceilingMat;
                    if (zone == ZoneType.RED_ROOM) {
                        ceilingMat = (light == LightState.NONE) ? Material.CALCITE : Material.SHROOMLIGHT;
                    } else {
                        ceilingMat = switch (light) {
                            case WORKING -> Material.OCHRE_FROGLIGHT;
                            case FLICKERING -> Material.SEA_LANTERN;
                            case DEAD -> Material.POLISHED_ANDESITE;
                            case NONE -> Material.CALCITE;
                        };
                    }
                    chunkData.setBlock(lx, ceilingY, lz, ceilingMat);
                } else {
                    chunkData.setBlock(lx, ceilingY, lz, Material.CALCITE);
                }
            }
        }
    }

    private void buildWallColumn(ChunkData chunkData, BackroomsConfig config,
                                 int lx, int lz, int floorY, int ceilingY,
                                 long seed, int wx, int wz, int cx, int cz,
                                 int inCellX, int inCellZ, boolean corner, ZoneType zone) {
        if (zone == ZoneType.RED_ROOM) {
            for (int y = floorY + 1; y < ceilingY; y++) {
                chunkData.setBlock(lx, y, lz, Material.RED_TERRACOTTA);
            }
            return;
        }

        if (zone == ZoneType.MANILA_ROOM && isManilaPerimeterCell(cx, cz, inCellX, inCellZ)) {
            for (int y = floorY + 1; y < ceilingY; y++) {
                chunkData.setBlock(lx, y, lz, Material.WHITE_TERRACOTTA);
            }
            return;
        }

        // Arched half-wall partitions (waist-high wall + arched colonnade cutout) in Pillar Halls & select corridors
        if (!corner && zone != ZoneType.PITFALL_HALL) {
            boolean northWall = (inCellZ == 0);
            int posInPanel = northWall ? inCellX : inCellZ;
            int rlx = Math.floorMod(cx, REGION_CELLS);
            int rlz = Math.floorMod(cz, REGION_CELLS);
            boolean interiorPillarPartition = (zone == ZoneType.PILLAR_HALL)
                    && ((northWall && rlz >= 1 && rlz <= 7) || (!northWall && rlx >= 1 && rlx <= 7));

            long panelSeed = mix64(seed ^ 0x6A09E667F3BCC908L, cx * 2 + (inCellX == 0 ? 0 : 1), cz);
            double openRoll = toUnitDouble(panelSeed);

            if (interiorPillarPartition || (openRoll >= 0.08 && openRoll < 0.16)) {
                // Iconic arched office partition: solid half-wall at bottom, arched cutout, solid lintel at top
                chunkData.setBlock(lx, floorY + 1, lz, Material.ORANGE_TERRACOTTA);
                chunkData.setBlock(lx, ceilingY - 1, lz, Material.YELLOW_TERRACOTTA);
                if (posInPanel == 1 || posInPanel == 3) {
                    Stairs arch = (Stairs) Material.SMOOTH_SANDSTONE_STAIRS.createBlockData();
                    arch.setHalf(Bisected.Half.TOP);
                    if (northWall) {
                        arch.setFacing(posInPanel == 1 ? BlockFace.WEST : BlockFace.EAST);
                    } else {
                        arch.setFacing(posInPanel == 1 ? BlockFace.NORTH : BlockFace.SOUTH);
                    }
                    chunkData.setBlock(lx, ceilingY - 2, lz, arch);
                }
                return;
            }
            if (openRoll < 0.08 && (posInPanel == 2 || posInPanel == 3)) {
                // 2-block-wide pass-through archway (3 blocks tall, header lintel at top)
                chunkData.setBlock(lx, ceilingY - 1, lz, Material.YELLOW_TERRACOTTA);
                return;
            }
        }

        long colHash = mix64(seed ^ 0x517CC1B727220A95L, wx, wz);
        double dampRoll = toUnitDouble(colHash);

        boolean stainedBase = dampRoll < config.getDampPillarChance() * (corner ? 1.35 : 0.45);
        boolean fullMoldPillar = corner && dampRoll < (config.getDampPillarChance() * 0.28);
        boolean hvacVentTop = !corner && !fullMoldPillar && dampRoll > (1.0 - config.getVentChance());
        boolean deadOutlet = !corner && !fullMoldPillar && !stainedBase && !hvacVentTop
                && toUnitDouble(mix64(seed ^ 0x4B72E682A51F31D9L, wx, wz)) < config.getOutletChance();

        for (int y = floorY + 1; y < ceilingY; y++) {
            Material mat;
            if (fullMoldPillar) {
                mat = Material.BROWN_TERRACOTTA;
            } else if (y == floorY + 1) {
                mat = stainedBase ? Material.BROWN_TERRACOTTA
                        : (deadOutlet ? Material.CYAN_TERRACOTTA : Material.ORANGE_TERRACOTTA);
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

    private boolean isManilaPerimeterCell(int cx, int cz, int inCellX, int inCellZ) {
        int rlx = Math.floorMod(cx, REGION_CELLS);
        int rlz = Math.floorMod(cz, REGION_CELLS);
        if (rlx >= 2 && rlx <= 4 && rlz >= 2 && rlz <= 4) {
            return true;
        }
        if (rlx == 5 && inCellX == 0 && rlz >= 2 && rlz <= 5) {
            return true;
        }
        return rlz == 5 && inCellZ == 0 && rlx >= 2 && rlx <= 5;
    }

    public boolean isNaturalNoclipSpot(World world, int wx, int wz) {
        BackroomsConfig config = cfg();
        long seed = effectiveSeed(world);
        int cx = Math.floorDiv(wx, CELL_SIZE);
        int cz = Math.floorDiv(wz, CELL_SIZE);
        int inCellX = Math.floorMod(wx, CELL_SIZE);
        int inCellZ = Math.floorMod(wz, CELL_SIZE);
        ZoneType zone = getZoneType(seed, cx, cz);
        if (isWallBlock(seed, cx, cz, inCellX, inCellZ, zone)) {
            return false;
        }
        return isNaturalNoclipSpot(seed, config, cx, cz, wx, wz, zone);
    }

    private boolean isNaturalNoclipSpot(long seed, BackroomsConfig config,
                                        int cx, int cz, int wx, int wz, ZoneType zone) {
        if (zone == ZoneType.MANILA_ROOM || zone == ZoneType.RED_ROOM) {
            return false;
        }
        if (Math.abs(cx) <= 2 && Math.abs(cz) <= 2) {
            return false;
        }
        // Only one small patch per cell center, keeping frequency strictly governed by config
        if (Math.floorMod(wx, CELL_SIZE) != 2 || Math.floorMod(wz, CELL_SIZE) != 2) {
            return false;
        }
        double roll = toUnitDouble(mix64(seed ^ 0x243F6A8885A308D3L, wx, wz));
        return roll < config.getSoftCarpetChance();
    }

    private boolean isWallBlock(long seed, int cx, int cz, int inCellX, int inCellZ, ZoneType zone) {
        // Thick 2x2 structural pillars in select Pillar Hall bays
        if (zone == ZoneType.PILLAR_HALL && inCellX <= 1 && inCellZ <= 1) {
            double pRoll = toUnitDouble(mix64(seed ^ 0xA24BAED4963EE407L, cx, cz));
            if (pRoll > 0.84) {
                return true;
            }
        }
        if (inCellX > 0 && inCellZ > 0) {
            return false;
        }
        int rlx = Math.floorMod(cx, REGION_CELLS);
        int rlz = Math.floorMod(cz, REGION_CELLS);

        if (inCellX == 0 && inCellZ == 0) {
            // Keep Manila Room 12x12 center clear of corner pillars
            if (zone == ZoneType.MANILA_ROOM && rlx >= 3 && rlx <= 4 && rlz >= 3 && rlz <= 4) {
                return false;
            }
            // Keep Pitfall Hall interior floor open around the 4x4 grid of square pitfall shafts
            if (zone == ZoneType.PITFALL_HALL && rlx >= 2 && rlx <= 6 && rlz >= 2 && rlz <= 6) {
                return false;
            }
            if (zone == ZoneType.PILLAR_HALL) {
                if (hasWestWall(seed, cx, cz) || hasWestWall(seed, cx, cz - 1)
                        || hasNorthWall(seed, cx, cz) || hasNorthWall(seed, cx - 1, cz)) {
                    return true;
                }
                long h = mix64(seed ^ 0xA24BAED4963EE407L, cx, cz);
                return toUnitDouble(h) > 0.18;
            }
            boolean w1 = hasWestWall(seed, cx, cz);
            boolean w2 = hasWestWall(seed, cx, cz - 1);
            boolean n1 = hasNorthWall(seed, cx, cz);
            boolean n2 = hasNorthWall(seed, cx - 1, cz);
            if (!w1 && !w2 && !n1 && !n2) {
                return (cx != 0 || cz != 0) && toUnitDouble(mix64(seed, cx, cz)) < 0.72;
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

    public ZoneType getZoneAtRegion(long seed, int rx, int rz) {
        return getRegionPlan(seed, rx, rz).zoneType;
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
        BackroomsConfig config = cfg();
        ZoneType zone = pickZoneType(seed, config, rx, rz);
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

        double braidChance = switch (zone) {
            case PILLAR_HALL -> 0.88;
            case PITFALL_HALL -> 0.50;
            case NARROW_MAZE -> config.getExtraBraidingChance() * 0.45;
            case BLACKOUT_ZONE -> config.getExtraBraidingChance() * 1.10;
            case RED_ROOM -> config.getExtraBraidingChance() * 0.90;
            case MANILA_ROOM -> 0.62;
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

        // Long repeating arched colonnade partition wall inside Pillar Halls
        if (zone == ZoneType.PILLAR_HALL) {
            boolean alongZ = rng.nextBoolean();
            int lineIdx = 2 + rng.nextInt(4);
            if (alongZ) {
                for (int z = 1; z <= 6; z++) {
                    plan.westWall[lineIdx][z] = true;
                }
            } else {
                for (int x = 1; x <= 6; x++) {
                    plan.northWall[x][lineIdx] = true;
                }
            }
        }

        // Open Pitfalls Hall (cells 1..6 x 1..6) with wide 2-cell doorways into the pitfall grid
        if (zone == ZoneType.PITFALL_HALL) {
            for (int x = 2; x <= 6; x++) {
                for (int z = 1; z <= 6; z++) {
                    plan.westWall[x][z] = false;
                }
            }
            for (int x = 1; x <= 6; x++) {
                for (int z = 2; z <= 6; z++) {
                    plan.northWall[x][z] = false;
                }
            }
            for (int z = 1; z <= 6; z++) {
                plan.westWall[1][z] = (z != 3 && z != 4);
                plan.westWall[7][z] = (z != 3 && z != 4);
            }
            for (int x = 1; x <= 6; x++) {
                plan.northWall[x][1] = (x != 3 && x != 4);
                plan.northWall[x][7] = (x != 3 && x != 4);
            }
        }

        // Carve occasional 2x2 alcove rooms in Classic / Blackout / Red zones
        if (zone == ZoneType.CLASSIC_MAZE || zone == ZoneType.BLACKOUT_ZONE || zone == ZoneType.RED_ROOM) {
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

        // Carve enclosed 12x12 Manila Room (cells 2..4 x 2..4) with 4 cardinal doorway entrances
        if (zone == ZoneType.MANILA_ROOM) {
            for (int x = 3; x <= 4; x++) {
                for (int z = 2; z <= 4; z++) {
                    plan.westWall[x][z] = false;
                }
            }
            for (int x = 2; x <= 4; x++) {
                for (int z = 3; z <= 4; z++) {
                    plan.northWall[x][z] = false;
                }
            }
            for (int z = 2; z <= 4; z++) {
                plan.westWall[2][z] = true;
                plan.westWall[5][z] = true;
            }
            for (int x = 2; x <= 4; x++) {
                plan.northWall[x][2] = true;
                plan.northWall[x][5] = true;
            }
            plan.westWall[2][3] = false;
            plan.westWall[5][3] = false;
            plan.northWall[3][2] = false;
            plan.northWall[3][5] = false;
        }

        if (rx == 0 && rz == 0) {
            plan.westWall[1][0] = false;
            plan.westWall[1][1] = false;
            plan.northWall[0][1] = false;
            plan.northWall[1][1] = false;
        }

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
        int count = 2 + r.nextInt(2);
        for (int i = 0; i < count; i++) {
            open[r.nextInt(REGION_CELLS)] = true;
        }
        return open;
    }

    private ZoneType pickZoneType(long seed, BackroomsConfig config, int rx, int rz) {
        if (rx == 0 && rz == 0) {
            return ZoneType.CLASSIC_MAZE;
        }
        double roll = toUnitDouble(mix64(seed ^ 0xD6E8FEB86659FD93L, rx, rz));
        double pManila = config.getManilaRoomChance();
        double pRed = config.getRedRoomChance();
        double pBlackout = config.getBlackoutZoneChance();
        double pPillar = config.getPillarHallChance();
        double pNarrow = config.getNarrowMazeChance();

        double acc = pManila;
        if (roll < acc) {
            return ZoneType.MANILA_ROOM;
        }
        acc += pRed;
        if (roll < acc) {
            return ZoneType.RED_ROOM;
        }
        acc += 0.055;
        if (roll < acc) {
            return ZoneType.PITFALL_HALL;
        }
        acc += pBlackout;
        if (roll < acc) {
            return ZoneType.BLACKOUT_ZONE;
        }
        acc += pPillar;
        if (roll < acc) {
            return ZoneType.PILLAR_HALL;
        }
        acc += pNarrow;
        if (roll < acc) {
            return ZoneType.NARROW_MAZE;
        }
        return ZoneType.CLASSIC_MAZE;
    }

    private LightState getLightState(long seed, BackroomsConfig config, int cx, int cz, ZoneType zone) {
        if (zone == ZoneType.MANILA_ROOM) {
            return LightState.WORKING;
        }
        if (Math.abs(cx) <= 2 && Math.abs(cz) <= 2) {
            if ((cx & 1) == 0 || (cz & 1) == 0) {
                return LightState.WORKING;
            }
            return (cx == 1 && cz == 1) ? LightState.WORKING : LightState.FLICKERING;
        }

        double roll = toUnitDouble(mix64(seed ^ 0x94D049BB133111EBL, cx, cz));
        if (zone == ZoneType.BLACKOUT_ZONE || zone == ZoneType.RED_ROOM) {
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

        if (zone == ZoneType.PILLAR_HALL || zone == ZoneType.PITFALL_HALL) {
            if (roll < config.getFlickerLightChance()) {
                return LightState.FLICKERING;
            }
            if (roll < config.getFlickerLightChance() + config.getDeadLightChance()) {
                return LightState.DEAD;
            }
            return LightState.WORKING;
        }

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
        long seed = effectiveSeed(world);
        int floorY = cfg().getFloorY();
        for (int r = 0; r <= 4; r++) {
            for (int cx = -r; cx <= r; cx++) {
                for (int cz = -r; cz <= r; cz++) {
                    if (!isWallBlock(seed, cx, cz, 2, 2, getZoneType(seed, cx, cz))) {
                        return new Location(world, cx * CELL_SIZE + 2.5, floorY + 1.0, cz * CELL_SIZE + 2.5, -45f, 0f);
                    }
                }
            }
        }
        return new Location(world, 2.5, floorY + 1.0, 2.5, -45f, 0f);
    }

    /**
     * Picks an isolated random corridor spawn across the huge Level 0 map, distanced from existing explorers.
     */
    public Location findRandomFarSpawn(World world, Collection<? extends Player> onlinePlayers, int maxRadius) {
        BackroomsConfig config = cfg();
        long seed = effectiveSeed(world);
        int floorY = config.getFloorY();
        int cellRadius = Math.max(16, maxRadius / CELL_SIZE);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        Location bestFallback = null;
        double bestMinDistSq = -1.0;

        for (int attempt = 0; attempt < 96; attempt++) {
            int cx = rng.nextInt(-cellRadius, cellRadius + 1);
            int cz = rng.nextInt(-cellRadius, cellRadius + 1);
            ZoneType zone = getZoneType(seed, cx, cz);
            if (zone == ZoneType.RED_ROOM || zone == ZoneType.BLACKOUT_ZONE || zone == ZoneType.MANILA_ROOM) {
                continue;
            }
            if (isWallBlock(seed, cx, cz, 2, 2, zone)) {
                continue;
            }
            int wx = cx * CELL_SIZE + 2;
            int wz = cz * CELL_SIZE + 2;
            if (isNaturalNoclipSpot(seed, config, cx, cz, wx, wz, zone)) {
                continue;
            }

            double minDistSq = Double.MAX_VALUE;
            for (Player other : onlinePlayers) {
                if (other.getWorld().equals(world)) {
                    double dx = other.getLocation().getX() - (wx + 0.5);
                    double dz = other.getLocation().getZ() - (wz + 0.5);
                    minDistSq = Math.min(minDistSq, dx * dx + dz * dz);
                }
            }

            float yaw = rng.nextInt(4) * 90f;
            Location candidate = new Location(world, wx + 0.5, floorY + 1.0, wz + 0.5, yaw, 0f);
            if (minDistSq >= 300.0 * 300.0) {
                return candidate;
            }
            if (minDistSq > bestMinDistSq) {
                bestMinDistSq = minDistSq;
                bestFallback = candidate;
            }
        }
        return bestFallback != null ? bestFallback : findSafeSpawn(world);
    }

    public ZoneType getZoneAtBlock(World world, int blockX, int blockZ) {
        long seed = effectiveSeed(world);
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
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return List.of(new Level0Populator(plugin));
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
