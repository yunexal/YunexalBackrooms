package com.yunexal.backrooms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class BackroomsCommand implements CommandExecutor, TabCompleter {
    private final YunexalBackroomsPlugin plugin;

    public BackroomsCommand(YunexalBackroomsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            BackroomsConfig cfg = plugin.getBackroomsConfig();
            boolean inL1 = (sender instanceof Player p) && plugin.getLevel1World() != null && p.getWorld().equals(plugin.getLevel1World());
            boolean inL2 = (sender instanceof Player p) && plugin.getLevel2World() != null && p.getWorld().equals(plugin.getLevel2World());
            String titleStr = inL2 ? "• Рівень 2 «Покинуті підсобні приміщення»"
                    : (inL1 ? "• Рівень 1 «Населена зона»" : "• Рівень 0 «Лобі»");
            String descStr = inL2
                    ? "2 (Тунелі труб, спека >43°C, Смертомолі та Шкірокради)"
                    : (inL1 ? "2 (Склади, ящики, мерехтіння, аванпости M.E.G. / B.N.T.G.)"
                    : "1 (Виживайте, щоб знайти блимаючу стіну до Рівня 1)");
            sender.sendMessage(Component.text("YUNEXAL BACKROOMS ", NamedTextColor.YELLOW)
                    .append(Component.text(titleStr, NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("Клас виживання: ", NamedTextColor.GRAY)
                    .append(Component.text(descStr, NamedTextColor.YELLOW)));
            if (!inL1 && !inL2 && sender instanceof Player p) {
                int sec = plugin.getPlayerData().getSurvivalSecondsLevel0(p);
                int minSec = cfg.getExitMinSurvivalSeconds();
                sender.sendMessage(Component.text("Час виживання на Рівні 0: ", NamedTextColor.GRAY)
                        .append(Component.text(sec + "с / " + minSec + "с", sec >= minSec ? NamedTextColor.GREEN : NamedTextColor.GOLD)));
            }
            sender.sendMessage(Component.text("Ресурс-пак: ", NamedTextColor.GRAY)
                    .append(Component.text(cfg.isRpEnabled() ? "Увімкнений (обов'язковий)" : "Вимкнений", NamedTextColor.AQUA)));
            sender.sendMessage(Component.text("Рецепти: ", NamedTextColor.GRAY)
                    .append(Component.text("/br craft", NamedTextColor.GOLD))
                    .append(Component.text(" | Бодікам: ", NamedTextColor.GRAY))
                    .append(Component.text("/br bodycam", NamedTextColor.YELLOW))
                    .append(Component.text(" | Хаб: ", NamedTextColor.GRAY))
                    .append(Component.text("/br hub", NamedTextColor.AQUA)));
            if (sender.hasPermission("yunexal.backrooms.admin") && sender instanceof Player p) {
                Location l = p.getLocation();
                if (inL2) {
                    Level2ChunkGenerator.Level2ZoneType z2 = plugin.getLevel2Generator().getZoneAtBlock(l.getWorld(), l.getBlockX(), l.getBlockZ());
                    sender.sendMessage(Component.text("[Admin] Рівень 2 зона: " + z2.name(), NamedTextColor.DARK_GRAY));
                } else if (inL1) {
                    Level1ChunkGenerator.Level1ZoneType z1 = plugin.getLevel1Generator().getZoneAtBlock(l.getWorld(), l.getBlockX(), l.getBlockZ());
                    sender.sendMessage(Component.text("[Admin] Рівень 1 зона: " + z1.name() + " | Світло: " + plugin.getLevel1Manager().getBlackoutPhase().name(), NamedTextColor.DARK_GRAY));
                } else if (plugin.getLevel0World() != null && p.getWorld().equals(plugin.getLevel0World())) {
                    Level0ChunkGenerator.ZoneType zone = plugin.getLevel0Generator().getZoneAtBlock(l.getWorld(), l.getBlockX(), l.getBlockZ());
                    sender.sendMessage(Component.text("[Admin] Поточний сектор: " + zone.name(), NamedTextColor.DARK_GRAY));
                }
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "bodycam", "cam" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("Команда лише для гравців.", NamedTextColor.RED));
                    return true;
                }
                boolean on = plugin.getBodycam().toggleFor(p);
                p.sendMessage(Component.text("Ефект бодікаму (хитання камери): ", NamedTextColor.GRAY)
                        .append(Component.text(on ? "Увімкнено" : "Вимкнено", on ? NamedTextColor.GREEN : NamedTextColor.RED)));
                return true;
            }
            case "craft", "recipes" -> {
                if (sender instanceof Player p) {
                    plugin.getCraftingManager().sendRecipeGuide(p);
                } else {
                    sender.sendMessage(Component.text("Команда лише для гравців.", NamedTextColor.RED));
                }
                return true;
            }
            case "hub", "leave" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(Component.text("Команда лише для гравців.", NamedTextColor.RED));
                    return true;
                }
                p.sendMessage(Component.text("Повертаємося у хаб...", NamedTextColor.AQUA));
                plugin.getLimboManager().sendToHubOrKick(p, "Перехід у хаб Yunexal.");
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("yunexal.backrooms.admin")) {
                    sender.sendMessage(Component.text("Недостатньо прав.", NamedTextColor.RED));
                    return true;
                }
                plugin.reloadBackroomsConfig();
                sender.sendMessage(Component.text("Конфігурацію YunexalBackrooms перезавантажено.", NamedTextColor.GREEN));
                return true;
            }
            case "reloadrp" -> {
                if (!sender.hasPermission("yunexal.backrooms.admin")) {
                    sender.sendMessage(Component.text("Недостатньо прав.", NamedTextColor.RED));
                    return true;
                }
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage(Component.text("Гравець не знайдений.", NamedTextColor.RED));
                    return true;
                }
                plugin.getLimboManager().beginLimboGate(target);
                sender.sendMessage(Component.text("Повторно відправлено у Лімбо з перевіркою ресурс-паку: " + target.getName(), NamedTextColor.YELLOW));
                return true;
            }
            case "tp" -> {
                if (!sender.hasPermission("yunexal.backrooms.admin")) {
                    sender.sendMessage(Component.text("Недостатньо прав.", NamedTextColor.RED));
                    return true;
                }
                if (!(sender instanceof Player p) || args.length < 2) {
                    sender.sendMessage(Component.text("Використання: /br tp <level0|level1|level2|limbo|basealpha|trader|diner>", NamedTextColor.YELLOW));
                    return true;
                }
                String dest = args[1].toLowerCase(Locale.ROOT);
                if (dest.equals("limbo")) {
                    p.teleport(plugin.getLimboManager().getLimboSpawnLocation());
                    p.sendMessage(Component.text("Телепортація в Лімбо.", NamedTextColor.GRAY));
                } else if (dest.equals("level2") || dest.equals("l2")) {
                    World l2 = plugin.getLevel2World();
                    if (l2 != null) {
                        p.teleport(plugin.getLevel2Generator().getArrivalLocation(l2));
                        p.sendMessage(Component.text("Телепортація на Рівень 2 («Мрії про труби»).", NamedTextColor.GOLD));
                    }
                } else if (dest.equals("level1") || dest.equals("l1") || dest.equals("basealpha") || dest.equals("trader") || dest.equals("diner")) {
                    World l1 = plugin.getLevel1World();
                    if (l1 != null) {
                        Location target = switch (dest) {
                            case "basealpha" -> plugin.getLevel1Generator().getOutpostSpawn(l1, Level1ChunkGenerator.Level1ZoneType.MEG_BASE_ALPHA);
                            case "trader" -> plugin.getLevel1Generator().getOutpostSpawn(l1, Level1ChunkGenerator.Level1ZoneType.BNTG_TRADER_KEEP);
                            case "diner" -> plugin.getLevel1Generator().getOutpostSpawn(l1, Level1ChunkGenerator.Level1ZoneType.TOMS_DINER);
                            default -> plugin.getLevel1Generator().getArrivalLocation(l1);
                        };
                        p.teleport(target);
                        p.sendMessage(Component.text("Телепортація на Рівень 1 (" + dest + ").", NamedTextColor.GRAY));
                    }
                } else {
                    World w = plugin.getLevel0World();
                    if (w != null) {
                        p.teleport(plugin.getLevel0Generator().findRandomFarSpawn(
                                w, Bukkit.getOnlinePlayers(), plugin.getBackroomsConfig().getSpawnRadius()));
                        p.sendMessage(Component.text("Телепортація на Рівень 0 «Лобі».", NamedTextColor.YELLOW));
                    }
                }
                return true;
            }
            case "flicker", "blackout" -> {
                if (!sender.hasPermission("yunexal.backrooms.admin")) {
                    sender.sendMessage(Component.text("Недостатньо прав.", NamedTextColor.RED));
                    return true;
                }
                plugin.getLevel1Manager().triggerFlickerNow();
                sender.sendMessage(Component.text("Запущено подію «Мерехтіння» на Рівні 1.", NamedTextColor.YELLOW));
                return true;
            }
            case "entity", "mob" -> {
                if (!sender.hasPermission("yunexal.backrooms.admin")) {
                    sender.sendMessage(Component.text("Недостатньо прав.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Використання: /br entity <spawn|autospawn|list|clear>", NamedTextColor.YELLOW));
                    return true;
                }
                BackroomsEntityManager em = plugin.getEntities();
                switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "spawn" -> {
                        if (!(sender instanceof Player p) || args.length < 3) {
                            sender.sendMessage(Component.text("Використання: /br entity spawn <bacteria|smiler|hound|faceless|skin_stealer|deathmoth>", NamedTextColor.YELLOW));
                            return true;
                        }
                        BackroomsEntityManager.EntityKind kind = BackroomsEntityManager.parseKind(args[2]);
                        if (kind == null) {
                            sender.sendMessage(Component.text("Невідома сутність. Доступні: bacteria, smiler, hound, faceless, skin_stealer, deathmoth", NamedTextColor.RED));
                            return true;
                        }
                        int floorY = p.getWorld().equals(plugin.getLevel2World())
                                ? plugin.getBackroomsConfig().getLevel2FloorY()
                                : (p.getWorld().equals(plugin.getLevel1World())
                                ? plugin.getBackroomsConfig().getLevel1FloorY()
                                : plugin.getBackroomsConfig().getFloorY());
                        Location spot = em.findSpawnSpotNear(p.getWorld(), p.getLocation(), floorY, 5, 10);
                        if (spot == null) {
                            spot = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(4));
                            spot.setY(floorY + 1.0);
                        }
                        boolean ok = em.spawnEntity(spot, kind);
                        sender.sendMessage(Component.text(ok
                                ? "Створено сутність " + kind.name() + " неподалік."
                                : "Не вдалося створити сутність (безпечна зона / Лімбо).",
                                ok ? NamedTextColor.GREEN : NamedTextColor.RED));
                    }
                    case "autospawn" -> {
                        if (args.length >= 3) {
                            boolean enable = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
                            em.setAutoSpawnEnabled(enable);
                        }
                        sender.sendMessage(Component.text("Автоспавн сутностей: ", NamedTextColor.GRAY)
                                .append(Component.text(em.isAutoSpawnEnabled() ? "Увімкнено" : "Вимкнено (підготовлено)",
                                        em.isAutoSpawnEnabled() ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
                    }
                    case "list" -> sender.sendMessage(Component.text("Активних сутностей: " + em.getActiveCount(), NamedTextColor.AQUA));
                    case "clear" -> sender.sendMessage(Component.text("Прибрано сутностей: " + em.clearAll(), NamedTextColor.GREEN));
                    default -> sender.sendMessage(Component.text("Використання: /br entity <spawn|autospawn|list|clear>", NamedTextColor.YELLOW));
                }
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("Використання: /br <info|craft|bodycam|hub|reload|reloadrp|tp|entity>", NamedTextColor.YELLOW));
                return true;
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            if (sender.hasPermission("yunexal.backrooms.admin")) {
                return List.of("info", "craft", "bodycam", "hub", "reload", "reloadrp", "tp", "flicker", "entity");
            }
            return List.of("info", "craft", "bodycam", "hub");
        }
        if (sender.hasPermission("yunexal.backrooms.admin")) {
            if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
                return List.of("level0", "level1", "level2", "limbo", "basealpha", "trader", "diner");
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("entity") || args[0].equalsIgnoreCase("mob"))) {
                return List.of("spawn", "autospawn", "list", "clear");
            }
            if (args.length == 3 && (args[0].equalsIgnoreCase("entity") || args[0].equalsIgnoreCase("mob"))) {
                if (args[1].equalsIgnoreCase("spawn")) {
                    return List.of("bacteria", "smiler", "hound", "faceless", "skin_stealer", "deathmoth");
                }
                if (args[1].equalsIgnoreCase("autospawn")) {
                    return List.of("on", "off", "status");
                }
            }
        }
        return List.of();
    }
}
