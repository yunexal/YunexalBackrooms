package com.yunexal.backrooms;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists each wanderer's Backrooms world, coordinates, Level 0 survival timer, and crafted inventory.
 */
public final class PlayerDataManager {
    private final YunexalBackroomsPlugin plugin;
    private final File playersDir;
    private final Map<UUID, Integer> survivalSecondsLevel0 = new ConcurrentHashMap<>();

    public PlayerDataManager(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
        this.playersDir = new File(plugin.getDataFolder(), "players");
        if (!playersDir.exists()) {
            playersDir.mkdirs();
        }
    }

    public int getSurvivalSecondsLevel0(Player player) {
        return survivalSecondsLevel0.getOrDefault(player.getUniqueId(), 0);
    }

    public void addSurvivalSecondsLevel0(Player player, int delta) {
        survivalSecondsLevel0.merge(player.getUniqueId(), delta, Integer::sum);
    }

    public void setSurvivalSecondsLevel0(Player player, int seconds) {
        survivalSecondsLevel0.put(player.getUniqueId(), Math.max(0, seconds));
    }

    public void resetSurvivalSecondsLevel0(Player player) {
        survivalSecondsLevel0.put(player.getUniqueId(), 0);
    }

    public boolean hasSavedData(UUID uuid) {
        return new File(playersDir, uuid + ".yml").exists();
    }

    public void savePlayer(Player player) {
        World w = player.getWorld();
        if (!plugin.isBackroomsGameplayWorld(w)) {
            return;
        }
        if (plugin.getLimboManager().isInLimbo(player) || !plugin.getLimboManager().isVerified(player)) {
            return;
        }

        File file = new File(playersDir, player.getUniqueId() + ".yml");
        YamlConfiguration yml = new YamlConfiguration();
        Location loc = player.getLocation();
        yml.set("location.world", w.getName());
        yml.set("location.x", loc.getX());
        yml.set("location.y", loc.getY());
        yml.set("location.z", loc.getZ());
        yml.set("location.yaw", (double) loc.getYaw());
        yml.set("location.pitch", (double) loc.getPitch());
        yml.set("health", player.getHealth());
        yml.set("food", player.getFoodLevel());
        yml.set("saturation", (double) player.getSaturation());
        yml.set("survivalSecondsLevel0", getSurvivalSecondsLevel0(player));
        if (plugin.getHallucinations() != null) {
            yml.set("fatigue", plugin.getHallucinations().getFatigue(player));
        }

        PlayerInventory inv = player.getInventory();
        yml.set("inventory.storage", inv.getStorageContents());
        yml.set("inventory.armor", inv.getArmorContents());
        yml.set("inventory.offhand", inv.getItemInOffHand());

        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save Backrooms data for " + player.getName() + ": " + e.getMessage());
        }
    }

    public void saveAllOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            savePlayer(p);
        }
    }

    public Location loadAndRestore(Player player, World level0) {
        File file = new File(playersDir, player.getUniqueId() + ".yml");
        if (!file.exists()) {
            return null;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

        List<?> storageRaw = yml.getList("inventory.storage");
        if (storageRaw != null) {
            ItemStack[] storage = new ItemStack[player.getInventory().getStorageContents().length];
            for (int i = 0; i < Math.min(storage.length, storageRaw.size()); i++) {
                if (storageRaw.get(i) instanceof ItemStack stack) {
                    storage[i] = stack;
                }
            }
            player.getInventory().setStorageContents(storage);
        }

        List<?> armorRaw = yml.getList("inventory.armor");
        if (armorRaw != null) {
            ItemStack[] armor = new ItemStack[player.getInventory().getArmorContents().length];
            for (int i = 0; i < Math.min(armor.length, armorRaw.size()); i++) {
                if (armorRaw.get(i) instanceof ItemStack stack) {
                    armor[i] = stack;
                }
            }
            player.getInventory().setArmorContents(armor);
        }

        ItemStack offhand = yml.getItemStack("inventory.offhand");
        if (offhand != null) {
            player.getInventory().setItemInOffHand(offhand);
        }

        double hp = yml.getDouble("health", 20.0);
        player.setHealth(Math.max(2.0, Math.min(20.0, hp)));
        player.setFoodLevel(Math.max(4, Math.min(20, yml.getInt("food", 20))));
        player.setSaturation((float) Math.max(0.0, Math.min(20.0, yml.getDouble("saturation", 20.0))));
        if (plugin.getHallucinations() != null) {
            plugin.getHallucinations().setFatigue(player, yml.getDouble("fatigue", 0.0));
        }

        survivalSecondsLevel0.put(player.getUniqueId(), Math.max(0, yml.getInt("survivalSecondsLevel0", 0)));
        if (!yml.contains("location.x")) {
            return null;
        }
        String savedWorldName = yml.getString("location.world", level0.getName());
        World targetWorld = Bukkit.getWorld(savedWorldName);
        if (targetWorld == null || !plugin.isBackroomsGameplayWorld(targetWorld)) {
            targetWorld = level0;
        }
        double x = yml.getDouble("location.x");
        double y = yml.getDouble("location.y");
        double z = yml.getDouble("location.z");
        float yaw = (float) yml.getDouble("location.yaw", 0.0);
        float pitch = (float) yml.getDouble("location.pitch", 0.0);

        BackroomsConfig cfg = plugin.getBackroomsConfig();
        boolean isL1 = targetWorld.equals(plugin.getLevel1World());
        boolean isL2 = targetWorld.equals(plugin.getLevel2World());
        double border = isL2 ? cfg.getLevel2WorldBorderSize() : (isL1 ? cfg.getLevel1WorldBorderSize() : cfg.getWorldBorderSize());
        int floorY = isL2 ? cfg.getLevel2FloorY() : (isL1 ? cfg.getLevel1FloorY() : cfg.getFloorY());
        int ceilingY = isL2 ? cfg.getLevel2CeilingY() : (isL1 ? cfg.getLevel1CeilingY() : cfg.getCeilingY());
        double half = (border * 0.5) - 4.0;
        if (Math.abs(x) > half || Math.abs(z) > half) {
            return null;
        }
        if (y < floorY + 0.5 || y > ceilingY - 1.5) {
            y = floorY + 1.0;
        }
        Location target = new Location(targetWorld, x, y, z, yaw, pitch);
        if (!target.getBlock().isPassable() || !target.clone().add(0, 1, 0).getBlock().isPassable()) {
            if (isL2) return plugin.getLevel2Generator().getArrivalLocation(targetWorld);
            return isL1 ? plugin.getLevel1Generator().getArrivalLocation(targetWorld) : null;
        }
        return target;
    }
}
