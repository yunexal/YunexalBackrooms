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
    private BackroomsItems items;
    private PlayerDataManager playerData;
    private CraftingManager craftingManager;
    private Level0ChunkGenerator level0Generator;
    private VoidChunkGenerator voidGenerator;
    private LimboManager limboManager;
    private BackroomsListeners listeners;
    private HallucinationManager hallucinations;
    private BackroomsVoicePlugin voicePlugin;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        this.items = new BackroomsItems(this);
        reloadBackroomsConfig();
    }

    @Override
    public void onEnable() {
        if (backroomsConfig == null) {
            saveDefaultConfig();
            this.items = new BackroomsItems(this);
            reloadBackroomsConfig();
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        this.playerData = new PlayerDataManager(this);
        this.craftingManager = new CraftingManager(this, items);
        this.craftingManager.registerRecipes();
        this.limboManager = new LimboManager(this);
        this.listeners = new BackroomsListeners(this);
        this.hallucinations = new HallucinationManager(this);

        getServer().getPluginManager().registerEvents(craftingManager, this);
        getServer().getPluginManager().registerEvents(limboManager, this);
        getServer().getPluginManager().registerEvents(listeners, this);

        BackroomsCommand cmd = new BackroomsCommand(this);
        PluginCommand pluginCmd = getCommand("backrooms");
        if (pluginCmd != null) {
            pluginCmd.setExecutor(cmd);
            pluginCmd.setTabCompleter(cmd);
        }

        Bukkit.getScheduler().runTask(this, () -> {
            configureLevel0WorldRules();
            this.limboManager.init();
            this.listeners.start();
            this.hallucinations.start();
            registerVoiceChatHook();
            getLogger().info("YunexalBackrooms worlds ready: Level 0='"
                    + backroomsConfig.getLevel0WorldName() + "', Limbo='"
                    + backroomsConfig.getLimboWorldName() + "', Border="
                    + (int) backroomsConfig.getWorldBorderSize());
        });

        getLogger().info("YunexalBackrooms enabled (STARTUP). Level 0 world='" + backroomsConfig.getLevel0WorldName()
                + "', RP required=" + backroomsConfig.isRpRequired());
    }

    @Override
    public void onDisable() {
        if (playerData != null) {
            playerData.saveAllOnline();
        }
        if (hallucinations != null) {
            hallucinations.shutdown();
        }
        if (voicePlugin != null) {
            voicePlugin.shutdown();
        }
        if (listeners != null) {
            listeners.shutdown();
        }
        if (limboManager != null) {
            limboManager.shutdown();
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, "BungeeCord");
    }

    private void registerVoiceChatHook() {
        try {
            if (getServer().getPluginManager().isPluginEnabled("voicechat")) {
                var service = getServer().getServicesManager().load(de.maxhenkel.voicechat.api.BukkitVoicechatService.class);
                if (service != null) {
                    this.voicePlugin = new BackroomsVoicePlugin(this);
                    service.registerPlugin(this.voicePlugin);
                    getLogger().info("Registered Simple Voice Chat interference hook for Level 0.");
                }
            }
        } catch (NoClassDefFoundError | Exception e) {
            getLogger().warning("Simple Voice Chat hook unavailable: " + e.getMessage());
        }
    }

    public void reloadBackroomsConfig() {
        reloadConfig();
        this.backroomsConfig = new BackroomsConfig(getConfig());
        if (this.level0Generator == null) {
            this.level0Generator = new Level0ChunkGenerator(this);
        } else {
            this.level0Generator.clearCache();
        }
        this.voidGenerator = new VoidChunkGenerator(this.backroomsConfig);
        if (Bukkit.getServer() != null) {
            configureLevel0WorldRules();
        }
    }

    private void configureLevel0WorldRules() {
        World w = getLevel0World();
        if (w == null) {
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
        w.setGameRule(GameRules.REDUCED_DEBUG_INFO, true);
        w.getWorldBorder().setCenter(0.0, 0.0);
        w.getWorldBorder().setSize(backroomsConfig.getWorldBorderSize());
        w.setSpawnLocation(level0Generator.findSafeSpawn(w));
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        if (backroomsConfig == null) {
            saveDefaultConfig();
            this.items = new BackroomsItems(this);
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

    public BackroomsItems getItems() {
        return items;
    }

    public PlayerDataManager getPlayerData() {
        return playerData;
    }

    public CraftingManager getCraftingManager() {
        return craftingManager;
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

    public HallucinationManager getHallucinations() {
        return hallucinations;
    }

    public @Nullable World getLevel0World() {
        return Bukkit.getWorld(backroomsConfig.getLevel0WorldName());
    }
}
