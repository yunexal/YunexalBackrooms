package com.yunexal.backrooms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Custom raw materials, crafted survival gear, and M.E.G. Manila Room lore books for Level 0.
 */
public final class BackroomsItems {
    public static final String ID_WALLPAPER_SCRAP = "wallpaper_scrap";
    public static final String ID_DAMP_WALLPAPER = "damp_wallpaper";
    public static final String ID_BASEBOARD_PLANK = "baseboard_plank";
    public static final String ID_CARPET_FIBER = "carpet_fiber";
    public static final String ID_METAL_PLATE = "metal_plate";
    public static final String ID_COPPER_WIRE = "copper_wire";
    public static final String ID_LAMP_SHARD = "lamp_shard";

    public static final String ID_WORKBENCH = "workbench";
    public static final String ID_BANDAGE = "bandage";
    public static final String ID_CROWBAR = "crowbar";
    public static final String ID_FLASHLIGHT = "flashlight";
    public static final String ID_MARKER = "marker";

    private final NamespacedKey itemKey;

    public BackroomsItems(YunexalBackroomsPlugin plugin) {
        this.itemKey = new NamespacedKey(plugin, "item_id");
    }

    public String getItemId(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    public boolean isItem(ItemStack stack, String id) {
        return id.equals(getItemId(stack));
    }

    public ItemStack wallpaperScrap(int amount) {
        return build(Material.PAPER, amount, ID_WALLPAPER_SCRAP,
                Component.text("Шматок жовтих шпалер", NamedTextColor.YELLOW),
                List.of(
                        Component.text("Зідраний папір зі стін Лобі.", NamedTextColor.GRAY),
                        Component.text("Тхне сирістю і старим клеєм.", NamedTextColor.DARK_GRAY)
                ), false);
    }

    public ItemStack dampWallpaper(int amount) {
        return build(Material.CLAY_BALL, amount, ID_DAMP_WALLPAPER,
                Component.text("Вологий шматок шпалер", NamedTextColor.GOLD),
                List.of(
                        Component.text("Просякнутий невідомою рідиною.", NamedTextColor.GRAY),
                        Component.text("Пити або вичавлювати — небезпечно.", NamedTextColor.RED)
                ), false);
    }

    public ItemStack baseboardPlank(int amount) {
        return build(Material.STICK, amount, ID_BASEBOARD_PLANK,
                Component.text("Рейка плінтуса", NamedTextColor.GOLD),
                List.of(
                        Component.text("Відірвана дерев'яна планка вздовж підлоги.", NamedTextColor.GRAY),
                        Component.text("Основа для верстака, лому та міток.", NamedTextColor.DARK_GRAY)
                ), false);
    }

    public ItemStack carpetFiber(int amount) {
        return build(Material.STRING, amount, ID_CARPET_FIBER,
                Component.text("Волокна килиму", NamedTextColor.GRAY),
                List.of(
                        Component.text("Міцні нитки з вологого килиму.", NamedTextColor.GRAY),
                        Component.text("Підходять для бинтів та кріплень.", NamedTextColor.DARK_GRAY)
                ), false);
    }

    public ItemStack metalPlate(int amount) {
        return build(Material.IRON_NUGGET, amount, ID_METAL_PLATE,
                Component.text("Бляшана пластина HVAC", NamedTextColor.WHITE),
                List.of(
                        Component.text("Частина вент-решітки або короба.", NamedTextColor.GRAY),
                        Component.text("Матеріал для лому та корпусу ліхтаря.", NamedTextColor.DARK_GRAY)
                ), false);
    }

    public ItemStack copperWire(int amount) {
        return build(Material.GOLD_NUGGET, amount, ID_COPPER_WIRE,
                Component.text("Мідний провід", NamedTextColor.YELLOW),
                List.of(
                        Component.text("Тонкий дріт з-під шпалер чи вентиляції.", NamedTextColor.GRAY)
                ), false);
    }

    public ItemStack lampShard(int amount) {
        return build(Material.QUARTZ, amount, ID_LAMP_SHARD,
                Component.text("Уламок люмінесцентної лампи", NamedTextColor.AQUA),
                List.of(
                        Component.text("Світиться залишковим люмінофором.", NamedTextColor.GRAY)
                ), false);
    }

    public ItemStack workbench(int amount) {
        return build(Material.CRAFTING_TABLE, amount, ID_WORKBENCH,
                Component.text("Імпровізований верстак", NamedTextColor.YELLOW),
                List.of(
                        Component.text("ПКМ у повітрі або на підлозі —", NamedTextColor.GRAY),
                        Component.text("відкриває верстак 3x3.", NamedTextColor.AQUA)
                ), false);
    }

    public ItemStack bandage(int amount) {
        return build(Material.PAPER, amount, ID_BANDAGE,
                Component.text("Тканинний бинт", NamedTextColor.GREEN),
                List.of(
                        Component.text("ПКМ — відновлює 6 HP і знімає", NamedTextColor.GRAY),
                        Component.text("галюцинації та напад паніки.", NamedTextColor.DARK_GREEN)
                ), true);
    }

    public ItemStack crowbar() {
        return build(Material.IRON_SHOVEL, 1, ID_CROWBAR,
                Component.text("Саморобний лом", NamedTextColor.WHITE),
                List.of(
                        Component.text("Миттєво зриває плінтуси та", NamedTextColor.GRAY),
                        Component.text("металеві решітки вентиляції.", NamedTextColor.AQUA)
                ), true);
    }

    public ItemStack flashlight() {
        return build(Material.LANTERN, 1, ID_FLASHLIGHT,
                Component.text("Аварійний ліхтар", NamedTextColor.GOLD),
                List.of(
                        Component.text("Тримайте в будь-якій руці, щоб", NamedTextColor.GRAY),
                        Component.text("освітлювати темні сектори та Червоні кімнати.", NamedTextColor.YELLOW)
                ), true);
    }

    public ItemStack marker(int amount) {
        return build(Material.YELLOW_CANDLE, amount, ID_MARKER,
                Component.text("Стрічка-мітка", NamedTextColor.YELLOW),
                List.of(
                        Component.text("ПКМ по килиму — ставить орієнтир,", NamedTextColor.GRAY),
                        Component.text("щоб не блукати колами.", NamedTextColor.DARK_GRAY)
                ), false);
    }

    public ItemStack createMegManualBook() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK, 1);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Протокол M.E.G. // Рівень 0");
        meta.setAuthor("Дослідник M.E.G.");
        meta.addPages(
                Component.text("РІВЕНЬ 0: «ЛОБІ»\nКлас виживання: 1\n\nЯкщо ви це читаєте — ви знайшли Манільську кімнату.\n\nНавколо нескінченні жовті коридори. Не шукайте інші обличчя чи координати — простір нелінійний."),
                Component.text("ПРАВИЛА ВИЖИВАННЯ:\n\n1. НІКОЛИ не пийте рідину з вологих килимів.\n2. Остерігайтеся Червоних кімнат — там наростає паніка.\n3. Рідкісні темні западини в підлозі («м'який килим») провалюють до межі Рівня 1."),
                Component.text("ІМПРОВІЗАЦІЯ:\n\nЗдирайте шпалери, плінтуси, нитки килиму та решітки вентиляції (ПКМ/ЛКМ).\n\nКоманда /br craft відкриває схему всіх саморобних рецептів.\nЗіберіть 4 рейки для верстака 3x3!"),
                Component.text("ОСНОВНІ СХЕМИ:\n\n- Верстак: 4 рейки (2x2)\n- Бинт: 2 волокна + шпалера\n- Мітка: 2 шпалери + волокно\n- Лом: 2 пластини + рейка\n- Ліхтар: уламок + дріт + пластина")
        );
        book.setItemMeta(meta);
        return book;
    }

    private ItemStack build(Material mat, int amount, String id, Component name, List<Component> lore, boolean glow) {
        ItemStack stack = new ItemStack(mat, amount);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
        if (glow) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }
}
