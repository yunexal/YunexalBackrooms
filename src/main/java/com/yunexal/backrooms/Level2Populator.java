package com.yunexal.backrooms;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlockState;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * Furnishes Level 2 Machinery Bays (dormant industrial turbines, boilers & tool lockers)
 * and M.E.G. Survivor Office refuges with manuals and Almond Water.
 */
public final class Level2Populator extends BlockPopulator {
    private final YunexalBackroomsPlugin plugin;

    public Level2Populator(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random,
                         int chunkX, int chunkZ, @NotNull LimitedRegion region) {
        if (Math.floorMod(chunkX, 2) != 0 || Math.floorMod(chunkZ, 2) != 0) {
            return;
        }
        Level2ChunkGenerator gen = plugin.getLevel2Generator();
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        if (gen == null || cfg == null) {
            return;
        }

        int rx = Math.floorDiv(chunkX, 2);
        int rz = Math.floorDiv(chunkZ, 2);
        long seed = cfg.getLevel2Seed() != 0L ? cfg.getLevel2Seed() : worldInfo.getSeed();
        Level2ChunkGenerator.Level2ZoneType zone = gen.getZoneAtRegion(seed, rx, rz);

        int bx = rx * 32;
        int bz = rz * 32;
        int y = cfg.getLevel2FloorY() + 1;
        NamespacedKey crateKey = new NamespacedKey(plugin, "level2_crate");

        if (zone == Level2ChunkGenerator.Level2ZoneType.SURVIVOR_OFFICE) {
            region.setType(bx + 13, y, bz + 13, Material.CRAFTING_TABLE);
            region.setType(bx + 14, y, bz + 13, Material.OAK_FENCE);
            region.setType(bx + 14, y + 1, bz + 13, Material.LANTERN);
            region.setType(bx + 15, y, bz + 13, Material.BARREL);
            if (region.getBlockState(bx + 15, y, bz + 13) instanceof Barrel barrel) {
                barrel.getPersistentDataContainer().set(crateKey, PersistentDataType.BYTE, (byte) 1);
                Inventory inv = barrel.getSnapshotInventory();
                inv.clear();
                inv.setItem(0, plugin.getItems().createLevel2ManualBook());
                inv.setItem(2, plugin.getItems().almondWater(3));
                inv.setItem(4, plugin.getItems().cannedRation(2));
                inv.setItem(6, plugin.getItems().bandage(2));
                inv.setItem(8, plugin.getItems().batteryCell(2));
                try {
                    barrel.update(true, false);
                } catch (RuntimeException ignored) {
                }
            }
            return;
        }

        if (zone == Level2ChunkGenerator.Level2ZoneType.MACHINERY_BAY) {
            // Heavy dormant industrial pump / boiler machinery block assembly
            region.setType(bx + 13, y, bz + 13, Material.BLAST_FURNACE);
            region.setType(bx + 13, y + 1, bz + 13, Material.IRON_BLOCK);
            region.setType(bx + 13, y + 2, bz + 13, Material.COPPER_BLOCK);
            region.setType(bx + 14, y, bz + 13, Material.IRON_BLOCK);
            region.setType(bx + 14, y + 1, bz + 13, Material.PISTON);

            placeUtilityLocker(region, bx + 15, y, bz + 13, random, crateKey);
        } else if (random.nextDouble() < 0.45) {
            placeUtilityLocker(region, bx + 6 + random.nextInt(20), y, bz + 6 + random.nextInt(20), random, crateKey);
        }
    }

    private void placeUtilityLocker(LimitedRegion r, int x, int y, int z, Random rng, NamespacedKey crateKey) {
        if (!r.getType(x, y, z).isAir() || r.getType(x, y - 1, z).isAir()) {
            return;
        }
        r.setType(x, y, z, Material.BARREL);
        BlockState state = r.getBlockState(x, y, z);
        if (state instanceof Barrel barrel) {
            fillUtilityLockerLoot(barrel.getSnapshotInventory(), barrel, rng, plugin.getItems(), crateKey);
            try {
                barrel.update(true, false);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public static void fillUtilityLockerLoot(Inventory inv, Barrel barrel, Random rng,
                                             BackroomsItems items, NamespacedKey crateKey) {
        barrel.getPersistentDataContainer().set(crateKey, PersistentDataType.BYTE, (byte) 1);
        inv.clear();
        if (rng.nextDouble() < 0.25) {
            return;
        }
        if (rng.nextDouble() < 0.70) inv.setItem(1, items.almondWater(1 + (rng.nextDouble() < 0.35 ? 1 : 0)));
        if (rng.nextDouble() < 0.55) inv.setItem(3, items.metalPlate(1 + rng.nextInt(2)));
        if (rng.nextDouble() < 0.48) inv.setItem(5, items.copperWire(1 + rng.nextInt(2)));
        if (rng.nextDouble() < 0.40) inv.setItem(7, items.batteryCell(1));
        if (rng.nextDouble() < 0.32) inv.setItem(10, items.cannedRation(1));
        if (rng.nextDouble() < 0.25) inv.setItem(12, items.bandage(1));
        if (rng.nextDouble() < 0.22) inv.setItem(14, items.looseCoins(2));
        if (rng.nextDouble() < 0.12) inv.setItem(16, items.crowbar());
    }
}
