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
    private Level1ChunkGenerator level1Generator;
    private Level2ChunkGenerator level2Generator;
    private VoidChunkGenerator voidGenerator;
    private LimboManager limboManager;
    private Level1Manager level1Manager;
    private Level2Manager level2Manager;
    private BackroomsListeners listeners;
    private HallucinationManager hallucinations;
    private BodycamManager bodycam;
    private BackroomsEntityManager entities;
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
        this.level1Manager = new Level1Manager(this);
        this.level2Manager = new Level2Manager(this);
        this.listeners = new BackroomsListeners(this);
        this.hallucinations = new HallucinationManager(this);
        this.bodycam = new BodycamManager(this);
        this.entities = new BackroomsEntityManager(this);

        getServer().getPluginManager().registerEvents(craftingManager, this);
        getServer().getPluginManager().registerEvents(limboManager, this);
        getServer().getPluginManager().registerEvents(level1Manager, this);
        getServer().getPluginManager().registerEvents(listeners, this);
        getServer().getPluginManager().registerEvents(entities, this);

        BackroomsCommand cmd = new BackroomsCommand(this);
        PluginCommand pluginCmd = getCommand("backrooms");
        if (pluginCmd != null) {
            pluginCmd.setExecutor(cmd);
            pluginCmd.setTabCompleter(cmd);
        }

        Bukkit.getScheduler().runTask(this, () -> {
            configureLevel0WorldRules();
            this.limboManager.init();
            this.level1Manager.start();
            this.level2Manager.start();
            this.listeners.start();
            this.hallucinations.start();
            this.bodycam.start();
            this.entities.start();
            registerVoiceChatHook();
            getLogger().info("YunexalBackrooms worlds ready: Level 0='"
                    + backroomsConfig.getLevel0WorldName() + "', Level 1='"
                    + backroomsConfig.getLevel1WorldName() + "', Level 2='"
                    + backroomsConfig.getLevel2WorldName() + "', Limbo='"
                    + backroomsConfig.getLimboWorldName() + "', Border="
                    + (int) backroomsConfig.getWorldBorderSize()
                    + ", View/Sim=" + backroomsConfig.getViewDistance() + "/" + backroomsConfig.getSimulationDistance());
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
        if (bodycam != null) {
            bodycam.shutdown();
        }
        if (entities != null) {
            entities.shutdown();
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
        if (level1Manager != null) {
            level1Manager.shutdown();
        }
        if (level2Manager != null) {
            level2Manager.shutdown();
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
        if (this.level1Generator == null) {
            this.level1Generator = new Level1ChunkGenerator(this);
        } else {
            this.level1Generator.clearCache();
        }
        if (this.level2Generator == null) {
            this.level2Generator = new Level2ChunkGenerator(this);
        } else {
            this.level2Generator.clearCache();
        }
        this.voidGenerator = new VoidChunkGenerator(this.backroomsConfig);
        if (Bukkit.getServer() != null) {
            configureLevel0WorldRules();
            if (entities != null) {
                entities.reloadFromConfig();
            }
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
        w.setGameRule(GameRules.KEEP_INVENTORY, false);
        w.setGameRule(GameRules.LOCATOR_BAR, false);
        w.setGameRule(GameRules.REDUCED_DEBUG_INFO, true);
        w.setViewDistance(backroomsConfig.getViewDistance());
        w.setSendViewDistance(backroomsConfig.getViewDistance());
        w.setSimulationDistance(backroomsConfig.getSimulationDistance());
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
        if (worldName.equalsIgnoreCase(backroomsConfig.getLevel1WorldName())
                || (id != null && id.equalsIgnoreCase("level1"))) {
            return level1Generator;
        }
        if (worldName.equalsIgnoreCase(backroomsConfig.getLevel2WorldName())
                || (id != null && id.equalsIgnoreCase("level2"))) {
            return level2Generator;
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

    public Level1ChunkGenerator getLevel1Generator() {
        return level1Generator;
    }

    public Level1Manager getLevel1Manager() {
        return level1Manager;
    }

    public Level2ChunkGenerator getLevel2Generator() {
        return level2Generator;
    }

    public Level2Manager getLevel2Manager() {
        return level2Manager;
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

    public BodycamManager getBodycam() {
        return bodycam;
    }

    public BackroomsEntityManager getEntities() {
        return entities;
    }

    public @Nullable World getLevel0World() {
        return Bukkit.getWorld(backroomsConfig.getLevel0WorldName());
    }

    public @Nullable World getLevel1World() {
        return level1Manager != null ? level1Manager.getLevel1World() : Bukkit.getWorld(backroomsConfig.getLevel1WorldName());
    }

    public @Nullable World getLevel2World() {
        return level2Manager != null ? level2Manager.getLevel2World() : Bukkit.getWorld(backroomsConfig.getLevel2WorldName());
    }

    public boolean isBackroomsGameplayWorld(@Nullable World world) {
        if (world == null) {
            return false;
        }
        return world.equals(getLevel0World()) || world.equals(getLevel1World()) || world.equals(getLevel2World());
    }
}
