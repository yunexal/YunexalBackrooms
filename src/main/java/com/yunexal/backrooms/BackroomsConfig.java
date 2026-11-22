package com.yunexal.backrooms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

public final class BackroomsConfig {
    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private final boolean rpEnabled;
    private final String rpUrl;
    private final String rpSha1Hex;
    private final byte[] rpSha1Bytes;
    private final UUID rpUuid;
    private final boolean rpRequired;
    private final Component rpPrompt;
    private final int rpTimeoutSeconds;
    private final long rpSendDelayTicks;
    private final String fallbackServer;

    private final String limboWorldName;
    private final double limboSpawnX;
    private final double limboSpawnY;
    private final double limboSpawnZ;
    private final int limboBoxRadius;

    private final String level0WorldName;
    private final long level0Seed;
    private final int floorY;
    private final int wallHeight;
    private final int viewDistance;
    private final int simulationDistance;
    private final double worldBorderSize;
    private final int spawnRadius;
    private final double extraBraidingChance;
    private final double pillarHallChance;
    private final double narrowMazeChance;
    private final double blackoutZoneChance;
    private final double manilaRoomChance;
    private final double redRoomChance;
    private final double softCarpetChance;
    private final double nonLinearAnomalyChance;
    private final double flickerLightChance;
    private final double deadLightChance;
    private final double ventChance;
    private final double outletChance;
    private final double dampPillarChance;

    private final boolean ambientEnabled;
    private final long humIntervalTicks;
    private final int flickerCooldownSeconds;
    private final double flickerSoundChance;
    private final double distantThumpChance;
    private final boolean bodycamEnabled;
    private final double bodycamIntensity;

    public BackroomsConfig(FileConfiguration cfg) {
        this.rpEnabled = cfg.getBoolean("resource-pack.enabled", true);
        this.rpUrl = cfg.getString("resource-pack.url", "https://mirror.yunexal.com/packs/YunexalBackrooms-RP.zip");
        this.rpSha1Hex = cfg.getString("resource-pack.sha1", "").trim().toLowerCase(Locale.ROOT);
        this.rpSha1Bytes = parseSha1(this.rpSha1Hex);
        this.rpUuid = UUID.nameUUIDFromBytes((this.rpUrl + "|" + this.rpSha1Hex).getBytes(StandardCharsets.UTF_8));
        this.rpRequired = cfg.getBoolean("resource-pack.required", true);
        this.rpPrompt = AMP.deserialize(cfg.getString(
                "resource-pack.prompt",
                "&e&lYUNEXAL BACKROOMS\n&7Обов'язкове завантаження кастомних текстур та звуку для входу на &fРівень 0&7."
        ));
        this.rpTimeoutSeconds = Math.max(10, cfg.getInt("resource-pack.timeout-seconds", 45));
        this.rpSendDelayTicks = Math.max(1L, cfg.getLong("resource-pack.send-delay-ticks", 10L));
        this.fallbackServer = cfg.getString("resource-pack.fallback-server", "hub1");

        this.limboWorldName = cfg.getString("limbo.world-name", "world_limbo");
        this.limboSpawnX = cfg.getDouble("limbo.spawn-x", 0.5);
        this.limboSpawnY = cfg.getDouble("limbo.spawn-y", 65.0);
        this.limboSpawnZ = cfg.getDouble("limbo.spawn-z", 0.5);
        this.limboBoxRadius = Math.max(3, Math.min(12, cfg.getInt("limbo.box-radius", 5)));

        this.level0WorldName = cfg.getString("level0.world-name", "world");
        this.level0Seed = cfg.getLong("level0.seed", 19780412L);
        this.floorY = Math.max(-50, Math.min(200, cfg.getInt("level0.floor-y", 64)));
        this.wallHeight = Math.max(3, Math.min(8, cfg.getInt("level0.wall-height", 4)));
        this.viewDistance = Math.max(3, Math.min(16, cfg.getInt("level0.view-distance", 6)));
        this.simulationDistance = Math.max(3, Math.min(16, cfg.getInt("level0.simulation-distance", 6)));
        this.worldBorderSize = Math.max(256.0, cfg.getDouble("level0.world-border-size", 10000.0));
        this.spawnRadius = Math.max(64, cfg.getInt("level0.spawn-radius", 3800));
        this.extraBraidingChance = clamp01(cfg.getDouble("level0.extra-braiding-chance", 0.34));
        this.pillarHallChance = clamp01(cfg.getDouble("level0.pillar-hall-chance", 0.16));
        this.narrowMazeChance = clamp01(cfg.getDouble("level0.narrow-maze-chance", 0.18));
        this.blackoutZoneChance = clamp01(cfg.getDouble("level0.blackout-zone-chance", 0.14));
        this.manilaRoomChance = clamp01(cfg.getDouble("level0.manila-room-chance", 0.008));
        this.redRoomChance = clamp01(cfg.getDouble("level0.red-room-chance", 0.007));
        this.softCarpetChance = clamp01(cfg.getDouble("level0.soft-carpet-chance", 0.0004));
        this.nonLinearAnomalyChance = clamp01(cfg.getDouble("level0.non-linear-anomaly-chance", 0.004));
        this.flickerLightChance = clamp01(cfg.getDouble("level0.flicker-light-chance", 0.12));
        this.deadLightChance = clamp01(cfg.getDouble("level0.dead-light-chance", 0.10));
        this.ventChance = clamp01(cfg.getDouble("level0.vent-chance", 0.045));
        this.outletChance = clamp01(cfg.getDouble("level0.outlet-chance", 0.018));
        this.dampPillarChance = clamp01(cfg.getDouble("level0.damp-pillar-chance", 0.18));

        this.ambientEnabled = cfg.getBoolean("ambient.enabled", true);
        this.humIntervalTicks = Math.max(60L, cfg.getLong("ambient.hum-interval-ticks", 300L));
        this.flickerCooldownSeconds = Math.max(5, cfg.getInt("ambient.flicker-cooldown-seconds", 22));
        this.flickerSoundChance = clamp01(cfg.getDouble("ambient.flicker-sound-chance", 0.12));
        this.distantThumpChance = clamp01(cfg.getDouble("ambient.distant-thump-chance", 0.015));
        this.bodycamEnabled = cfg.getBoolean("bodycam.enabled", true);
        this.bodycamIntensity = Math.max(0.1, Math.min(2.5, cfg.getDouble("bodycam.intensity", 1.0)));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static byte[] parseSha1(String hex) {
        if (hex == null || hex.length() != 40) {
            return new byte[0];
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException ignored) {
            return new byte[0];
        }
    }

    public boolean isRpEnabled() { return rpEnabled; }
    public String getRpUrl() { return rpUrl; }
    public String getRpSha1Hex() { return rpSha1Hex; }
    public byte[] getRpSha1Bytes() { return rpSha1Bytes; }
    public UUID getRpUuid() { return rpUuid; }
    public boolean isRpRequired() { return rpRequired; }
    public Component getRpPrompt() { return rpPrompt; }
    public int getRpTimeoutSeconds() { return rpTimeoutSeconds; }
    public long getRpSendDelayTicks() { return rpSendDelayTicks; }
    public String getFallbackServer() { return fallbackServer; }

    public String getLimboWorldName() { return limboWorldName; }
    public double getLimboSpawnX() { return limboSpawnX; }
    public double getLimboSpawnY() { return limboSpawnY; }
    public double getLimboSpawnZ() { return limboSpawnZ; }
    public int getLimboBoxRadius() { return limboBoxRadius; }

    public String getLevel0WorldName() { return level0WorldName; }
    public long getLevel0Seed() { return level0Seed; }
    public int getFloorY() { return floorY; }
    public int getWallHeight() { return wallHeight; }
    public int getCeilingY() { return floorY + wallHeight + 1; }
    public int getViewDistance() { return viewDistance; }
    public int getSimulationDistance() { return simulationDistance; }
    public double getWorldBorderSize() { return worldBorderSize; }
    public int getSpawnRadius() { return spawnRadius; }
    public double getExtraBraidingChance() { return extraBraidingChance; }
    public double getPillarHallChance() { return pillarHallChance; }
    public double getNarrowMazeChance() { return narrowMazeChance; }
    public double getBlackoutZoneChance() { return blackoutZoneChance; }
    public double getManilaRoomChance() { return manilaRoomChance; }
    public double getRedRoomChance() { return redRoomChance; }
    public double getSoftCarpetChance() { return softCarpetChance; }
    public double getNonLinearAnomalyChance() { return nonLinearAnomalyChance; }
    public double getFlickerLightChance() { return flickerLightChance; }
    public double getDeadLightChance() { return deadLightChance; }
    public double getVentChance() { return ventChance; }
    public double getOutletChance() { return outletChance; }
    public double getDampPillarChance() { return dampPillarChance; }

    public boolean isAmbientEnabled() { return ambientEnabled; }
    public long getHumIntervalTicks() { return humIntervalTicks; }
    public int getFlickerCooldownSeconds() { return flickerCooldownSeconds; }
    public double getFlickerSoundChance() { return flickerSoundChance; }
    public double getDistantThumpChance() { return distantThumpChance; }
    public boolean isBodycamEnabled() { return bodycamEnabled; }
    public double getBodycamIntensity() { return bodycamIntensity; }
}
