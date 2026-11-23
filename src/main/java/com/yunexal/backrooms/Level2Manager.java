package com.yunexal.backrooms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages live Backrooms Level 2 ("Pipe Dreams") mechanics:
 * - World creation & border/gamerule setup
 * - >43C Superheated Boiler Zone heat exhaustion & rapid dehydration
 * - Steam vent bursts (CLOUD particles + steam_hiss audio) & faint pipe hums
 */
public final class Level2Manager {
    private final YunexalBackroomsPlugin plugin;
    private World level2World;
    private BukkitTask mainTask;

    public Level2Manager(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    public World ensureLevel2World() {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        World existing = Bukkit.getWorld(cfg.getLevel2WorldName());
        if (existing != null) {
            configureRules(existing, cfg);
            this.level2World = existing;
            return existing;
        }
        WorldCreator creator = new WorldCreator(cfg.getLevel2WorldName());
        creator.environment(World.Environment.NORMAL);
        creator.type(WorldType.FLAT);
        creator.generateStructures(false);
        creator.generator(plugin.getLevel2Generator());
        World created = creator.createWorld();
        if (created != null) {
            configureRules(created, cfg);
            this.level2World = created;
        }
        return created;
    }

    private void configureRules(World w, BackroomsConfig cfg) {
        w.setTime(18000L);
        w.setDifficulty(Difficulty.NORMAL);
        w.setGameRule(GameRules.ADVANCE_TIME, false);
        w.setGameRule(GameRules.ADVANCE_WEATHER, false);
        w.setGameRule(GameRules.SPAWN_MOBS, false);
        w.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        w.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false);
        w.setGameRule(GameRules.KEEP_INVENTORY, false);
        w.setGameRule(GameRules.LOCATOR_BAR, false);
        w.setGameRule(GameRules.REDUCED_DEBUG_INFO, true);
        w.setViewDistance(cfg.getViewDistance());
        w.setSendViewDistance(cfg.getViewDistance());
        w.setSimulationDistance(cfg.getSimulationDistance());
        WorldBorder border = w.getWorldBorder();
        border.setCenter(0.0, 0.0);
        border.setSize(cfg.getLevel2WorldBorderSize());
    }

    public World getLevel2World() {
        return level2World != null ? level2World : Bukkit.getWorld(plugin.getBackroomsConfig().getLevel2WorldName());
    }

    public void start() {
        shutdown();
        ensureLevel2World();
        mainTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (mainTask != null) {
            mainTask.cancel();
            mainTask = null;
        }
    }

    private void tick() {
        World l2 = getLevel2World();
        if (l2 == null) {
            return;
        }
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        if (!cfg.isLevel2SteamHazardEnabled()) {
            return;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(l2) || !plugin.getLimboManager().isVerified(p)) {
                continue;
            }
            Location loc = p.getLocation();
            Level2ChunkGenerator.Level2ZoneType zone = plugin.getLevel2Generator()
                    .getZoneAtBlock(l2, loc.getBlockX(), loc.getBlockZ());

            // 1. Hot steam jets & unbearable >43C heat exhaustion in Boiler Hot Zones
            if (zone == Level2ChunkGenerator.Level2ZoneType.BOILER_HOT_ZONE) {
                for (int i = 0; i < 4; i++) {
                    double ox = (rng.nextDouble() - 0.5) * 10.0;
                    double oz = (rng.nextDouble() - 0.5) * 10.0;
                    p.spawnParticle(Particle.CLOUD, loc.getX() + ox, cfg.getLevel2FloorY() + 1.2 + rng.nextDouble() * 1.4,
                            loc.getZ() + oz, 2, 0.25, 0.25, 0.25, 0.03);
                }
                if (rng.nextDouble() < 0.28) {
                    p.playSound(loc.clone().add((rng.nextDouble() - 0.5) * 6, 1.0, (rng.nextDouble() - 0.5) * 6),
                            "minecraft:backrooms.fx.steam_hiss", SoundCategory.AMBIENT, 0.36f, 0.9f + rng.nextFloat() * 0.2f);
                }

                // Rapid stamina & thirst drain above 43C (preventable temporarily with Almond Water)
                if (rng.nextDouble() < 0.35 && p.getFoodLevel() > 4) {
                    p.setFoodLevel(Math.max(4, p.getFoodLevel() - 1));
                }
                if (plugin.getHallucinations() != null) {
                    plugin.getHallucinations().setFatigue(p,
                            plugin.getHallucinations().getFatigue(p) + 0.008);
                }
                p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 45, 0, false, false, false));
                p.sendActionBar(Component.text("⚠ СПЕКА >43°C У ГАРЯЧИХ ТРУБАХ! Пийте Мигдальну воду й тікайте в прохолодний тунель!", NamedTextColor.RED));
            } else if (rng.nextDouble() < 0.08) {
                // Occasional distant pipe groan / hiss in Pipe Corridors & Machinery Bays
                p.playSound(loc.clone().add((rng.nextDouble() - 0.5) * 10, 1.0, (rng.nextDouble() - 0.5) * 10),
                        "minecraft:backrooms.fx.steam_hiss", SoundCategory.AMBIENT, 0.20f, 0.78f);
            }
        }
    }
}
