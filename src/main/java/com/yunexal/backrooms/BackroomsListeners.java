package com.yunexal.backrooms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles Level 0 ambient 60Hz fluorescent hum, lamp flicker audio, protection, and sidebar HUD.
 */
public final class BackroomsListeners implements Listener {
    private final YunexalBackroomsPlugin plugin;
    private final Map<UUID, Integer> humTickCounter = new ConcurrentHashMap<>();

    private BukkitTask ambientTask;

    public BackroomsListeners(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (ambientTask != null) {
            ambientTask.cancel();
        }
        ambientTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPlayers, 20L, 20L);
    }

    public void shutdown() {
        if (ambientTask != null) {
            ambientTask.cancel();
            ambientTask = null;
        }
        humTickCounter.clear();
    }

    private void tickPlayers() {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        World level0 = plugin.getLevel0World();
        if (level0 == null) {
            return;
        }

        int humPeriodSeconds = (int) Math.max(2L, cfg.getHumIntervalTicks() / 20L);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.getLimboManager().isInLimbo(player) || !plugin.getLimboManager().isVerified(player)) {
                continue;
            }
            if (!player.getWorld().equals(level0)) {
                continue;
            }

            // Keep void safety inside sealed floorY..ceilingY
            if (player.getLocation().getY() < cfg.getFloorY() - 3.0) {
                player.teleport(plugin.getLevel0Generator().findSafeSpawn(level0));
            }

            updateScoreboard(player);

            if (!cfg.isAmbientEnabled()) {
                continue;
            }

            UUID uid = player.getUniqueId();
            int counter = humTickCounter.getOrDefault(uid, humPeriodSeconds);
            if (counter >= humPeriodSeconds) {
                player.playSound(player.getLocation(), "minecraft:backrooms.ambient.hum", SoundCategory.AMBIENT, 0.28f, 1.0f);
                humTickCounter.put(uid, 1);
            } else {
                humTickCounter.put(uid, counter + 1);
            }

            // Spatial flicker audio near buzzing SEA_LANTERN lamps
            if (rng.nextDouble() < cfg.getFlickerSoundChance()) {
                Location flickerLoc = findNearbyFlickerLamp(player.getLocation(), cfg.getCeilingY());
                if (flickerLoc != null) {
                    player.playSound(flickerLoc, "minecraft:backrooms.ambient.flicker", SoundCategory.AMBIENT, 0.42f,
                            0.94f + rng.nextFloat() * 0.12f);
                }
            }

            // Rare distant muffled structural thump
            if (rng.nextDouble() < cfg.getDistantThumpChance()) {
                double angle = rng.nextDouble() * Math.PI * 2.0;
                double dist = 12.0 + rng.nextDouble() * 8.0;
                Location thumpLoc = player.getLocation().clone().add(Math.cos(angle) * dist, 0.5, Math.sin(angle) * dist);
                player.playSound(thumpLoc, "minecraft:backrooms.ambient.thump", SoundCategory.AMBIENT, 0.25f,
                        0.82f + rng.nextFloat() * 0.18f);
            }
        }
    }

    private Location findNearbyFlickerLamp(Location center, int ceilingY) {
        World w = center.getWorld();
        int bx = center.getBlockX();
        int bz = center.getBlockZ();
        for (int dx = -8; dx <= 8; dx += 2) {
            for (int dz = -8; dz <= 8; dz += 2) {
                if (w.getBlockAt(bx + dx, ceilingY, bz + dz).getType() == Material.SEA_LANTERN) {
                    return new Location(w, bx + dx + 0.5, ceilingY + 0.2, bz + dz + 0.5);
                }
            }
        }
        return null;
    }

    public void updateScoreboard(Player player) {
        if (plugin.getLimboManager().isInLimbo(player)) {
            return;
        }
        Location loc = player.getLocation();
        Level0ChunkGenerator.ZoneType zone = plugin.getLevel0Generator()
                .getZoneAtBlock(loc.getWorld(), loc.getBlockX(), loc.getBlockZ());
        String zoneLabel = switch (zone) {
            case CLASSIC_MAZE -> "§eКоридори Лобі";
            case PILLAR_HALL -> "§6Колонна зала";
            case NARROW_MAZE -> "§cТісний лабіринт";
            case BLACKOUT_ZONE -> "§8Темний сектор";
        };
        String coordsLine = "  §fX: §7" + loc.getBlockX() + "  §fZ: §7" + loc.getBlockZ();

        Scoreboard sb = player.getScoreboard();
        Objective obj = sb.getObjective("backrooms");
        if (obj == null) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            obj = sb.registerNewObjective("backrooms", Criteria.DUMMY,
                    Component.text("§e§lYUNEXAL §8// §6BACKROOMS"));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);

            Team sectorTeam = sb.registerNewTeam("br_sector");
            sectorTeam.addEntry("§1 ");
            obj.getScore("§1 ").setScore(6);

            Team coordsTeam = sb.registerNewTeam("br_coords");
            coordsTeam.addEntry("§2 ");
            obj.getScore("§2 ").setScore(4);

            obj.getScore("§7 ").setScore(10);
            obj.getScore("§8▎ §eЛокація:").setScore(9);
            obj.getScore("  §fРівень 0 §7«Лобі»").setScore(8);
            obj.getScore("§8▎ §eСектор:").setScore(7);
            obj.getScore("§8▎ §eКоординати:").setScore(5);
            obj.getScore("§0 ").setScore(3);
            obj.getScore("§8▎ §b/br hub §7— в хаб").setScore(2);
            obj.getScore("§7play.yunexal.com").setScore(1);

            player.setScoreboard(sb);
        }

        Team sectorTeam = sb.getTeam("br_sector");
        if (sectorTeam != null) {
            sectorTeam.prefix(Component.text("  " + zoneLabel));
        }
        Team coordsTeam = sb.getTeam("br_coords");
        if (coordsTeam != null) {
            coordsTeam.prefix(Component.text(coordsLine));
        }
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent event) {
        event.setCancelled(true);
        if (event.getEntity() instanceof Player p) {
            p.setFoodLevel(20);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && plugin.getLimboManager().isInLimbo(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (p.getGameMode() != GameMode.CREATIVE || !p.hasPermission("yunexal.backrooms.admin")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (p.getGameMode() != GameMode.CREATIVE || !p.hasPermission("yunexal.backrooms.admin")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPortal(PlayerPortalEvent event) {
        event.setCancelled(true);
    }
}
