package com.yunexal.backrooms;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

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
    public static final String ID_ALMOND_WATER = "almond_water";
    public static final String ID_BROKEN_COMPASS = "broken_compass";

    // Level 1 ("Habitable Zone") canonical crate loot, trader currency & weird items
    public static final String ID_CANNED_RATION = "canned_ration";
    public static final String ID_BATTERY = "battery_cell";
    public static final String ID_RUSTY_AUTOPART = "rusty_autopart";
    public static final String ID_USED_SYRINGE = "used_syringe";
    public static final String ID_CRAYON_BOX = "crayon_box";
    public static final String ID_BURNT_PAPER = "burnt_paper";
    public static final String ID_COINS = "loose_coins";
    public static final String ID_CATATONIC_MOUSE = "catatonia_mouse";
    public static final String ID_STEEL_MACHETE = "warehouse_machete";
    public static final String ID_EXPLORER_HELMET = "explorer_helmet";

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
        return build(Material.IRON_PICKAXE, 1, ID_CROWBAR,
                Component.text("Саморобний лом", NamedTextColor.WHITE),
                List.of(
                        Component.text("Зриває плінтуси, решітки та", NamedTextColor.GRAY),
                        Component.text("руйнує стіни й бетон як кайло.", NamedTextColor.AQUA)
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

    public ItemStack almondWater(int amount) {
        ItemStack potion = new ItemStack(Material.POTION, amount);
        if (potion.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(PotionType.WATER);
            meta.setColor(Color.fromRGB(0xE6D3A3));
            meta.displayName(Component.text("Мигдальна вода", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Солодкувата на смак вода зі слабким ароматом мигдалю.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Повністю відновлює голод, насиченість і знімає втому.", NamedTextColor.GREEN)
                            .decoration(TextDecoration.ITALIC, false)
            ));
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, ID_ALMOND_WATER);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            potion.setItemMeta(meta);
        }
        return potion;
    }

    public ItemStack brokenCompass() {
        ItemStack compass = new ItemStack(Material.COMPASS, 1);
        if (compass.getItemMeta() instanceof CompassMeta meta) {
            meta.setLodestoneTracked(false);
            meta.displayName(Component.text("Аномальний компас", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Стрілка хаотично обертається...", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Магнітного поля на Рівні 0 немає.", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)
            ));
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, ID_BROKEN_COMPASS);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            compass.setItemMeta(meta);
        }
        return compass;
    }

    public ItemStack createMegManualBook() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK, 1);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Протокол M.E.G. // Рівень 0");
        meta.setAuthor("Дослідник M.E.G.");
        meta.addPages(
                Component.text("РІВЕНЬ 0: «ЛОБІ»\nКлас виживання: 1\n\nЯкщо ви це читаєте — ви знайшли Манільську кімнату.\n\nНавколо нескінченні жовті коридори. Не шукайте інші обличчя чи координати — простір нелінійний."),
                Component.text("ПРАВИЛА ВИЖИВАННЯ:\n\n1. НІКОЛИ не пийте рідину з вологих килимів.\n2. Остерігайтеся Червоних кімнат і глибоких темних ям (особливо коли втомлені чи спраглі!).\n3. Рідкісні темні западини («м'який килим») провалюють до межі Рівня 1."),
                Component.text("ІМПРОВІЗАЦІЯ ТА БАЗИ:\n\nРежим Виживання: ламайте стіни, зривайте ковролін (під ним бетон), будуйте укриття й сховища!\n\nКоманда /br craft відкриває всі рецепти. Зберіть 4 рейки для верстака 3x3!"),
                Component.text("ОСНОВНІ СХЕМИ:\n\n- Верстак: 4 рейки (2x2)\n- Бинт: 2 волокна + шпалера\n- Мітка: 2 шпалери + волокно\n- Лом: 2 пластини + рейка\n- Ліхтар: уламок + дріт + пластина")
        );
        book.setItemMeta(meta);
        return book;
    }

    public ItemStack createLevel2ManualBook() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK, 1);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Протокол M.E.G. // Рівень 2");
        meta.setAuthor("Розвідник M.E.G.");
        meta.addPages(
                Component.text("РІВЕНЬ 2: «ПОКИНУТІ ПІДСОБНІ ПРИМІЩЕННЯ»\n(«Мрії про труби»)\nКлас виживання: 2\n\nДовгі тісні бетонно-цегляні тунелі, захаращені промисловими трубами й затихлими агрегатами."),
                Component.text("НЕБЕЗПЕКА СПЕКИ (>43°C):\n\nУ котельних і перегрітих секціях із труб виривається гаряча пара, яка швидко виснажує та зневоднює.\nЗавжди тримайте при собі Мигдальну воду!"),
                Component.text("СУТНОСТІ:\n\nОкрім Усміхнених, Гончаків і Шкірокрадів, тут мешкають Смертомолі (Deathmoths).\nТісні тунелі не дають легко втекти — не затримуйтесь надовго в темряві."),
                Component.text("ВИХОДИ:\n\nГлибше в комунікаціях трапляються зачинені броньовані двері на Рівень 3 («Електрична станція»).")
        );
        book.setItemMeta(meta);
        return book;
    }

    public ItemStack cannedRation(int amount) {
        return build(Material.COOKED_BEEF, amount, ID_CANNED_RATION,
                Component.text("Консерви M.E.G.", NamedTextColor.GOLD),
                List.of(
                        Component.text("Армійська тушкованка з ящика Рівня 1.", NamedTextColor.GRAY),
                        Component.text("Надійно втамовує голод на вилазках.", NamedTextColor.GREEN)
                ), false);
    }

    public ItemStack batteryCell(int amount) {
        return build(Material.AMETHYST_SHARD, amount, ID_BATTERY,
                Component.text("Лужна батарейка", NamedTextColor.AQUA),
                List.of(
                        Component.text("Живлення для ліхтарів та обмін у B.N.T.G.", NamedTextColor.GRAY),
                        Component.text("ПКМ — короткий імпульс ясності.", NamedTextColor.DARK_GRAY)
                ), false);
    }

    public ItemStack rustyAutopart(int amount) {
        return build(Material.RAW_IRON, amount, ID_RUSTY_AUTOPART,
                Component.text("Іржаві автозапчастини", NamedTextColor.GRAY),
                List.of(
                        Component.text("Поршень чи шестерня невідомого авто з ящика.", NamedTextColor.GRAY),
                        Component.text("Приймають торговці B.N.T.G. за припаси.", NamedTextColor.DARK_GRAY)
                ), false);
    }

    public ItemStack usedSyringe(int amount) {
        return build(Material.BLAZE_ROD, amount, ID_USED_SYRINGE,
                Component.text("Використаний шприц", NamedTextColor.RED),
                List.of(
                        Component.text("Безглузда та небезпечна знахідка з ящика Рівня 1.", NamedTextColor.GRAY),
                        Component.text("Обережно — голка забруднена!", NamedTextColor.DARK_RED)
                ), false);
    }

    public ItemStack crayonBox(int amount) {
        return build(Material.YELLOW_DYE, amount, ID_CRAYON_BOX,
                Component.text("Коробка кольорових олівців", NamedTextColor.YELLOW),
                List.of(
                        Component.text("Наполовину сточені воскові олівці з ящика.", NamedTextColor.GRAY),
                        Component.text("Незрозуміло, хто й навіщо їх поклав.", NamedTextColor.DARK_GRAY)
                ), false);
    }

    public ItemStack burntPaper(int amount) {
        return build(Material.PAPER, amount, ID_BURNT_PAPER,
                Component.text("Частково спалений папір", NamedTextColor.GRAY),
                List.of(
                        Component.text("Обгорілі уривки нерозбірливого журналу.", NamedTextColor.GRAY)
                ), false);
    }

    public ItemStack looseCoins(int amount) {
        return build(Material.RAW_GOLD, amount, ID_COINS,
                Component.text("Жменя дрібних монет", NamedTextColor.GOLD),
                List.of(
                        Component.text("Старі потерті монети, що з'являються у ящиках.", NamedTextColor.GRAY),
                        Component.text("Головна розмінна монета B.N.T.G. на Рівні 1.", NamedTextColor.YELLOW)
                ), false);
    }

    public ItemStack catatonicMouse(int amount) {
        return build(Material.ROTTEN_FLESH, amount, ID_CATATONIC_MOUSE,
                Component.text("Кататонічна миша", NamedTextColor.DARK_GRAY),
                List.of(
                        Component.text("Ледве дихає... типовий дивний вміст ящика.", NamedTextColor.GRAY),
                        Component.text("Їсти її — жахлива ідея.", NamedTextColor.RED)
                ), false);
    }

    public ItemStack warehouseMachete() {
        return build(Material.IRON_SWORD, 1, ID_STEEL_MACHETE,
                Component.text("Складський тесак", NamedTextColor.WHITE),
                List.of(
                        Component.text("Важке лезо з аванпостів M.E.G.", NamedTextColor.GRAY),
                        Component.text("Ефективний проти Гончаків та Шкірокрадів.", NamedTextColor.AQUA)
                ), true);
    }

    public ItemStack explorerHelmet() {
        return build(Material.CHAINMAIL_HELMET, 1, ID_EXPLORER_HELMET,
                Component.text("Каска дослідника B.N.T.G.", NamedTextColor.YELLOW),
                List.of(
                        Component.text("Посилена будівельна каска для темних складів.", NamedTextColor.GRAY)
                ), true);
    }

    public ItemStack createLevel1ManualBook() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK, 1);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Протокол M.E.G. // Рівень 1");
        meta.setAuthor("База Альфа [M.E.G.]");
        meta.addPages(
                Component.text("РІВЕНЬ 1: «НАСЕЛЕНА ЗОНА»\nКлас виживання: 2\n\nВи прорвалися крізь блимаючу стіну з Рівня 0 у гігантські бетонні склади.\nТут є низький туман, калюжі конденсату й сліди людства."),
                Component.text("ЯЩИКИ ПОСТАЧАННЯ:\n\nДерев'яні ящики матеріалізуються та зникають самі по собі.\nУсередині можна знайти Мигдальну воду, консерви, батарейки, зброю... або ж абсурдні речі на кшталт олівців, шприців чи автозапчастин."),
                Component.text("МЕРЕХТІННЯ (BLACKOUT):\n\nПеріодично світло на всьому рівні блимає й гасне!\nУ темряві пробуджуються Усміхнені, Гончаки, Безликі та Шкірокради.\nТримайте ліхтар напоготові або сховайтеся на базі."),
                Component.text("АВАНПОСТИ ЛЮДСТВА:\n\n▸ База Альфа (M.E.G.)\n▸ Дім Торговця (B.N.T.G. — обмін монет, запчастин і батарейок)\n▸ Їдальня Тома\n\nБережіть припаси — ви все ще у режимі виживання!")
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
