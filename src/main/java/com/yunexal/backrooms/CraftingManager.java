package com.yunexal.backrooms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
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
    private final NamespacedKey keyBrokenCompass;
    private final NamespacedKey keyWoolFiber;
    private final NamespacedKey keyRedWoolFiber;
    private final NamespacedKey keyBarrel;
    private final NamespacedKey keyTorch;
    private final NamespacedKey keyLadder;

    public CraftingManager(YunexalBackroomsPlugin plugin, BackroomsItems items) {
        this.plugin = plugin;
        this.items = items;
        this.keyWorkbench = new NamespacedKey(plugin, "recipe_workbench");
        this.keyBandage = new NamespacedKey(plugin, "recipe_bandage");
        this.keyCrowbar = new NamespacedKey(plugin, "recipe_crowbar");
        this.keyFlashlight = new NamespacedKey(plugin, "recipe_flashlight");
        this.keyMarker = new NamespacedKey(plugin, "recipe_marker");
        this.keyDryWallpaper = new NamespacedKey(plugin, "recipe_dry_wallpaper");
        this.keyBrokenCompass = new NamespacedKey(plugin, "recipe_broken_compass");
        this.keyWoolFiber = new NamespacedKey(plugin, "recipe_wool_fiber");
        this.keyRedWoolFiber = new NamespacedKey(plugin, "recipe_red_wool_fiber");
        this.keyBarrel = new NamespacedKey(plugin, "recipe_barrel");
        this.keyTorch = new NamespacedKey(plugin, "recipe_torch");
        this.keyLadder = new NamespacedKey(plugin, "recipe_ladder");
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

        // 7. Anomalous Broken Compass (3x3 workbench: metal plates around wire & lamp shard)
        ShapedRecipe bc = new ShapedRecipe(keyBrokenCompass, items.brokenCompass());
        bc.shape(" M ", "MWM", " S ");
        bc.setIngredient('M', plate);
        bc.setIngredient('W', wire);
        bc.setIngredient('S', shard);
        Bukkit.addRecipe(bc);

        // 8. Unravel torn carpet (BROWN_WOOL / RED_WOOL) into carpet fiber (2x2)
        ShapelessRecipe wf = new ShapelessRecipe(keyWoolFiber, items.carpetFiber(2));
        wf.addIngredient(Material.BROWN_WOOL);
        Bukkit.addRecipe(wf);

        ShapelessRecipe rwf = new ShapelessRecipe(keyRedWoolFiber, items.carpetFiber(2));
        rwf.addIngredient(Material.RED_WOOL);
        Bukkit.addRecipe(rwf);

        // 9. Supply Barrel for player bases (3x3: 6 baseboard planks + 2 metal plates)
        ShapedRecipe br = new ShapedRecipe(keyBarrel, new ItemStack(Material.BARREL, 1));
        br.shape("PMP", "P P", "PMP");
        br.setIngredient('P', plank);
        br.setIngredient('M', plate);
        Bukkit.addRecipe(br);

        // 10. Base Wall Torch (2x2: lamp shard over baseboard plank -> 2x Torch)
        ShapedRecipe tc = new ShapedRecipe(keyTorch, new ItemStack(Material.TORCH, 2));
        tc.shape("S", "P");
        tc.setIngredient('S', shard);
        tc.setIngredient('P', plank);
        Bukkit.addRecipe(tc);

        // 11. Maintenance Ladder (3x3: 7 baseboard planks)
        ShapedRecipe ld = new ShapedRecipe(keyLadder, new ItemStack(Material.LADDER, 2));
        ld.shape("P P", "PPP", "P P");
        ld.setIngredient('P', plank);
        Bukkit.addRecipe(ld);
    }

    private void removeExisting() {
        for (NamespacedKey key : new NamespacedKey[]{
                keyWorkbench, keyBandage, keyCrowbar, keyFlashlight, keyMarker, keyDryWallpaper, keyBrokenCompass,
                keyWoolFiber, keyRedWoolFiber, keyBarrel, keyTorch, keyLadder
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

        if (recipe instanceof Keyed keyed && keyed.getKey().getNamespace().equalsIgnoreCase(plugin.getName())) {
            return;
        }

        // Prevent Backrooms custom PDC ingredients from accidentally crafting vanilla sticks/candles/bows
        for (ItemStack mat : inv.getMatrix()) {
            if (items.getItemId(mat) != null) {
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
        player.sendMessage(Component.text("▸ Аномальний компас (верстак 3x3): ", NamedTextColor.AQUA)
                .append(Component.text("4× Пластини навколо Дроту + Уламок лампи знизу", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("▸ Розпустити ковролін (2x2): ", NamedTextColor.GRAY)
                .append(Component.text("1× Здертий килим → 2× Волокна килиму", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("▸ Сховок-бочка (верстак 3x3): ", NamedTextColor.GOLD)
                .append(Component.text("6× Рейок плінтуса + 2× Бляшані пластини (верх/низ)", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("▸ Смолоскип ×2 / Драбина ×2: ", NamedTextColor.YELLOW)
                .append(Component.text("Уламок над Рейкою / 7× Рейок буквою H", NamedTextColor.WHITE)));
    }
}
