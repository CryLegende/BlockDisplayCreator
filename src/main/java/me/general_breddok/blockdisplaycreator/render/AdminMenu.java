package me.general_breddok.blockdisplaycreator.render;

import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.custom.ConfiguredInteraction;
import me.general_breddok.blockdisplaycreator.custom.block.AbstractCustomBlock;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlock;
import me.general_breddok.blockdisplaycreator.file.config.loader.CustomBlockYamlFile;
import me.general_breddok.blockdisplaycreator.util.ChatUtil;
import me.general_breddok.blockdisplaycreator.util.ItemUtil;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The admin panel of the gizmo: what is set on one placed block alone, on top of its file (the other blocks of the
 * same file keep what their file gives them). Opened by the command block at the left end of the gizmo's strip, or by
 * {@code /bdc settings} aiming at a block. Each click applies at once; what has to be written (a command, a value) is
 * typed with {@code /bdc settings ...}, which the panel suggests in the chat.
 * <p>
 * Its texts and icons are in messages.yml ({@code gizmo-admin}), its limits and sounds in config.yml ({@code gizmo}).
 */
public final class AdminMenu implements Listener {

    private static final int SIZE = 45;
    private static final int INFO = 4;
    private static final int SCALE = 10;
    private static final int VIEW = 11;
    private static final int LOOPS = 12;
    private static final int BREAKABLE = 14;
    private static final int LOCKED = 15;
    private static final int NOTE = 16;
    private static final int COMMANDS = 19;
    private static final int SOURCE = 20;
    private static final int MODEL_COMMANDS = 21;
    private static final int CLICK_ANIMATION = 22;
    private static final int COOLDOWN = 23;
    private static final int PERMISSION = 24;
    private static final int CLICK_BOX = 25;
    private static final int COPY = 28;
    private static final int PASTE = 29;
    private static final int RESET = 33;
    private static final int BREAK = 34;
    private static final int BACK = 36;
    /**
     * How many blocks a click on the view distance adds or takes.
     */
    private static final int VIEW_STEP = 8;
    /**
     * How much of the file's size a click on the size adds or takes.
     */
    private static final double SCALE_STEP = 0.1;
    private static final String COMMAND = "/bdc settings ";

    /**
     * A block, by where it stands: the panel and the commands find it again each time, as it may have been read again.
     */
    private record BlockRef(UUID world, int x, int y, int z) {
    }

    private static final class Holder implements InventoryHolder {
        final BlockRef block;
        Inventory inventory;

        Holder(BlockRef block) {
            this.block = block;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return this.inventory;
        }
    }

    private final BlockDisplayCreator plugin;
    private final DisplayRenderer renderer;
    private final GizmoManager gizmos;
    /**
     * The block each administrator last opened the panel of: the one {@code /bdc settings ...} acts on.
     */
    private final Map<UUID, BlockRef> editing = new HashMap<>();
    private final Map<UUID, BlockSettings> clipboard = new HashMap<>();
    /**
     * The panel each administrator has open: a click in any other inventory is left alone without asking it for its
     * holder (which copies the state of a container block on Paper).
     */
    private final Map<UUID, Holder> open = new HashMap<>();

    AdminMenu(BlockDisplayCreator plugin, DisplayRenderer renderer, GizmoManager gizmos) {
        this.plugin = plugin;
        this.renderer = renderer;
        this.gizmos = gizmos;
    }

    // ---------- Opening and drawing ----------

    void open(Player player, PlacedDisplay placed) {
        BlockRef ref = new BlockRef(placed.world.getUID(), placed.x, placed.y, placed.z);
        this.editing.put(player.getUniqueId(), ref);
        Holder holder = new Holder(ref);
        Inventory inventory = this.plugin.getServer().createInventory(holder, SIZE, Texts.get("gizmo-admin.title", "%model%", placed.modelName));
        holder.inventory = inventory;
        draw(inventory, player, placed);
        player.openInventory(inventory);
        this.open.put(player.getUniqueId(), holder);
        sound(player, "open", Sound.UI_TOAST_IN);
    }

    private void draw(Inventory inventory, Player player, PlacedDisplay placed) {
        BlockSettings settings = placed.settings == null ? new BlockSettings() : placed.settings;
        ItemStack filler = item("filler", false);
        for (int slot = 0; slot < SIZE; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            inventory.setItem(slot, row == 0 || row == 4 || column == 0 || column == 8 ? filler : null);
        }

        String where = placed.x + " " + placed.y + " " + placed.z + " " + placed.world.getName();
        ItemStack info = item("info", false, "%model%", placed.modelName, "%where%", where,
                "%note%", settings.note == null ? Texts.get("gizmo-admin.none") : settings.note,
                "%count%", settings.isDefault() ? 0 : count(settings));
        AbstractCustomBlock definition = definition(placed);
        if (definition != null && definition.getItem() != null) {
            info.setType(definition.getItem().getType());
        }
        inventory.setItem(INFO, info);

        double min = this.gizmos.setting("gizmo.admin-size-min", 0.1);
        double max = this.gizmos.setting("gizmo.admin-size-max", 10);
        inventory.setItem(SCALE, item("size", Math.abs(placed.scale() - 1) > 1.0E-6, "%value%", percent(placed.scale()),
                "%min%", percent(min), "%max%", percent(max)));

        String modelView = placed.model.viewDistance > 0 ? placed.model.viewDistance + "" : Texts.get("gizmo-admin.server-distance", "%blocks%", defaultView(placed.world));
        inventory.setItem(VIEW, item("view-distance", settings.viewDistance >= 0,
                "%value%", settings.viewDistance >= 0 ? settings.viewDistance + "" : modelView, "%model%", modelView));

        inventory.setItem(LOOPS, placed.model.loops
                ? toggle("loops", settings.loops, !settings.loops)
                : item("loops-none", false));
        inventory.setItem(BREAKABLE, toggle("breakable", settings.breakable, !settings.breakable));
        inventory.setItem(LOCKED, toggle("locked", settings.locked, settings.locked));
        inventory.setItem(NOTE, item("note", settings.note != null, "%value%", settings.note == null ? Texts.get("gizmo-admin.none") : settings.note));

        List<String> listed = new ArrayList<>();
        for (int i = 0; i < settings.commands.size(); i++) {
            listed.add(Texts.get("gizmo-admin.command-line", "%n%", i + 1, "%command%", settings.commands.get(i)));
        }
        inventory.setItem(COMMANDS, item("commands", !settings.commands.isEmpty(),
                "%count%", settings.commands.size(), "%list%", listed.isEmpty() ? Texts.get("gizmo-admin.no-command") : String.join("\n", listed)));
        inventory.setItem(SOURCE, toggle("source", settings.asPlayer, settings.asPlayer));
        boolean modelCommands = hasModelCommands(definition);
        inventory.setItem(MODEL_COMMANDS, modelCommands
                ? toggle("model-commands", settings.modelCommands, !settings.modelCommands)
                : item("model-commands-none", false));
        inventory.setItem(CLICK_ANIMATION, hasClickAnimation(placed)
                ? toggle("click-animation", settings.clickAnimation, !settings.clickAnimation)
                : item("click-animation-none", false));
        inventory.setItem(COOLDOWN, item("cooldown", settings.cooldown > 0, "%value%", settings.cooldown > 0 ? duration(settings.cooldown) : Texts.get("gizmo-admin.none")));
        inventory.setItem(PERMISSION, item("permission", settings.permission != null, "%value%", settings.permission == null ? Texts.get("gizmo-admin.none") : settings.permission));
        inventory.setItem(CLICK_BOX, placed.model.clickable
                ? item("click-box-model", false)
                : toggle("click-box", settings.clickBox != null, settings.clickBox != null));

        inventory.setItem(COPY, item("copy", false));
        BlockSettings copied = this.clipboard.get(player.getUniqueId());
        inventory.setItem(PASTE, copied == null ? item("paste-empty", false) : item("paste", true, "%count%", count(copied)));
        inventory.setItem(RESET, item("reset", false));
        inventory.setItem(BREAK, item("break", false));
        inventory.setItem(BACK, item("back", false));
    }

    private static int count(BlockSettings settings) {
        int count = settings.commands.size();
        count += settings.asPlayer ? 1 : 0;
        count += settings.modelCommands ? 0 : 1;
        count += settings.clickAnimation ? 0 : 1;
        count += settings.cooldown > 0 ? 1 : 0;
        count += settings.permission != null ? 1 : 0;
        count += settings.clickBox != null ? 1 : 0;
        count += settings.breakable ? 0 : 1;
        count += settings.locked ? 1 : 0;
        count += settings.loops ? 0 : 1;
        count += settings.viewDistance >= 0 ? 1 : 0;
        count += settings.note != null ? 1 : 0;
        return count;
    }

    /**
     * An option that is on or off: its state's text in its name and lore.
     *
     * @param on       which of its two states it is in
     * @param modified whether that state differs from the file (the icon shines)
     */
    private ItemStack toggle(String key, boolean on, boolean modified) {
        return item(key, modified, "%state%", Texts.get("gizmo-admin.items." + key + (on ? ".state-on" : ".state-off")));
    }

    /**
     * An icon of the panel from messages.yml: {@code gizmo-admin.items.<key>} with its material, name and lore.
     */
    private ItemStack item(String key, boolean shine, Object... pairs) {
        String path = "gizmo-admin.items." + key;
        Material material = Material.matchMaterial(Texts.get(path + ".material"));
        ItemStack stack = new ItemStack(material == null || material.isAir() ? Material.BARRIER : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Texts.has(path + ".name") ? Texts.get(path + ".name", pairs) : " ");
            meta.setLore(Texts.lines(path + ".lore", pairs));
            meta.addItemFlags(ItemFlag.values());
            if (shine) {
                meta.addEnchant(Enchantment.LURE, 1, true);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void redraw(Player player, PlacedDisplay placed) {
        Holder holder = this.open.get(player.getUniqueId());
        if (holder != null && player.getOpenInventory().getTopInventory() == holder.inventory) {
            draw(holder.inventory, player, placed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        Holder holder = this.open.get(event.getPlayer().getUniqueId());
        if (holder != null && event.getInventory() == holder.inventory) {
            this.open.remove(event.getPlayer().getUniqueId());
        }
    }

    // ---------- Clicks in the panel ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        Holder holder = this.open.isEmpty() ? null : this.open.get(event.getWhoClicked().getUniqueId());
        if (holder != null && event.getView().getTopInventory() == holder.inventory) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Holder holder = this.open.isEmpty() ? null : this.open.get(event.getWhoClicked().getUniqueId());
        if (holder == null || event.getView().getTopInventory() != holder.inventory) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != holder.inventory) {
            return;
        }
        if (!player.hasPermission(GizmoManager.ADMIN_PERMISSION)) {
            player.closeInventory();
            return;
        }
        PlacedDisplay placed = find(holder.block);
        if (placed == null || placed.model == null) {
            player.closeInventory();
            this.gizmos.say(player, "gizmo-admin.gone");
            return;
        }
        ClickType click = event.getClick();
        boolean left = click == ClickType.LEFT;
        boolean right = click == ClickType.RIGHT;
        boolean shiftLeft = click == ClickType.SHIFT_LEFT;
        boolean shiftRight = click == ClickType.SHIFT_RIGHT;
        BlockSettings settings = placed.settings == null ? new BlockSettings() : placed.settings.copy();
        boolean changed = true;
        switch (event.getRawSlot()) {
            case SCALE -> {
                double min = this.gizmos.setting("gizmo.admin-size-min", 0.1);
                double max = this.gizmos.setting("gizmo.admin-size-max", 10);
                double size = placed.scale();
                if (shiftLeft) {
                    prompt(player, "size");
                    return;
                }
                if (left || right) {
                    size = Math.round((size + (left ? SCALE_STEP : -SCALE_STEP)) * 100) / 100.0;
                } else if (shiftRight) {
                    size = 1;
                } else {
                    return;
                }
                size = Math.max(min, Math.min(max, size));
                if (Math.abs(size - placed.scale()) < 1.0E-9) {
                    refuse(player);
                    return;
                }
                resize(placed, size);
                changed = false;
            }
            case VIEW -> {
                if (shiftLeft) {
                    prompt(player, "view-distance");
                    return;
                }
                if (shiftRight) {
                    settings.viewDistance = -1;
                } else if (left || right) {
                    int current = settings.viewDistance >= 0 ? settings.viewDistance
                            : placed.model.viewDistance > 0 ? placed.model.viewDistance : defaultView(placed.world);
                    int next = (current / VIEW_STEP + (left ? 1 : -1)) * VIEW_STEP;
                    settings.viewDistance = Math.max(VIEW_STEP, Math.min(maxView(placed.world), next));
                } else {
                    return;
                }
            }
            case LOOPS -> {
                if (!placed.model.loops) {
                    return;
                }
                settings.loops = !settings.loops;
            }
            case BREAKABLE -> settings.breakable = !settings.breakable;
            case LOCKED -> settings.locked = !settings.locked;
            case NOTE -> {
                if (right) {
                    settings.note = null;
                } else {
                    prompt(player, "note");
                    return;
                }
            }
            case COMMANDS -> {
                if (left || shiftLeft) {
                    prompt(player, "command");
                    return;
                }
                if (settings.commands.isEmpty()) {
                    refuse(player);
                    return;
                }
                if (shiftRight) {
                    settings.commands.clear();
                } else if (right) {
                    settings.commands.remove(settings.commands.size() - 1);
                } else {
                    return;
                }
            }
            case SOURCE -> settings.asPlayer = !settings.asPlayer;
            case MODEL_COMMANDS -> {
                if (!hasModelCommands(definition(placed))) {
                    return;
                }
                settings.modelCommands = !settings.modelCommands;
            }
            case CLICK_ANIMATION -> {
                if (!hasClickAnimation(placed)) {
                    return;
                }
                settings.clickAnimation = !settings.clickAnimation;
            }
            case COOLDOWN -> {
                if (shiftLeft) {
                    prompt(player, "cooldown");
                    return;
                }
                if (shiftRight) {
                    settings.cooldown = 0;
                } else if (left || right) {
                    settings.cooldown = nextCooldown(settings.cooldown, left);
                } else {
                    return;
                }
            }
            case PERMISSION -> {
                if (right) {
                    settings.permission = null;
                } else {
                    prompt(player, "permission");
                    return;
                }
            }
            case CLICK_BOX -> {
                if (placed.model.clickable) {
                    return;
                }
                if (settings.clickBox != null) {
                    settings.clickBox = null;
                } else {
                    settings.clickBox = clickBoxOf(placed);
                    if (settings.clickBox == null) {
                        refuse(player);
                        return;
                    }
                }
            }
            case COPY -> {
                this.clipboard.put(player.getUniqueId(), settings.copy());
                this.gizmos.say(player, "gizmo-admin.copied", "%model%", placed.modelName);
                changed = false;
            }
            case PASTE -> {
                BlockSettings copied = this.clipboard.get(player.getUniqueId());
                if (copied == null) {
                    refuse(player);
                    return;
                }
                settings = copied.copy();
                // the added click box is the one this model needs, or none when its file gives it one
                settings.clickBox = settings.clickBox == null || placed.model.clickable ? null : clickBoxOf(placed);
                if (!placed.model.loops) {
                    settings.loops = true;
                }
            }
            case RESET -> {
                if (!shiftLeft && !shiftRight) {
                    this.gizmos.say(player, "gizmo-admin.confirm-reset");
                    refuse(player);
                    return;
                }
                settings = new BlockSettings();
            }
            case BREAK -> {
                if (!shiftLeft && !shiftRight) {
                    this.gizmos.say(player, "gizmo-admin.confirm-break");
                    refuse(player);
                    return;
                }
                player.closeInventory();
                breakBlock(player, placed);
                return;
            }
            case BACK -> {
                player.closeInventory();
                return;
            }
            default -> {
                return;
            }
        }
        if (changed) {
            apply(placed, settings);
        }
        sound(player, "click", Sound.UI_BUTTON_CLICK);
        redraw(player, placed);
    }

    private void apply(PlacedDisplay placed, BlockSettings settings) {
        this.renderer.applySettings(placed, settings);
        this.gizmos.follow(placed);
    }

    private void resize(PlacedDisplay placed, double size) {
        this.renderer.resize(placed, size);
        this.gizmos.follow(placed);
    }

    private void breakBlock(Player player, PlacedDisplay placed) {
        Location at = new Location(placed.world, placed.x, placed.y, placed.z);
        CustomBlock block = this.plugin.getCustomBlockService().getCustomBlock(at);
        if (block == null) {
            this.gizmos.say(player, "gizmo-admin.gone");
            return;
        }
        ItemStack item = block.getItem();
        try {
            if (!this.plugin.getCustomBlockService().breakBlock(block, player)) {
                refuse(player);
                return;
            }
        } catch (IllegalArgumentException e) {
            this.gizmos.say(player, "gizmo-admin.gone");
            return;
        }
        if (item != null) {
            item.setAmount(1);
            ItemUtil.distributeItem(player, item);
        }
        this.gizmos.say(player, "gizmo-admin.broken", "%model%", placed.modelName);
    }

    /**
     * Closes the panel and puts in the player's chat the command that writes a value, to finish and send.
     */
    private void prompt(Player player, String field) {
        player.closeInventory();
        String suggested = COMMAND + switch (field) {
            case "command" -> "command add ";
            default -> field + " ";
        };
        BaseComponent[] message = TextComponent.fromLegacyText(Texts.get("gizmo-admin.prompt." + field));
        for (BaseComponent part : message) {
            part.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggested));
            part.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(Texts.get("gizmo-admin.prompt.hover", "%command%", suggested))));
        }
        player.spigot().sendMessage(message);
        sound(player, "click", Sound.UI_BUTTON_CLICK);
    }

    private void refuse(Player player) {
        sound(player, "refused", Sound.BLOCK_NOTE_BLOCK_BASS);
    }

    private void sound(Player player, String which, Sound fallback) {
        Object name = this.plugin.getYamlConfiguration().get("gizmo.admin-sounds." + which);
        Sound sound = fallback;
        if (name != null && !String.valueOf(name).isBlank()) {
            try {
                sound = Sound.valueOf(String.valueOf(name).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // an unknown sound plays the default one
            }
        }
        player.playSound(player.getLocation(), sound, 0.6f, 1f);
    }

    // ---------- What the panel needs to know of a block ----------

    @Nullable
    private PlacedDisplay find(BlockRef ref) {
        World world = this.plugin.getServer().getWorld(ref.world());
        return world == null ? null : this.renderer.find(world, ref.x(), ref.y(), ref.z());
    }

    @Nullable
    private AbstractCustomBlock definition(PlacedDisplay placed) {
        return this.plugin.getCustomBlockService().getStorage().getAbstractCustomBlock(placed.modelName);
    }

    private static boolean hasModelCommands(@Nullable AbstractCustomBlock definition) {
        if (definition == null) {
            return false;
        }
        for (ConfiguredInteraction interaction : definition.getConfiguredInteractions()) {
            if (interaction.getInteractionHandler() != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasClickAnimation(PlacedDisplay placed) {
        for (DisplayAnimation animation : placed.model.animations) {
            if (animation.trigger == DisplayAnimation.Trigger.CLICK) {
                return true;
            }
        }
        return false;
    }

    /**
     * The click box a block without one gets: a box with a square base on the block's axis around what it draws
     * (width, height, height of its floor), or null for a model that draws nothing.
     */
    @Nullable
    private static double[] clickBoxOf(PlacedDisplay placed) {
        float[] box = placed.model.bounds();
        double half = Math.max(Math.max(Math.abs(box[0]), Math.abs(box[3])), Math.max(Math.abs(box[2]), Math.abs(box[5])));
        double floor = Math.max(0, box[1]);
        double height = box[4] - floor;
        if (half <= 0 || height <= 0) {
            return null;
        }
        return new double[]{round(2 * half), round(height), round(floor)};
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private int defaultView(World world) {
        return this.renderer.defaultViewBlocks(world);
    }

    private static int maxView(World world) {
        return world.getViewDistance() * 16;
    }

    private long nextCooldown(long current, boolean longer) {
        List<Long> steps = new ArrayList<>();
        Object listed = this.plugin.getYamlConfiguration().get("gizmo.admin-cooldowns");
        if (listed instanceof List<?> list) {
            for (Object step : list) {
                long millis = step instanceof Number number ? number.longValue() : CustomBlockYamlFile.parseDuration(String.valueOf(step));
                if (millis >= 0) {
                    steps.add(millis);
                }
            }
        }
        if (steps.isEmpty()) {
            steps = List.of(0L, 1_000L, 5_000L, 30_000L, 60_000L, 300_000L, 3_600_000L, 86_400_000L);
        }
        steps = new ArrayList<>(steps);
        steps.sort(Long::compare);
        if (longer) {
            for (long step : steps) {
                if (step > current) {
                    return step;
                }
            }
            return steps.get(steps.size() - 1);
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i) < current) {
                return steps.get(i);
            }
        }
        return 0;
    }

    private static String percent(double size) {
        double value = size * 100;
        return (Math.abs(value - Math.rint(value)) < 1.0E-6 ? String.valueOf((long) Math.rint(value)) : String.format(Locale.ROOT, "%.1f", value)) + " %";
    }

    /**
     * {@code 1 h 30 min}, {@code 45 s}, {@code 500 ms}.
     */
    static String duration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        long rest = seconds % 60;
        List<String> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(days + " j");
        }
        if (hours > 0) {
            parts.add(hours + " h");
        }
        if (minutes > 0) {
            parts.add(minutes + " min");
        }
        if (rest > 0) {
            parts.add(rest + " s");
        }
        return String.join(" ", parts);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.editing.remove(event.getPlayer().getUniqueId());
        this.clipboard.remove(event.getPlayer().getUniqueId());
        this.open.remove(event.getPlayer().getUniqueId());
        BlockSettings.forget(event.getPlayer().getUniqueId());
    }

    // ---------- /bdc settings ----------

    private static final List<String> FIELDS = List.of("size", "view-distance", "command", "cooldown", "permission", "note", "list");

    /**
     * {@code /bdc settings}: opens the panel of the block the player's gizmo is on, or that he aims at. With a field,
     * sets it on the block whose panel he opened last, then opens the panel again.
     */
    public void command(CommandSender sender, String[] args) {
        if (!sender.hasPermission(GizmoManager.ADMIN_PERMISSION)) {
            ChatUtil.sendMessage(sender, Texts.get("gizmo-admin.no-permission"));
            return;
        }
        if (!(sender instanceof Player player)) {
            ChatUtil.sendMessage(sender, Texts.get("gizmo-admin.players-only"));
            return;
        }
        if (args.length < 2) {
            PlacedDisplay placed = this.gizmos.targetOf(player);
            if (placed == null || placed.model == null) {
                this.gizmos.say(player, "gizmo-admin.aim");
                return;
            }
            open(player, placed);
            return;
        }
        String field = args[1].toLowerCase(Locale.ROOT);
        if (field.equals("list")) {
            list(player, args.length > 2 ? args[2] : null);
            return;
        }
        if (!FIELDS.contains(field)) {
            this.gizmos.say(player, "gizmo-admin.usage");
            return;
        }
        BlockRef ref = this.editing.get(player.getUniqueId());
        PlacedDisplay placed = ref == null ? null : find(ref);
        if (placed == null || placed.model == null) {
            this.gizmos.say(player, ref == null ? "gizmo-admin.open-first" : "gizmo-admin.gone");
            return;
        }
        String value = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim() : "";
        BlockSettings settings = placed.settings == null ? new BlockSettings() : placed.settings.copy();
        boolean none = value.equalsIgnoreCase("none") || value.equalsIgnoreCase("default");
        switch (field) {
            case "size" -> {
                Double percent = number(value.replace("%", "").trim());
                double min = this.gizmos.setting("gizmo.admin-size-min", 0.1);
                double max = this.gizmos.setting("gizmo.admin-size-max", 10);
                double size = none ? 1 : percent == null ? -1 : percent / 100;
                if (size < min - 1.0E-9 || size > max + 1.0E-9) {
                    this.gizmos.say(player, "gizmo-admin.bad-size", "%min%", percent(min), "%max%", percent(max));
                    return;
                }
                resize(placed, size);
                open(player, placed);
                return;
            }
            case "view-distance" -> {
                Double blocks = number(value);
                if (!none && (blocks == null || blocks < 1 || blocks > maxView(placed.world))) {
                    this.gizmos.say(player, "gizmo-admin.bad-view-distance", "%max%", maxView(placed.world));
                    return;
                }
                settings.viewDistance = none ? -1 : (int) Math.round(blocks);
            }
            case "command" -> {
                String action = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
                String rest = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).trim() : "";
                switch (action) {
                    case "add" -> {
                        String command = rest.startsWith("/") ? rest.substring(1) : rest;
                        if (command.isEmpty() || !BlockSettings.isCommand(command)) {
                            this.gizmos.say(player, "gizmo-admin.bad-command");
                            return;
                        }
                        settings.commands.add(command);
                    }
                    case "remove" -> {
                        Double index = number(rest);
                        if (index == null || index < 1 || index > settings.commands.size()) {
                            this.gizmos.say(player, "gizmo-admin.bad-command-number", "%count%", settings.commands.size());
                            return;
                        }
                        settings.commands.remove((int) Math.round(index) - 1);
                    }
                    case "clear" -> settings.commands.clear();
                    default -> {
                        this.gizmos.say(player, "gizmo-admin.usage");
                        return;
                    }
                }
            }
            case "cooldown" -> {
                long millis = none || value.equals("0") ? 0 : CustomBlockYamlFile.parseDuration(value);
                if (millis < 0) {
                    this.gizmos.say(player, "gizmo-admin.bad-cooldown");
                    return;
                }
                settings.cooldown = millis;
            }
            case "permission" -> {
                if (value.isEmpty() || value.contains(" ")) {
                    this.gizmos.say(player, "gizmo-admin.bad-permission");
                    return;
                }
                settings.permission = none ? null : value;
            }
            case "note" -> {
                if (value.isEmpty()) {
                    this.gizmos.say(player, "gizmo-admin.usage");
                    return;
                }
                settings.note = none ? null : value;
            }
            default -> {
                return;
            }
        }
        apply(placed, settings);
        this.gizmos.say(player, "gizmo-admin.saved", "%model%", placed.modelName);
        open(player, placed);
    }

    private void list(Player player, @Nullable String radius) {
        Double distance = radius == null ? Double.valueOf(64) : number(radius);
        if (distance == null || distance <= 0) {
            this.gizmos.say(player, "gizmo-admin.usage");
            return;
        }
        List<PlacedDisplay> found = this.renderer.withSettings(player.getWorld(), player.getLocation(), distance);
        if (found.isEmpty()) {
            this.gizmos.say(player, "gizmo-admin.list-empty", "%radius%", Math.round(distance));
            return;
        }
        this.gizmos.say(player, "gizmo-admin.list-title", "%count%", found.size(), "%radius%", Math.round(distance));
        for (PlacedDisplay placed : found) {
            BlockSettings settings = placed.settings;
            String where = placed.x + " " + placed.y + " " + placed.z;
            BaseComponent[] line = TextComponent.fromLegacyText(Texts.get("gizmo-admin.list-line", "%model%", placed.modelName, "%where%", where,
                    "%count%", count(settings), "%note%", settings.note == null ? "" : settings.note));
            for (BaseComponent part : line) {
                part.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/minecraft:tp @s " + placed.x + " " + (placed.y + 1) + " " + placed.z));
            }
            player.spigot().sendMessage(line);
        }
    }

    @Nullable
    private static Double number(String text) {
        try {
            return Double.parseDouble(text.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public List<String> tab(CommandSender sender, String[] args) {
        List<String> result = new ArrayList<>();
        if (!sender.hasPermission(GizmoManager.ADMIN_PERMISSION)) {
            return result;
        }
        String typed = args[args.length - 1];
        if (args.length == 2) {
            StringUtil.copyPartialMatches(typed, FIELDS, result);
        } else if (args.length == 3) {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "size" -> StringUtil.copyPartialMatches(typed, List.of("50", "100", "150", "200", "300", "500", "default"), result);
                case "view-distance" -> StringUtil.copyPartialMatches(typed, List.of("16", "32", "48", "64", "96", "128", "default"), result);
                case "command" -> StringUtil.copyPartialMatches(typed, List.of("add", "remove", "clear"), result);
                case "cooldown" -> StringUtil.copyPartialMatches(typed, List.of("1s", "5s", "30s", "1m", "5m", "1h", "24h", "none"), result);
                case "permission", "note" -> StringUtil.copyPartialMatches(typed, List.of("none"), result);
                case "list" -> StringUtil.copyPartialMatches(typed, List.of("16", "32", "64", "128"), result);
                default -> {
                }
            }
        }
        return result;
    }
}
