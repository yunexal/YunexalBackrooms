package com.yunexal.backrooms;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class YunexalBackroomsPlugin extends JavaPlugin {
    private BackroomsConfig backroomsConfig;
    private Level0ChunkGenerator level0Generator;
    private VoidChunkGenerator voidGenerator;
    private LimboManager limboManager;
    private BackroomsListeners listeners;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        reloadBackroomsConfig();
    }

    @Override
    public void onEnable() {
        if (backroomsConfig == null) {
            saveDefaultConfig();
            reloadBackroomsConfig();
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        this.limboManager = new LimboManager(this);
        this.listeners = new BackroomsListeners(this);

        getServer().getPluginManager().registerEvents(limboManager, this);
        getServer().getPluginManager().registerEvents(listeners, this);

        BackroomsCommand cmd = new BackroomsCommand(this);
        PluginCommand pluginCmd = getCommand("backrooms");
        if (pluginCmd != null) {
            pluginCmd.setExecutor(cmd);
            pluginCmd.setTabCompleter(cmd);
        }

        // Defer world rules & world_limbo creation until POSTWORLD (after STARTUP finishes loading "world")
        Bukkit.getScheduler().runTask(this, () -> {
            configureLevel0WorldRules();
            this.limboManager.init();
            this.listeners.start();
            getLogger().info("YunexalBackrooms worlds ready: Level 0='"
                    + backroomsConfig.getLevel0WorldName() + "', Limbo='"
                    + backroomsConfig.getLimboWorldName() + "'.");
        });

        getLogger().info("YunexalBackrooms enabled (STARTUP). Level 0 world='" + backroomsConfig.getLevel0WorldName()
                + "', Limbo world='" + backroomsConfig.getLimboWorldName()
                + "', RP required=" + backroomsConfig.isRpRequired());
    }

    @Override
    public void onDisable() {
        if (listeners != null) {
            listeners.shutdown();
        }
        if (limboManager != null) {
            limboManager.shutdown();
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, "BungeeCord");
    }

    public void reloadBackroomsConfig() {
        reloadConfig();
        this.backroomsConfig = new BackroomsConfig(getConfig());
        this.level0Generator = new Level0ChunkGenerator(this.backroomsConfig);
        this.voidGenerator = new VoidChunkGenerator(this.backroomsConfig);
    }

    private void configureLevel0WorldRules() {
        World w = getLevel0World();
        if (w == null) {
            getLogger().warning("Level 0 world '" + backroomsConfig.getLevel0WorldName() + "' is not loaded yet.");
            return;
        }
        w.setTime(18000L);
        w.setDifficulty(Difficulty.NORMAL);
        w.setGameRule(GameRules.ADVANCE_TIME, false);
        w.setGameRule(GameRules.ADVANCE_WEATHER, false);
        w.setGameRule(GameRules.SPAWN_MOBS, false);
        w.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        w.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false);
        w.setGameRule(GameRules.KEEP_INVENTORY, true);
        w.setGameRule(GameRules.LOCATOR_BAR, false);
        w.setSpawnLocation(level0Generator.findSafeSpawn(w));
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        if (backroomsConfig == null) {
            saveDefaultConfig();
            reloadBackroomsConfig();
        }
        if (worldName.equalsIgnoreCase(backroomsConfig.getLimboWorldName())
                || (id != null && id.equalsIgnoreCase("limbo"))) {
            return voidGenerator;
        }
        return level0Generator;
    }

    public BackroomsConfig getBackroomsConfig() {
        return backroomsConfig;
    }

    public Level0ChunkGenerator getLevel0Generator() {
        return level0Generator;
    }

    public VoidChunkGenerator getVoidGenerator() {
        return voidGenerator;
    }

    public LimboManager getLimboManager() {
        return limboManager;
    }

    public BackroomsListeners getListeners() {
        return listeners;
    }

    public @Nullable World getLevel0World() {
        return Bukkit.getWorld(backroomsConfig.getLevel0WorldName());
    }
}
