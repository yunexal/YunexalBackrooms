package com.yunexal.backrooms;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
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
import org.joml.Quaternionf;
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
 * Custom Blockbench-grade Backrooms entities framework (Bacteria, Smiler, Hound):
 * - Invisible silent Zombie pathfinding hosts paired with multi-bone ItemDisplay rigs compiled from Blockbench models
 * - Smooth 60fps interpolated skeletal walking/snarling/floating joint animations
 * - Canonical behaviors: Manila Room safe sanctuary, Smiler flashlight stare-lock, Hound eye intimidation, Bacteria shriek chase
 */
public final class BackroomsEntityManager implements Listener {
    public enum EntityKind {
        BACTERIA,
        SMILER,
        HOUND,
        FACELESS,
        SKIN_STEALER
    }

    private final YunexalBackroomsPlugin plugin;
    private final NamespacedKey entityKindKey;
    private final NamespacedKey partTagKey;
    private final Map<UUID, TrackedEntity> tracked = new ConcurrentHashMap<>();

    private volatile boolean autoSpawnOverride;
    private BukkitTask aiTask;
    private BukkitTask spawnTask;

    private static final class BonePart {
        final ItemDisplay display;
        final float baseX;
        final float baseY;
        final float baseZ;
        final float scale;

        BonePart(ItemDisplay display, float baseX, float baseY, float baseZ, float scale) {
            this.display = display;
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
            this.scale = scale;
        }
    }

    private static final class TrackedEntity {
        final EntityKind kind;
        final Zombie host;
        final Map<String, BonePart> bones = new ConcurrentHashMap<>();
        double animPhase;
        long nextVocalAtMs;
        long nextStepAtMs;
        long chaseBurstUntilMs;
        long houndPauseUntilMs;
        long provokedUntilMs;
        boolean stunned;

        TrackedEntity(EntityKind kind, Zombie host) {
            this.kind = kind;
            this.host = host;
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
        purgeOrphanParts(plugin.getLevel1World());
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

    public boolean isTrackedEntity(Entity e) {
        return e != null && tracked.containsKey(e.getUniqueId());
    }

    public Zombie findNearestEntityHost(Location origin, double maxRange) {
        World w = origin.getWorld();
        if (w == null || tracked.isEmpty()) {
            return null;
        }
        double bestDistSq = maxRange * maxRange;
        Zombie best = null;
        for (TrackedEntity te : tracked.values()) {
            Zombie z = te.host;
            if (!z.isValid() || z.isDead() || !z.getWorld().equals(w)) {
                continue;
            }
            double dSq = z.getLocation().distanceSquared(origin);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = z;
            }
        }
        return best;
    }

    public int clearAll() {
        int removed = tracked.size();
        for (TrackedEntity te : tracked.values()) {
            removeTracked(te);
        }
        tracked.clear();
        purgeOrphanParts(plugin.getLevel0World());
        purgeOrphanParts(plugin.getLevel1World());
        return removed;
    }

    public boolean isInSafeSanctuary(World w, int x, int z) {
        if (w.equals(plugin.getLevel0World())) {
            return plugin.getLevel0Generator().getZoneAtBlock(w, x, z) == Level0ChunkGenerator.ZoneType.MANILA_ROOM;
        }
        if (w.equals(plugin.getLevel1World())) {
            return Level1ChunkGenerator.isOutpostZone(plugin.getLevel1Generator().getZoneAtBlock(w, x, z));
        }
        return true;
    }

    public boolean spawnEntity(Location loc, EntityKind kind) {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        World w = loc.getWorld();
        if (!cfg.isEntitiesEnabled() || w == null || !plugin.isBackroomsGameplayWorld(w)) {
            return false;
        }

        if (isInSafeSanctuary(w, loc.getBlockX(), loc.getBlockZ())) {
            return false;
        }

        Zombie host = w.spawn(loc, Zombie.class, z -> {
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
                case FACELESS -> {
                    setAttr(z, Attribute.MAX_HEALTH, 24.0);
                    z.setHealth(24.0);
                    setAttr(z, Attribute.MOVEMENT_SPEED, 0.24);
                    setAttr(z, Attribute.ATTACK_DAMAGE, 4.0);
                    setAttr(z, Attribute.FOLLOW_RANGE, 22.0);
                    setAttr(z, Attribute.STEP_HEIGHT, 1.0);
                }
                case SKIN_STEALER -> {
                    setAttr(z, Attribute.MAX_HEALTH, 44.0);
                    z.setHealth(44.0);
                    setAttr(z, Attribute.MOVEMENT_SPEED, 0.30);
                    setAttr(z, Attribute.ATTACK_DAMAGE, 6.0);
                    setAttr(z, Attribute.FOLLOW_RANGE, 32.0);
                    setAttr(z, Attribute.STEP_HEIGHT, 1.0);
                }
            }
        });

        TrackedEntity te = new TrackedEntity(kind, host);
        buildSkeletalRig(w, loc, te);
        tracked.put(host.getUniqueId(), te);
        return true;
    }

    private void buildSkeletalRig(World w, Location loc, TrackedEntity te) {
        // Adult Zombie passenger anchor sits ~1.62m above ground.
        // In ItemDisplay(FIXED) with yaw=0, +Z is forward along entity facing.
        switch (te.kind) {
            case BACTERIA -> {
                float s = 0.92f;
                addBone(w, loc, te, "leg_left", "bacteria_leg_left", +0.13f, -0.60f, 0.0f, s, false);
                addBone(w, loc, te, "leg_right", "bacteria_leg_right", -0.13f, -0.60f, 0.0f, s, false);
                addBone(w, loc, te, "torso", "bacteria_torso", 0.0f, -0.49f, 0.0f, s, false);
                addBone(w, loc, te, "arm_left", "bacteria_arm_left", +0.26f, +0.22f, 0.0f, s, false);
                addBone(w, loc, te, "arm_right", "bacteria_arm_right", -0.26f, +0.22f, 0.0f, s, false);
                addBone(w, loc, te, "head", "bacteria_head", 0.0f, +0.34f, +0.06f, s, false);
            }
            case SMILER -> {
                addBone(w, loc, te, "shroud", "smiler_shroud", 0.0f, -0.15f, 0.0f, 1.05f, false);
                addBone(w, loc, te, "face", "smiler_face", 0.0f, -0.15f, 0.0f, 1.05f, true);
            }
            case HOUND -> {
                float s = 0.96f;
                addBone(w, loc, te, "torso", "hound_torso", 0.0f, -0.92f, 0.0f, s, false);
                addBone(w, loc, te, "head", "hound_head", 0.0f, -0.82f, +0.42f, s, false);
                addBone(w, loc, te, "eyes", "hound_eyes", 0.0f, -0.82f, +0.42f, s, true);
                addBone(w, loc, te, "leg_fl", "hound_leg_fl", +0.19f, -0.87f, +0.33f, s, false);
                addBone(w, loc, te, "leg_fr", "hound_leg_fr", -0.19f, -0.87f, +0.33f, s, false);
                addBone(w, loc, te, "leg_bl", "hound_leg_bl", +0.18f, -0.90f, -0.35f, s, false);
                addBone(w, loc, te, "leg_br", "hound_leg_br", -0.18f, -0.90f, -0.35f, s, false);
            }
            case FACELESS -> {
                float s = 0.96f;
                addBone(w, loc, te, "leg_left", "faceless_leg_left", +0.12f, -0.78f, 0.0f, s, false);
                addBone(w, loc, te, "leg_right", "faceless_leg_right", -0.12f, -0.78f, 0.0f, s, false);
                addBone(w, loc, te, "torso", "faceless_torso", 0.0f, -0.58f, 0.0f, s, false);
                addBone(w, loc, te, "arm_left", "faceless_arm_left", +0.30f, -0.05f, 0.0f, s, false);
                addBone(w, loc, te, "arm_right", "faceless_arm_right", -0.30f, -0.05f, 0.0f, s, false);
                addBone(w, loc, te, "head", "faceless_head", 0.0f, +0.06f, 0.0f, s, false);
            }
            case SKIN_STEALER -> {
                float s = 0.96f;
                addBone(w, loc, te, "leg_left", "skinstealer_leg_left", +0.13f, -0.68f, 0.0f, s, false);
                addBone(w, loc, te, "leg_right", "skinstealer_leg_right", -0.13f, -0.68f, 0.0f, s, false);
                addBone(w, loc, te, "torso", "skinstealer_torso", 0.0f, -0.49f, 0.0f, s, false);
                addBone(w, loc, te, "arm_left", "skinstealer_arm_left", +0.29f, +0.05f, 0.0f, s, false);
                addBone(w, loc, te, "arm_right", "skinstealer_arm_right", -0.29f, +0.05f, 0.0f, s, false);
                addBone(w, loc, te, "head", "skinstealer_head", 0.0f, +0.20f, +0.05f, s, false);
                addBone(w, loc, te, "eyes", "skinstealer_eyes", 0.0f, +0.20f, +0.05f, s, true);
            }
        }
    }

    private void addBone(World w, Location loc, TrackedEntity te, String key, String modelName,
                         float bx, float by, float bz, float scale, boolean fullbright) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(NamespacedKey.minecraft(modelName));
        stack.setItemMeta(meta);

        ItemDisplay d = w.spawn(loc, ItemDisplay.class, id -> {
            id.setItemStack(stack);
            id.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            id.setBillboard(Display.Billboard.FIXED);
            id.setInterpolationDelay(0);
            id.setInterpolationDuration(3);
            id.setTeleportDuration(2);
            id.setViewRange(1.2f);
            if (fullbright) {
                id.setBrightness(new Display.Brightness(15, 15));
            }
            id.setTransformation(new Transformation(
                    new Vector3f(bx, by, bz),
                    new Quaternionf(),
                    new Vector3f(scale, scale, scale),
                    new Quaternionf()
            ));
            id.getPersistentDataContainer().set(partTagKey, PersistentDataType.BYTE, (byte) 1);
            id.setPersistent(false);
        });
        te.host.addPassenger(d);
        te.bones.put(key, new BonePart(d, bx, by, bz, scale));
    }

    private void animateBone(BonePart bone, float dx, float dy, float dz, float pitchRad, float yawRad, float rollRad) {
        if (bone == null || !bone.display.isValid()) {
            return;
        }
        Quaternionf q = new Quaternionf().rotateYXZ(yawRad, pitchRad, rollRad);
        bone.display.setInterpolationDelay(0);
        bone.display.setTransformation(new Transformation(
                new Vector3f(bone.baseX + dx, bone.baseY + dy, bone.baseZ + dz),
                q,
                new Vector3f(bone.scale, bone.scale, bone.scale),
                new Quaternionf()
        ));
    }

    private void tickAI() {
        if (tracked.isEmpty()) {
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
            World w = mLoc.getWorld();
            if (w == null || !plugin.isBackroomsGameplayWorld(w)
                    || isInSafeSanctuary(w, mLoc.getBlockX(), mLoc.getBlockZ())) {
                removeTracked(te);
                it.remove();
                continue;
            }

            Player nearest = null;
            double nearestSq = Double.MAX_VALUE;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getWorld().equals(w) || plugin.getLimboManager().isInLimbo(p)
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

            if (isInSafeSanctuary(w, nearest.getLocation().getBlockX(), nearest.getLocation().getBlockZ())) {
                z.setTarget(null);
            } else if (te.kind == EntityKind.FACELESS) {
                boolean l1Blackout = w.equals(plugin.getLevel1World())
                        && plugin.getLevel1Manager() != null
                        && plugin.getLevel1Manager().getBlackoutPhase() == Level1Manager.BlackoutPhase.BLACKOUT;
                z.setTarget((l1Blackout || now < te.provokedUntilMs) ? nearest : null);
            } else {
                z.setTarget(nearest);
            }

            boolean los = nearest.hasLineOfSight(z);
            double dist = Math.sqrt(nearestSq);
            switch (te.kind) {
                case BACTERIA -> tickBacteria(te, z, nearest, mLoc, dist, los, now);
                case SMILER -> tickSmiler(te, z, nearest, mLoc, dist, los, now);
                case HOUND -> tickHound(te, z, nearest, mLoc, dist, los, now);
                case FACELESS -> tickFaceless(te, z, nearest, mLoc, dist, los, now);
                case SKIN_STEALER -> tickSkinStealer(te, z, nearest, mLoc, dist, los, now);
            }
            animateEntity(te, z, nearest, mLoc, now);
        }
    }

    private void animateEntity(TrackedEntity te, Zombie z, Player nearest, Location mLoc, long now) {
        double hSpeed = Math.hypot(z.getVelocity().getX(), z.getVelocity().getZ());
        boolean moving = hSpeed > 0.015;
        te.animPhase += moving ? Math.min(0.65, 0.25 + hSpeed * 2.2) : 0.10;
        float ph = (float) te.animPhase;

        float rigYaw = mLoc.getYaw();
        if (te.kind == EntityKind.SMILER && nearest != null) {
            Vector toP = nearest.getLocation().toVector().subtract(mLoc.toVector());
            rigYaw = (float) Math.toDegrees(Math.atan2(-toP.getX(), toP.getZ()));
        }
        for (BonePart bp : te.bones.values()) {
            if (bp.display.isValid()) {
                bp.display.setRotation(rigYaw, 0f);
            }
        }

        switch (te.kind) {
            case BACTERIA -> {
                boolean bursting = now < te.chaseBurstUntilMs;
                float swing = moving ? (float) Math.sin(ph) * (bursting ? 0.58f : 0.40f) : 0.0f;
                float bob = moving ? (float) Math.abs(Math.cos(ph)) * 0.05f : (float) Math.sin(ph) * 0.015f;
                float twitch = bursting ? (float) Math.sin(ph * 3.1f) * 0.22f : (float) Math.sin(ph * 0.6f) * 0.14f;

                animateBone(te.bones.get("torso"), 0f, bob, 0f, moving ? 0.14f : 0.05f, 0f, twitch * 0.35f);
                animateBone(te.bones.get("head"), 0f, bob, 0f, -0.06f + twitch * 0.4f, 0f, 0.22f + twitch);
                animateBone(te.bones.get("arm_left"), 0f, bob, 0f, (bursting ? 0.55f : 0.18f) - swing * 0.85f, 0f, -0.14f);
                animateBone(te.bones.get("arm_right"), 0f, bob, 0f, (bursting ? 0.55f : 0.18f) + swing * 0.85f, 0f, +0.14f);
                animateBone(te.bones.get("leg_left"), 0f, 0f, 0f, +swing, 0f, 0f);
                animateBone(te.bones.get("leg_right"), 0f, 0f, 0f, -swing, 0f, 0f);
            }
            case SMILER -> {
                float hover = (float) Math.sin(ph * 0.7f) * 0.08f;
                float tilt = (float) Math.sin(ph * 0.45f) * 0.09f;
                animateBone(te.bones.get("shroud"), 0f, hover, 0f, 0f, 0f, tilt);
                animateBone(te.bones.get("face"), 0f, hover, 0f, 0f, 0f, tilt);
            }
            case HOUND -> {
                float stride = moving ? (float) Math.sin(ph * 1.25f) * 0.55f : 0.0f;
                float bounce = moving ? (float) Math.abs(Math.cos(ph * 1.25f)) * 0.05f : (float) Math.sin(ph) * 0.01f;
                float snarl = (now < te.houndPauseUntilMs) ? -0.18f : (float) Math.sin(ph * 0.8f) * 0.06f;

                animateBone(te.bones.get("torso"), 0f, bounce, 0f, moving ? 0.05f : 0f, 0f, 0f);
                animateBone(te.bones.get("head"), 0f, bounce, 0f, snarl, 0f, (float) Math.sin(ph * 0.5f) * 0.08f);
                animateBone(te.bones.get("eyes"), 0f, bounce, 0f, snarl, 0f, (float) Math.sin(ph * 0.5f) * 0.08f);
                animateBone(te.bones.get("leg_fl"), 0f, 0f, 0f, +stride, 0f, 0f);
                animateBone(te.bones.get("leg_br"), 0f, 0f, 0f, +stride, 0f, 0f);
                animateBone(te.bones.get("leg_fr"), 0f, 0f, 0f, -stride, 0f, 0f);
                animateBone(te.bones.get("leg_bl"), 0f, 0f, 0f, -stride, 0f, 0f);
            }
            case FACELESS -> {
                float stride = moving ? (float) Math.sin(ph) * 0.42f : 0.0f;
                float bob = moving ? (float) Math.abs(Math.cos(ph)) * 0.03f : 0.0f;
                float tilt = (float) Math.sin(ph * 0.4f) * 0.14f;
                animateBone(te.bones.get("torso"), 0f, bob, 0f, 0f, 0f, 0f);
                animateBone(te.bones.get("head"), 0f, bob, 0f, 0f, 0f, tilt);
                animateBone(te.bones.get("arm_left"), 0f, bob, 0f, -stride * 0.7f, 0f, -0.05f);
                animateBone(te.bones.get("arm_right"), 0f, bob, 0f, +stride * 0.7f, 0f, +0.05f);
                animateBone(te.bones.get("leg_left"), 0f, 0f, 0f, +stride, 0f, 0f);
                animateBone(te.bones.get("leg_right"), 0f, 0f, 0f, -stride, 0f, 0f);
            }
            case SKIN_STEALER -> {
                boolean lunge = now < te.chaseBurstUntilMs;
                float stride = moving ? (float) Math.sin(ph * 1.15f) * (lunge ? 0.62f : 0.45f) : 0.0f;
                float bob = moving ? (float) Math.abs(Math.cos(ph * 1.15f)) * 0.045f : (float) Math.sin(ph) * 0.015f;
                float snap = (float) Math.sin(ph * 2.2f) * (lunge ? 0.25f : 0.08f);
                animateBone(te.bones.get("torso"), 0f, bob, 0f, moving ? 0.18f : 0.08f, 0f, snap * 0.25f);
                animateBone(te.bones.get("head"), 0f, bob, 0f, -0.05f, 0f, snap);
                animateBone(te.bones.get("eyes"), 0f, bob, 0f, -0.05f, 0f, snap);
                animateBone(te.bones.get("arm_left"), 0f, bob, 0f, (lunge ? 0.65f : 0.28f) - stride * 0.8f, 0f, -0.10f);
                animateBone(te.bones.get("arm_right"), 0f, bob, 0f, (lunge ? 0.65f : 0.28f) + stride * 0.8f, 0f, +0.10f);
                animateBone(te.bones.get("leg_left"), 0f, 0f, 0f, +stride, 0f, 0f);
                animateBone(te.bones.get("leg_right"), 0f, 0f, 0f, -stride, 0f, 0f);
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

    private void tickFaceless(TrackedEntity te, Zombie z, Player target, Location mLoc, double dist, boolean los, long now) {
        boolean aggressive = z.getTarget() != null;
        if (aggressive && los && dist <= 12.0 && now >= te.nextVocalAtMs) {
            te.nextVocalAtMs = now + 6500L;
            target.playSound(mLoc, "minecraft:backrooms.fx.whisper", SoundCategory.HOSTILE, 0.42f, 0.85f);
        }
        setAttr(z, Attribute.MOVEMENT_SPEED, aggressive ? 0.27 : 0.14);
    }

    private void tickSkinStealer(TrackedEntity te, Zombie z, Player target, Location mLoc, double dist, boolean los, long now) {
        if (los && dist <= 18.0 && now >= te.nextVocalAtMs) {
            te.nextVocalAtMs = now + 7000L;
            te.chaseBurstUntilMs = now + 3000L;
            target.playSound(mLoc, "minecraft:backrooms.entity.hound.growl", SoundCategory.HOSTILE, 0.55f, 0.72f);
            target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 55, 0, false, false, false));
        }
        setAttr(z, Attribute.MOVEMENT_SPEED, now < te.chaseBurstUntilMs ? 0.33 : 0.28);
    }

    private void tickAutoSpawn() {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        if (!cfg.isEntitiesEnabled() || getActiveCount() >= cfg.getEntitiesMaxGlobal()) {
            return;
        }
        World level0 = plugin.getLevel0World();
        World level1 = plugin.getLevel1World();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (Player player : Bukkit.getOnlinePlayers()) {
            World w = player.getWorld();
            if (!plugin.isBackroomsGameplayWorld(w)
                    || plugin.getLimboManager().isInLimbo(player)
                    || !plugin.getLimboManager().isVerified(player)) {
                continue;
            }
            boolean inL0 = w.equals(level0);
            boolean inL1 = level1 != null && w.equals(level1);
            if (inL0 && !autoSpawnOverride) {
                continue;
            }
            boolean l1Blackout = inL1 && plugin.getLevel1Manager() != null
                    && plugin.getLevel1Manager().getBlackoutPhase() != Level1Manager.BlackoutPhase.LIT;
            if (inL1 && (!cfg.isLevel1BlackoutEntitySpawns() || (!l1Blackout && rng.nextDouble() > 0.18))) {
                continue;
            }
            if (inL0 && rng.nextDouble() >= cfg.getEntitiesAutoSpawnChance()) {
                continue;
            }

            Location pLoc = player.getLocation();
            if (isInSafeSanctuary(w, pLoc.getBlockX(), pLoc.getBlockZ())) {
                continue;
            }

            int floorY = inL1 ? cfg.getLevel1FloorY() : cfg.getFloorY();
            Location spawnSpot = findSpawnSpotNear(w, pLoc, floorY, 18, 30);
            if (spawnSpot == null) {
                continue;
            }

            EntityKind kind;
            if (inL1) {
                if (l1Blackout) {
                    int roll = rng.nextInt(4);
                    kind = switch (roll) {
                        case 0 -> EntityKind.SMILER;
                        case 1 -> EntityKind.SKIN_STEALER;
                        case 2 -> EntityKind.HOUND;
                        default -> EntityKind.FACELESS;
                    };
                } else {
                    kind = rng.nextDouble() < 0.72 ? EntityKind.FACELESS : EntityKind.HOUND;
                }
            } else {
                Level0ChunkGenerator.ZoneType sZone = plugin.getLevel0Generator()
                        .getZoneAtBlock(level0, spawnSpot.getBlockX(), spawnSpot.getBlockZ());
                if (sZone == Level0ChunkGenerator.ZoneType.BLACKOUT_ZONE || sZone == Level0ChunkGenerator.ZoneType.RED_ROOM) {
                    kind = rng.nextBoolean() ? EntityKind.SMILER : EntityKind.BACTERIA;
                } else if (sZone == Level0ChunkGenerator.ZoneType.NARROW_MAZE) {
                    kind = EntityKind.HOUND;
                } else {
                    kind = rng.nextDouble() < 0.60 ? EntityKind.BACTERIA : EntityKind.HOUND;
                }
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
            if (isInSafeSanctuary(w, x, z)) {
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
        for (BonePart bp : te.bones.values()) {
            if (bp.display.isValid()) {
                bp.display.remove();
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
        TrackedEntity attacked = tracked.get(event.getEntity().getUniqueId());
        if (attacked != null && attacked.kind == EntityKind.FACELESS && event.getDamager() instanceof Player) {
            attacked.provokedUntilMs = System.currentTimeMillis() + 30_000L;
        }
        TrackedEntity te = tracked.get(event.getDamager().getUniqueId());
        if (te != null && event.getEntity() instanceof Player p) {
            if (te.kind == EntityKind.SMILER) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false, false));
            } else if (te.kind == EntityKind.BACTERIA || te.kind == EntityKind.SKIN_STEALER) {
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
        for (BonePart bp : te.bones.values()) {
            if (bp.display.isValid()) {
                bp.display.remove();
            }
        }
        if (te.kind == EntityKind.SKIN_STEALER || te.kind == EntityKind.FACELESS) {
            event.getDrops().add(plugin.getItems().looseCoins(1 + ThreadLocalRandom.current().nextInt(3)));
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
