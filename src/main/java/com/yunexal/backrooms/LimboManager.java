package com.yunexal.backrooms;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the isolated pitch-black Limbo chamber and mandatory Resource Pack gate.
 * Players spawn inside world_limbo and cannot enter Level 0 until SUCCESSFULLY_LOADED fires.
 */
public final class LimboManager implements Listener {
    private final YunexalBackroomsPlugin plugin;
    private final Map<UUID, Long> pendingJoinAtMs = new ConcurrentHashMap<>();
    private final Set<UUID> packDispatched = ConcurrentHashMap.newKeySet();
    private final Set<UUID> verifiedPlayers = ConcurrentHashMap.newKeySet();

    private World limboWorld;
    private volatile Location cachedLimboSpawn;
    private BukkitTask watchdogTask;

    public LimboManager(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        ensureLimboWorld();
        if (watchdogTask != null) {
            watchdogTask.cancel();
        }
        watchdogTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWatchdog, 20L, 20L);
    }

    public void shutdown() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
            watchdogTask = null;
        }
        pendingJoinAtMs.clear();
        packDispatched.clear();
        verifiedPlayers.clear();
    }

    public World ensureLimboWorld() {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        World existing = Bukkit.getWorld(cfg.getLimboWorldName());
        if (existing != null) {
            this.limboWorld = existing;
            this.cachedLimboSpawn = new Location(existing, cfg.getLimboSpawnX(), cfg.getLimboSpawnY(), cfg.getLimboSpawnZ(), 0f, 0f);
            buildLimboChamber(existing, cfg);
            return existing;
        }

        WorldCreator creator = new WorldCreator(cfg.getLimboWorldName());
        creator.environment(World.Environment.NORMAL);
        creator.type(WorldType.FLAT);
        creator.generateStructures(false);
        creator.generator(plugin.getVoidGenerator());

        World created = creator.createWorld();
        if (created != null) {
            created.setTime(18000L);
            created.setDifficulty(Difficulty.PEACEFUL);
            created.setGameRule(GameRules.ADVANCE_TIME, false);
            created.setGameRule(GameRules.ADVANCE_WEATHER, false);
            created.setGameRule(GameRules.SPAWN_MOBS, false);
            created.setGameRule(GameRules.FALL_DAMAGE, false);
            created.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
            created.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false);
            created.setGameRule(GameRules.LOCATOR_BAR, false);
            buildLimboChamber(created, cfg);
            this.limboWorld = created;
            this.cachedLimboSpawn = new Location(created, cfg.getLimboSpawnX(), cfg.getLimboSpawnY(), cfg.getLimboSpawnZ(), 0f, 0f);
        }
        return created;
    }

    private void buildLimboChamber(World world, BackroomsConfig cfg) {
        int cx = (int) Math.floor(cfg.getLimboSpawnX());
        int cy = (int) Math.floor(cfg.getLimboSpawnY());
        int cz = (int) Math.floor(cfg.getLimboSpawnZ());
        int r = cfg.getLimboBoxRadius();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 5; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    boolean boundary = Math.abs(dx) == r || Math.abs(dz) == r || dy == -2 || dy == 5;
                    int bx = cx + dx;
                    int by = cy + dy;
                    int bz = cz + dz;
                    Material target = boundary ? Material.BLACK_CONCRETE : Material.AIR;
                    if (world.getBlockAt(bx, by, bz).getType() != target) {
                        world.getBlockAt(bx, by, bz).setType(target, false);
                    }
                }
            }
        }
        // Subtle solid floor right beneath spawn point
        world.getBlockAt(cx, cy - 1, cz).setType(Material.BLACK_CONCRETE, false);
        for (int dx = -r + 1; dx < r; dx++) {
            for (int dz = -r + 1; dz < r; dz++) {
                world.getBlockAt(cx + dx, cy - 1, cz + dz).setType(Material.BLACK_CONCRETE, false);
            }
        }
    }

    public Location getLimboSpawnLocation() {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        World w = ensureLimboWorld();
        return new Location(w, cfg.getLimboSpawnX(), cfg.getLimboSpawnY(), cfg.getLimboSpawnZ(), 0f, 0f);
    }

    public boolean isInLimbo(Player player) {
        return pendingJoinAtMs.containsKey(player.getUniqueId())
                || (limboWorld != null && player.getWorld().equals(limboWorld));
    }

    public boolean isVerified(Player player) {
        return verifiedPlayers.contains(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        if (!cfg.isRpEnabled()) {
            return;
        }
        Location limboLoc = cachedLimboSpawn;
        if (limboLoc != null) {
            event.setSpawnLocation(limboLoc.clone());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
        Player player = event.getPlayer();
        BackroomsConfig cfg = plugin.getBackroomsConfig();

        if (!cfg.isRpEnabled()) {
            verifiedPlayers.add(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(plugin, () -> enterLevel0(player, false), 1L);
            return;
        }

        beginLimboGate(player);
    }

    public void beginLimboGate(Player player) {
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        UUID uid = player.getUniqueId();
        verifiedPlayers.remove(uid);
        pendingJoinAtMs.put(uid, System.currentTimeMillis());

        Location limboLoc = getLimboSpawnLocation();
        player.teleport(limboLoc);
        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.getInventory().clear();
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 120, 1, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 20 * 120, 1, false, false, false));

        // Isolate players inside Limbo so they cannot see anyone else waiting or in Level 0
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                player.hidePlayer(plugin, other);
                other.hidePlayer(plugin, player);
            }
        }

        player.showTitle(Title.title(
                Component.text("ЛІМБО", NamedTextColor.DARK_GRAY, TextDecoration.BOLD),
                Component.text("Синхронізація текстур та звуку...", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(15), Duration.ofMillis(250))
        ));
        player.sendMessage(Component.text("▸ ", NamedTextColor.DARK_GRAY)
                .append(Component.text("Завантаження ресурс-паку Backrooms обов'язкове для переходу на Рівень 0.", NamedTextColor.YELLOW)));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !pendingJoinAtMs.containsKey(uid)) {
                return;
            }
            sendMandatoryPack(player, cfg);
        }, cfg.getRpSendDelayTicks());
    }

    public void sendMandatoryPack(Player player, BackroomsConfig cfg) {
        packDispatched.add(player.getUniqueId());
        byte[] sha1 = cfg.getRpSha1Bytes();
        if (sha1.length == 20) {
            player.setResourcePack(cfg.getRpUuid(), cfg.getRpUrl(), sha1, cfg.getRpPrompt(), cfg.isRpRequired());
        } else {
            player.setResourcePack(cfg.getRpUuid(), cfg.getRpUrl(), cfg.getRpSha1Hex(), cfg.getRpPrompt(), cfg.isRpRequired());
        }
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        UUID uid = player.getUniqueId();
        if (!pendingJoinAtMs.containsKey(uid) || !packDispatched.contains(uid)) {
            return;
        }
        BackroomsConfig cfg = plugin.getBackroomsConfig();
        if (!cfg.getRpUuid().equals(event.getID())) {
            return;
        }

        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        switch (status) {
            case ACCEPTED -> {
                player.sendActionBar(Component.text("Завантаження ресурс-паку Backrooms...", NamedTextColor.YELLOW));
            }
            case DOWNLOADED -> {
                player.sendActionBar(Component.text("Застосування текстур Level 0...", NamedTextColor.GOLD));
            }
            case SUCCESSFULLY_LOADED -> {
                pendingJoinAtMs.remove(uid);
                packDispatched.remove(uid);
                verifiedPlayers.add(uid);
                player.sendActionBar(Component.text("Ресурси завантажено. Перехід у реальність...", NamedTextColor.GREEN));
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        enterLevel0(player, true);
                    }
                }, 6L);
            }
            case DECLINED -> failGate(player, "Ресурс-пак є обов'язковим для гри у Backrooms!");
            case FAILED_DOWNLOAD -> failGate(player, "Не вдалося завантажити ресурс-пак (перевірте мережу).");
            case INVALID_URL -> failGate(player, "Помилка посилання ресурс-паку. Повідомте адміністрацію.");
            case FAILED_RELOAD -> failGate(player, "Клієнт не зміг застосувати ресурс-пак Backrooms.");
            case DISCARDED -> failGate(player, "Завантаження ресурс-паку було скасовано.");
        }
    }

    private void enterLevel0(Player player, boolean playNoclip) {
        World level0 = plugin.getLevel0World();
        if (level0 == null) {
            player.kick(Component.text("Світ Level 0 ще ініціалізується. Спробуйте ще раз.", NamedTextColor.RED));
            return;
        }

        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.DARKNESS);
        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);

        Location spawnLoc = plugin.getLevel0Generator().findSafeSpawn(level0);
        player.teleport(spawnLoc);

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player) && !pendingJoinAtMs.containsKey(other.getUniqueId())) {
                player.showPlayer(plugin, other);
                other.showPlayer(plugin, player);
            }
        }

        if (playNoclip) {
            player.playSound(spawnLoc, "minecraft:backrooms.ui.noclip", SoundCategory.MASTER, 0.85f, 1.0f);
        }

        player.showTitle(Title.title(
                Component.text("РІВЕНЬ 0", NamedTextColor.YELLOW, TextDecoration.BOLD),
                Component.text("«Лобі» // 600 млн кв. миль моно-жовтих стін", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(800))
        ));
        player.sendMessage(Component.text("▸ ", NamedTextColor.YELLOW)
                .append(Component.text("Ви провалилися крізь реальність на ", NamedTextColor.GRAY))
                .append(Component.text("Рівень 0 «Лобі»", NamedTextColor.GOLD))
                .append(Component.text(". Повернутись у хаб: ", NamedTextColor.GRAY))
                .append(Component.text("/br hub", NamedTextColor.AQUA)));
        plugin.getListeners().updateScoreboard(player);
    }

    private void failGate(Player player, String reason) {
        pendingJoinAtMs.remove(player.getUniqueId());
        packDispatched.remove(player.getUniqueId());
        verifiedPlayers.remove(player.getUniqueId());
        player.sendMessage(Component.text("✖ " + reason, NamedTextColor.RED));
        sendToHubOrKick(player, reason);
    }

    public void sendToHubOrKick(Player player, String kickReason) {
        String target = plugin.getBackroomsConfig().getFallbackServer();
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeUTF("Connect");
            out.writeUTF(target);
            player.sendPluginMessage(plugin, "BungeeCord", b.toByteArray());
        } catch (IOException ignored) {
            player.kick(Component.text(kickReason, NamedTextColor.RED));
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.kick(Component.text(kickReason, NamedTextColor.RED));
            }
        }, 12L);
    }

    private void tickWatchdog() {
        long now = System.currentTimeMillis();
        long timeoutMs = plugin.getBackroomsConfig().getRpTimeoutSeconds() * 1000L;

        for (Map.Entry<UUID, Long> entry : pendingJoinAtMs.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                pendingJoinAtMs.remove(entry.getKey());
                continue;
            }
            long elapsed = now - entry.getValue();
            if (elapsed >= timeoutMs) {
                failGate(player, "Час очікування завантаження ресурс-паку вичерпано.");
            } else {
                long left = Math.max(1L, (timeoutMs - elapsed + 999L) / 1000L);
                player.sendActionBar(Component.text("Лімбо • Очікування ресурс-паку (" + left + "с)", NamedTextColor.DARK_GRAY));
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!pendingJoinAtMs.containsKey(player.getUniqueId())) {
            return;
        }
        Location to = event.getTo();
        Location spawn = getLimboSpawnLocation();
        if (!to.getWorld().equals(spawn.getWorld())
                || Math.abs(to.getX() - spawn.getX()) > 0.35
                || Math.abs(to.getZ() - spawn.getZ()) > 0.35
                || Math.abs(to.getY() - spawn.getY()) > 0.35) {
            Location locked = spawn.clone();
            locked.setYaw(to.getYaw());
            locked.setPitch(to.getPitch());
            event.setTo(locked);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
        UUID uid = event.getPlayer().getUniqueId();
        pendingJoinAtMs.remove(uid);
        packDispatched.remove(uid);
        verifiedPlayers.remove(uid);
    }
}
