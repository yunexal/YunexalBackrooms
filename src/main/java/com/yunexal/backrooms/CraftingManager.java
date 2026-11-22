package com.yunexal.backrooms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

/**
 * Registers custom from-scratch Backrooms survival recipes and protects custom PDC ingredient checks.
 */
public final class CraftingManager implements Listener {
    private final YunexalBackroomsPlugin plugin;
    private final BackroomsItems items;

    private final NamespacedKey keyWorkbench;
    private final NamespacedKey keyBandage;
    private final NamespacedKey keyCrowbar;
    private final NamespacedKey keyFlashlight;
    private final NamespacedKey keyMarker;
    private final NamespacedKey keyDryWallpaper;

    public CraftingManager(YunexalBackroomsPlugin plugin, BackroomsItems items) {
        this.plugin = plugin;
        this.items = items;
        this.keyWorkbench = new NamespacedKey(plugin, "recipe_workbench");
        this.keyBandage = new NamespacedKey(plugin, "recipe_bandage");
        this.keyCrowbar = new NamespacedKey(plugin, "recipe_crowbar");
        this.keyFlashlight = new NamespacedKey(plugin, "recipe_flashlight");
        this.keyMarker = new NamespacedKey(plugin, "recipe_marker");
        this.keyDryWallpaper = new NamespacedKey(plugin, "recipe_dry_wallpaper");
    }

    public void registerRecipes() {
        removeExisting();

        RecipeChoice.ExactChoice wallpaper = new RecipeChoice.ExactChoice(items.wallpaperScrap(1));
        RecipeChoice.ExactChoice plank = new RecipeChoice.ExactChoice(items.baseboardPlank(1));
        RecipeChoice.ExactChoice fiber = new RecipeChoice.ExactChoice(items.carpetFiber(1));
        RecipeChoice.ExactChoice plate = new RecipeChoice.ExactChoice(items.metalPlate(1));
        RecipeChoice.ExactChoice wire = new RecipeChoice.ExactChoice(items.copperWire(1));
        RecipeChoice.ExactChoice shard = new RecipeChoice.ExactChoice(items.lampShard(1));
        RecipeChoice.ExactChoice damp = new RecipeChoice.ExactChoice(items.dampWallpaper(1));

        // 1. Workbench (2x2 baseboard planks -> 3x3 crafting table)
        ShapedRecipe wb = new ShapedRecipe(keyWorkbench, items.workbench(1));
        wb.shape("PP", "PP");
        wb.setIngredient('P', plank);
        Bukkit.addRecipe(wb);

        // 2. Bandage (2x carpet fiber + 1x wallpaper scrap)
        ShapelessRecipe bd = new ShapelessRecipe(keyBandage, items.bandage(1));
        bd.addIngredient(fiber);
        bd.addIngredient(fiber);
        bd.addIngredient(wallpaper);
        Bukkit.addRecipe(bd);

        // 3. Crowbar (2x metal plate + 1x plank in vertical or L shape -> shapeless for 2x2 ease)
        ShapelessRecipe cb = new ShapelessRecipe(keyCrowbar, items.crowbar());
        cb.addIngredient(plate);
        cb.addIngredient(plate);
        cb.addIngredient(plank);
        Bukkit.addRecipe(cb);

        // 4. Flashlight (requires 3x3 workbench or arranged cleanly: plate + wire + shard)
        ShapedRecipe fl = new ShapedRecipe(keyFlashlight, items.flashlight());
        fl.shape(" S ", " W ", " M ");
        fl.setIngredient('S', shard);
        fl.setIngredient('W', wire);
        fl.setIngredient('M', plate);
        Bukkit.addRecipe(fl);

        // 5. Path Marker (2x wallpaper + 1x fiber)
        ShapelessRecipe mk = new ShapelessRecipe(keyMarker, items.marker(2));
        mk.addIngredient(wallpaper);
        mk.addIngredient(wallpaper);
        mk.addIngredient(fiber);
        Bukkit.addRecipe(mk);

        // 6. Salvage damp wallpaper with dry paper
        ShapelessRecipe dw = new ShapelessRecipe(keyDryWallpaper, items.wallpaperScrap(2));
        dw.addIngredient(damp);
        dw.addIngredient(wallpaper);
        Bukkit.addRecipe(dw);
    }

    private void removeExisting() {
        for (NamespacedKey key : new NamespacedKey[]{
                keyWorkbench, keyBandage, keyCrowbar, keyFlashlight, keyMarker, keyDryWallpaper
        }) {
            Bukkit.removeRecipe(key);
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) {
            return;
        }
        CraftingInventory inv = event.getInventory();
        ItemStack result = inv.getResult();
        if (result == null) {
            return;
        }

        // Prevent vanilla Paper/String/Stick from accidentally crafting vanilla sticks/candles/torches in Backrooms
        String resultCustomId = items.getItemId(result);
        if (resultCustomId == null) {
            for (ItemStack mat : inv.getMatrix()) {
                if (items.getItemId(mat) != null) {
                    inv.setResult(new ItemStack(Material.AIR));
                    return;
                }
            }
            return;
        }

        // Verify every non-empty slot actually carries a Backrooms custom ingredient (ExactChoice already checks meta,
        // but this guarantees zero bypasses).
        for (ItemStack mat : inv.getMatrix()) {
            if (mat != null && !mat.getType().isAir() && items.getItemId(mat) == null) {
                inv.setResult(new ItemStack(Material.AIR));
                return;
            }
        }
    }

    public void sendRecipeGuide(Player player) {
        player.sendMessage(Component.text("═══ КАСТОМНІ КРАФТИ // РІВЕНЬ 0 ═══", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("▸ Імпровізований верстак (2x2): ", NamedTextColor.GOLD)
                .append(Component.text("4× Рейка плінтуса", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("▸ Тканинний бинт (2x2): ", NamedTextColor.GREEN)
                .append(Component.text("2× Волокна килиму + 1× Шматок шпалер", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("▸ Саморобний лом (2x2): ", NamedTextColor.AQUA)
                .append(Component.text("2× Бляшана пластина + 1× Рейка плінтуса", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("▸ Стрічка-мітка ×2 (2x2): ", NamedTextColor.YELLOW)
                .append(Component.text("2× Шматок шпалер + 1× Волокна килиму", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("▸ Аварійний ліхтар (верстак 3x3): ", NamedTextColor.GOLD)
                .append(Component.text("Уламок лампи над Мідним дротом над Пластиною", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("▸ Просушити шпалери (2x2): ", NamedTextColor.GRAY)
                .append(Component.text("Вологі шпалери + Сухі шпалери → 2× Шматки шпалер", NamedTextColor.WHITE)));
    }
}
