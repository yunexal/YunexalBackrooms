package com.yunexal.backrooms;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages canonical Level 0 anomalies & psychological effects:
 * - Explorer fatigue & Almond Water serenity immunity
 * - Peripheral shadow silhouettes (isolated per-player entities that vanish when looked at)
 * - Client-side mirage doors & dead-end stairs disappearing on approach
 * - Auditory illusions behind isolated explorers
 * - Periodic Manila Room blackouts with hollow wall knocks behind perimeter drywall
 * - Broken anomaly compass erratic needle behavior
 */
public final class HallucinationManager {
    private final YunexalBackroomsPlugin plugin;

    private final Map<UUID, Double> fatigueMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> almondCalmUntilMs = new ConcurrentHashMap<>();
    private final Map<UUID, ShadowEntity> activeShadows = new ConcurrentHashMap<>();
    private final Map<UUID, MirageStructure> activeMirages = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextShadowCheckAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextMirageCheckAtMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextWhisperAtMs = new ConcurrentHashMap<>();
    private final Map<Long, ManilaBlackout> manilaBlackouts = new ConcurrentHashMap<>();

    private BukkitTask fastTask;
    private BukkitTask slowTask;

    private record ShadowEntity(ArmorStand stand, long expiresAtMs) {}

    private record MirageStructure(List<Location> blocks, Location anchor, long expiresAtMs) {}

    private static final class ManilaBlackout {
        boolean lightsOut;
        long nextToggleAtMs;
        long nextKnockAtMs;

        ManilaBlackout(long now) {
            this.lightsOut = false;
            this.nextToggleAtMs = now + ThreadLocalRandom.current().nextLong(35_000L, 80_000L);
            this.nextKnockAtMs = now;
        }
    }

    public HallucinationManager(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        shutdown();
        fastTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFast, 2L, 2L);
        slowTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSlow, 20L, 20L);
    }

    public void shutdown() {
        if (fastTask != null) {
            fastTask.cancel();
            fastTask = null;
        }
        if (slowTask != null) {
            slowTask.cancel();
            slowTask = null;
        }
        for (ShadowEntity s : activeShadows.values()) {
            if (s.stand() != null && !s.stand().isDead()) {
                s.stand().remove();
            }
        }
        activeShadows.clear();
        for (Map.Entry<UUID, MirageStructure> e : activeMirages.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline()) {
                restoreBlocks(p, e.getValue().blocks());
            }
        }
        activeMirages.clear();
        for (Long regionKey : manilaBlackouts.keySet()) {
            restoreManilaLightsAll(regionKey);
        }
        manilaBlackouts.clear();
    }

    public void clearPlayerState(Player player) {
        UUID uid = player.getUniqueId();
        despawnShadow(uid);
        despawnMirage(player);
        fatigueMap.remove(uid);
        almondCalmUntilMs.remove(uid);
        nextShadowCheckAtMs.remove(uid);
        nextMirageCheckAtMs.remove(uid);
        nextWhisperAtMs.remove(uid);
    }

    public double getFatigue(Player player) {
        return fatigueMap.getOrDefault(player.getUniqueId(), 0.0);
    }

    public void setFatigue(Player player, double val) {
        fatigueMap.put(player.getUniqueId(), Math.max(0.0, Math.min(1.0, val)));
    }

    public void reduceFatigue(Player player, double amount) {
        UUID uid = player.getUniqueId();
        double cur = fatigueMap.getOrDefault(uid, 0.0);
        fatigueMap.put(uid, Math.max(0.0, cur - amount));
    }

    public void resetFatigueWithCalm(Player player, long calmDurationMs) {
        UUID uid = player.getUniqueId();
        fatigueMap.put(uid, 0.0);
        almondCalmUntilMs.put(uid, System.currentTimeMillis() + calmDurationMs);
        despawnShadow(uid);
        despawnMirage(player);
    }

    private void tickFast() {
        long now = System.currentTimeMillis();
        World level0 = plugin.getLevel0World();
        if (level0 == null) {
            return;
        }

        // 1. Dismiss peripheral shadow silhouettes as soon as looked at or expired
        Iterator<Map.Entry<UUID, ShadowEntity>> it = activeShadows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ShadowEntity> entry = it.next();
            UUID uid = entry.getKey();
            ShadowEntity se = entry.getValue();
            Player p = Bukkit.getPlayer(uid);
            if (p == null || !p.isOnline() || !p.getWorld().equals(level0)
                    || now >= se.expiresAtMs() || se.stand().isDead() || isLookingAt(p, se.stand().getEyeLocation(), 0.84)) {
                if (se.stand() != null && !se.stand().isDead()) {
                    se.stand().remove();
                }
                it.remove();
            }
        }

        // 2. Dismiss mirage doors/stairs cleanly when explorer approaches closely (< 5.5 blocks) or times out
        Iterator<Map.Entry<UUID, MirageStructure>> mit = activeMirages.entrySet().iterator();
        while (mit.hasNext()) {
            Map.Entry<UUID, MirageStructure> entry = mit.next();
            Player p = Bukkit.getPlayer(entry.getKey());
            MirageStructure ms = entry.getValue();
            if (p == null || !p.isOnline() || !p.getWorld().equals(level0)
                    || now >= ms.expiresAtMs()
                    || p.getLocation().distanceSquared(ms.anchor()) <= 30.25) {
                if (p != null && p.isOnline()) {
                    restoreBlocks(p, ms.blocks());
                }
                mit.remove();
            }
        }

        // 3. Spin compass needles erratically every 8 ticks (handled on tickSlow / modular tick)
        if ((Bukkit.getCurrentTick() & 7) == 0) {
            spinCompasses(level0);
        }
    }

    private void tickSlow() {
        World level0 = plugin.getLevel0World();
        if (level0 == null) {
            return;
        }
        long now = System.currentTimeMillis();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Update fatigue & trigger individual psychological hallucinations
        World level1 = plugin.getLevel1World();
        World level2 = plugin.getLevel2World();
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean inL0 = player.getWorld().equals(level0);
            boolean inL1 = level1 != null && player.getWorld().equals(level1);
            boolean inL2 = level2 != null && player.getWorld().equals(level2);
            if ((!inL0 && !inL1 && !inL2)
                    || plugin.getLimboManager().isInLimbo(player)
                    || !plugin.getLimboManager().isVerified(player)) {
                continue;
            }

            UUID uid = player.getUniqueId();
            Location loc = player.getLocation();
            boolean inSafeSanctuary;
            if (inL0) {
                Level0ChunkGenerator.ZoneType zone = plugin.getLevel0Generator()
                        .getZoneAtBlock(level0, loc.getBlockX(), loc.getBlockZ());
                inSafeSanctuary = (zone == Level0ChunkGenerator.ZoneType.MANILA_ROOM);
                if (now >= almondCalmUntilMs.getOrDefault(uid, 0L)) {
                    double delta = switch (zone) {
                        case MANILA_ROOM -> -0.012;
                        case RED_ROOM -> +0.008;
                        case BLACKOUT_ZONE -> +0.004;
                        default -> +0.0018;
                    };
                    double next = Math.max(0.0, Math.min(1.0, fatigueMap.getOrDefault(uid, 0.0) + delta));
                    fatigueMap.put(uid, next);
                }
            } else if (inL2) {
                Level2ChunkGenerator.Level2ZoneType z2 = plugin.getLevel2Generator().getZoneAtBlock(level2, loc.getBlockX(), loc.getBlockZ());
                inSafeSanctuary = (z2 == Level2ChunkGenerator.Level2ZoneType.SURVIVOR_OFFICE);
                if (now >= almondCalmUntilMs.getOrDefault(uid, 0L)) {
                    double delta = inSafeSanctuary ? -0.015 : (z2 == Level2ChunkGenerator.Level2ZoneType.BOILER_HOT_ZONE ? +0.005 : +0.002);
                    fatigueMap.put(uid, Math.max(0.0, Math.min(1.0, fatigueMap.getOrDefault(uid, 0.0) + delta)));
                }
            } else {
                inSafeSanctuary = plugin.getLevel1Generator() != null
                        && plugin.getLevel1Generator().isInProtectedOutpostCore(level1, loc.getBlockX(), loc.getBlockZ());
                if (now >= almondCalmUntilMs.getOrDefault(uid, 0L)) {
                    boolean blackout = plugin.getLevel1Manager() != null
                            && plugin.getLevel1Manager().getBlackoutPhase() == Level1Manager.BlackoutPhase.BLACKOUT;
                    double delta = inSafeSanctuary ? -0.015 : (blackout ? +0.0042 : +0.0005);
                    double next = Math.max(0.0, Math.min(1.0, fatigueMap.getOrDefault(uid, 0.0) + delta));
                    fatigueMap.put(uid, next);
                }
            }

            boolean calm = now < almondCalmUntilMs.getOrDefault(uid, 0L);
            double fatigue = fatigueMap.getOrDefault(uid, 0.0);
            if (calm || inSafeSanctuary || !inL0) {
                continue;
            }

            // Mirage door / dead-end stairs ahead
            if (now >= nextMirageCheckAtMs.getOrDefault(uid, 0L)) {
                nextMirageCheckAtMs.put(uid, now + rng.nextLong(28_000L, 58_000L));
                if (!activeMirages.containsKey(uid) && rng.nextDouble() < (0.28 + fatigue * 0.45)) {
                    trySpawnMirage(player, level0);
                }
            }

            // Peripheral shadow figure when fatigued
            if (fatigue >= 0.18 && now >= nextShadowCheckAtMs.getOrDefault(uid, 0L)) {
                nextShadowCheckAtMs.put(uid, now + rng.nextLong(18_000L, 42_000L));
                if (!activeShadows.containsKey(uid) && rng.nextDouble() < (0.20 + fatigue * 0.55)) {
                    trySpawnShadow(player, level0);
                }
            }

            // Auditory whisper or faint footsteps directly behind player
            if (fatigue >= 0.16 && now >= nextWhisperAtMs.getOrDefault(uid, 0L)) {
                nextWhisperAtMs.put(uid, now + rng.nextLong(32_000L, 70_000L));
                if (rng.nextDouble() < (0.25 + fatigue * 0.50)) {
                    playAuditoryHallucination(player, rng);
                }
            }
        }

        // Canonical Manila Room periodic blackouts & wall knocks
        tickManilaBlackouts(level0, now, rng);
    }

    private void tickManilaBlackouts(World level0, long now, ThreadLocalRandom rng) {
        // Track regions currently occupied inside the Manila core
        Map<Long, List<Player>> activeRegions = new ConcurrentHashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(level0) || !plugin.getLimboManager().isVerified(p)) {
                continue;
            }
            int cx = Math.floorDiv(p.getLocation().getBlockX(), Level0ChunkGenerator.CELL_SIZE);
            int cz = Math.floorDiv(p.getLocation().getBlockZ(), Level0ChunkGenerator.CELL_SIZE);
            if (plugin.getLevel0Generator().getZoneAtBlock(level0, p.getLocation().getBlockX(), p.getLocation().getBlockZ())
                    != Level0ChunkGenerator.ZoneType.MANILA_ROOM) {
                continue;
            }
            int rlx = Math.floorMod(cx, 8);
            int rlz = Math.floorMod(cz, 8);
            if (rlx >= 2 && rlx <= 4 && rlz >= 2 && rlz <= 4) {
                int rx = Math.floorDiv(cx, 8);
                int rz = Math.floorDiv(cz, 8);
                long key = packRegion(rx, rz);
                activeRegions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(p);
            }
        }

        for (Map.Entry<Long, List<Player>> e : activeRegions.entrySet()) {
            long rKey = e.getKey();
            ManilaBlackout mb = manilaBlackouts.computeIfAbsent(rKey, ignored -> new ManilaBlackout(now));
            List<Player> inside = e.getValue();

            if (!mb.lightsOut && now >= mb.nextToggleAtMs) {
                mb.lightsOut = true;
                mb.nextToggleAtMs = now + rng.nextLong(8500L, 13_000L);
                mb.nextKnockAtMs = now + 1200L;
                setManilaLights(rKey, inside, false);
            } else if (mb.lightsOut && now >= mb.nextToggleAtMs) {
                mb.lightsOut = false;
                mb.nextToggleAtMs = now + rng.nextLong(45_000L, 95_000L);
                setManilaLights(rKey, inside, true);
            } else if (mb.lightsOut) {
                // Ensure late joiners inside dark Manila keep blackout view & subtle darkness
                for (Player p : inside) {
                    applyManilaDarkBlocks(p, rKey, false);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 45, 0, false, false, false));
                }
                if (now >= mb.nextKnockAtMs) {
                    mb.nextKnockAtMs = now + rng.nextLong(1900L, 3400L);
                    playManilaWallKnock(level0, rKey, inside, rng);
                }
            }
        }

        // Cleanup abandoned Manila blackout timers
        manilaBlackouts.keySet().removeIf(k -> !activeRegions.containsKey(k));
    }

    private void setManilaLights(long regionKey, List<Player> players, boolean on) {
        for (Player p : players) {
            applyManilaDarkBlocks(p, regionKey, on);
            p.playSound(p, "minecraft:backrooms.fx.light_switch", SoundCategory.BLOCKS, 0.38f, on ? 1.08f : 0.92f);
            if (!on) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 120, 0, false, false, false));
            } else {
                p.removePotionEffect(PotionEffectType.DARKNESS);
            }
        }
    }

    private void restoreManilaLightsAll(long regionKey) {
        World w = plugin.getLevel0World();
        if (w == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(w)) {
                applyManilaDarkBlocks(p, regionKey, true);
            }
        }
    }

    private void applyManilaDarkBlocks(Player p, long regionKey, boolean restore) {
        World w = plugin.getLevel0World();
        if (w == null) {
            return;
        }
        int rx = (int) (regionKey >> 32);
        int rz = (int) regionKey;
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        int ceilingY = cfg.getCeilingY();
        int lanternY = cfg.getFloorY() + 2;

        for (int dx = 2; dx <= 4; dx++) {
            for (int dz = 2; dz <= 4; dz++) {
                int wx = rx * 32 + dx * 4 + 2;
                int wz = rz * 32 + dz * 4 + 2;
                Location loc = new Location(w, wx, ceilingY, wz);
                p.sendBlockChange(loc, restore ? loc.getBlock().getBlockData() : Material.POLISHED_ANDESITE.createBlockData());
            }
        }
        Location lanternLoc = new Location(w, rx * 32 + 14, lanternY, rz * 32 + 13);
        p.sendBlockChange(lanternLoc, restore ? lanternLoc.getBlock().getBlockData() : Material.AIR.createBlockData());
    }

    private void playManilaWallKnock(World level0, long regionKey, List<Player> inside, ThreadLocalRandom rng) {
        int rx = (int) (regionKey >> 32);
        int rz = (int) regionKey;
        int y = plugin.getBackroomsConfig().getFloorY() + 2;
        int offset = rng.nextInt(9, 19);
        Location knockLoc = switch (rng.nextInt(4)) {
            case 0 -> new Location(level0, rx * 32 + 7.5, y, rz * 32 + offset + 0.5);
            case 1 -> new Location(level0, rx * 32 + 21.5, y, rz * 32 + offset + 0.5);
            case 2 -> new Location(level0, rx * 32 + offset + 0.5, y, rz * 32 + 7.5);
            default -> new Location(level0, rx * 32 + offset + 0.5, y, rz * 32 + 21.5);
        };
        for (Player p : inside) {
            p.playSound(knockLoc, "minecraft:backrooms.fx.wall_knock", SoundCategory.BLOCKS, 0.36f, 0.92f + rng.nextFloat() * 0.16f);
        }
    }

    private void trySpawnShadow(Player player, World level0) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location eye = player.getEyeLocation();
        float sideSign = rng.nextBoolean() ? 1f : -1f;
        float yawDeg = eye.getYaw() + sideSign * (44f + rng.nextFloat() * 26f);
        double rad = Math.toRadians(yawDeg);
        Vector dir = new Vector(-Math.sin(rad), 0.0, Math.cos(rad)).normalize();
        int floorY = plugin.getBackroomsConfig().getFloorY();

        for (int dist = 10; dist <= 15; dist++) {
            Location spot = eye.clone().add(dir.clone().multiply(dist));
            spot.setY(floorY + 1.0);
            Block feet = spot.getBlock();
            Block head = feet.getRelative(BlockFace.UP);
            Block below = feet.getRelative(BlockFace.DOWN);
            if (!feet.getType().isAir() || !head.getType().isAir() || below.getType().isAir()) {
                continue;
            }
            if (!player.hasLineOfSight(head.getLocation().add(0.5, 0.5, 0.5))) {
                continue;
            }

            Location spawnLoc = feet.getLocation().add(0.5, 0.0, 0.5);
            spawnLoc.setYaw(yawDeg + 180f);
            ArmorStand stand = level0.spawn(spawnLoc, ArmorStand.class, st -> {
                st.setVisibleByDefault(false);
                st.setInvisible(true);
                st.setGravity(false);
                st.setMarker(true);
                st.setBasePlate(false);
                st.setArms(true);
                st.setSilent(true);
                EntityEquipment eq = st.getEquipment();
                if (eq != null) {
                    eq.setHelmet(new ItemStack(Material.BLACK_CONCRETE));
                    eq.setChestplate(dyedLeather(Material.LEATHER_CHESTPLATE));
                    eq.setLeggings(dyedLeather(Material.LEATHER_LEGGINGS));
                    eq.setBoots(dyedLeather(Material.LEATHER_BOOTS));
                }
            });
            player.showEntity(plugin, stand);
            activeShadows.put(player.getUniqueId(), new ShadowEntity(stand, System.currentTimeMillis() + 2000L));
            return;
        }
    }

    private ItemStack dyedLeather(Material mat) {
        ItemStack item = new ItemStack(mat);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(Color.fromRGB(0x09090C));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void despawnShadow(UUID uid) {
        ShadowEntity se = activeShadows.remove(uid);
        if (se != null && se.stand() != null && !se.stand().isDead()) {
            se.stand().remove();
        }
    }

    private void trySpawnMirage(Player player, World level0) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Vector fwd = player.getEyeLocation().getDirection().setY(0);
        if (fwd.lengthSquared() < 1e-4) {
            return;
        }
        fwd.normalize();
        int floorY = plugin.getBackroomsConfig().getFloorY();
        BlockFace facing = yawToCardinal(player.getLocation().getYaw()).getOppositeFace();

        for (int dist = 11; dist <= 16; dist++) {
            Location center = player.getLocation().add(fwd.clone().multiply(dist));
            int bx = center.getBlockX();
            int bz = center.getBlockZ();
            Block b1 = level0.getBlockAt(bx, floorY + 1, bz);
            Block b2 = level0.getBlockAt(bx, floorY + 2, bz);
            Block b3 = level0.getBlockAt(bx, floorY + 3, bz);
            Block under = level0.getBlockAt(bx, floorY, bz);
            if (!b1.getType().isAir() || !b2.getType().isAir() || !b3.getType().isAir() || under.getType().isAir()) {
                continue;
            }
            // Also keep adjacent spot open so mirage doesn't spawn inside a single narrow doorframe
            Block back = b1.getRelative(facing.getOppositeFace());
            if (!back.getType().isAir()) {
                continue;
            }

            List<Location> changed = new ArrayList<>();
            if (rng.nextBoolean()) {
                // Mirage Door in corridor
                Door lower = (Door) Material.DARK_OAK_DOOR.createBlockData();
                lower.setHalf(Bisected.Half.BOTTOM);
                lower.setFacing(facing);
                Door upper = (Door) Material.DARK_OAK_DOOR.createBlockData();
                upper.setHalf(Bisected.Half.TOP);
                upper.setFacing(facing);
                player.sendBlockChange(b1.getLocation(), lower);
                player.sendBlockChange(b2.getLocation(), upper);
                changed.add(b1.getLocation());
                changed.add(b2.getLocation());
            } else {
                // Dead-end ascending oak stairs leading nowhere into the drop ceiling
                Block step1 = b1;
                Block step2 = b2.getRelative(facing.getOppositeFace());
                Block step3 = b3.getRelative(facing.getOppositeFace(), 2);
                if (!step2.getType().isAir() || !step3.getType().isAir()) {
                    return;
                }
                Stairs s1 = (Stairs) Material.OAK_STAIRS.createBlockData();
                s1.setFacing(facing.getOppositeFace());
                Stairs s2 = (Stairs) Material.OAK_STAIRS.createBlockData();
                s2.setFacing(facing.getOppositeFace());
                Stairs s3 = (Stairs) Material.OAK_STAIRS.createBlockData();
                s3.setFacing(facing.getOppositeFace());
                player.sendBlockChange(step1.getLocation(), s1);
                player.sendBlockChange(step2.getLocation(), s2);
                player.sendBlockChange(step3.getLocation(), s3);
                changed.add(step1.getLocation());
                changed.add(step2.getLocation());
                changed.add(step3.getLocation());
            }
            activeMirages.put(player.getUniqueId(), new MirageStructure(
                    changed, b1.getLocation().add(0.5, 0.5, 0.5), System.currentTimeMillis() + 24_000L));
            return;
        }
    }

    private void despawnMirage(Player player) {
        MirageStructure ms = activeMirages.remove(player.getUniqueId());
        if (ms != null) {
            restoreBlocks(player, ms.blocks());
        }
    }

    private void restoreBlocks(Player player, List<Location> blocks) {
        for (Location loc : blocks) {
            if (loc.getWorld() != null) {
                player.sendBlockChange(loc, loc.getBlock().getBlockData());
            }
        }
    }

    private void playAuditoryHallucination(Player player, ThreadLocalRandom rng) {
        Vector back = player.getEyeLocation().getDirection().setY(0);
        if (back.lengthSquared() < 1e-4) {
            return;
        }
        back.normalize().multiply(-4.5);
        Location soundLoc = player.getLocation().add(back).add(rng.nextDouble(-1.2, 1.2), 0.2, rng.nextDouble(-1.2, 1.2));
        if (rng.nextBoolean()) {
            player.playSound(soundLoc, "minecraft:backrooms.fx.whisper", SoundCategory.AMBIENT, 0.20f, 0.90f + rng.nextFloat() * 0.20f);
        } else {
            player.playSound(soundLoc, Sound.BLOCK_WOOL_STEP, SoundCategory.PLAYERS, 0.22f, 0.72f + rng.nextFloat() * 0.14f);
        }
    }

    private void spinCompasses(World level0) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int floorY = plugin.getBackroomsConfig().getFloorY();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(level0)) {
                continue;
            }
            for (int slot = 0; slot < p.getInventory().getSize(); slot++) {
                ItemStack stack = p.getInventory().getItem(slot);
                if (stack == null || stack.getType() != Material.COMPASS) {
                    continue;
                }
                if (stack.getItemMeta() instanceof CompassMeta meta) {
                    Location erratic = new Location(level0,
                            p.getLocation().getX() + rng.nextInt(-600, 601),
                            floorY,
                            p.getLocation().getZ() + rng.nextInt(-600, 601));
                    meta.setLodestone(erratic);
                    meta.setLodestoneTracked(false);
                    stack.setItemMeta(meta);
                }
            }
        }
    }

    private static boolean isLookingAt(Player player, Location target, double dotThreshold) {
        Vector toTarget = target.toVector().subtract(player.getEyeLocation().toVector());
        if (toTarget.lengthSquared() < 1e-6) {
            return true;
        }
        return player.getEyeLocation().getDirection().normalize().dot(toTarget.normalize()) >= dotThreshold;
    }

    private static BlockFace yawToCardinal(float yaw) {
        float y = ((yaw % 360f) + 360f) % 360f;
        if (y >= 45f && y < 135f) return BlockFace.WEST;
        if (y >= 135f && y < 225f) return BlockFace.NORTH;
        if (y >= 225f && y < 315f) return BlockFace.EAST;
        return BlockFace.SOUTH;
    }

    private static long packRegion(int rx, int rz) {
        return (((long) rx) << 32) ^ (rz & 0xFFFFFFFFL);
    }
}
