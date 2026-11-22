package com.yunexal.backrooms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles canonical Level 0 gameplay: entity-attached ambient hum & lamp-anchored flicker,
 * Red Room dread, soft-carpet noclip, rare non-linear space shifts, Adventure scavenging,
 * flashlight light projection, and privacy without scoreboards or coordinates.
 */
public final class BackroomsListeners implements Listener {
    private final YunexalBackroomsPlugin plugin;
    private final NamespacedKey manilaStockedKey;
    private final NamespacedKey manilaOpenedKey;

    private final Map<UUID, Long> lastHumAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFlickerAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRedPulseAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastInteractAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAnomalyAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNoclipAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerCellKey = new ConcurrentHashMap<>();
    private final Map<UUID, Location> activeFlashlightLoc = new ConcurrentHashMap<>();
    private final Map<BlockKey, Integer> ventHitProgress = new ConcurrentHashMap<>();

    private BukkitTask ambientTask;
    private BukkitTask flashlightTask;
    private BukkitTask autosaveTask;

    private record BlockKey(UUID worldId, int x, int y, int z) {}

    public BackroomsListeners(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
        this.manilaStockedKey = new NamespacedKey(plugin, "manila_stocked");
        this.manilaOpenedKey = new NamespacedKey(plugin, "manila_opened");
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
        }
        activeFlashlightLoc.clear();
        lastHumAtMs.clear();
        lastFlickerAtMs.clear();
        lastRedPulseAtMs.clear();
        lastInteractAtMs.clear();
        lastAnomalyAtMs.clear();
        lastNoclipAtMs.clear();
        playerCellKey.clear();
        ventHitProgress.clear();
    }

    public void clearPlayerState(Player player) {
        restoreFlashlight(player);
        UUID uid = player.getUniqueId();
        lastHumAtMs.remove(uid);
        lastFlickerAtMs.remove(uid);
        lastRedPulseAtMs.remove(uid);
        lastInteractAtMs.remove(uid);
        lastAnomalyAtMs.remove(uid);
        lastNoclipAtMs.remove(uid);
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

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(level0)) {
                continue;
            }
            if (plugin.getLimboManager().isInLimbo(player) || !plugin.getLimboManager().isVerified(player)) {
                continue;
            }

            UUID uid = player.getUniqueId();
            Location loc = player.getLocation();
            Level0ChunkGenerator.ZoneType zone = plugin.getLevel0Generator()
                    .getZoneAtBlock(level0, loc.getBlockX(), loc.getBlockZ());

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

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(level0)
                    || plugin.getLimboManager().isInLimbo(player)
                    || !plugin.getLimboManager().isVerified(player)) {
                restoreFlashlight(player);
                continue;
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
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        BackroomsConfig cfg = plugin.getBackroomsConfig();
        long now = System.currentTimeMillis();
        UUID uid = player.getUniqueId();
        int floorY = cfg.getFloorY();
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
        player.playSound(player, "minecraft:backrooms.ui.noclip", SoundCategory.MASTER, 0.65f, 1.0f);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 36, 0, false, false, false));
        player.showTitle(Title.title(
                Component.text("МЕЖА РІВНЯ 1", NamedTextColor.GRAY, TextDecoration.BOLD),
                Component.text("«Житлова зона» у розробці — вас відкинуло назад у Лобі", NamedTextColor.DARK_GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(700))
        ));
        Location nextSpot = plugin.getLevel0Generator().findRandomFarSpawn(
                level0, Bukkit.getOnlinePlayers(), plugin.getBackroomsConfig().getSpawnRadius());
        player.teleport(nextSpot);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        World level0 = plugin.getLevel0World();
        if (level0 == null || !player.getWorld().equals(level0)) {
            return;
        }
        if (plugin.getLimboManager().isInLimbo(player) || !plugin.getLimboManager().isVerified(player)) {
            return;
        }

        BackroomsItems items = plugin.getItems();
        ItemStack held = player.getInventory().getItemInMainHand();
        String heldId = items.getItemId(held);
        Action action = event.getAction();
        Block clicked = event.getClickedBlock();

        // Allow native container/station usage cleanly, ensuring Manila barrel always has loot
        if (action == Action.RIGHT_CLICK_BLOCK && clicked != null) {
            if (clicked.getType() == Material.CRAFTING_TABLE) {
                return;
            }
            if (clicked.getType() == Material.BARREL) {
                if (clicked.getState() instanceof Barrel barrel
                        && plugin.getLevel0Generator().getZoneAtBlock(level0, clicked.getX(), clicked.getZ())
                        == Level0ChunkGenerator.ZoneType.MANILA_ROOM) {
                    var pdc = barrel.getPersistentDataContainer();
                    if (!pdc.has(manilaStockedKey, PersistentDataType.BYTE)
                            || (!pdc.has(manilaOpenedKey, PersistentDataType.BYTE) && barrel.getInventory().isEmpty())) {
                        Level0Populator.fillManilaBarrelLoot(barrel.getInventory(), barrel, ThreadLocalRandom.current(), items, manilaStockedKey);
                        pdc.set(manilaOpenedKey, PersistentDataType.BYTE, (byte) 1);
                        barrel.update(true, false);
                    } else if (!pdc.has(manilaOpenedKey, PersistentDataType.BYTE)) {
                        pdc.set(manilaOpenedKey, PersistentDataType.BYTE, (byte) 1);
                        barrel.update(true, false);
                    }
                }
                return;
            }
        }

        // 1. Usable custom survival items on right-click
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (BackroomsItems.ID_WORKBENCH.equals(heldId)) {
                event.setCancelled(true);
                player.openWorkbench(null, true);
                return;
            }
            if (BackroomsItems.ID_BANDAGE.equals(heldId)) {
                event.setCancelled(true);
                applyBandage(player, held);
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
                    && clicked.getType() == Material.BROWN_WOOL) {
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

        // Dead 1980s North American electrical wall outlet (non-functional on Level 0)
        if (clicked != null && clicked.getType() == Material.CYAN_TERRACOTTA
                && (action == Action.LEFT_CLICK_BLOCK || action == Action.RIGHT_CLICK_BLOCK)) {
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

        // 2. Scavenging Level 0 materials & picking up markers in Adventure mode
        if (clicked == null || (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK)) {
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
        if (m != Material.BROWN_WOOL && m != Material.GRAY_WOOL && m != Material.RED_WOOL) {
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
                block.setType(Material.GRAY_WOOL, false);
                giveOrDrop(player, items.carpetFiber(1 + rng.nextInt(2)), soundLoc);
                player.playSound(soundLoc, "minecraft:backrooms.fx.carpet_rip", SoundCategory.BLOCKS, 0.7f, 0.95f + rng.nextFloat() * 0.1f);
                return true;
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
        if (!event.getPlayer().hasPermission("yunexal.backrooms.admin")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!event.getPlayer().hasPermission("yunexal.backrooms.admin")) {
            event.setCancelled(true);
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
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (plugin.getLimboManager().isInLimbo(player)) {
            event.setRespawnLocation(plugin.getLimboManager().getLimboSpawnLocation());
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
