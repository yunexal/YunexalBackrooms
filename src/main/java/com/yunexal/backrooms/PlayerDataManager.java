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
import java.util.UUID;

/**
 * Persists each wanderer's Level 0 location and crafted inventory across reconnects.
 */
public final class PlayerDataManager {
    private final YunexalBackroomsPlugin plugin;
    private final File playersDir;

    public PlayerDataManager(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
        this.playersDir = new File(plugin.getDataFolder(), "players");
        if (!playersDir.exists()) {
            playersDir.mkdirs();
        }
    }

    public boolean hasSavedData(UUID uuid) {
        return new File(playersDir, uuid + ".yml").exists();
    }

    public void savePlayer(Player player) {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        World level0 = plugin.getLevel0World();
        if (level0 == null || !player.getWorld().equals(level0)) {
            return;
        }
        if (plugin.getLimboManager().isInLimbo(player) || !plugin.getLimboManager().isVerified(player)) {
            return;
        }

        File file = new File(playersDir, player.getUniqueId() + ".yml");
        YamlConfiguration yml = new YamlConfiguration();
        Location loc = player.getLocation();
        yml.set("location.x", loc.getX());
        yml.set("location.y", loc.getY());
        yml.set("location.z", loc.getZ());
        yml.set("location.yaw", (double) loc.getYaw());
        yml.set("location.pitch", (double) loc.getPitch());
        yml.set("health", player.getHealth());

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

        if (!yml.contains("location.x")) {
            return null;
        }
        double x = yml.getDouble("location.x");
        double y = yml.getDouble("location.y");
        double z = yml.getDouble("location.z");
        float yaw = (float) yml.getDouble("location.yaw", 0.0);
        float pitch = (float) yml.getDouble("location.pitch", 0.0);

        BackroomsConfig cfg = plugin.getBackroomsConfig();
        double half = (cfg.getWorldBorderSize() * 0.5) - 4.0;
        if (Math.abs(x) > half || Math.abs(z) > half) {
            return null;
        }
        if (y < cfg.getFloorY() + 0.5 || y > cfg.getCeilingY() - 1.5) {
            y = cfg.getFloorY() + 1.0;
        }
        Location target = new Location(level0, x, y, z, yaw, pitch);
        if (!target.getBlock().isPassable() || !target.clone().add(0, 1, 0).getBlock().isPassable()) {
            return null;
        }
        return target;
    }
}
