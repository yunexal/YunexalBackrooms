package com.yunexal.backrooms;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * Furnishes canonical Level 0 Manila Rooms with an M.E.G. outpost table, crafting station, and manual barrel.
 */
public final class Level0Populator extends BlockPopulator {
    private final YunexalBackroomsPlugin plugin;

    public Level0Populator(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random,
                         int chunkX, int chunkZ, @NotNull LimitedRegion limitedRegion) {
        // Each macro-region is 2x2 chunks (8x8 cells = 32x32 blocks).
        // The Manila chamber core covers local region blocks 12..15 in its (0, 0) quadrant chunk.
        if (Math.floorMod(chunkX, 2) != 0 || Math.floorMod(chunkZ, 2) != 0) {
            return;
        }

        Level0ChunkGenerator generator = plugin.getLevel0Generator();
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        if (generator == null || cfg == null) {
            return;
        }

        int rx = Math.floorDiv(chunkX, 2);
        int rz = Math.floorDiv(chunkZ, 2);
        long seed = cfg.getLevel0Seed() != 0L ? cfg.getLevel0Seed() : worldInfo.getSeed();

        Level0ChunkGenerator.ZoneType zone = generator.getZoneAtRegion(seed, rx, rz);
        if (zone == Level0ChunkGenerator.ZoneType.PITFALL_HALL) {
            int baseX = rx * 32;
            int baseZ = rz * 32;
            int floorY = cfg.getFloorY();
            // Maintenance ladder shaft in the (1,1) corner walkway so players who fall into a pit can climb back out
            int lx = baseX + 5;
            int lz = baseZ + 5;
            for (int y = floorY - 9; y <= floorY; y++) {
                limitedRegion.setType(lx, y, lz, Material.LADDER);
                if (limitedRegion.getBlockData(lx, y, lz) instanceof Ladder ladder) {
                    ladder.setFacing(BlockFace.EAST);
                    limitedRegion.setBlockData(lx, y, lz, ladder);
                }
            }
            return;
        }
        if (zone != Level0ChunkGenerator.ZoneType.MANILA_ROOM) {
            return;
        }

        int baseX = rx * 32;
        int baseZ = rz * 32;
        int y = cfg.getFloorY() + 1;

        int tableX = baseX + 13;
        int tableZ = baseZ + 13;

        limitedRegion.setType(tableX, y, tableZ, Material.OAK_FENCE);
        limitedRegion.setType(tableX, y + 1, tableZ, Material.OAK_PRESSURE_PLATE);
        limitedRegion.setType(tableX + 1, y, tableZ, Material.OAK_FENCE);
        limitedRegion.setType(tableX + 1, y + 1, tableZ, Material.LANTERN);

        limitedRegion.setType(tableX - 1, y, tableZ, Material.CRAFTING_TABLE);
        limitedRegion.setType(tableX + 2, y, tableZ, Material.BARREL);
        limitedRegion.setType(tableX, y, tableZ + 2, Material.OAK_STAIRS);

        BlockState state = limitedRegion.getBlockState(tableX + 2, y, tableZ);
        if (state instanceof Barrel barrel) {
            fillManilaBarrelLoot(barrel.getSnapshotInventory(), barrel, random,
                    plugin.getItems(), new NamespacedKey(plugin, "manila_stocked"));
            try {
                barrel.update(true, false);
            } catch (RuntimeException ignored) {
                // Handled atomically on first interact in BackroomsListeners if LimitedRegion defers TE commits.
            }
        }
    }

    public static void fillManilaBarrelLoot(Inventory inv, Barrel barrel, Random rng,
                                            BackroomsItems items, NamespacedKey stockedKey) {
        barrel.getPersistentDataContainer().set(stockedKey, PersistentDataType.BYTE, (byte) 1);
        inv.clear();
        inv.setItem(4, items.createMegManualBook());
        inv.setItem(10, items.wallpaperScrap(2 + rng.nextInt(3)));
        inv.setItem(12, items.baseboardPlank(2 + rng.nextInt(3)));
        inv.setItem(14, items.carpetFiber(2 + rng.nextInt(2)));
        inv.setItem(16, items.bandage(1));
        if (rng.nextDouble() < 0.65) {
            inv.setItem(21, items.metalPlate(1 + rng.nextInt(2)));
        }
        if (rng.nextDouble() < 0.50) {
            inv.setItem(22, items.copperWire(1));
        }
        if (rng.nextDouble() < 0.45) {
            inv.setItem(23, items.marker(2));
        }
        if (rng.nextDouble() < 0.35) {
            inv.setItem(24, items.almondWater(1));
        }
        if (rng.nextDouble() < 0.25) {
            inv.setItem(25, items.brokenCompass());
        }
    }
}
