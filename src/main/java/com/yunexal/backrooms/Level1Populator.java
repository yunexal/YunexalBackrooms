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
 * Populates Backrooms Level 1 ("Habitable Zone") supply crates (with canonical useful supplies
 * and bizarre junk items from the Wiki) as well as humanity outposts: M.E.G. Base Alpha,
 * B.N.T.G. Trader's Keep, and Tom's Diner.
 */
public final class Level1Populator extends BlockPopulator {
    private final YunexalBackroomsPlugin plugin;

    public Level1Populator(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random,
                         int chunkX, int chunkZ, @NotNull LimitedRegion region) {
        if (Math.floorMod(chunkX, 2) != 0 || Math.floorMod(chunkZ, 2) != 0) {
            return;
        }

        Level1ChunkGenerator gen = plugin.getLevel1Generator();
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        if (gen == null || cfg == null) {
            return;
        }

        int rx = Math.floorDiv(chunkX, 2);
        int rz = Math.floorDiv(chunkZ, 2);
        long seed = cfg.getLevel1Seed() != 0L ? cfg.getLevel1Seed() : worldInfo.getSeed();
        Level1ChunkGenerator.Level1ZoneType zone = gen.getZoneAtRegion(seed, rx, rz);

        int baseX = rx * 32;
        int baseZ = rz * 32;
        int y = cfg.getLevel1FloorY() + 1;
        NamespacedKey crateKey = new NamespacedKey(plugin, "level1_crate");

        switch (zone) {
            case MEG_BASE_ALPHA -> populateBaseAlpha(region, baseX, y, baseZ, random, crateKey);
            case BNTG_TRADER_KEEP -> populateTraderKeep(region, baseX, y, baseZ, random, crateKey);
            case TOMS_DINER -> populateTomsDiner(region, baseX, y, baseZ, random, crateKey);
            default -> {
                if (random.nextDouble() < cfg.getLevel1CrateChancePerRegion()) {
                    int count = 1 + random.nextInt(3);
                    for (int i = 0; i < count; i++) {
                        int cx = baseX + 2 + random.nextInt(28);
                        int cz = baseZ + 2 + random.nextInt(28);
                        placeAndStockCrate(region, cx, y, cz, random, crateKey);
                    }
                }
            }
        }
    }

    private void populateBaseAlpha(LimitedRegion r, int bx, int y, int bz, Random rng, NamespacedKey crateKey) {
        BackroomsItems items = plugin.getItems();
        // Warm permanent lanterns & command table
        r.setType(bx + 15, y, bz + 15, Material.OAK_FENCE);
        r.setType(bx + 15, y + 1, bz + 15, Material.LANTERN);
        r.setType(bx + 16, y, bz + 15, Material.OAK_FENCE);
        r.setType(bx + 16, y + 1, bz + 15, Material.OAK_PRESSURE_PLATE);
        r.setType(bx + 14, y, bz + 15, Material.CRAFTING_TABLE);
        r.setType(bx + 17, y, bz + 15, Material.FURNACE);
        r.setType(bx + 10, y, bz + 10, Material.LANTERN);
        r.setType(bx + 21, y, bz + 21, Material.LANTERN);

        // Outpost bunker supplies & M.E.G. Level 1 dossier barrel
        r.setType(bx + 16, y, bz + 17, Material.BARREL);
        if (r.getBlockState(bx + 16, y, bz + 17) instanceof Barrel barrel) {
            barrel.getPersistentDataContainer().set(crateKey, PersistentDataType.BYTE, (byte) 1);
            Inventory inv = barrel.getSnapshotInventory();
            inv.clear();
            inv.setItem(0, items.createLevel1ManualBook());
            inv.setItem(2, items.almondWater(2));
            inv.setItem(4, items.cannedRation(2));
            inv.setItem(6, items.bandage(2));
            inv.setItem(8, new ItemStack(Material.TORCH, 6));
            try {
                barrel.update(true, false);
            } catch (RuntimeException ignored) {
            }
        }

        // Stacked storage crates along base perimeter
        placeAndStockCrate(r, bx + 10, y, bz + 21, rng, crateKey);
        placeAndStockCrate(r, bx + 21, y, bz + 10, rng, crateKey);
    }

    private void populateTraderKeep(LimitedRegion r, int bx, int y, int bz, Random rng, NamespacedKey crateKey) {
        // Trading counter & warm stall lamps
        for (int dx = 14; dx <= 18; dx++) {
            r.setType(bx + dx, y, bz + 14, Material.OAK_PLANKS);
            r.setType(bx + dx, y + 3, bz + 14, Material.OAK_SLAB);
        }
        r.setType(bx + 14, y + 1, bz + 14, Material.OAK_FENCE);
        r.setType(bx + 14, y + 2, bz + 14, Material.OAK_FENCE);
        r.setType(bx + 18, y + 1, bz + 14, Material.OAK_FENCE);
        r.setType(bx + 18, y + 2, bz + 14, Material.OAK_FENCE);
        r.setType(bx + 14, y + 1, bz + 15, Material.LANTERN);
        r.setType(bx + 18, y + 1, bz + 15, Material.LANTERN);

        placeAndStockCrate(r, bx + 11, y, bz + 11, rng, crateKey);
        placeAndStockCrate(r, bx + 11, y + 1, bz + 11, rng, crateKey);
        placeAndStockCrate(r, bx + 20, y, bz + 19, rng, crateKey);
    }

    private void populateTomsDiner(LimitedRegion r, int bx, int y, int bz, Random rng, NamespacedKey crateKey) {
        // Kitchen counter + warm diner lighting + pantry crates
        for (int dx = 13; dx <= 18; dx++) {
            r.setType(bx + dx, y, bz + 14, Material.SMOOTH_STONE);
        }
        r.setType(bx + 14, y, bz + 14, Material.SMOKER);
        r.setType(bx + 17, y, bz + 14, Material.BARREL);
        if (r.getBlockState(bx + 17, y, bz + 14) instanceof Barrel barrel) {
            barrel.getPersistentDataContainer().set(crateKey, PersistentDataType.BYTE, (byte) 1);
            Inventory inv = barrel.getSnapshotInventory();
            inv.setItem(1, plugin.getItems().cannedRation(2));
            inv.setItem(3, new ItemStack(Material.BREAD, 3));
            inv.setItem(5, plugin.getItems().almondWater(2));
            try {
                barrel.update(true, false);
            } catch (RuntimeException ignored) {
            }
        }
        r.setType(bx + 13, y + 1, bz + 14, Material.LANTERN);
        r.setType(bx + 18, y + 1, bz + 14, Material.LANTERN);
        // Diner booths
        r.setType(bx + 13, y, bz + 18, Material.OAK_FENCE);
        r.setType(bx + 13, y + 1, bz + 18, Material.OAK_PRESSURE_PLATE);
        r.setType(bx + 14, y, bz + 18, Material.OAK_STAIRS);
        r.setType(bx + 18, y, bz + 18, Material.OAK_FENCE);
        r.setType(bx + 18, y + 1, bz + 18, Material.OAK_PRESSURE_PLATE);
    }

    private void placeAndStockCrate(LimitedRegion r, int x, int y, int z, Random rng, NamespacedKey crateKey) {
        if (!r.getType(x, y, z).isAir()) {
            return;
        }
        Material floor = r.getType(x, y - 1, z);
        if (floor == Material.WATER || floor.isAir()) {
            return;
        }
        r.setType(x, y, z, Material.BARREL);
        BlockState state = r.getBlockState(x, y, z);
        if (state instanceof Barrel barrel) {
            fillLevel1CrateLoot(barrel.getSnapshotInventory(), barrel, rng, plugin.getItems(), crateKey);
            try {
                barrel.update(true, false);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Populates a Level 1 Crate following Fandom Wiki canon: some crates are empty/ransacked,
     * some hold life-saving M.E.G. supplies, and some contain bizarre junk (coins, car parts,
     * used syringes, crayons, burnt paper, shoelaces, catatonic mice).
     */
    public static void fillLevel1CrateLoot(Inventory inv, Barrel barrel, Random rng,
                                           BackroomsItems items, NamespacedKey crateKey) {
        barrel.getPersistentDataContainer().set(crateKey, PersistentDataType.BYTE, (byte) 1);
        inv.clear();

        double archetype = rng.nextDouble();
        if (archetype < 0.20) {
            // Empty or near-empty looted crate
            if (rng.nextDouble() < 0.35) {
                inv.setItem(rng.nextInt(inv.getSize()), items.burntPaper(1));
            }
            return;
        }

        if (archetype < 0.36) {
            // Rare bizarre / creepy Wiki crate
            putRandom(inv, rng, items.usedSyringe(1));
            putRandom(inv, rng, rng.nextBoolean() ? items.catatonicMouse(1) : items.crayonBox(1));
            if (rng.nextBoolean()) putRandom(inv, rng, items.rustyAutopart(1 + rng.nextInt(2)));
            if (rng.nextDouble() < 0.55) putRandom(inv, rng, items.looseCoins(1 + rng.nextInt(2)));
            return;
        }

        // Standard Survival Supply + Oddities Crate
        if (rng.nextDouble() < 0.65) putRandom(inv, rng, items.almondWater(1 + (rng.nextDouble() < 0.28 ? 1 : 0)));
        if (rng.nextDouble() < 0.62) putRandom(inv, rng, items.cannedRation(1 + rng.nextInt(2)));
        if (rng.nextDouble() < 0.50) putRandom(inv, rng, items.looseCoins(1 + rng.nextInt(3)));
        if (rng.nextDouble() < 0.42) putRandom(inv, rng, items.batteryCell(1 + rng.nextInt(2)));
        if (rng.nextDouble() < 0.42) putRandom(inv, rng, items.bandage(1));
        if (rng.nextDouble() < 0.48) putRandom(inv, rng, items.rustyAutopart(1));
        if (rng.nextDouble() < 0.35) putRandom(inv, rng, items.carpetFiber(1 + rng.nextInt(2))); // shoelaces / string
        if (rng.nextDouble() < 0.25) putRandom(inv, rng, items.burntPaper(1));
        if (rng.nextDouble() < 0.22) putRandom(inv, rng, new ItemStack(Material.TORCH, 2 + rng.nextInt(3)));
        if (rng.nextDouble() < 0.14) putRandom(inv, rng, items.flashlight());
        if (rng.nextDouble() < 0.12) putRandom(inv, rng, items.crowbar());
        if (rng.nextDouble() < 0.09) putRandom(inv, rng, items.warehouseMachete());
        if (rng.nextDouble() < 0.08) putRandom(inv, rng, items.explorerHelmet());
    }

    private static void putRandom(Inventory inv, Random rng, ItemStack stack) {
        int slot = rng.nextInt(inv.getSize());
        for (int tries = 0; tries < 6 && inv.getItem(slot) != null; tries++) {
            slot = rng.nextInt(inv.getSize());
        }
        inv.setItem(slot, stack);
    }
}
