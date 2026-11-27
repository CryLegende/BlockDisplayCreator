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
import me.general_breddok.blockdisplaycreator.custom.block.BDCCustomBlockService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
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
import org.bukkit.scheduler.BukkitTask;
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
    private static final int HITBOX = 25;
    private static final int COPY = 28;
    private static final int PASTE = 29;
    private static final int RESET = 33;
    private static final int BREAK = 34;
    private static final int BACK = 36;
    // the hitbox page
    private static final int H_FILE = 10;
    private static final int H_CUSTOM = 11;
    private static final int H_NONE = 12;
    private static final int H_COLLISION = 14;
    private static final int H_SHOW = 16;
    private static final int H_WIDTH = 19;
    private static final int H_HEIGHT = 20;
    private static final int H_FLOOR = 21;
    private static final int H_HINT = 22;
    private static final int H_FIT_MODEL = 23;
    private static final int H_FIT_FILE = 24;
    private static final int H_TYPE = 25;
    /**
     * How often the outline of a hitbox is drawn again (ticks), and how many dots it is drawn with at most.
     */
    private static final int OUTLINE_PERIOD = 8;
    private static final int OUTLINE_DOTS = 1200;
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
        /**
         * Whether it is the page of the block's click boxes and collisions.
         */
        final boolean hitbox;
        Inventory inventory;

        Holder(BlockRef block, boolean hitbox) {
            this.block = block;
            this.hitbox = hitbox;
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
     * The model the copied settings come from: a click box drawn for it is drawn again for another model.
     */
    private final Map<UUID, String> clipboardModel = new HashMap<>();
    /**
     * The outline of a hitbox drawn for each administrator.
     */
    private final Map<UUID, BukkitTask> outlines = new HashMap<>();
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
        open(player, placed, false);
    }

    private void open(Player player, PlacedDisplay placed, boolean hitbox) {
        BlockRef ref = new BlockRef(placed.world.getUID(), placed.x, placed.y, placed.z);
        this.editing.put(player.getUniqueId(), ref);
        Holder holder = new Holder(ref, hitbox);
        Inventory inventory = this.plugin.getServer().createInventory(holder, SIZE,
                Texts.get(hitbox ? "gizmo-admin.hitbox-title" : "gizmo-admin.title", "%model%", placed.modelName));
        holder.inventory = inventory;
        draw(inventory, player, placed, hitbox);
        player.openInventory(inventory);
        this.open.put(player.getUniqueId(), holder);
        sound(player, "open", Sound.UI_TOAST_IN);
        if (hitbox) {
            outline(player, ref);
        }
    }

    private void draw(Inventory inventory, Player player, PlacedDisplay placed, boolean hitbox) {
        ItemStack filler = item("filler", false);
        for (int slot = 0; slot < SIZE; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            inventory.setItem(slot, row == 0 || row == 4 || column == 0 || column == 8 ? filler : null);
        }
        if (hitbox) {
            drawHitbox(inventory, placed);
        } else {
            drawMain(inventory, player, placed);
        }
    }

    private void drawMain(Inventory inventory, Player player, PlacedDisplay placed) {
        BlockSettings settings = placed.settings == null ? new BlockSettings() : placed.settings;

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
        inventory.setItem(HITBOX, item("hitbox", settings.noClickBox || settings.clickBox != null || !settings.collisions,
                "%click%", clickState(placed, settings, definition), "%collision%", collisionState(settings, definition)));

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
        count += settings.clickBox != null || settings.noClickBox ? 1 : 0;
        count += settings.collisions ? 0 : 1;
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
            draw(holder.inventory, player, placed, holder.hitbox);
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
        if (holder.hitbox) {
            hitboxClick(player, placed, holder.block, event.getRawSlot(), click);
            return;
        }
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
            case HITBOX -> {
                open(player, placed, true);
                return;
            }
            case COPY -> {
                this.clipboard.put(player.getUniqueId(), settings.copy());
                this.clipboardModel.put(player.getUniqueId(), placed.modelName);
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
                // a click box drawn for another model is drawn again for this one
                if (settings.clickBox != null && !placed.modelName.equals(this.clipboardModel.get(player.getUniqueId()))) {
                    BlockSettings none = new BlockSettings();
                    settings.clickBox = startBox(placed, none, definition(placed));
                }
                if (!placed.model.loops) {
                    settings.loops = true;
                }
                AbstractCustomBlock pasted = definition(placed);
                if (pasted == null || BDCCustomBlockService.expectedCollisions(pasted) == 0) {
                    settings.collisions = true;
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
        if (changed && !apply(placed, settings)) {
            this.gizmos.say(player, "gizmo-admin.hitbox-later");
        }
        sound(player, "click", Sound.UI_BUTTON_CLICK);
        redraw(player, placed);
    }

    /**
     * @return false when the hitbox changed but is only summoned again at the block's next read
     */
    private boolean apply(PlacedDisplay placed, BlockSettings settings) {
        boolean now = this.renderer.applySettings(placed, settings);
        this.gizmos.follow(placed);
        return now;
    }

    // ---------- The hitbox page ----------

    private void drawHitbox(Inventory inventory, PlacedDisplay placed) {
        BlockSettings settings = placed.settings == null ? new BlockSettings() : placed.settings;
        AbstractCustomBlock definition = definition(placed);
        int fileBoxes = fileBoxes(definition);
        boolean custom = !settings.noClickBox && settings.clickBox != null;
        boolean file = !settings.noClickBox && settings.clickBox == null;

        inventory.setItem(INFO, item("hitbox-info", false, "%model%", placed.modelName,
                "%click%", clickState(placed, settings, definition), "%collision%", collisionState(settings, definition)));
        inventory.setItem(H_FILE, choice(fileBoxes > 0 ? "hitbox-file" : "hitbox-file-empty", file, "%count%", fileBoxes));
        inventory.setItem(H_CUSTOM, choice("hitbox-custom", custom));
        inventory.setItem(H_NONE, choice("hitbox-none", settings.noClickBox));
        inventory.setItem(H_COLLISION, definition != null && BDCCustomBlockService.expectedCollisions(definition) > 0
                ? toggle("collision", settings.collisions, !settings.collisions)
                : item("collision-none", false));
        inventory.setItem(H_SHOW, item("hitbox-show", false, "%seconds%", outlineSeconds()));
        if (custom) {
            double scale = placed.scale();
            String max = blocks(hitboxMax());
            inventory.setItem(H_WIDTH, item("hitbox-width", false, "%value%", blocks(settings.clickBox[0] * scale), "%max%", max));
            inventory.setItem(H_HEIGHT, item("hitbox-height", false, "%value%", blocks(settings.clickBox[1] * scale), "%max%", max));
            inventory.setItem(H_FLOOR, item("hitbox-floor", false, "%value%", blocks(settings.clickBox[2] * scale), "%max%", max));
            if (clickBoxOf(placed) != null) {
                inventory.setItem(H_FIT_MODEL, item("hitbox-fit-model", false));
            }
            if (definition != null && BDCCustomBlockService.fileClickBox(definition) != null) {
                inventory.setItem(H_FIT_FILE, item("hitbox-fit-file", false));
            }
            inventory.setItem(H_TYPE, item("hitbox-type", false));
        } else {
            inventory.setItem(H_HINT, item("hitbox-size-hint", false));
        }
        inventory.setItem(BACK, item("hitbox-back", false));
    }

    private void hitboxClick(Player player, PlacedDisplay placed, BlockRef ref, int slot, ClickType click) {
        BlockSettings settings = placed.settings == null ? new BlockSettings() : placed.settings.copy();
        AbstractCustomBlock definition = definition(placed);
        boolean custom = !settings.noClickBox && settings.clickBox != null;
        switch (slot) {
            case H_FILE -> {
                if (!settings.noClickBox && settings.clickBox == null) {
                    refuse(player);
                    return;
                }
                settings.noClickBox = false;
                settings.clickBox = null;
            }
            case H_CUSTOM -> {
                double[] start = custom ? null : startBox(placed, settings, definition);
                if (start == null) {
                    refuse(player);
                    return;
                }
                settings.noClickBox = false;
                settings.clickBox = start;
            }
            case H_NONE -> {
                if (settings.noClickBox) {
                    refuse(player);
                    return;
                }
                settings.noClickBox = true;
                settings.clickBox = null;
            }
            case H_COLLISION -> {
                if (definition == null || BDCCustomBlockService.expectedCollisions(definition) == 0) {
                    return;
                }
                settings.collisions = !settings.collisions;
            }
            case H_WIDTH, H_HEIGHT, H_FLOOR -> {
                double step = click == ClickType.LEFT ? 0.1 : click == ClickType.RIGHT ? -0.1
                        : click == ClickType.SHIFT_LEFT ? 1 : click == ClickType.SHIFT_RIGHT ? -1 : 0;
                if (!custom || step == 0) {
                    return;
                }
                int index = slot == H_WIDTH ? 0 : slot == H_HEIGHT ? 1 : 2;
                double scale = placed.scale();
                double max = hitboxMax();
                double real = Math.round((settings.clickBox[index] * scale + step) * 100) / 100.0;
                real = Math.max(index == 2 ? -max : 0.1, Math.min(max, real));
                if (Math.abs(real / scale - settings.clickBox[index]) < 1.0E-9) {
                    refuse(player);
                    return;
                }
                settings.clickBox[index] = real / scale;
            }
            case H_FIT_MODEL, H_FIT_FILE -> {
                double[] box = !custom ? null : slot == H_FIT_MODEL ? clickBoxOf(placed)
                        : definition == null ? null : BDCCustomBlockService.fileClickBox(definition);
                if (box == null) {
                    if (custom) {
                        refuse(player);
                    }
                    return;
                }
                settings.clickBox = box;
            }
            case H_TYPE -> {
                if (custom) {
                    prompt(player, "hitbox");
                }
                return;
            }
            case H_SHOW -> {
                player.closeInventory();
                outline(player, ref);
                sound(player, "click", Sound.UI_BUTTON_CLICK);
                return;
            }
            case BACK -> {
                open(player, placed, false);
                return;
            }
            default -> {
                return;
            }
        }
        if (!apply(placed, settings)) {
            this.gizmos.say(player, "gizmo-admin.hitbox-later");
        }
        sound(player, "click", Sound.UI_BUTTON_CLICK);
        redraw(player, placed);
        outline(player, ref);
    }

    /**
     * The click box a block starts from when it gets its own: the one it already has, else one around the click
     * boxes of its file, else one around what it draws (null for a model that draws nothing).
     */
    @Nullable
    private static double[] startBox(PlacedDisplay placed, BlockSettings settings, @Nullable AbstractCustomBlock definition) {
        if (settings.clickBox != null) {
            return settings.clickBox.clone();
        }
        double[] file = definition == null ? null : BDCCustomBlockService.fileClickBox(definition);
        return file != null ? file : clickBoxOf(placed);
    }

    private static int fileBoxes(@Nullable AbstractCustomBlock definition) {
        int count = 0;
        if (definition != null) {
            for (ConfiguredInteraction interaction : definition.getConfiguredInteractions()) {
                if (!BDCCustomBlockService.isUseless(interaction)) {
                    count++;
                }
            }
        }
        return count;
    }

    private String clickState(PlacedDisplay placed, BlockSettings settings, @Nullable AbstractCustomBlock definition) {
        if (settings.noClickBox) {
            return Texts.get("gizmo-admin.hitbox-state.none");
        }
        if (settings.clickBox != null) {
            double scale = placed.scale();
            return Texts.get("gizmo-admin.hitbox-state.custom", "%width%", blocks(settings.clickBox[0] * scale), "%height%", blocks(settings.clickBox[1] * scale));
        }
        int count = fileBoxes(definition);
        return count > 0 ? Texts.get("gizmo-admin.hitbox-state.file", "%count%", count) : Texts.get("gizmo-admin.hitbox-state.file-empty");
    }

    private String collisionState(BlockSettings settings, @Nullable AbstractCustomBlock definition) {
        if (definition == null || BDCCustomBlockService.expectedCollisions(definition) == 0) {
            return Texts.get("gizmo-admin.hitbox-state.collision-empty");
        }
        return Texts.get(settings.collisions ? "gizmo-admin.hitbox-state.collision-file" : "gizmo-admin.hitbox-state.collision-none");
    }

    /**
     * One of the choices of the hitbox page: it shines and says so when chosen.
     */
    private ItemStack choice(String key, boolean chosen, Object... pairs) {
        Object[] all = java.util.Arrays.copyOf(pairs, pairs.length + 2);
        all[pairs.length] = "%chosen%";
        all[pairs.length + 1] = Texts.get(chosen ? "gizmo-admin.chosen" : "gizmo-admin.choose");
        return item(key, chosen, all);
    }

    private double hitboxMax() {
        return Math.max(0.5, this.gizmos.setting("gizmo.hitbox-max", 32));
    }

    private int outlineSeconds() {
        return (int) Math.round(this.gizmos.setting("gizmo.hitbox-outline.seconds", 10));
    }

    /**
     * {@code 1,25}, {@code 2}: a length in blocks as the panel writes it.
     */
    private static String blocks(double value) {
        double rounded = Math.round(value * 100) / 100.0;
        if (Math.abs(rounded - Math.rint(rounded)) < 1.0E-9) {
            return String.valueOf((long) Math.rint(rounded));
        }
        String text = String.format(Locale.ROOT, "%.2f", rounded);
        return (text.endsWith("0") ? text.substring(0, text.length() - 1) : text).replace('.', ',');
    }

    // ---------- Outline of a hitbox ----------

    private record Dot(Particle particle, @Nullable Object data) {
    }

    /**
     * Draws the click boxes (white) and collisions (orange) of a block for its administrator alone, while he has its
     * hitbox page open and {@code gizmo.hitbox-outline.seconds} more.
     */
    private void outline(Player player, BlockRef ref) {
        UUID id = player.getUniqueId();
        stopOutline(id);
        int seconds = outlineSeconds();
        if (seconds <= 0) {
            return;
        }
        Dot clickDot;
        Dot collisionDot;
        try {
            clickDot = dot("click-box", "#FFFFFF");
            collisionDot = dot("collision", "#FF9A3C");
        } catch (LinkageError | RuntimeException e) {
            return;
        }
        int[] left = {Math.max(1, seconds * 20 / OUTLINE_PERIOD)};
        this.outlines.put(id, this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, () -> {
            Holder holder = this.open.get(id);
            boolean viewing = holder != null && holder.hitbox && holder.block.equals(ref);
            PlacedDisplay placed = player.isOnline() && (viewing || left[0]-- > 0) ? find(ref) : null;
            if (placed == null || placed.world != player.getWorld()) {
                stopOutline(id);
                return;
            }
            List<double[]> boxes = this.renderer.hitboxOf(placed);
            double length = 0;
            for (double[] box : boxes) {
                length += 4 * (box[3] - box[0] + box[4] - box[1] + box[5] - box[2]);
            }
            double spacing = Math.max(0.25, length / OUTLINE_DOTS);
            try {
                for (double[] box : boxes) {
                    drawBox(player, box[6] > 0 ? collisionDot : clickDot, box, spacing);
                }
            } catch (LinkageError | RuntimeException e) {
                stopOutline(id);
            }
        }, 0L, OUTLINE_PERIOD));
    }

    private void stopOutline(UUID player) {
        BukkitTask task = this.outlines.remove(player);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * The dot an outline is drawn with, from {@code gizmo.hitbox-outline.<which>-particle} and {@code -color}.
     */
    private Dot dot(String which, String color) {
        Object configured = this.plugin.getYamlConfiguration().get("gizmo.hitbox-outline." + which + "-particle");
        Particle particle = null;
        for (Object name : new Object[]{configured, "DUST", "REDSTONE", "END_ROD"}) {
            if (name == null || String.valueOf(name).isBlank()) {
                continue;
            }
            try {
                particle = Particle.valueOf(String.valueOf(name).trim().toUpperCase(Locale.ROOT));
                break;
            } catch (IllegalArgumentException ignored) {
                // the next one
            }
        }
        if (particle == null) {
            particle = Particle.END_ROD;
        }
        Class<?> type = particle.getDataType();
        if (type == Particle.DustOptions.class) {
            Object configuredColor = this.plugin.getYamlConfiguration().get("gizmo.hitbox-outline." + which + "-color");
            int rgb;
            try {
                rgb = Integer.parseInt(String.valueOf(configuredColor == null ? color : configuredColor).trim().replace("#", ""), 16);
            } catch (NumberFormatException e) {
                rgb = Integer.parseInt(color.replace("#", ""), 16);
            }
            return new Dot(particle, new Particle.DustOptions(org.bukkit.Color.fromRGB(rgb & 0xFFFFFF), 0.8f));
        }
        return type == Void.class ? new Dot(particle, null) : new Dot(Particle.END_ROD, null);
    }

    private static void drawBox(Player player, Dot dot, double[] box, double spacing) {
        double[] xs = {box[0], box[3]};
        double[] ys = {box[1], box[4]};
        double[] zs = {box[2], box[5]};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                line(player, dot, xs[0], ys[i], zs[j], xs[1], ys[i], zs[j], spacing);
                line(player, dot, xs[i], ys[0], zs[j], xs[i], ys[1], zs[j], spacing);
                line(player, dot, xs[i], ys[j], zs[0], xs[i], ys[j], zs[1], spacing);
            }
        }
    }

    private static void line(Player player, Dot dot, double x1, double y1, double z1, double x2, double y2, double z2, double spacing) {
        double length = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        int steps = Math.max(1, (int) Math.ceil(length / spacing));
        for (int k = 0; k <= steps; k++) {
            double t = (double) k / steps;
            player.spawnParticle(dot.particle(), x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t, 1, 0, 0, 0, 0, dot.data());
        }
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
            case "hitbox" -> "hitbox " + typedBox(player);
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

    /**
     * The values of the click box the player edits, ready to be changed in the chat.
     */
    private String typedBox(Player player) {
        BlockRef ref = this.editing.get(player.getUniqueId());
        PlacedDisplay placed = ref == null ? null : find(ref);
        if (placed == null || placed.settings == null || placed.settings.clickBox == null) {
            return "";
        }
        double scale = placed.scale();
        double[] box = placed.settings.clickBox;
        return (blocks(box[0] * scale) + " " + blocks(box[1] * scale) + " " + blocks(box[2] * scale)).replace(',', '.');
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
        this.clipboardModel.remove(event.getPlayer().getUniqueId());
        this.open.remove(event.getPlayer().getUniqueId());
        stopOutline(event.getPlayer().getUniqueId());
        BlockSettings.forget(event.getPlayer().getUniqueId());
    }

    // ---------- /bdc settings ----------

    private static final List<String> FIELDS = List.of("size", "view-distance", "hitbox", "collision", "command", "cooldown", "permission", "note", "list");

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
        boolean hitboxPage = field.equals("hitbox") || field.equals("collision");
        switch (field) {
            case "hitbox" -> {
                String word = value.toLowerCase(Locale.ROOT);
                double max = hitboxMax();
                if (word.equals("file") || word.equals("default")) {
                    settings.noClickBox = false;
                    settings.clickBox = null;
                } else if (word.equals("none")) {
                    settings.noClickBox = true;
                    settings.clickBox = null;
                } else if (word.equals("model")) {
                    double[] box = clickBoxOf(placed);
                    if (box == null) {
                        this.gizmos.say(player, "gizmo-admin.bad-hitbox", "%max%", blocks(max));
                        return;
                    }
                    settings.noClickBox = false;
                    settings.clickBox = box;
                } else {
                    String[] parts = value.split("\\s+");
                    Double width = parts.length >= 2 ? number(parts[0]) : null;
                    Double height = parts.length >= 2 ? number(parts[1]) : null;
                    Double floor = parts.length >= 3 ? number(parts[2]) : Double.valueOf(0);
                    if (parts.length > 3 || width == null || height == null || floor == null || width < 0.1 || width > max
                            || height < 0.1 || height > max || floor < -max || floor > max) {
                        this.gizmos.say(player, "gizmo-admin.bad-hitbox", "%max%", blocks(max));
                        return;
                    }
                    double scale = placed.scale();
                    settings.noClickBox = false;
                    settings.clickBox = new double[]{width / scale, height / scale, floor / scale};
                }
            }
            case "collision" -> {
                AbstractCustomBlock definition = definition(placed);
                String word = value.toLowerCase(Locale.ROOT);
                boolean on = List.of("on", "true", "yes", "file", "default").contains(word);
                if (!on && !List.of("off", "false", "no", "none").contains(word)) {
                    this.gizmos.say(player, "gizmo-admin.usage");
                    return;
                }
                if (definition == null || BDCCustomBlockService.expectedCollisions(definition) == 0) {
                    this.gizmos.say(player, "gizmo-admin.no-collision");
                    return;
                }
                settings.collisions = on;
            }
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
        if (apply(placed, settings)) {
            this.gizmos.say(player, "gizmo-admin.saved", "%model%", placed.modelName);
        } else {
            this.gizmos.say(player, "gizmo-admin.hitbox-later");
        }
        open(player, placed, hitboxPage);
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
                case "hitbox" -> StringUtil.copyPartialMatches(typed, List.of("file", "model", "none", "1", "2"), result);
                case "collision" -> StringUtil.copyPartialMatches(typed, List.of("on", "off"), result);
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
