package com.yunexal.backrooms;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerRotation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates a subtle handheld / chest-mounted bodycam sway (breathing + stride Lissajous rock)
 * using 1.21.2+ relative camera rotation packets without disturbing movement or teleporting.
 */
public final class BodycamManager {
    private final YunexalBackroomsPlugin plugin;
    private final Map<UUID, SwayState> states = new ConcurrentHashMap<>();
    private final Set<UUID> disabledPlayers = ConcurrentHashMap.newKeySet();

    private BukkitTask swayTask;
    private boolean packetEventsReady;

    private static final class SwayState {
        double phase;
        double smoothSpeed;
        double lastX;
        double lastZ;
        boolean hasLastPos;
        float appliedYaw;
        float appliedPitch;
    }

    public BodycamManager(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        shutdown();
        this.packetEventsReady = checkPacketEvents();
        if (!packetEventsReady) {
            plugin.getLogger().warning("PacketEvents not ready — bodycam camera sway disabled.");
            return;
        }
        swayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (swayTask != null) {
            swayTask.cancel();
            swayTask = null;
        }
        states.clear();
    }

    public void clearPlayer(Player player) {
        states.remove(player.getUniqueId());
    }

    public boolean isEnabledFor(Player player) {
        return !disabledPlayers.contains(player.getUniqueId());
    }

    public boolean toggleFor(Player player) {
        UUID uid = player.getUniqueId();
        if (disabledPlayers.remove(uid)) {
            return true;
        }
        disabledPlayers.add(uid);
        resetApplied(player, states.remove(uid));
        return false;
    }

    private boolean checkPacketEvents() {
        try {
            return Bukkit.getPluginManager().isPluginEnabled("packetevents")
                    && PacketEvents.getAPI() != null
                    && PacketEvents.getAPI().isInitialized();
        } catch (NoClassDefFoundError | Exception e) {
            return false;
        }
    }

    private void tick() {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        if (!cfg.isBodycamEnabled() || !packetEventsReady) {
            return;
        }
        World level0 = plugin.getLevel0World();
        if (level0 == null) {
            return;
        }

        double intensity = cfg.getBodycamIntensity();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uid = player.getUniqueId();
            if (!plugin.isBackroomsGameplayWorld(player.getWorld())
                    || plugin.getLimboManager().isInLimbo(player)
                    || !plugin.getLimboManager().isVerified(player)
                    || disabledPlayers.contains(uid)) {
                SwayState st = states.remove(uid);
                if (st != null) {
                    resetApplied(player, st);
                }
                continue;
            }

            Location loc = player.getLocation();
            SwayState st = states.computeIfAbsent(uid, ignored -> new SwayState());
            double instantSpeed = 0.0;
            if (st.hasLastPos) {
                double dx = loc.getX() - st.lastX;
                double dz = loc.getZ() - st.lastZ;
                instantSpeed = Math.hypot(dx, dz);
            }
            st.lastX = loc.getX();
            st.lastZ = loc.getZ();
            st.hasLastPos = true;

            // Teleport guard: discard accumulated phase offset cleanly instead of snapping
            if (instantSpeed > 1.2) {
                st.smoothSpeed = 0.0;
                st.appliedYaw = 0.0f;
                st.appliedPitch = 0.0f;
                continue;
            }

            if (!player.isOnGround()) {
                instantSpeed *= 0.35;
            }
            st.smoothSpeed = st.smoothSpeed * 0.72 + Math.min(instantSpeed, 0.40) * 0.28;
            st.phase += 0.075 + Math.min(st.smoothSpeed, 0.36) * 0.65;

            boolean lookingExtreme = Math.abs(loc.getPitch()) > 82.0f;
            boolean guiOpen = player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING;
            float targetYaw = 0.0f;
            float targetPitch = 0.0f;

            if (!lookingExtreme && !guiOpen) {
                double fatigue = plugin.getHallucinations() != null ? plugin.getHallucinations().getFatigue(player) : 0.0;
                double redTremor = 0.0;
                if (player.getWorld().equals(level0)) {
                    Level0ChunkGenerator.ZoneType zone = plugin.getLevel0Generator()
                            .getZoneAtBlock(level0, loc.getBlockX(), loc.getBlockZ());
                    if (zone == Level0ChunkGenerator.ZoneType.RED_ROOM) {
                        redTremor = Math.sin(st.phase * 4.3) * 0.06;
                    }
                } else if (player.getWorld().equals(plugin.getLevel2World())
                        && plugin.getLevel2Generator().getZoneAtBlock(player.getWorld(), loc.getBlockX(), loc.getBlockZ())
                        == Level2ChunkGenerator.Level2ZoneType.BOILER_HOT_ZONE) {
                    redTremor = Math.sin(st.phase * 5.0) * 0.05;
                } else if (plugin.getLevel1Manager() != null
                        && plugin.getLevel1Manager().getBlackoutPhase() == Level1Manager.BlackoutPhase.BLACKOUT) {
                    redTremor = Math.sin(st.phase * 3.8) * 0.04;
                }

                double yawBreath = Math.sin(st.phase * 0.37 + 0.5) * (0.20 + fatigue * 0.16);
                double pitchBreath = Math.sin(st.phase * 0.52) * (0.24 + fatigue * 0.20);

                double walkFactor = Math.max(0.0, Math.min(1.45, st.smoothSpeed / 0.21));
                double yawWalk = Math.sin(st.phase) * 0.56 * walkFactor;
                double pitchWalk = Math.sin(st.phase * 2.0 + 0.35) * 0.34 * walkFactor;

                targetYaw = (float) ((yawBreath + yawWalk) * intensity);
                targetPitch = (float) ((pitchBreath + pitchWalk + redTremor) * intensity);
            }

            float dYaw = clampDelta(targetYaw - st.appliedYaw, 0.32f);
            float dPitch = clampDelta(targetPitch - st.appliedPitch, 0.32f);
            if (Math.abs(dYaw) < 0.002f && Math.abs(dPitch) < 0.002f) {
                continue;
            }

            st.appliedYaw += dYaw;
            st.appliedPitch += dPitch;
            sendRelativeRotation(player, dYaw, dPitch);
        }
    }

    private void resetApplied(Player player, SwayState st) {
        if (st == null || !packetEventsReady || !player.isOnline()) {
            return;
        }
        if (Math.abs(st.appliedYaw) >= 0.005f || Math.abs(st.appliedPitch) >= 0.005f) {
            sendRelativeRotation(player, -st.appliedYaw, -st.appliedPitch);
        }
    }

    private void sendRelativeRotation(Player player, float dYaw, float dPitch) {
        try {
            var pm = PacketEvents.getAPI().getPlayerManager();
            ClientVersion ver = pm.getClientVersion(player);
            if (ver == null || !ver.isNewerThanOrEquals(ClientVersion.V_1_21_2)) {
                return;
            }
            pm.sendPacket(player, new WrapperPlayServerPlayerRotation(dYaw, true, dPitch, true));
        } catch (Throwable ignored) {
        }
    }

    private static float clampDelta(float delta, float maxAbs) {
        if (delta > maxAbs) return maxAbs;
        if (delta < -maxAbs) return -maxAbs;
        return delta;
    }
}
