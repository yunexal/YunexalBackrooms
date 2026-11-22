package com.yunexal.backrooms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages live Backrooms Level 1 ("Habitable Zone") mechanics:
 * - World initialization & border/gamerule alignment
 * - Global flickering -> Blackout power outage cycles ("Мерехтіння") + hostile escalation
 * - Materializing & dematerializing Supply Crates ("Ящики")
 * - Low industrial floor mist ("Низький туман")
 * - Humanity Outpost NPCs (M.E.G. Base Alpha, B.N.T.G. Trader's Keep, Tom's Diner)
 */
public final class Level1Manager implements Listener {
    public enum BlackoutPhase {
        LIT,
        FLICKERING,
        BLACKOUT
    }

    private record DynamicCrate(int x, int y, int z, long expiresAtMs) {}

    private final YunexalBackroomsPlugin plugin;
    private final NamespacedKey crateKey;
    private final NamespacedKey dynamicCrateKey;
    private final NamespacedKey npcRoleKey;

    private final List<DynamicCrate> dynamicCrates = new ArrayList<>();
    private final Map<UUID, Set<Location>> activeDimmedLamps = new ConcurrentHashMap<>();

    private World level1World;
    private BlackoutPhase blackoutPhase = BlackoutPhase.LIT;
    private long nextPhaseChangeMs = 0L;
    private long lastCrateSpawnMs = 0L;

    private BukkitTask mainTask;
    private BukkitTask ambientFogTask;

    public Level1Manager(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
        this.crateKey = new NamespacedKey(plugin, "level1_crate");
        this.dynamicCrateKey = new NamespacedKey(plugin, "level1_dynamic_crate");
        this.npcRoleKey = new NamespacedKey(plugin, "level1_npc_role");
    }

    public World ensureLevel1World() {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        World existing = Bukkit.getWorld(cfg.getLevel1WorldName());
        if (existing != null) {
            configureRules(existing, cfg);
            this.level1World = existing;
            return existing;
        }

        WorldCreator creator = new WorldCreator(cfg.getLevel1WorldName());
        creator.environment(World.Environment.NORMAL);
        creator.type(WorldType.FLAT);
        creator.generateStructures(false);
        creator.generator(plugin.getLevel1Generator());
        World created = creator.createWorld();
        if (created != null) {
            configureRules(created, cfg);
            this.level1World = created;
        }
        return created;
    }

    private void configureRules(World w, BackroomsConfig cfg) {
        w.setTime(18000L);
        w.setDifficulty(org.bukkit.Difficulty.NORMAL);
        w.setGameRule(org.bukkit.GameRules.ADVANCE_TIME, false);
        w.setGameRule(org.bukkit.GameRules.ADVANCE_WEATHER, false);
        w.setGameRule(org.bukkit.GameRules.SPAWN_MOBS, false);
        w.setGameRule(org.bukkit.GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        w.setGameRule(org.bukkit.GameRules.SHOW_DEATH_MESSAGES, false);
        w.setGameRule(org.bukkit.GameRules.KEEP_INVENTORY, false);
        w.setGameRule(org.bukkit.GameRules.LOCATOR_BAR, false);
        w.setGameRule(org.bukkit.GameRules.REDUCED_DEBUG_INFO, true);
        w.setViewDistance(cfg.getViewDistance());
        w.setSendViewDistance(cfg.getViewDistance());
        w.setSimulationDistance(cfg.getSimulationDistance());
        WorldBorder border = w.getWorldBorder();
        border.setCenter(0.0, 0.0);
        border.setSize(cfg.getLevel1WorldBorderSize());
    }

    public World getLevel1World() {
        return level1World != null ? level1World : Bukkit.getWorld(plugin.getBackroomsConfig().getLevel1WorldName());
    }

    public BlackoutPhase getBlackoutPhase() {
        return blackoutPhase;
    }

    public void start() {
        shutdown();
        ensureLevel1World();
        long now = System.currentTimeMillis();
        nextPhaseChangeMs = now + plugin.getBackroomsConfig().getLevel1BlackoutIntervalSeconds() * 1000L;
        lastCrateSpawnMs = now;
        mainTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickMain, 10L, 10L);
        ambientFogTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFogAndPuddles, 15L, 15L);
    }

    public void shutdown() {
        if (mainTask != null) {
            mainTask.cancel();
            mainTask = null;
        }
        if (ambientFogTask != null) {
            ambientFogTask.cancel();
            ambientFogTask = null;
        }
        restoreAllDimmedLamps();
    }

    public void triggerFlickerNow() {
        blackoutPhase = BlackoutPhase.FLICKERING;
        nextPhaseChangeMs = System.currentTimeMillis() + 8_000L;
        World l1 = getLevel1World();
        if (l1 != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld().equals(l1)) {
                    p.playSound(p, "minecraft:backrooms.ambient.flicker", SoundCategory.AMBIENT, 0.45f, 1.0f);
                    p.sendActionBar(Component.text("Лампи комплексу тривожно мерехтять!", NamedTextColor.YELLOW));
                }
            }
        }
    }

    private void tickMain() {
        World l1 = getLevel1World();
        if (l1 == null) {
            return;
        }
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        long now = System.currentTimeMillis();

        // 1. Advance Blackout ("Мерехтіння") cycle
        if (now >= nextPhaseChangeMs) {
            switch (blackoutPhase) {
                case LIT -> {
                    blackoutPhase = BlackoutPhase.FLICKERING;
                    nextPhaseChangeMs = now + 9_000L;
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getWorld().equals(l1)) {
                            p.playSound(p, "minecraft:backrooms.ambient.flicker", SoundCategory.AMBIENT, 0.40f, 1.0f);
                            p.sendActionBar(Component.text("Увага: освітлення Рівня 1 починає мерехтіти...", NamedTextColor.YELLOW));
                        }
                    }
                }
                case FLICKERING -> {
                    blackoutPhase = BlackoutPhase.BLACKOUT;
                    nextPhaseChangeMs = now + cfg.getLevel1BlackoutDurationSeconds() * 1000L;
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getWorld().equals(l1)) {
                            p.playSound(p, "minecraft:backrooms.fx.blackout_breaker", SoundCategory.AMBIENT, 0.65f, 1.0f);
                            p.sendActionBar(Component.text("ВИМКНЕННЯ СВІТЛА! Дістаньте ліхтар або повертайтесь на базу!", NamedTextColor.RED));
                        }
                    }
                }
                case BLACKOUT -> {
                    blackoutPhase = BlackoutPhase.LIT;
                    nextPhaseChangeMs = now + cfg.getLevel1BlackoutIntervalSeconds() * 1000L;
                    restoreAllDimmedLamps();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getWorld().equals(l1)) {
                            p.playSound(p, "minecraft:backrooms.fx.light_switch", SoundCategory.AMBIENT, 0.55f, 0.9f);
                            p.sendActionBar(Component.text("Живлення відновлено — лампи знову гудуть.", NamedTextColor.GREEN));
                        }
                    }
                }
            }
        }

        // 2. Apply client-side light flickering / dimming + Darkness outside safe outposts during blackout
        int ceilingY = cfg.getLevel1CeilingY();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(l1) || !plugin.getLimboManager().isVerified(p)) {
                clearDimmedFor(p);
                continue;
            }
            updateLampOverlayForPlayer(p, l1, ceilingY);

            Level1ChunkGenerator.Level1ZoneType zone = plugin.getLevel1Generator()
                    .getZoneAtBlock(l1, p.getLocation().getBlockX(), p.getLocation().getBlockZ());
            if (Level1ChunkGenerator.isOutpostZone(zone)) {
                ensureOutpostNpcNear(l1, zone, p.getLocation().getBlockX(), p.getLocation().getBlockZ());
            }
            if (Level1ChunkGenerator.isOutpostZone(zone) && plugin.getLevel1Generator().isInProtectedOutpostCore(l1,
                    p.getLocation().getBlockX(), p.getLocation().getBlockZ())) {
                p.removePotionEffect(PotionEffectType.DARKNESS);
                continue;
            }

            if (blackoutPhase == BlackoutPhase.BLACKOUT) {
                boolean holdingLight = plugin.getItems().isItem(p.getInventory().getItemInMainHand(), BackroomsItems.ID_FLASHLIGHT)
                        || plugin.getItems().isItem(p.getInventory().getItemInOffHand(), BackroomsItems.ID_FLASHLIGHT);
                if (!holdingLight) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 45, 0, false, false, false));
                }
            }
        }

        // 3. Dynamic Supply Crates (materialize and vanish randomly over time)
        tickDynamicCrates(l1, cfg, now);
    }

    private void updateLampOverlayForPlayer(Player player, World l1, int ceilingY) {
        if (blackoutPhase == BlackoutPhase.LIT) {
            clearDimmedFor(player);
            return;
        }
        Set<Location> tracked = activeDimmedLamps.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
        Location pLoc = player.getLocation();
        int px = pLoc.getBlockX();
        int pz = pLoc.getBlockZ();

        // Restore stale blocks moved out of range or during lit phase of FLICKERING
        boolean toggleDead = (blackoutPhase == BlackoutPhase.BLACKOUT) || ThreadLocalRandom.current().nextDouble() < 0.55;
        if (!toggleDead) {
            clearDimmedFor(player);
            return;
        }

        BlockData deadData = Material.POLISHED_ANDESITE.createBlockData();
        for (int dx = -16; dx <= 16; dx += 2) {
            for (int dz = -16; dz <= 16; dz += 2) {
                int bx = px + dx;
                int bz = pz + dz;
                if (plugin.getLevel1Generator().isInProtectedOutpostCore(l1, bx, bz)) {
                    continue;
                }
                Block b = l1.getBlockAt(bx, ceilingY, bz);
                if (b.getType() == Material.OCHRE_FROGLIGHT || b.getType() == Material.SEA_LANTERN) {
                    player.sendBlockChange(b.getLocation(), deadData);
                    tracked.add(b.getLocation());
                }
            }
        }
    }

    private void restoreAllDimmedLamps() {
        for (UUID uid : new ArrayList<>(activeDimmedLamps.keySet())) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                clearDimmedFor(p);
            } else {
                activeDimmedLamps.remove(uid);
            }
        }
    }

    private void clearDimmedFor(Player player) {
        Set<Location> locs = activeDimmedLamps.remove(player.getUniqueId());
        if (locs == null || !player.isOnline()) {
            return;
        }
        for (Location loc : locs) {
            if (loc.getWorld() != null && loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                player.sendBlockChange(loc, loc.getBlock().getBlockData());
            }
        }
    }

    private void tickDynamicCrates(World l1, BackroomsConfig cfg, long now) {
        Iterator<DynamicCrate> it = dynamicCrates.iterator();
        while (it.hasNext()) {
            DynamicCrate dc = it.next();
            if (now < dc.expiresAtMs()) {
                continue;
            }
            if (!l1.isChunkLoaded(dc.x() >> 4, dc.z() >> 4)) {
                continue;
            }
            Block b = l1.getBlockAt(dc.x(), dc.y(), dc.z());
            if (b.getType() != Material.BARREL) {
                it.remove();
                continue;
            }
            // Do not vanish while a player is looking right inside it or standing right beside it
            boolean busy = false;
            Location crateLoc = new Location(l1, dc.x() + 0.5, dc.y() + 0.5, dc.z() + 0.5);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld().equals(l1) && p.getLocation().distanceSquared(crateLoc) < 12.0 * 12.0) {
                    busy = true;
                    break;
                }
            }
            if (!busy) {
                b.setType(Material.AIR, false);
                it.remove();
            }
        }

        if (now - lastCrateSpawnMs < cfg.getLevel1DynamicCrateIntervalSeconds() * 1000L || dynamicCrates.size() >= 18) {
            return;
        }
        lastCrateSpawnMs = now;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(l1) || !plugin.getLimboManager().isVerified(p)) {
                continue;
            }
            if (rng.nextDouble() > 0.65) {
                continue;
            }
            int floorY = cfg.getLevel1FloorY() + 1;
            for (int attempt = 0; attempt < 12; attempt++) {
                double ang = rng.nextDouble() * Math.PI * 2.0;
                double dist = 14.0 + rng.nextDouble() * 14.0;
                int x = (int) Math.floor(p.getLocation().getX() + Math.cos(ang) * dist);
                int z = (int) Math.floor(p.getLocation().getZ() + Math.sin(ang) * dist);
                if (!l1.isChunkLoaded(x >> 4, z >> 4) || plugin.getLevel1Generator().isInProtectedOutpostCore(l1, x, z)) {
                    continue;
                }
                Block floor = l1.getBlockAt(x, floorY - 1, z);
                Block spot = l1.getBlockAt(x, floorY, z);
                Block above = l1.getBlockAt(x, floorY + 1, z);
                if (floor.getType() != Material.WATER && floor.getType().isSolid()
                        && spot.getType().isAir() && above.getType().isAir()) {
                    spot.setType(Material.BARREL, false);
                    if (spot.getState() instanceof Barrel barrel) {
                        barrel.getPersistentDataContainer().set(dynamicCrateKey, PersistentDataType.BYTE, (byte) 1);
                        Level1Populator.fillLevel1CrateLoot(barrel.getInventory(), barrel, rng, plugin.getItems(), crateKey);
                        barrel.update(true, false);
                    }
                    dynamicCrates.add(new DynamicCrate(x, floorY, z,
                            now + cfg.getLevel1DynamicCrateLifetimeSeconds() * 1000L));
                    break;
                }
            }
            break;
        }
    }

    private void tickFogAndPuddles() {
        World l1 = getLevel1World();
        if (l1 == null) {
            return;
        }
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        int floorY = cfg.getLevel1FloorY();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(l1) || !plugin.getLimboManager().isVerified(p)) {
                continue;
            }
            Location pLoc = p.getLocation();

            if (cfg.isLevel1FogParticles()) {
                int wisps = (blackoutPhase == BlackoutPhase.BLACKOUT) ? 10 : 6;
                for (int i = 0; i < wisps; i++) {
                    double ox = (rng.nextDouble() - 0.5) * 16.0;
                    double oz = (rng.nextDouble() - 0.5) * 16.0;
                    p.spawnParticle(Particle.CLOUD, pLoc.getX() + ox, floorY + 1.15 + rng.nextDouble() * 0.45,
                            pLoc.getZ() + oz, 1, 0.4, 0.05, 0.4, 0.002);
                }
            }

            if (rng.nextDouble() < 0.08) {
                p.playSound(pLoc.clone().add((rng.nextDouble() - 0.5) * 10, 1.5, (rng.nextDouble() - 0.5) * 10),
                        Sound.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, SoundCategory.AMBIENT, 0.35f, 0.9f);
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World l1 = getLevel1World();
        if (l1 == null || !event.getWorld().equals(l1)) {
            return;
        }
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        if (Math.floorMod(chunkX, 2) != 0 || Math.floorMod(chunkZ, 2) != 0) {
            return;
        }

        int rx = Math.floorDiv(chunkX, 2);
        int rz = Math.floorDiv(chunkZ, 2);
        long seed = plugin.getBackroomsConfig().getLevel1Seed();
        Level1ChunkGenerator.Level1ZoneType zone = plugin.getLevel1Generator().getZoneAtRegion(seed, rx, rz);
        if (!Level1ChunkGenerator.isOutpostZone(zone)) {
            return;
        }

        Location npcLoc = new Location(l1, rx * 32 + 16.5, plugin.getBackroomsConfig().getLevel1FloorY() + 1.0, rz * 32 + 13.5, 0f, 0f);
        ensureOutpostNpcAtRegion(l1, zone, rx, rz, npcLoc);
    }

    private void ensureOutpostNpcNear(World l1, Level1ChunkGenerator.Level1ZoneType zone, int bx, int bz) {
        int rx = Math.floorDiv(Math.floorDiv(bx, Level1ChunkGenerator.CELL_SIZE), Level1ChunkGenerator.REGION_CELLS);
        int rz = Math.floorDiv(Math.floorDiv(bz, Level1ChunkGenerator.CELL_SIZE), Level1ChunkGenerator.REGION_CELLS);
        Location npcLoc = new Location(l1, rx * 32 + 16.5, plugin.getBackroomsConfig().getLevel1FloorY() + 1.0, rz * 32 + 13.5, 0f, 0f);
        ensureOutpostNpcAtRegion(l1, zone, rx, rz, npcLoc);
    }

    private void ensureOutpostNpcAtRegion(World l1, Level1ChunkGenerator.Level1ZoneType zone, int rx, int rz, Location npcLoc) {
        String expectedRole = zone.name();
        for (Entity e : l1.getNearbyEntities(npcLoc, 8.0, 4.0, 8.0)) {
            if (e instanceof Villager v && expectedRole.equals(v.getPersistentDataContainer().get(npcRoleKey, PersistentDataType.STRING))) {
                return;
            }
        }
        spawnOutpostNpc(npcLoc, zone);
    }

    private void spawnOutpostNpc(Location loc, Level1ChunkGenerator.Level1ZoneType zone) {
        loc.getWorld().spawn(loc, Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setRemoveWhenFarAway(false);
            v.getPersistentDataContainer().set(npcRoleKey, PersistentDataType.STRING, zone.name());

            BackroomsItems items = plugin.getItems();
            switch (zone) {
                case MEG_BASE_ALPHA -> {
                    v.setProfession(Villager.Profession.CARTOGRAPHER);
                    v.customName(Component.text("Командир M.E.G. [База Альфа]", NamedTextColor.AQUA));
                    v.setRecipes(List.of(
                            trade(items.looseCoins(3), items.almondWater(1), 16),
                            trade(items.looseCoins(4), items.cannedRation(2), 16),
                            trade(items.looseCoins(5), items.bandage(2), 16),
                            trade(items.rustyAutopart(3), items.flashlight(), 8),
                            trade(items.looseCoins(2), items.createLevel1ManualBook(), 12)
                    ));
                }
                case BNTG_TRADER_KEEP -> {
                    v.setProfession(Villager.Profession.WEAPONSMITH);
                    v.customName(Component.text("Торговець B.N.T.G. [Дім Торговця]", NamedTextColor.GOLD));
                    v.setRecipes(List.of(
                            trade(items.looseCoins(3), items.almondWater(1), 24),
                            trade(items.batteryCell(2), items.cannedRation(2), 24),
                            trade(items.rustyAutopart(2), items.looseCoins(3), 24),
                            trade(items.looseCoins(7), items.crowbar(), 8),
                            trade(items.looseCoins(10), items.warehouseMachete(), 8),
                            trade(items.looseCoins(8), items.explorerHelmet(), 8),
                            trade(items.looseCoins(4), new ItemStack(Material.BARREL, 2), 16),
                            trade(items.looseCoins(3), new ItemStack(Material.TORCH, 8), 24)
                    ));
                }
                case TOMS_DINER -> {
                    v.setProfession(Villager.Profession.BUTCHER);
                    v.customName(Component.text("Шеф-кухар Том [Їдальня Тома]", NamedTextColor.YELLOW));
                    v.setRecipes(List.of(
                            trade(items.looseCoins(2), items.cannedRation(2), 24),
                            trade(items.looseCoins(2), new ItemStack(Material.BREAD, 4), 24),
                            trade(items.looseCoins(3), items.almondWater(1), 24),
                            trade(items.rustyAutopart(2), new ItemStack(Material.BAKED_POTATO, 4), 24)
                    ));
                }
                default -> { }
            }
            v.setCustomNameVisible(true);
        });
    }

    private static MerchantRecipe trade(ItemStack cost, ItemStack result, int maxUses) {
        MerchantRecipe recipe = new MerchantRecipe(result, maxUses);
        recipe.addIngredient(cost);
        return recipe;
    }

    @EventHandler(ignoreCancelled = true)
    public void onNpcDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(npcRoleKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Villager v
                && v.getPersistentDataContainer().has(npcRoleKey, PersistentDataType.STRING)) {
            String role = v.getPersistentDataContainer().get(npcRoleKey, PersistentDataType.STRING);
            Player p = event.getPlayer();
            if ("MEG_BASE_ALPHA".equals(role)) {
                p.sendActionBar(Component.text("M.E.G.: «Ласкаво просимо на Базу Альфа. Стежте за світлом під час Мерехтіння!»", NamedTextColor.AQUA));
            } else if ("BNTG_TRADER_KEEP".equals(role)) {
                p.sendActionBar(Component.text("B.N.T.G.: «Приніс монети, батарейки чи запчастини з ящиків? Обміняємося!»", NamedTextColor.GOLD));
            } else if ("TOMS_DINER".equals(role)) {
                p.sendActionBar(Component.text("Том: «Сідай, перепочинь — у нас є тепла їжа та Мигдальна вода.»", NamedTextColor.YELLOW));
            }
        }
    }
}
