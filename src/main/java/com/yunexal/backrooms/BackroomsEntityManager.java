package com.yunexal.backrooms;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom Backrooms entities framework (Bacteria, Smiler, Hound):
 * - Invisible silent Zombie pathfinding hosts paired with smooth 60fps interpolated Display passenger rigs
 * - Canonical behaviors: Manila Room safe sanctuary, Smiler flashlight stare-lock, Hound eye intimidation, Bacteria shriek chase
 * - Ready for both admin testing (/br entity spawn) and automatic spawning (/br entity autospawn on)
 */
public final class BackroomsEntityManager implements Listener {
    public enum EntityKind {
        BACTERIA,
        SMILER,
        HOUND
    }

    private final YunexalBackroomsPlugin plugin;
    private final NamespacedKey entityKindKey;
    private final NamespacedKey partTagKey;
    private final Map<UUID, TrackedEntity> tracked = new ConcurrentHashMap<>();

    private volatile boolean autoSpawnOverride;
    private BukkitTask aiTask;
    private BukkitTask spawnTask;

    private static final class TrackedEntity {
        final EntityKind kind;
        final Zombie host;
        final List<Display> parts;
        final boolean syncYaw;
        long nextVocalAtMs;
        long nextStepAtMs;
        long chaseBurstUntilMs;
        long houndPauseUntilMs;
        boolean stunned;

        TrackedEntity(EntityKind kind, Zombie host, List<Display> parts, boolean syncYaw) {
            this.kind = kind;
            this.host = host;
            this.parts = parts;
            this.syncYaw = syncYaw;
        }
    }

    public BackroomsEntityManager(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
        this.entityKindKey = new NamespacedKey(plugin, "backrooms_entity_kind");
        this.partTagKey = new NamespacedKey(plugin, "backrooms_entity_part");
        this.autoSpawnOverride = plugin.getBackroomsConfig().isEntitiesAutoSpawn();
    }

    public void reloadFromConfig() {
        this.autoSpawnOverride = plugin.getBackroomsConfig().isEntitiesAutoSpawn();
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        startSpawnTimer();
    }

    public void start() {
        shutdown();
        purgeOrphanParts(plugin.getLevel0World());
        aiTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAI, 2L, 2L);
        startSpawnTimer();
    }

    private void startSpawnTimer() {
        long intervalTicks = Math.max(200L, plugin.getBackroomsConfig().getEntitiesAutoSpawnIntervalSeconds() * 20L);
        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAutoSpawn, intervalTicks, intervalTicks);
    }

    public void shutdown() {
        if (aiTask != null) {
            aiTask.cancel();
            aiTask = null;
        }
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        clearAll();
    }

    public boolean isAutoSpawnEnabled() {
        return autoSpawnOverride;
    }

    public void setAutoSpawnEnabled(boolean enabled) {
        this.autoSpawnOverride = enabled;
    }

    public int getActiveCount() {
        tracked.entrySet().removeIf(e -> !e.getValue().host.isValid() || e.getValue().host.isDead());
        return tracked.size();
    }

    public int clearAll() {
        int removed = tracked.size();
        for (TrackedEntity te : tracked.values()) {
            removeTracked(te);
        }
        tracked.clear();
        purgeOrphanParts(plugin.getLevel0World());
        return removed;
    }

    public boolean spawnEntity(Location loc, EntityKind kind) {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        World level0 = plugin.getLevel0World();
        if (!cfg.isEntitiesEnabled() || level0 == null || !loc.getWorld().equals(level0)) {
            return false;
        }

        // Prevent spawning inside walls or Manila Room sanctuary
        if (plugin.getLevel0Generator().getZoneAtBlock(level0, loc.getBlockX(), loc.getBlockZ())
                == Level0ChunkGenerator.ZoneType.MANILA_ROOM) {
            return false;
        }

        Zombie host = level0.spawn(loc, Zombie.class, z -> {
            z.setAdult();
            z.setInvisible(true);
            z.setSilent(true);
            z.setShouldBurnInDay(false);
            z.setCanBreakDoors(false);
            z.setRemoveWhenFarAway(false);
            z.setPersistent(false);
            if (z.getEquipment() != null) {
                z.getEquipment().clear();
            }
            z.getPersistentDataContainer().set(entityKindKey, PersistentDataType.STRING, kind.name());
            z.getPersistentDataContainer().set(partTagKey, PersistentDataType.BYTE, (byte) 1);

            switch (kind) {
                case BACTERIA -> {
                    setAttr(z, Attribute.MAX_HEALTH, 40.0);
                    z.setHealth(40.0);
                    setAttr(z, Attribute.MOVEMENT_SPEED, 0.27);
                    setAttr(z, Attribute.ATTACK_DAMAGE, 6.0);
                    setAttr(z, Attribute.FOLLOW_RANGE, 32.0);
                    setAttr(z, Attribute.STEP_HEIGHT, 1.0);
                }
                case SMILER -> {
                    setAttr(z, Attribute.MAX_HEALTH, 26.0);
                    z.setHealth(26.0);
                    setAttr(z, Attribute.MOVEMENT_SPEED, 0.30);
                    setAttr(z, Attribute.ATTACK_DAMAGE, 5.0);
                    setAttr(z, Attribute.FOLLOW_RANGE, 28.0);
                    setAttr(z, Attribute.STEP_HEIGHT, 1.0);
                }
                case HOUND -> {
                    setAttr(z, Attribute.MAX_HEALTH, 22.0);
                    z.setHealth(22.0);
                    setAttr(z, Attribute.MOVEMENT_SPEED, 0.34);
                    setAttr(z, Attribute.ATTACK_DAMAGE, 4.0);
                    setAttr(z, Attribute.FOLLOW_RANGE, 26.0);
                    setAttr(z, Attribute.STEP_HEIGHT, 1.0);
                }
            }
        });

        List<Display> parts = buildVisualRig(level0, loc, host, kind);
        boolean syncYaw = (kind != EntityKind.SMILER);
        tracked.put(host.getUniqueId(), new TrackedEntity(kind, host, parts, syncYaw));
        return true;
    }

    private List<Display> buildVisualRig(World w, Location loc, Zombie host, EntityKind kind) {
        List<Display> parts = new ArrayList<>();
        switch (kind) {
            case BACTERIA -> {
                // Tall (~2.4m) gaunt dark wire creature mounted cleanly onto Zombie seat
                Material mat = Material.BLACK_GLAZED_TERRACOTTA;
                parts.add(spawnBlockPart(w, loc, host, mat, -0.20f, -1.62f, -0.07f, 0.13f, 1.10f, 0.13f, 0, false));
                parts.add(spawnBlockPart(w, loc, host, mat, +0.07f, -1.62f, -0.07f, 0.13f, 1.10f, 0.13f, 0, false));
                parts.add(spawnBlockPart(w, loc, host, mat, -0.14f, -0.54f, -0.09f, 0.28f, 0.90f, 0.18f, 0, false));
                parts.add(spawnBlockPart(w, loc, host, mat, -0.34f, -0.62f, -0.06f, 0.11f, 0.95f, 0.11f, 0, false));
                parts.add(spawnBlockPart(w, loc, host, mat, +0.23f, -0.62f, -0.06f, 0.11f, 0.95f, 0.11f, 0, false));
                parts.add(spawnBlockPart(w, loc, host, mat, -0.15f, +0.34f, -0.15f, 0.30f, 0.44f, 0.30f, 0, false));
            }
            case SMILER -> {
                // Floating fullbright glowing white eyes + smile sprite in darkness
                ItemDisplay face = spawnItemPart(w, loc, host, "backrooms_smiler", 0.0f, -0.15f, 0.0f, 1.05f, 1.05f, 1.05f, Display.Billboard.VERTICAL, true);
                parts.add(face);
            }
            case HOUND -> {
                // Low hunched dark quadruped body + glowing predator eyes
                Material hide = Material.GRAY_GLAZED_TERRACOTTA;
                parts.add(spawnBlockPart(w, loc, host, hide, -0.22f, -1.15f, -0.45f, 0.44f, 0.38f, 0.90f, 0, false));
                parts.add(spawnBlockPart(w, loc, host, hide, -0.18f, -0.92f, +0.34f, 0.36f, 0.34f, 0.36f, 0, false));
                parts.add(spawnBlockPart(w, loc, host, hide, -0.23f, -1.62f, +0.26f, 0.12f, 0.50f, 0.12f, 0, false));
                parts.add(spawnBlockPart(w, loc, host, hide, +0.11f, -1.62f, +0.26f, 0.12f, 0.50f, 0.12f, 0, false));
                parts.add(spawnBlockPart(w, loc, host, hide, -0.23f, -1.62f, -0.38f, 0.12f, 0.50f, 0.12f, 0, false));
                parts.add(spawnBlockPart(w, loc, host, hide, +0.11f, -1.62f, -0.38f, 0.12f, 0.50f, 0.12f, 0, false));
                parts.add(spawnItemPart(w, loc, host, "backrooms_hound_eyes", 0.0f, -0.75f, +0.55f, 0.42f, 0.42f, 0.42f, Display.Billboard.FIXED, true));
            }
        }
        return parts;
    }

    private BlockDisplay spawnBlockPart(World w, Location loc, Zombie host, Material mat,
                                        float tx, float ty, float tz, float sx, float sy, float sz,
                                        int bright, boolean fullbright) {
        BlockDisplay d = w.spawn(loc, BlockDisplay.class, bd -> {
            bd.setBlock(mat.createBlockData());
            bd.setBillboard(Display.Billboard.FIXED);
            bd.setInterpolationDelay(0);
            bd.setInterpolationDuration(3);
            bd.setTeleportDuration(2);
            if (fullbright) {
                bd.setBrightness(new Display.Brightness(15, 15));
            } else if (bright > 0) {
                bd.setBrightness(new Display.Brightness(bright, 0));
            }
            bd.setTransformation(new Transformation(
                    new Vector3f(tx, ty, tz),
                    new AxisAngle4f(0f, 0f, 1f, 0f),
                    new Vector3f(sx, sy, sz),
                    new AxisAngle4f(0f, 0f, 1f, 0f)
            ));
            bd.getPersistentDataContainer().set(partTagKey, PersistentDataType.BYTE, (byte) 1);
            bd.setPersistent(false);
        });
        host.addPassenger(d);
        return d;
    }

    private ItemDisplay spawnItemPart(World w, Location loc, Zombie host, String modelName,
                                      float tx, float ty, float tz, float sx, float sy, float sz,
                                      Display.Billboard billboard, boolean fullbright) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(NamespacedKey.minecraft(modelName));
        stack.setItemMeta(meta);

        ItemDisplay d = w.spawn(loc, ItemDisplay.class, id -> {
            id.setItemStack(stack);
            id.setBillboard(billboard);
            id.setInterpolationDelay(0);
            id.setInterpolationDuration(3);
            id.setTeleportDuration(2);
            if (fullbright) {
                id.setBrightness(new Display.Brightness(15, 15));
            }
            id.setTransformation(new Transformation(
                    new Vector3f(tx, ty, tz),
                    new AxisAngle4f(0f, 0f, 1f, 0f),
                    new Vector3f(sx, sy, sz),
                    new AxisAngle4f(0f, 0f, 1f, 0f)
            ));
            id.getPersistentDataContainer().set(partTagKey, PersistentDataType.BYTE, (byte) 1);
            id.setPersistent(false);
        });
        host.addPassenger(d);
        return d;
    }

    private void tickAI() {
        World level0 = plugin.getLevel0World();
        if (level0 == null || tracked.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        double despawnDistSq = Math.pow(plugin.getBackroomsConfig().getEntitiesDespawnDistance(), 2);

        Iterator<Map.Entry<UUID, TrackedEntity>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            TrackedEntity te = it.next().getValue();
            Zombie z = te.host;
            if (!z.isValid() || z.isDead()) {
                removeTracked(te);
                it.remove();
                continue;
            }

            Location mLoc = z.getLocation();
            Level0ChunkGenerator.ZoneType zone = plugin.getLevel0Generator()
                    .getZoneAtBlock(level0, mLoc.getBlockX(), mLoc.getBlockZ());
            if (zone == Level0ChunkGenerator.ZoneType.MANILA_ROOM) {
                removeTracked(te);
                it.remove();
                continue;
            }

            Player nearest = null;
            double nearestSq = Double.MAX_VALUE;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getWorld().equals(level0) || plugin.getLimboManager().isInLimbo(p)
                        || !plugin.getLimboManager().isVerified(p)) {
                    continue;
                }
                double dSq = p.getLocation().distanceSquared(mLoc);
                if (dSq < nearestSq) {
                    nearestSq = dSq;
                    nearest = p;
                }
            }

            if (nearest == null || nearestSq > despawnDistSq) {
                removeTracked(te);
                it.remove();
                continue;
            }

            // Do not pursue into Manila Room safe zone
            Level0ChunkGenerator.ZoneType pZone = plugin.getLevel0Generator()
                    .getZoneAtBlock(level0, nearest.getLocation().getBlockX(), nearest.getLocation().getBlockZ());
            if (pZone == Level0ChunkGenerator.ZoneType.MANILA_ROOM) {
                z.setTarget(null);
            } else {
                z.setTarget(nearest);
            }

            if (te.syncYaw) {
                float yaw = mLoc.getYaw();
                for (Display d : te.parts) {
                    if (d.isValid()) {
                        d.setRotation(yaw, 0f);
                    }
                }
            }

            boolean los = nearest.hasLineOfSight(z);
            double dist = Math.sqrt(nearestSq);
            switch (te.kind) {
                case BACTERIA -> tickBacteria(te, z, nearest, mLoc, dist, los, now);
                case SMILER -> tickSmiler(te, z, nearest, mLoc, dist, los, now);
                case HOUND -> tickHound(te, z, nearest, mLoc, dist, los, now);
            }
        }
    }

    private void tickBacteria(TrackedEntity te, Zombie z, Player target, Location mLoc, double dist, boolean los, long now) {
        if (los && dist <= 20.0 && now >= te.nextVocalAtMs) {
            te.nextVocalAtMs = now + 7500L;
            te.chaseBurstUntilMs = now + 3500L;
            target.playSound(mLoc, "minecraft:backrooms.entity.bacteria.roar", SoundCategory.HOSTILE, 0.58f, 1.0f);
            target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 65, 0, false, false, false));
        }
        double speed = (now < te.chaseBurstUntilMs) ? 0.34 : 0.27;
        setAttr(z, Attribute.MOVEMENT_SPEED, speed);
        if (z.getVelocity().lengthSquared() > 0.002 && now >= te.nextStepAtMs) {
            te.nextStepAtMs = now + (now < te.chaseBurstUntilMs ? 280L : 440L);
            mLoc.getWorld().playSound(mLoc, "minecraft:backrooms.entity.bacteria.step", SoundCategory.HOSTILE, 0.38f,
                    0.92f + ThreadLocalRandom.current().nextFloat() * 0.16f);
        }
    }

    private void tickSmiler(TrackedEntity te, Zombie z, Player target, Location mLoc, double dist, boolean los, long now) {
        boolean holdingLight = plugin.getItems().isItem(target.getInventory().getItemInMainHand(), BackroomsItems.ID_FLASHLIGHT)
                || plugin.getItems().isItem(target.getInventory().getItemInOffHand(), BackroomsItems.ID_FLASHLIGHT);
        boolean staredAt = los && holdingLight && isLookingAt(target, mLoc.clone().add(0, 1.4, 0), 0.87);

        if (staredAt && dist <= 16.0) {
            if (!te.stunned && now >= te.nextVocalAtMs) {
                te.nextVocalAtMs = now + 3200L;
                target.playSound(mLoc, "minecraft:backrooms.entity.smiler.hiss", SoundCategory.HOSTILE, 0.50f, 1.05f);
            }
            te.stunned = true;
            setAttr(z, Attribute.MOVEMENT_SPEED, 0.0);
        } else {
            te.stunned = false;
            setAttr(z, Attribute.MOVEMENT_SPEED, los ? 0.33 : 0.28);
            if (los && dist <= 14.0 && now >= te.nextVocalAtMs) {
                te.nextVocalAtMs = now + 5000L;
                target.playSound(mLoc, "minecraft:backrooms.entity.smiler.hiss", SoundCategory.HOSTILE, 0.45f, 0.95f);
            }
        }
    }

    private void tickHound(TrackedEntity te, Zombie z, Player target, Location mLoc, double dist, boolean los, long now) {
        if (los && dist <= 9.0 && isLookingAt(target, mLoc.clone().add(0, 0.9, 0), 0.90) && now >= te.nextVocalAtMs) {
            te.nextVocalAtMs = now + 4500L;
            te.houndPauseUntilMs = now + 1100L;
            target.playSound(mLoc, "minecraft:backrooms.entity.hound.growl", SoundCategory.HOSTILE, 0.52f, 1.0f);
        }
        setAttr(z, Attribute.MOVEMENT_SPEED, now < te.houndPauseUntilMs ? 0.08 : 0.35);
    }

    private void tickAutoSpawn() {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        World level0 = plugin.getLevel0World();
        if (!cfg.isEntitiesEnabled() || !autoSpawnOverride || level0 == null) {
            return;
        }
        if (getActiveCount() >= cfg.getEntitiesMaxGlobal()) {
            return;
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(level0)
                    || plugin.getLimboManager().isInLimbo(player)
                    || !plugin.getLimboManager().isVerified(player)) {
                continue;
            }
            if (rng.nextDouble() >= cfg.getEntitiesAutoSpawnChance()) {
                continue;
            }
            Location pLoc = player.getLocation();
            Level0ChunkGenerator.ZoneType zone = plugin.getLevel0Generator()
                    .getZoneAtBlock(level0, pLoc.getBlockX(), pLoc.getBlockZ());
            if (zone == Level0ChunkGenerator.ZoneType.MANILA_ROOM) {
                continue;
            }

            Location spawnSpot = findSpawnSpotNear(level0, pLoc, cfg.getFloorY(), 18, 30);
            if (spawnSpot == null) {
                continue;
            }

            Level0ChunkGenerator.ZoneType sZone = plugin.getLevel0Generator()
                    .getZoneAtBlock(level0, spawnSpot.getBlockX(), spawnSpot.getBlockZ());
            EntityKind kind;
            if (sZone == Level0ChunkGenerator.ZoneType.BLACKOUT_ZONE || sZone == Level0ChunkGenerator.ZoneType.RED_ROOM) {
                kind = rng.nextBoolean() ? EntityKind.SMILER : EntityKind.BACTERIA;
            } else if (sZone == Level0ChunkGenerator.ZoneType.NARROW_MAZE) {
                kind = EntityKind.HOUND;
            } else {
                kind = rng.nextDouble() < 0.60 ? EntityKind.BACTERIA : EntityKind.HOUND;
            }
            spawnEntity(spawnSpot, kind);
            break;
        }
    }

    public Location findSpawnSpotNear(World w, Location origin, int floorY, int minDist, int maxDist) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 24; attempt++) {
            double ang = rng.nextDouble() * Math.PI * 2.0;
            double d = minDist + rng.nextDouble() * (maxDist - minDist);
            int x = (int) Math.floor(origin.getX() + Math.cos(ang) * d);
            int z = (int) Math.floor(origin.getZ() + Math.sin(ang) * d);
            if (plugin.getLevel0Generator().getZoneAtBlock(w, x, z) == Level0ChunkGenerator.ZoneType.MANILA_ROOM) {
                continue;
            }
            Block floor = w.getBlockAt(x, floorY, z);
            Block feet = w.getBlockAt(x, floorY + 1, z);
            Block head = w.getBlockAt(x, floorY + 2, z);
            Block top = w.getBlockAt(x, floorY + 3, z);
            if (floor.getType().isSolid() && feet.getType().isAir() && head.getType().isAir() && top.getType().isAir()) {
                return new Location(w, x + 0.5, floorY + 1.0, z + 0.5);
            }
        }
        return null;
    }

    private void removeTracked(TrackedEntity te) {
        for (Entity p : te.host.getPassengers()) {
            p.remove();
        }
        for (Display d : te.parts) {
            if (d.isValid()) {
                d.remove();
            }
        }
        if (te.host.isValid()) {
            te.host.remove();
        }
    }

    private void purgeOrphanParts(World w) {
        if (w == null) {
            return;
        }
        for (Entity e : w.getEntities()) {
            if (e.getPersistentDataContainer().has(partTagKey, PersistentDataType.BYTE)
                    && !tracked.containsKey(e.getUniqueId())) {
                e.remove();
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity e : event.getChunk().getEntities()) {
            if (e.getPersistentDataContainer().has(partTagKey, PersistentDataType.BYTE)
                    && !tracked.containsKey(e.getUniqueId())) {
                e.remove();
            }
        }
    }

    @EventHandler
    public void onCombust(EntityCombustEvent event) {
        if (tracked.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent event) {
        TrackedEntity te = tracked.get(event.getEntity().getUniqueId());
        if (te == null) {
            return;
        }
        if (!(event.getTarget() instanceof Player p) || plugin.getLimboManager().isInLimbo(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        TrackedEntity te = tracked.get(event.getDamager().getUniqueId());
        if (te != null && event.getEntity() instanceof Player p) {
            if (te.kind == EntityKind.SMILER) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false, false));
            } else if (te.kind == EntityKind.BACTERIA) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, false));
            }
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        TrackedEntity te = tracked.remove(event.getEntity().getUniqueId());
        if (te == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        for (Entity p : te.host.getPassengers()) {
            p.remove();
        }
        for (Display d : te.parts) {
            if (d.isValid()) {
                d.remove();
            }
        }
        if (ThreadLocalRandom.current().nextDouble() < 0.35) {
            event.getDrops().add(plugin.getItems().almondWater(1));
        } else {
            event.getDrops().add(plugin.getItems().metalPlate(2));
        }
    }

    public static EntityKind parseKind(String raw) {
        try {
            return EntityKind.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void setAttr(Zombie z, Attribute attr, double value) {
        var inst = z.getAttribute(attr);
        if (inst != null) {
            inst.setBaseValue(value);
        }
    }

    private static boolean isLookingAt(Player player, Location target, double dotThreshold) {
        Vector toTarget = target.toVector().subtract(player.getEyeLocation().toVector());
        if (toTarget.lengthSquared() < 1e-6) {
            return true;
        }
        return player.getEyeLocation().getDirection().normalize().dot(toTarget.normalize()) >= dotThreshold;
    }
}
