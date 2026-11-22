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
            sender.sendMessage(Component.text("YUNEXAL BACKROOMS ", NamedTextColor.YELLOW)
                    .append(Component.text("• Рівень 0 «Лобі»", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("Клас виживання: ", NamedTextColor.GRAY)
                    .append(Component.text("1 (Мінімум сутностей, нелінійний простір)", NamedTextColor.YELLOW)));
            sender.sendMessage(Component.text("Ресурс-пак: ", NamedTextColor.GRAY)
                    .append(Component.text(cfg.isRpEnabled() ? "Увімкнений (обов'язковий)" : "Вимкнений", NamedTextColor.AQUA)));
            sender.sendMessage(Component.text("Рецепти: ", NamedTextColor.GRAY)
                    .append(Component.text("/br craft", NamedTextColor.GOLD))
                    .append(Component.text(" | Хаб: ", NamedTextColor.GRAY))
                    .append(Component.text("/br hub", NamedTextColor.AQUA)));
            if (sender.hasPermission("yunexal.backrooms.admin") && sender instanceof Player p && plugin.getLevel0World() != null && p.getWorld().equals(plugin.getLevel0World())) {
                Location l = p.getLocation();
                Level0ChunkGenerator.ZoneType zone = plugin.getLevel0Generator().getZoneAtBlock(l.getWorld(), l.getBlockX(), l.getBlockZ());
                sender.sendMessage(Component.text("[Admin] Поточний сектор: " + zone.name(), NamedTextColor.DARK_GRAY));
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
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
                    sender.sendMessage(Component.text("Використання: /br tp <level0|limbo>", NamedTextColor.YELLOW));
                    return true;
                }
                if (args[1].equalsIgnoreCase("limbo")) {
                    p.teleport(plugin.getLimboManager().getLimboSpawnLocation());
                    p.sendMessage(Component.text("Телепортація в Лімбо.", NamedTextColor.GRAY));
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
            default -> {
                sender.sendMessage(Component.text("Використання: /br <info|craft|hub|reload|reloadrp|tp>", NamedTextColor.YELLOW));
                return true;
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            if (sender.hasPermission("yunexal.backrooms.admin")) {
                return List.of("info", "craft", "hub", "reload", "reloadrp", "tp");
            }
            return List.of("info", "craft", "hub");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("tp") && sender.hasPermission("yunexal.backrooms.admin")) {
            return List.of("level0", "limbo");
        }
        return List.of();
    }
}
