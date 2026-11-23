package com.yunexal.backrooms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Candle;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles canonical Level 0 gameplay: entity-attached ambient hum & lamp-anchored flicker,
 * Red Room dread, soft-carpet noclip, rare non-linear space shifts, Survival building & scavenging,
 * flashlight light projection, and privacy without scoreboards or coordinates.
 */
public final class BackroomsListeners implements Listener {
    private final YunexalBackroomsPlugin plugin;
    private final NamespacedKey manilaStockedKey;
    private final NamespacedKey manilaOpenedKey;
    private final NamespacedKey level1CrateKey;
    private final NamespacedKey level2CrateKey;

    private final Map<UUID, Long> lastHumAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFlickerAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRedPulseAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastInteractAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAnomalyAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNoclipAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastExitTransitionAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerCellKey = new ConcurrentHashMap<>();
    private final Map<UUID, Location> activeFlashlightLoc = new ConcurrentHashMap<>();
    private final Map<UUID, Set<BlockKey>> activeFlickerWalls = new ConcurrentHashMap<>();
    private final Map<BlockKey, Long> activeExitRifts = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastDeathWorld = new ConcurrentHashMap<>();
    private final Map<BlockKey, Integer> ventHitProgress = new ConcurrentHashMap<>();

    private BukkitTask ambientTask;
    private BukkitTask flashlightTask;
    private BukkitTask autosaveTask;

    private record BlockKey(UUID worldId, int x, int y, int z) {}

    public BackroomsListeners(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
        this.manilaStockedKey = new NamespacedKey(plugin, "manila_stocked");
        this.manilaOpenedKey = new NamespacedKey(plugin, "manila_opened");
        this.level1CrateKey = new NamespacedKey(plugin, "level1_crate");
        this.level2CrateKey = new NamespacedKey(plugin, "level2_crate");
    }

    public void start() {
        shutdown();
        ambientTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAmbient, 20L, 20L);
        flashlightTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFlashlights, 3L, 3L);
        autosaveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> plugin.getPlayerData().saveAllOnline(), 1200L, 1200L);
    }

    public void shutdown() {
        if (ambientTask != null) {
            ambientTask.cancel();
            ambientTask = null;
        }
        if (flashlightTask != null) {
            flashlightTask.cancel();
            flashlightTask = null;
        }
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            restoreFlashlight(p);
            restoreFlickerWalls(p);
        }
        activeFlashlightLoc.clear();
        activeFlickerWalls.clear();
        activeExitRifts.clear();
        lastHumAtMs.clear();
        lastFlickerAtMs.clear();
        lastRedPulseAtMs.clear();
        lastInteractAtMs.clear();
        lastAnomalyAtMs.clear();
        lastNoclipAtMs.clear();
        lastExitTransitionAtMs.clear();
        playerCellKey.clear();
        ventHitProgress.clear();
    }

    public void clearPlayerState(Player player) {
        restoreFlashlight(player);
        restoreFlickerWalls(player);
        UUID uid = player.getUniqueId();
        lastHumAtMs.remove(uid);
        lastFlickerAtMs.remove(uid);
        lastRedPulseAtMs.remove(uid);
        lastInteractAtMs.remove(uid);
        lastAnomalyAtMs.remove(uid);
        lastNoclipAtMs.remove(uid);
        lastExitTransitionAtMs.remove(uid);
        playerCellKey.remove(uid);
    }

    private void tickAmbient() {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        World level0 = plugin.getLevel0World();
        if (level0 == null || !cfg.isAmbientEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        long humIntervalMs = cfg.getHumIntervalTicks() * 50L;
        long flickerCooldownMs = cfg.getFlickerCooldownSeconds() * 1000L;
        int ceilingY = cfg.getCeilingY();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        World level1 = plugin.getLevel1World();
        World level2 = plugin.getLevel2World();

        // Seal expired Flickering Exit Wall breaches back into solid Level 0 walls
        Iterator<Map.Entry<BlockKey, Long>> riftIt = activeExitRifts.entrySet().iterator();
        while (riftIt.hasNext()) {
            Map.Entry<BlockKey, Long> entry = riftIt.next();
            if (now >= entry.getValue()) {
                BlockKey k = entry.getKey();
                World rw = Bukkit.getWorld(k.worldId());
                if (rw != null && rw.isChunkLoaded(k.x() >> 4, k.z() >> 4)) {
                    Block b = rw.getBlockAt(k.x(), k.y(), k.z());
                    if (b.getType().isAir()) {
                        b.setType(k.y() == cfg.getFloorY() + 1 ? Material.ORANGE_TERRACOTTA : Material.YELLOW_TERRACOTTA, false);
                    }
                }
                riftIt.remove();
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            World pw = player.getWorld();
            boolean inL0 = pw.equals(level0);
            boolean inL1 = level1 != null && pw.equals(level1);
            boolean inL2 = level2 != null && pw.equals(level2);
            if ((!inL0 && !inL1 && !inL2)
                    || plugin.getLimboManager().isInLimbo(player)
                    || !plugin.getLimboManager().isVerified(player)) {
                continue;
            }

            UUID uid = player.getUniqueId();
            Location loc = player.getLocation();

            if (inL1) {
                long prevHum = lastHumAtMs.getOrDefault(uid, 0L);
                if (now - prevHum >= humIntervalMs) {
                    lastHumAtMs.put(uid, now);
                    boolean blackout = plugin.getLevel1Manager() != null
                            && plugin.getLevel1Manager().getBlackoutPhase() == Level1Manager.BlackoutPhase.BLACKOUT;
                    if (!blackout) {
                        player.playSound(player, "minecraft:backrooms.ambient.hum", SoundCategory.AMBIENT, 0.10f, 0.88f);
                    }
                }
                continue;
            }

            if (inL2) {
                long prevHum = lastHumAtMs.getOrDefault(uid, 0L);
                if (now - prevHum >= humIntervalMs) {
                    lastHumAtMs.put(uid, now);
                    player.playSound(player, "minecraft:backrooms.ambient.hum", SoundCategory.AMBIENT, 0.11f, 0.78f);
                }
                continue;
            }

            plugin.getPlayerData().addSurvivalSecondsLevel0(player, 1);

            int floorY = cfg.getFloorY();
            GameMode gm = player.getGameMode();
            boolean survivalOrAdventure = (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE);

            // Pitfall deep-shaft abyss darkness & unsurvivable fall
            if (survivalOrAdventure && !player.isDead() && loc.getY() <= floorY - 3.0) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 90, 0, false, false, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 90, 0, false, false, false));
                if (loc.getY() <= floorY - 40.0) {
                    player.setHealth(0.0);
                    continue;
                }
            }

            Level0ChunkGenerator.ZoneType zone = plugin.getLevel0Generator()
                    .getZoneAtBlock(level0, loc.getBlockX(), loc.getBlockZ());

            // Exhausted / dehydrated explorers stumble & suffer vertigo inside Pitfall Halls
            if (zone == Level0ChunkGenerator.ZoneType.PITFALL_HALL && survivalOrAdventure && loc.getY() >= floorY - 1.5) {
                double fatigue = plugin.getHallucinations() != null ? plugin.getHallucinations().getFatigue(player) : 0.0;
                if (player.getFoodLevel() <= 10 || fatigue >= 0.45) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 110, 0, false, false, false));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 45, 0, false, false, false));
                    player.sendActionBar(Component.text("Паморочиться в голові через втому чи спрагу — не підходьте до ям!", NamedTextColor.RED));
                    if ((player.isSprinting() || rng.nextDouble() < 0.25) && rng.nextDouble() < 0.42) {
                        double ang = rng.nextDouble() * Math.PI * 2.0;
                        double push = player.isSprinting() ? 0.24 : 0.16;
                        player.setVelocity(player.getVelocity().add(new Vector(Math.cos(ang) * push, 0.02, Math.sin(ang) * push)));
                    }
                }
            }

            if (zone == Level0ChunkGenerator.ZoneType.RED_ROOM) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 85, 0, false, false, false));
                long prevPulse = lastRedPulseAtMs.getOrDefault(uid, 0L);
                if (now - prevPulse >= 3800L) {
                    lastRedPulseAtMs.put(uid, now);
                    player.playSound(player, "minecraft:backrooms.ambient.red_pulse", SoundCategory.AMBIENT, 0.30f, 1.0f);
                }
                continue;
            }

            // Head-attached 60/120Hz fluorescent hum (never jumps spatially while moving)
            long prevHum = lastHumAtMs.getOrDefault(uid, 0L);
            if (now - prevHum >= humIntervalMs) {
                lastHumAtMs.put(uid, now);
                float volume = (zone == Level0ChunkGenerator.ZoneType.BLACKOUT_ZONE) ? 0.10f
                        : (zone == Level0ChunkGenerator.ZoneType.MANILA_ROOM ? 0.12f : 0.22f);
                player.playSound(player, "minecraft:backrooms.ambient.hum", SoundCategory.AMBIENT, volume, 1.0f);
            }

            // Rare flickering buzz pinned strictly to an actual nearby SEA_LANTERN fixture coordinate
            long prevFlicker = lastFlickerAtMs.getOrDefault(uid, 0L);
            if (now - prevFlicker >= flickerCooldownMs && rng.nextDouble() < cfg.getFlickerSoundChance()) {
                Location lampLoc = findNearestFlickeringLamp(level0, loc.getBlockX(), ceilingY, loc.getBlockZ());
                if (lampLoc != null) {
                    lastFlickerAtMs.put(uid, now);
                    player.playSound(lampLoc, "minecraft:backrooms.ambient.flicker", SoundCategory.BLOCKS, 0.24f, 1.0f);
                }
            }

            // Rare distant thump echoing from a corridor direction (skipped in quiet Manila Room)
            if (zone != Level0ChunkGenerator.ZoneType.MANILA_ROOM && rng.nextDouble() < cfg.getDistantThumpChance()) {
                double angle = rng.nextDouble() * Math.PI * 2.0;
                double dist = 12.0 + rng.nextDouble() * 8.0;
                Location soundLoc = loc.clone().add(Math.cos(angle) * dist, 0.5, Math.sin(angle) * dist);
                player.playSound(soundLoc, "minecraft:backrooms.ambient.thump", SoundCategory.AMBIENT, 0.22f,
                        0.85f + rng.nextFloat() * 0.18f);
            }
        }
    }

    private Location findNearestFlickeringLamp(World world, int px, int ceilingY, int pz) {
        Location best = null;
        int bestDistSq = Integer.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (world.getBlockAt(px + dx, ceilingY, pz + dz).getType() == Material.SEA_LANTERN) {
                    int dSq = dx * dx + dz * dz;
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        best = new Location(world, px + dx + 0.5, ceilingY + 0.5, pz + dz + 0.5);
                    }
                }
            }
        }
        return best;
    }

    private void tickFlashlights() {
        World level0 = plugin.getLevel0World();
        if (level0 == null) {
            return;
        }
        BackroomsItems items = plugin.getItems();
        int tickMod = Bukkit.getCurrentTick() & 7;

        for (Player player : Bukkit.getOnlinePlayers()) {
            World pw = player.getWorld();
            if (!plugin.isBackroomsGameplayWorld(pw)
                    || plugin.getLimboManager().isInLimbo(player)
                    || !plugin.getLimboManager().isVerified(player)) {
                restoreFlashlight(player);
                restoreFlickerWalls(player);
                continue;
            }

            if (pw.equals(level0)) {
                if ((tickMod & 1) == 0) {
                    tickFlickerExitWalls(player, level0);
                }
            } else {
                restoreFlickerWalls(player);
            }

            boolean holdingLight = items.isItem(player.getInventory().getItemInMainHand(), BackroomsItems.ID_FLASHLIGHT)
                    || items.isItem(player.getInventory().getItemInOffHand(), BackroomsItems.ID_FLASHLIGHT);
            if (!holdingLight) {
                restoreFlashlight(player);
                continue;
            }

            Location target = pickLightTarget(player);
            if (target == null) {
                restoreFlashlight(player);
                continue;
            }

            Location prev = activeFlashlightLoc.get(player.getUniqueId());
            if (prev != null && prev.getWorld().equals(target.getWorld())
                    && prev.getBlockX() == target.getBlockX()
                    && prev.getBlockY() == target.getBlockY()
                    && prev.getBlockZ() == target.getBlockZ()) {
                continue;
            }

            restoreFlashlight(player);
            Light lightData = (Light) Material.LIGHT.createBlockData();
            lightData.setLevel(12);
            player.sendBlockChange(target, lightData);
            activeFlashlightLoc.put(player.getUniqueId(), target);
        }
    }

    private void tickFlickerExitWalls(Player player, World level0) {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        Level0ChunkGenerator gen = plugin.getLevel0Generator();
        Location loc = player.getLocation();
        int px = loc.getBlockX();
        int pz = loc.getBlockZ();
        int floorY = cfg.getFloorY();
        int ceilingY = cfg.getCeilingY();

        Set<BlockKey> prev = activeFlickerWalls.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
        Set<BlockKey> next = new HashSet<>();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        int minCx = Math.floorDiv(px - 16, Level0ChunkGenerator.CELL_SIZE);
        int maxCx = Math.floorDiv(px + 16, Level0ChunkGenerator.CELL_SIZE);
        int minCz = Math.floorDiv(pz - 16, Level0ChunkGenerator.CELL_SIZE);
        int maxCz = Math.floorDiv(pz + 16, Level0ChunkGenerator.CELL_SIZE);

        BlockData concreteBleed = Material.GRAY_TERRACOTTA.createBlockData();
        BlockData staticFlash = Material.OCHRE_FROGLIGHT.createBlockData();

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (boolean north : new boolean[]{true, false}) {
                    int bxBase = north ? cx * 4 + 2 : cx * 4;
                    int bzBase = north ? cz * 4 : cz * 4 + 2;
                    if (!gen.isFlickeringExitBlock(level0, bxBase, floorY + 2, bzBase)) {
                        continue;
                    }
                    double flickerPhase = rng.nextDouble();
                    boolean playSpark = flickerPhase < 0.36;
                    BlockData overrideData = (flickerPhase < 0.22) ? concreteBleed : (flickerPhase < 0.34 ? staticFlash : null);

                    for (int i = 0; i < 2; i++) {
                        int bx = north ? bxBase + i : bxBase;
                        int bz = north ? bzBase : bzBase + i;
                        for (int y = floorY + 1; y < ceilingY; y++) {
                            Block real = level0.getBlockAt(bx, y, bz);
                            if (real.getType().isAir()) {
                                continue;
                            }
                            BlockKey key = new BlockKey(level0.getUID(), bx, y, bz);
                            next.add(key);
                            if (overrideData != null) {
                                player.sendBlockChange(real.getLocation(), overrideData);
                            } else {
                                player.sendBlockChange(real.getLocation(), real.getBlockData());
                            }
                        }
                    }
                    if (playSpark && loc.distanceSquared(new Location(level0, bxBase + 0.5, floorY + 2.0, bzBase + 0.5)) <= 12.0 * 12.0) {
                        player.spawnParticle(Particle.ELECTRIC_SPARK, bxBase + 0.5, floorY + 2.2, bzBase + 0.5, 2, 0.35, 0.45, 0.35, 0.01);
                        if (rng.nextDouble() < 0.14) {
                            player.playSound(new Location(level0, bxBase + 0.5, floorY + 2.0, bzBase + 0.5),
                                    "minecraft:backrooms.fx.radio_static", SoundCategory.BLOCKS, 0.28f, 0.85f + rng.nextFloat() * 0.3f);
                        }
                    }
                }
            }
        }

        for (BlockKey stale : prev) {
            if (!next.contains(stale)) {
                Block real = level0.getBlockAt(stale.x(), stale.y(), stale.z());
                player.sendBlockChange(real.getLocation(), real.getBlockData());
            }
        }
        prev.clear();
        prev.addAll(next);
    }

    private void restoreFlickerWalls(Player player) {
        Set<BlockKey> prev = activeFlickerWalls.remove(player.getUniqueId());
        if (prev == null || prev.isEmpty() || !player.isOnline()) {
            return;
        }
        for (BlockKey k : prev) {
            World w = Bukkit.getWorld(k.worldId());
            if (w != null && w.isChunkLoaded(k.x() >> 4, k.z() >> 4)) {
                Block real = w.getBlockAt(k.x(), k.y(), k.z());
                player.sendBlockChange(real.getLocation(), real.getBlockData());
            }
        }
    }

    private Location pickLightTarget(Player player) {
        Location eye = player.getEyeLocation();
        Location ahead = eye.clone().add(eye.getDirection().multiply(2.0));
        Block b = ahead.getBlock();
        if (b.getType().isAir()) {
            return b.getLocation();
        }
        Block eyeBlock = eye.getBlock();
        return eyeBlock.getType().isAir() ? eyeBlock.getLocation() : null;
    }

    private void restoreFlashlight(Player player) {
        Location old = activeFlashlightLoc.remove(player.getUniqueId());
        if (old != null && old.getWorld() != null) {
            player.sendBlockChange(old, old.getBlock().getBlockData());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        World level0 = plugin.getLevel0World();
        if (level0 == null || !player.getWorld().equals(level0)) {
            return;
        }
        if (plugin.getLimboManager().isInLimbo(player) || !plugin.getLimboManager().isVerified(player)) {
            return;
        }

        Location to = event.getTo();
        Location from = event.getFrom();
        BlockKey feetKey = new BlockKey(level0.getUID(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        if (activeExitRifts.containsKey(feetKey)) {
            long nowExit = System.currentTimeMillis();
            if (nowExit - lastExitTransitionAtMs.getOrDefault(player.getUniqueId(), 0L) >= 4000L) {
                lastExitTransitionAtMs.put(player.getUniqueId(), nowExit);
                Bukkit.getScheduler().runTask(plugin, () -> transitionToLevel1(player));
                return;
            }
        }
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        int floorY = cfg.getFloorY();
        GameMode gm = player.getGameMode();
        if ((gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) && !player.isDead() && to.getY() <= floorY - 3.0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 90, 0, false, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 90, 0, false, false, false));
            if (to.getY() <= floorY - 40.0) {
                player.setHealth(0.0);
                return;
            }
        }
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        long now = System.currentTimeMillis();
        UUID uid = player.getUniqueId();
        int bx = to.getBlockX();
        int bz = to.getBlockZ();

        // 1. Canonical soft-carpet noclip spot
        Block floorBlock = level0.getBlockAt(bx, floorY, bz);
        if (floorBlock.getType() == Material.GRAY_WOOL
                && plugin.getLevel0Generator().isNaturalNoclipSpot(level0, bx, bz)) {
            if (now - lastNoclipAtMs.getOrDefault(uid, 0L) >= 6000L) {
                lastNoclipAtMs.put(uid, now);
                triggerSoftCarpetNoclip(player, level0);
                return;
            }
        }

        // 2. Very rare non-linear spatial shift when crossing macro-cell boundaries
        int newCx = Math.floorDiv(bx, Level0ChunkGenerator.CELL_SIZE);
        int newCz = Math.floorDiv(bz, Level0ChunkGenerator.CELL_SIZE);
        long cellPacked = (((long) newCx) << 32) ^ (newCz & 0xFFFFFFFFL);
        Long prevPacked = playerCellKey.put(uid, cellPacked);
        if (prevPacked == null || prevPacked == cellPacked) {
            return;
        }

        if (now - lastAnomalyAtMs.getOrDefault(uid, 0L) < 90_000L) {
            return;
        }
        if (Math.abs(newCx) <= 3 && Math.abs(newCz) <= 3) {
            return;
        }

        Level0ChunkGenerator.ZoneType zone = plugin.getLevel0Generator().getZoneAtBlock(level0, bx, bz);
        if (zone == Level0ChunkGenerator.ZoneType.MANILA_ROOM || zone == Level0ChunkGenerator.ZoneType.RED_ROOM) {
            return;
        }

        if (ThreadLocalRandom.current().nextDouble() < cfg.getNonLinearAnomalyChance()) {
            lastAnomalyAtMs.put(uid, now);
            Location shift = plugin.getLevel0Generator().findRandomFarSpawn(
                    level0, Bukkit.getOnlinePlayers(), Math.min(cfg.getSpawnRadius(), 1200));
            shift.setYaw(to.getYaw());
            shift.setPitch(to.getPitch());
            restoreFlashlight(player);
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 14, 0, false, false, false));
            player.teleport(shift);
        }
    }

    private void triggerSoftCarpetNoclip(Player player, World level0) {
        restoreFlashlight(player);
        restoreFlickerWalls(player);
        player.playSound(player, "minecraft:backrooms.ui.noclip", SoundCategory.MASTER, 0.65f, 1.0f);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 36, 0, false, false, false));
        player.showTitle(Title.title(
                Component.text("НОУКЛІП", NamedTextColor.GRAY, TextDecoration.BOLD),
                Component.text("М'який килим провалив вас у інший сектор Лобі", NamedTextColor.DARK_GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(700))
        ));
        Location nextSpot = plugin.getLevel0Generator().findRandomFarSpawn(
                level0, Bukkit.getOnlinePlayers(), plugin.getBackroomsConfig().getSpawnRadius());
        player.teleport(nextSpot);
    }

    public void transitionToLevel1(Player player) {
        World level1 = plugin.getLevel1World();
        if (level1 == null || !player.isOnline()) {
            return;
        }
        restoreFlashlight(player);
        restoreFlickerWalls(player);
        player.playSound(player, "minecraft:backrooms.ui.noclip", SoundCategory.MASTER, 0.75f, 0.92f);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 42, 0, false, false, false));
        Location arrival = plugin.getLevel1Generator().getArrivalLocation(level1);
        player.teleport(arrival);
        plugin.getPlayerData().savePlayer(player);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.playSound(player, "minecraft:backrooms.fx.blackout_breaker", SoundCategory.AMBIENT, 0.45f, 1.05f);
            player.showTitle(Title.title(
                    Component.text("РІВЕНЬ 1", NamedTextColor.GRAY, TextDecoration.BOLD),
                    Component.text("«Населена зона» // Ви вирвалися до залишків людства", NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(800))
            ));
            player.sendMessage(Component.text("▸ ", NamedTextColor.AQUA)
                    .append(Component.text("Ви прорвалися крізь блимаючу стіну на ", NamedTextColor.GRAY))
                    .append(Component.text("Рівень 1 «Населена зона»", NamedTextColor.WHITE))
                    .append(Component.text(". Попереду — вогні ", NamedTextColor.GRAY))
                    .append(Component.text("Бази Альфа [M.E.G.]", NamedTextColor.AQUA))
                    .append(Component.text(". Шукайте ящики з припасами та остерігайтеся «Мерехтіння»!", NamedTextColor.GRAY)));
        }, 10L);
    }

    public void transitionToLevel2(Player player) {
        World level2 = plugin.getLevel2World();
        if (level2 == null || !player.isOnline()) {
            return;
        }
        restoreFlashlight(player);
        restoreFlickerWalls(player);
        player.playSound(player, "minecraft:backrooms.ui.noclip", SoundCategory.MASTER, 0.7f, 0.82f);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 44, 0, false, false, false));
        Location arrival = plugin.getLevel2Generator().getArrivalLocation(level2);
        player.teleport(arrival);
        plugin.getPlayerData().savePlayer(player);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.playSound(player, "minecraft:backrooms.fx.steam_hiss", SoundCategory.AMBIENT, 0.55f, 0.92f);
            player.showTitle(Title.title(
                    Component.text("РІВЕНЬ 2", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("«Покинуті підсобні приміщення» // Мрії про труби", NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(800))
            ));
            player.sendMessage(Component.text("▸ ", NamedTextColor.GOLD)
                    .append(Component.text("Ви відчинили службові двері й спустилися на ", NamedTextColor.GRAY))
                    .append(Component.text("Рівень 2 «Мрії про труби»", NamedTextColor.WHITE))
                    .append(Component.text(". Повітря розпечене паром (>43°C), а в темряві вузьких тунелів кружляють ", NamedTextColor.GRAY))
                    .append(Component.text("Смертомолі", NamedTextColor.RED))
                    .append(Component.text(". Тримайте Мигдалеву Воду напоготові!", NamedTextColor.GRAY)));
        }, 10L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        World pw = player.getWorld();
        if (!plugin.isBackroomsGameplayWorld(pw)) {
            return;
        }
        World level0 = plugin.getLevel0World();
        World level1 = plugin.getLevel1World();
        World level2 = plugin.getLevel2World();
        if (plugin.getLimboManager().isInLimbo(player) || !plugin.getLimboManager().isVerified(player)) {
            return;
        }

        BackroomsItems items = plugin.getItems();
        ItemStack held = player.getInventory().getItemInMainHand();
        String heldId = items.getItemId(held);
        Action action = event.getAction();
        Block clicked = event.getClickedBlock();

        // Allow native container/station usage cleanly, ensuring Manila/Level 1/Level 2 barrels always have loot if unstocked
        if (action == Action.RIGHT_CLICK_BLOCK && clicked != null) {
            if (clicked.getType() == Material.CRAFTING_TABLE
                    || clicked.getType() == Material.FURNACE
                    || clicked.getType() == Material.BLAST_FURNACE
                    || clicked.getType() == Material.SMOKER) {
                return;
            }
            if (clicked.getType() == Material.BARREL) {
                if (clicked.getState() instanceof Barrel barrel) {
                    var pdc = barrel.getPersistentDataContainer();
                    if (level0 != null && pw.equals(level0)
                            && plugin.getLevel0Generator().getZoneAtBlock(level0, clicked.getX(), clicked.getZ())
                            == Level0ChunkGenerator.ZoneType.MANILA_ROOM) {
                        if (!pdc.has(manilaStockedKey, PersistentDataType.BYTE)
                                || (!pdc.has(manilaOpenedKey, PersistentDataType.BYTE) && barrel.getInventory().isEmpty())) {
                            Level0Populator.fillManilaBarrelLoot(barrel.getInventory(), barrel, ThreadLocalRandom.current(), items, manilaStockedKey);
                            pdc.set(manilaOpenedKey, PersistentDataType.BYTE, (byte) 1);
                            barrel.update(true, false);
                        } else if (!pdc.has(manilaOpenedKey, PersistentDataType.BYTE)) {
                            pdc.set(manilaOpenedKey, PersistentDataType.BYTE, (byte) 1);
                            barrel.update(true, false);
                        }
                    } else if (level1 != null && pw.equals(level1)) {
                        if (!pdc.has(manilaOpenedKey, PersistentDataType.BYTE) && !pdc.has(level1CrateKey, PersistentDataType.BYTE)) {
                            Level1Populator.fillLevel1CrateLoot(barrel.getInventory(), barrel, ThreadLocalRandom.current(), items, level1CrateKey);
                            pdc.set(manilaOpenedKey, PersistentDataType.BYTE, (byte) 1);
                            barrel.update(true, false);
                        }
                    } else if (level2 != null && pw.equals(level2)) {
                        if (!pdc.has(manilaOpenedKey, PersistentDataType.BYTE) && !pdc.has(level2CrateKey, PersistentDataType.BYTE)) {
                            Level2Populator.fillUtilityLockerLoot(barrel.getInventory(), barrel, ThreadLocalRandom.current(), items, level2CrateKey);
                            pdc.set(manilaOpenedKey, PersistentDataType.BYTE, (byte) 1);
                            barrel.update(true, false);
                        }
                    }
                }
                return;
            }
        }

        // 1. Usable custom survival items on right-click
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (BackroomsItems.ID_WORKBENCH.equals(heldId) && action == Action.RIGHT_CLICK_AIR) {
                event.setCancelled(true);
                player.openWorkbench(null, true);
                return;
            }
            if (BackroomsItems.ID_BANDAGE.equals(heldId)) {
                event.setCancelled(true);
                applyBandage(player, held);
                return;
            }
            if (BackroomsItems.ID_BATTERY.equals(heldId)) {
                event.setCancelled(true);
                consumeOne(player, held);
                player.removePotionEffect(PotionEffectType.DARKNESS);
                if (plugin.getHallucinations() != null) {
                    plugin.getHallucinations().reduceFatigue(player, 0.25);
                }
                player.playSound(player, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.45f, 1.8f);
                player.sendActionBar(Component.text("Лужна батарейка: ліхтар підживлено, темрява відступила!", NamedTextColor.AQUA));
                return;
            }
            if (BackroomsItems.ID_USED_SYRINGE.equals(heldId)) {
                event.setCancelled(true);
                consumeOne(player, held);
                player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 0, false, false, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0, false, false, false));
                player.sendActionBar(Component.text("Ви поранилися брудним шприцом з ящика!", NamedTextColor.RED));
                return;
            }
            if (BackroomsItems.ID_DAMP_WALLPAPER.equals(heldId)) {
                event.setCancelled(true);
                applyCarpetLiquidToxicity(player, "Рідина зі шпалер токсична! Просушіть з сухими шпалерами.");
                return;
            }
            if (held.getType() == Material.COMPASS || BackroomsItems.ID_BROKEN_COMPASS.equals(heldId)) {
                player.sendActionBar(Component.text("Стрілка хаотично обертається — магнітного поля немає.", NamedTextColor.GRAY));
            }
            if (player.isSneaking() && action == Action.RIGHT_CLICK_BLOCK && clicked != null
                    && held.getType().isAir() && clicked.getType() == Material.BROWN_WOOL) {
                event.setCancelled(true);
                applyCarpetLiquidToxicity(player, "Ви ковтнули каламутну рідину з вологого килиму!");
                return;
            }
            if (BackroomsItems.ID_MARKER.equals(heldId) && action == Action.RIGHT_CLICK_BLOCK && clicked != null) {
                if (tryPlaceMarker(player, clicked, held)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        // Level 0 Flickering Exit Wall feedback on right-click
        if (clicked != null && level0 != null && pw.equals(level0) && action == Action.RIGHT_CLICK_BLOCK
                && plugin.getLevel0Generator().isFlickeringExitBlock(level0, clicked.getX(), clicked.getY(), clicked.getZ())) {
            int survived = plugin.getPlayerData().getSurvivalSecondsLevel0(player);
            int minSec = plugin.getBackroomsConfig().getExitMinSurvivalSeconds();
            if (survived >= minSec || player.hasPermission("yunexal.backrooms.admin")) {
                player.sendActionBar(Component.text("БЛИМАЮЧА СТІНА! Проломіть її (ЛКМ), щоб вирватися на Рівень 1!", NamedTextColor.AQUA));
            } else {
                int rem = Math.max(1, minSec - survived);
                player.sendActionBar(Component.text("Стіна блимає, та простір ще тримає вас! Виживіть ще "
                        + (rem / 60) + ":" + String.format("%02d", rem % 60), NamedTextColor.YELLOW));
            }
            return;
        }

        // Level 1 Technical Utility Door -> opens transition into Level 2
        if (clicked != null && level1 != null && pw.equals(level1) && level2 != null && action == Action.RIGHT_CLICK_BLOCK
                && plugin.getLevel1Generator().isLevel2TransitionDoor(level1, clicked.getX(), clicked.getY(), clicked.getZ())) {
            event.setCancelled(true);
            Location doorLoc = clicked.getLocation().add(0.5, 0.5, 0.5);
            level1.playSound(doorLoc, Sound.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 0.85f, 0.85f);
            level1.playSound(doorLoc, "minecraft:backrooms.fx.steam_hiss", SoundCategory.AMBIENT, 0.7f, 1.0f);
            lastExitTransitionAtMs.put(player.getUniqueId(), System.currentTimeMillis());
            Bukkit.getScheduler().runTask(plugin, () -> transitionToLevel2(player));
            return;
        }

        // Level 2 -> Level 3 locked Electrical Station teaser door
        if (clicked != null && level2 != null && pw.equals(level2) && action == Action.RIGHT_CLICK_BLOCK
                && plugin.getLevel2Generator().isLevel3TeaserBlock(level2, clicked.getX(), clicked.getY(), clicked.getZ())) {
            event.setCancelled(true);
            Location doorLoc = clicked.getLocation().add(0.5, 0.5, 0.5);
            level2.playSound(doorLoc, Sound.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 0.8f, 0.7f);
            player.sendActionBar(Component.text("Двері замкнені: за ними чути високовольтний гул Рівня 3 («Електрична станція»)", NamedTextColor.RED));
            return;
        }

        // Dead 1980s North American electrical wall outlet (non-functional on Level 0)
        if (clicked != null && clicked.getType() == Material.CYAN_TERRACOTTA
                && action == Action.RIGHT_CLICK_BLOCK && (held.getType().isAir() || BackroomsItems.ID_CROWBAR.equals(heldId))) {
            long now = System.currentTimeMillis();
            UUID uid = player.getUniqueId();
            if (now - lastInteractAtMs.getOrDefault(uid, 0L) >= 280L) {
                lastInteractAtMs.put(uid, now);
                player.playSound(clicked.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_STONE_BUTTON_CLICK_OFF, SoundCategory.BLOCKS, 0.55f, 1.15f);
                player.sendActionBar(Component.text("Розетка мертва. Струму на Рівні 0 немає.", NamedTextColor.GRAY));
            }
            event.setCancelled(true);
            return;
        }

        // 2. Non-destructive surface scavenging on right-click ONLY with empty hand or crowbar (so Survival mining & block placement work naturally)
        if (clicked == null || action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!held.getType().isAir() && !BackroomsItems.ID_CROWBAR.equals(heldId)) {
            return;
        }
        long now = System.currentTimeMillis();
        UUID uid = player.getUniqueId();
        if (now - lastInteractAtMs.getOrDefault(uid, 0L) < 220L) {
            return;
        }

        if (handleBlockScavenge(player, clicked, heldId)) {
            lastInteractAtMs.put(uid, now);
            event.setCancelled(true);
        }
    }

    private void applyBandage(Player player, ItemStack held) {
        double maxHp = 20.0;
        var attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            maxHp = attr.getValue();
        }
        boolean hasNeg = player.hasPotionEffect(PotionEffectType.DARKNESS)
                || player.hasPotionEffect(PotionEffectType.NAUSEA)
                || player.hasPotionEffect(PotionEffectType.POISON);
        if (player.getHealth() >= maxHp && !hasNeg) {
            player.sendActionBar(Component.text("Здоров'я вже повне.", NamedTextColor.GRAY));
            return;
        }

        player.setHealth(Math.min(maxHp, player.getHealth() + 6.0));
        player.removePotionEffect(PotionEffectType.DARKNESS);
        player.removePotionEffect(PotionEffectType.NAUSEA);
        player.removePotionEffect(PotionEffectType.POISON);
        if (plugin.getHallucinations() != null) {
            plugin.getHallucinations().reduceFatigue(player, 0.30);
        }
        consumeOne(player, held);
        player.playSound(player, Sound.BLOCK_WOOL_PLACE, SoundCategory.PLAYERS, 0.7f, 1.25f);
        player.sendActionBar(Component.text("Перев'язка: +6 HP та ясність розуму", NamedTextColor.GREEN));
    }

    private void applyCarpetLiquidToxicity(Player player, String msg) {
        long now = System.currentTimeMillis();
        UUID uid = player.getUniqueId();
        if (now - lastInteractAtMs.getOrDefault(uid, 0L) < 800L) {
            return;
        }
        lastInteractAtMs.put(uid, now);
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 140, 0, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 0, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 120, 0, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, false));
        player.playSound(player, Sound.ENTITY_GENERIC_DRINK, SoundCategory.PLAYERS, 0.55f, 0.72f);
        player.sendActionBar(Component.text(msg, NamedTextColor.RED));
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getItems().isItem(event.getItem(), BackroomsItems.ID_ALMOND_WATER)) {
            return;
        }
        double maxHp = 20.0;
        var attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            maxHp = attr.getValue();
        }
        player.setHealth(Math.min(maxHp, player.getHealth() + 8.0));
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.removePotionEffect(PotionEffectType.NAUSEA);
        player.removePotionEffect(PotionEffectType.POISON);
        player.removePotionEffect(PotionEffectType.DARKNESS);
        player.removePotionEffect(PotionEffectType.HUNGER);
        if (plugin.getHallucinations() != null) {
            plugin.getHallucinations().resetFatigueWithCalm(player, 180_000L);
        }
        player.sendActionBar(Component.text("Мигдальна вода: голод, насиченість і розум відновлено!", NamedTextColor.GOLD));

        EquipmentSlot slot = event.getHand();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            ItemStack left = slot == EquipmentSlot.OFF_HAND
                    ? player.getInventory().getItemInOffHand()
                    : player.getInventory().getItemInMainHand();
            if (left.getType() == Material.GLASS_BOTTLE && plugin.getItems().getItemId(left) == null) {
                if (slot == EquipmentSlot.OFF_HAND) {
                    player.getInventory().setItemInOffHand(null);
                } else {
                    player.getInventory().setItemInMainHand(null);
                }
            }
        });
    }

    private boolean tryPlaceMarker(Player player, Block floor, ItemStack held) {
        Material m = floor.getType();
        if (m != Material.BROWN_WOOL && m != Material.GRAY_WOOL && m != Material.RED_WOOL && m != Material.GRAY_CONCRETE) {
            return false;
        }
        Block above = floor.getRelative(BlockFace.UP);
        if (!above.getType().isAir()) {
            return false;
        }
        above.setType(Material.YELLOW_CANDLE, false);
        if (above.getBlockData() instanceof Candle candle) {
            candle.setCandles(1);
            candle.setLit(true);
            above.setBlockData(candle, false);
        }
        consumeOne(player, held);
        player.playSound(above.getLocation().add(0.5, 0.2, 0.5), Sound.BLOCK_CANDLE_PLACE, SoundCategory.BLOCKS, 0.8f, 1.0f);
        return true;
    }

    private boolean handleBlockScavenge(Player player, Block block, String heldId) {
        BackroomsItems items = plugin.getItems();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        boolean hasCrowbar = BackroomsItems.ID_CROWBAR.equals(heldId);
        Material type = block.getType();
        Location soundLoc = block.getLocation().add(0.5, 0.5, 0.5);

        switch (type) {
            case YELLOW_CANDLE -> {
                block.setType(Material.AIR, false);
                giveOrDrop(player, items.marker(1), soundLoc);
                player.playSound(soundLoc, Sound.BLOCK_CANDLE_BREAK, SoundCategory.BLOCKS, 0.75f, 1.0f);
                return true;
            }
            case YELLOW_TERRACOTTA -> {
                block.setType(Material.LIGHT_GRAY_TERRACOTTA, false);
                giveOrDrop(player, items.wallpaperScrap(1 + rng.nextInt(2)), soundLoc);
                if (rng.nextDouble() < 0.12) {
                    giveOrDrop(player, items.copperWire(1), soundLoc);
                }
                player.playSound(soundLoc, "minecraft:backrooms.fx.wallpaper_tear", SoundCategory.BLOCKS, 0.7f, 0.95f + rng.nextFloat() * 0.15f);
                return true;
            }
            case ORANGE_TERRACOTTA -> {
                block.setType(Material.LIGHT_GRAY_TERRACOTTA, false);
                giveOrDrop(player, items.baseboardPlank(hasCrowbar ? 2 : 1), soundLoc);
                if (rng.nextDouble() < 0.40) {
                    giveOrDrop(player, items.wallpaperScrap(1), soundLoc);
                }
                player.playSound(soundLoc, "minecraft:backrooms.fx.wallpaper_tear", SoundCategory.BLOCKS, 0.75f, 0.85f + rng.nextFloat() * 0.1f);
                return true;
            }
            case BROWN_TERRACOTTA -> {
                block.setType(Material.LIGHT_GRAY_TERRACOTTA, false);
                giveOrDrop(player, items.dampWallpaper(1), soundLoc);
                if (block.getY() == plugin.getBackroomsConfig().getFloorY() + 1 && rng.nextDouble() < 0.50) {
                    giveOrDrop(player, items.baseboardPlank(1), soundLoc);
                }
                player.playSound(soundLoc, "minecraft:backrooms.fx.wallpaper_tear", SoundCategory.BLOCKS, 0.65f, 0.75f);
                return true;
            }
            case BROWN_WOOL -> {
                // In Survival, players tear off carpet by breaking it (revealing concrete underneath)
                return false;
            }
            case IRON_BLOCK -> {
                BlockKey key = new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
                if (!hasCrowbar) {
                    int hits = ventHitProgress.getOrDefault(key, 0) + 1;
                    if (hits < 2) {
                        ventHitProgress.put(key, hits);
                        player.playSound(soundLoc, Sound.BLOCK_METAL_HIT, SoundCategory.BLOCKS, 0.7f, 1.1f);
                        player.sendActionBar(Component.text("Решітка піддається... вдарте ще раз (або ломом)", NamedTextColor.GRAY));
                        return true;
                    }
                }
                ventHitProgress.remove(key);
                block.setType(Material.LIGHT_GRAY_TERRACOTTA, false);
                giveOrDrop(player, items.metalPlate(hasCrowbar ? 2 : 1), soundLoc);
                if (hasCrowbar || rng.nextDouble() < 0.35) {
                    giveOrDrop(player, items.copperWire(1), soundLoc);
                }
                if (rng.nextDouble() < (hasCrowbar ? 0.14 : 0.08)) {
                    giveOrDrop(player, items.almondWater(1), soundLoc);
                    player.sendActionBar(Component.text("У вентиляції знайдено пляшку Мигдальної води!", NamedTextColor.GOLD));
                }
                player.playSound(soundLoc, "minecraft:backrooms.fx.metal_pry", SoundCategory.BLOCKS, 0.65f, 0.95f);
                return true;
            }
            case POLISHED_ANDESITE -> {
                block.setType(Material.CALCITE, false);
                giveOrDrop(player, items.lampShard(1), soundLoc);
                if (rng.nextDouble() < 0.45) {
                    giveOrDrop(player, items.copperWire(1), soundLoc);
                }
                player.playSound(soundLoc, "minecraft:backrooms.fx.metal_pry", SoundCategory.BLOCKS, 0.65f, 1.25f);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void consumeOne(Player player, ItemStack stack) {
        if (stack.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            stack.setAmount(stack.getAmount() - 1);
        }
    }

    private void giveOrDrop(Player player, ItemStack stack, Location fallbackDrop) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
        for (ItemStack rem : overflow.values()) {
            player.getWorld().dropItemNaturally(fallbackDrop, rem);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (plugin.getLimboManager().isInLimbo(player) || !plugin.getLimboManager().isVerified(player)) {
            if (!player.hasPermission("yunexal.backrooms.admin")) {
                event.setCancelled(true);
            }
            return;
        }
        Block block = event.getBlock();
        World bw = block.getWorld();
        World level0 = plugin.getLevel0World();
        World level1 = plugin.getLevel1World();
        World level2 = plugin.getLevel2World();
        if (!plugin.isBackroomsGameplayWorld(bw)) {
            if (!player.hasPermission("yunexal.backrooms.admin")) {
                event.setCancelled(true);
            }
            return;
        }

        // Level 1 Technical Utility Door -> transitions into Level 2 ("Pipe Dreams")
        if (level1 != null && bw.equals(level1) && level2 != null
                && plugin.getLevel1Generator().isLevel2TransitionDoor(level1, block.getX(), block.getY(), block.getZ())) {
            event.setCancelled(true);
            Location doorLoc = block.getLocation().add(0.5, 0.5, 0.5);
            level1.playSound(doorLoc, Sound.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 0.85f, 0.85f);
            level1.playSound(doorLoc, "minecraft:backrooms.fx.steam_hiss", SoundCategory.AMBIENT, 0.7f, 1.0f);
            lastExitTransitionAtMs.put(player.getUniqueId(), System.currentTimeMillis());
            Bukkit.getScheduler().runTask(plugin, () -> transitionToLevel2(player));
            return;
        }

        // Level 2 -> Level 3 teaser door (locked electrical station)
        if (level2 != null && bw.equals(level2)
                && plugin.getLevel2Generator().isLevel3TeaserBlock(level2, block.getX(), block.getY(), block.getZ())) {
            event.setCancelled(true);
            Location doorLoc = block.getLocation().add(0.5, 0.5, 0.5);
            level2.playSound(doorLoc, Sound.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 0.8f, 0.7f);
            player.sendActionBar(Component.text("Двері замкнені: за ними чути високовольтний гул Рівня 3 («Електрична станція»)", NamedTextColor.RED));
            return;
        }

        // Level 0 Flickering Exit Wall breakthrough -> tears open doorway into Level 1!
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        if (level0 != null && bw.equals(level0)
                && plugin.getLevel0Generator().isFlickeringExitBlock(level0, block.getX(), block.getY(), block.getZ())) {
            int survived = plugin.getPlayerData().getSurvivalSecondsLevel0(player);
            int minSec = cfg.getExitMinSurvivalSeconds();
            if (!player.hasPermission("yunexal.backrooms.admin") && survived < minSec) {
                event.setCancelled(true);
                int rem = Math.max(1, minSec - survived);
                player.playSound(block.getLocation().add(0.5, 0.5, 0.5), "minecraft:backrooms.fx.radio_static", SoundCategory.BLOCKS, 0.5f, 1.0f);
                player.spawnParticle(Particle.ELECTRIC_SPARK, block.getLocation().add(0.5, 0.5, 0.5), 10, 0.35, 0.35, 0.35, 0.03);
                player.sendActionBar(Component.text("Блимаюча стіна не піддається! Виживіть ще "
                        + (rem / 60) + ":" + String.format("%02d", rem % 60), NamedTextColor.YELLOW));
                return;
            }
            event.setCancelled(true);
            Location[] bounds = plugin.getLevel0Generator().getFlickeringExitBounds(level0, block.getX(), block.getZ());
            long expiresAt = System.currentTimeMillis() + 45_000L;
            for (int x = bounds[0].getBlockX(); x <= bounds[1].getBlockX(); x++) {
                for (int z = bounds[0].getBlockZ(); z <= bounds[1].getBlockZ(); z++) {
                    for (int y = cfg.getFloorY() + 1; y < cfg.getCeilingY(); y++) {
                        Block b = level0.getBlockAt(x, y, z);
                        b.setType(Material.AIR, false);
                        activeExitRifts.put(new BlockKey(level0.getUID(), x, y, z), expiresAt);
                        level0.spawnParticle(Particle.PORTAL, x + 0.5, y + 0.5, z + 0.5, 14, 0.3, 0.3, 0.3, 0.1);
                    }
                }
            }
            if (level1 != null) {
                lastExitTransitionAtMs.put(player.getUniqueId(), System.currentTimeMillis());
                Bukkit.getScheduler().runTask(plugin, () -> transitionToLevel1(player));
            }
            return;
        }

        if (player.hasPermission("yunexal.backrooms.admin")) {
            return;
        }

        boolean inL1 = (level1 != null && bw.equals(level1));
        boolean inL2 = (level2 != null && bw.equals(level2));
        if (inL1 && plugin.getLevel1Generator().isInProtectedOutpostCore(level1, block.getX(), block.getZ())) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Територія аванпосту людства захищена!", NamedTextColor.RED));
            return;
        }
        if (inL2 && plugin.getLevel2Generator().isInProtectedOfficeCore(level2, block.getX(), block.getZ())) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Технічний офіс уцілілих захищений!", NamedTextColor.RED));
            return;
        }

        int ceilingY = inL1 ? cfg.getLevel1CeilingY() : (inL2 ? cfg.getLevel2CeilingY() : cfg.getCeilingY());
        if (block.getType() == Material.BEDROCK || block.getY() >= ceilingY) {
            event.setCancelled(true);
            return;
        }

        BackroomsItems items = plugin.getItems();
        Material type = block.getType();
        Location dropLoc = block.getLocation().add(0.5, 0.5, 0.5);
        ItemStack held = player.getInventory().getItemInMainHand();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (type == Material.CRAFTING_TABLE) {
            event.setDropItems(false);
            block.getWorld().dropItemNaturally(dropLoc, items.workbench(1));
            return;
        }
        if (type == Material.YELLOW_CANDLE) {
            event.setDropItems(false);
            block.getWorld().dropItemNaturally(dropLoc, items.marker(1));
            return;
        }
        if (type == Material.BROWN_WOOL || type == Material.RED_WOOL || type == Material.GRAY_WOOL) {
            block.getWorld().playSound(dropLoc, "minecraft:backrooms.fx.carpet_rip", SoundCategory.BLOCKS, 0.7f, 0.95f + rng.nextFloat() * 0.1f);
            return;
        }

        // When breaking walls/vents without a pickaxe, yield Backrooms scavenging scrap so Survival progression works smoothly
        if (block.getDrops(held, player).isEmpty()) {
            switch (type) {
                case YELLOW_TERRACOTTA, WHITE_TERRACOTTA, RED_TERRACOTTA -> {
                    block.getWorld().dropItemNaturally(dropLoc, items.wallpaperScrap(1));
                    block.getWorld().playSound(dropLoc, "minecraft:backrooms.fx.wallpaper_tear", SoundCategory.BLOCKS, 0.7f, 0.95f + rng.nextFloat() * 0.15f);
                }
                case ORANGE_TERRACOTTA -> {
                    block.getWorld().dropItemNaturally(dropLoc, items.baseboardPlank(1));
                    block.getWorld().playSound(dropLoc, "minecraft:backrooms.fx.wallpaper_tear", SoundCategory.BLOCKS, 0.75f, 0.85f + rng.nextFloat() * 0.1f);
                }
                case BROWN_TERRACOTTA -> {
                    block.getWorld().dropItemNaturally(dropLoc, items.dampWallpaper(1));
                    block.getWorld().playSound(dropLoc, "minecraft:backrooms.fx.wallpaper_tear", SoundCategory.BLOCKS, 0.65f, 0.75f);
                }
                case IRON_BLOCK, COPPER_BLOCK, CUT_COPPER -> {
                    block.getWorld().dropItemNaturally(dropLoc, items.metalPlate(1));
                    block.getWorld().playSound(dropLoc, "minecraft:backrooms.fx.metal_pry", SoundCategory.BLOCKS, 0.65f, 0.95f);
                }
                default -> { }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("yunexal.backrooms.admin")) {
            return;
        }
        if (plugin.getLimboManager().isInLimbo(player) || !plugin.getLimboManager().isVerified(player)) {
            event.setCancelled(true);
            return;
        }
        Block block = event.getBlock();
        World bw = block.getWorld();
        World level1 = plugin.getLevel1World();
        World level2 = plugin.getLevel2World();
        if (!plugin.isBackroomsGameplayWorld(bw)) {
            event.setCancelled(true);
            return;
        }
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        boolean inL1 = (level1 != null && bw.equals(level1));
        boolean inL2 = (level2 != null && bw.equals(level2));
        if (inL1 && plugin.getLevel1Generator().isInProtectedOutpostCore(level1, block.getX(), block.getZ())) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Територія аванпосту людства захищена!", NamedTextColor.RED));
            return;
        }
        if (inL2 && plugin.getLevel2Generator().isInProtectedOfficeCore(level2, block.getX(), block.getZ())) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Технічний офіс уцілілих захищений!", NamedTextColor.RED));
            return;
        }
        int floorY = inL1 ? cfg.getLevel1FloorY() : (inL2 ? cfg.getLevel2FloorY() : cfg.getFloorY());
        int ceilingY = inL1 ? cfg.getLevel1CeilingY() : (inL2 ? cfg.getLevel2CeilingY() : cfg.getCeilingY());
        if (block.getY() >= ceilingY || block.getY() < floorY - 1) {
            event.setCancelled(true);
            return;
        }
        if (plugin.getItems().isItem(event.getItemInHand(), BackroomsItems.ID_FLASHLIGHT)) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Ліхтар світить лише в руках — не ставте його на землю.", NamedTextColor.YELLOW));
            return;
        }
        if (block.getType() == Material.BARREL && block.getState() instanceof Barrel barrel) {
            barrel.getPersistentDataContainer().set(manilaStockedKey, PersistentDataType.BYTE, (byte) 1);
            barrel.getPersistentDataContainer().set(manilaOpenedKey, PersistentDataType.BYTE, (byte) 1);
            barrel.getPersistentDataContainer().set(level1CrateKey, PersistentDataType.BYTE, (byte) 1);
            barrel.getPersistentDataContainer().set(level2CrateKey, PersistentDataType.BYTE, (byte) 1);
            barrel.update(true, false);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.getLimboManager().isInLimbo(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player p && plugin.getLimboManager().isInLimbo(p)) {
            event.setCancelled(true);
            return;
        }
        // Allow gradual exhaustion down to 4 drumsticks so Almond Water matters without lethal starvation
        if (event.getFoodLevel() < 4) {
            event.setFoodLevel(4);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && plugin.getLimboManager().isInLimbo(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.deathMessage(null);
        Player p = event.getEntity();
        lastDeathWorld.put(p.getUniqueId(), p.getWorld().getName());
        if (p.getWorld().equals(plugin.getLevel0World())) {
            plugin.getPlayerData().resetSurvivalSecondsLevel0(p);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (plugin.getLimboManager().isInLimbo(player)) {
            event.setRespawnLocation(plugin.getLimboManager().getLimboSpawnLocation());
            return;
        }
        String deathWorldName = lastDeathWorld.remove(player.getUniqueId());
        World level2 = plugin.getLevel2World();
        if (level2 != null && level2.getName().equals(deathWorldName)) {
            event.setRespawnLocation(plugin.getLevel2Generator().getArrivalLocation(level2));
            return;
        }
        World level1 = plugin.getLevel1World();
        if (level1 != null && level1.getName().equals(deathWorldName)) {
            event.setRespawnLocation(plugin.getLevel1Generator().getArrivalLocation(level1));
            return;
        }
        World w = plugin.getLevel0World();
        if (w != null) {
            event.setRespawnLocation(plugin.getLevel0Generator().findRandomFarSpawn(
                    w, Bukkit.getOnlinePlayers(), plugin.getBackroomsConfig().getSpawnRadius()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearPlayerState(event.getPlayer());
        if (plugin.getHallucinations() != null) {
            plugin.getHallucinations().clearPlayerState(event.getPlayer());
        }
        if (plugin.getBodycam() != null) {
            plugin.getBodycam().clearPlayer(event.getPlayer());
        }
    }
}
