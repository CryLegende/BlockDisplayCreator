package me.general_breddok.blockdisplaycreator.render;

import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.commandparser.CommandLine;
import me.general_breddok.blockdisplaycreator.commandparser.MCCommandLine;
import me.general_breddok.blockdisplaycreator.custom.CommandBundle;
import me.general_breddok.blockdisplaycreator.custom.CommandBundleDta;
import me.general_breddok.blockdisplaycreator.placeholder.universal.UniversalPlaceholder;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What an administrator set on one placed custom block, on top of its file: the other blocks of the same file keep
 * what their file gives them. Stored with the block (see {@code CustomBlockKey.SETTINGS}) as a small YAML text, and
 * gone with it when it is broken.
 */
public final class BlockSettings {

    /**
     * The name of the click box an administrator drew on a block whose file gives it none.
     */
    public static final String CLICK_BOX = "admin-click-box";

    /**
     * From this cooldown on (milliseconds), the wait is stored on the player, outlives a restart and is told to him.
     */
    private static final long LONG_COOLDOWN = 60_000L;
    /**
     * The last click of each player on each block with a short cooldown, kept in memory only.
     */
    private static final Map<String, Map<UUID, Long>> LAST_CLICKS = new HashMap<>();

    /**
     * Commands run by a click on the block, with {@code %player%}, PlaceholderAPI and the block's placeholders.
     */
    final List<String> commands = new ArrayList<>();
    /**
     * Whether the commands are run by the player who clicks rather than by the console.
     */
    boolean asPlayer;
    /**
     * Whether the commands its file gives its click boxes are still run.
     */
    boolean modelCommands = true;
    /**
     * Whether a click plays the click animations of its file.
     */
    boolean clickAnimation = true;
    /**
     * Milliseconds between two clicks of the same player that do something, 0 for none.
     */
    long cooldown;
    /**
     * The permission a player needs for his click to do anything, null for none.
     */
    @Nullable
    String permission;
    /**
     * The one click box an administrator drew in place of those of its file (or for a file that gives it none):
     * width, height and how high its floor stands above the block, in blocks of the model at the size its file gives
     * (it grows and shrinks with the block). Null for the file's.
     */
    @Nullable
    double[] clickBox;
    /**
     * Whether its click boxes are taken away: nothing of it can be clicked nor hit (its panel still breaks it).
     */
    boolean noClickBox;
    /**
     * Whether the collisions of its file stop players and entities.
     */
    boolean collisions = true;
    /**
     * Whether a hit may break it.
     */
    boolean breakable = true;
    /**
     * Whether only administrators may move it with their gizmo.
     */
    boolean locked;
    /**
     * Whether the loops of its file play.
     */
    boolean loops = true;
    /**
     * How far, in blocks, players are sent it (also above and below it), -1 for what its file says.
     */
    int viewDistance = -1;
    /**
     * A note for the staff, shown in the panel only.
     */
    @Nullable
    String note;

    /**
     * Reads what {@link #write()} stored, or null for nothing stored (or an unreadable text: the block then keeps its
     * file's behaviour).
     */
    @Nullable
    static BlockSettings read(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            BlockDisplayCreator.getInstance().getLogger().warning("Unreadable settings of a placed custom block, ignored: " + e.getMessage());
            return null;
        }
        BlockSettings settings = new BlockSettings();
        for (String command : yaml.getStringList("commands")) {
            if (!command.isBlank()) {
                settings.commands.add(command);
            }
        }
        settings.asPlayer = "PLAYER".equalsIgnoreCase(yaml.getString("command-source"));
        settings.modelCommands = yaml.getBoolean("model-commands", true);
        settings.clickAnimation = yaml.getBoolean("click-animation", true);
        settings.cooldown = Math.max(0, yaml.getLong("cooldown", 0));
        settings.permission = blankToNull(yaml.getString("permission"));
        if (yaml.isConfigurationSection("click-box")) {
            settings.clickBox = new double[]{yaml.getDouble("click-box.width"), yaml.getDouble("click-box.height"), yaml.getDouble("click-box.y")};
            if (settings.clickBox[0] <= 0 || settings.clickBox[1] <= 0) {
                settings.clickBox = null;
            }
        } else {
            settings.noClickBox = "none".equalsIgnoreCase(yaml.getString("click-box"));
        }
        settings.collisions = yaml.getBoolean("collisions", true);
        settings.breakable = yaml.getBoolean("breakable", true);
        settings.locked = yaml.getBoolean("gizmo-admins-only", false);
        settings.loops = yaml.getBoolean("loops", true);
        settings.viewDistance = yaml.getInt("view-distance", -1);
        settings.note = blankToNull(yaml.getString("note"));
        return settings.isDefault() ? null : settings;
    }

    /**
     * The settings stored with a block, or null for a block that follows its file.
     */
    @Nullable
    public static BlockSettings of(@Nullable String text) {
        return read(text);
    }

    /**
     * The one click box drawn in place of those of the file (width, height, height of its floor), or null.
     */
    @Nullable
    public double[] clickBox() {
        return this.noClickBox ? null : this.clickBox;
    }

    /**
     * Whether its click boxes are taken away.
     */
    public boolean removesClickBoxes() {
        return this.noClickBox;
    }

    /**
     * Whether the collisions of its file are summoned.
     */
    public boolean keepsCollisions() {
        return this.collisions;
    }

    /**
     * What its hitbox entities depend on: when it changes, they are summoned again.
     */
    static String hitboxKey(@Nullable BlockSettings settings) {
        if (settings == null) {
            return "";
        }
        return (settings.noClickBox ? "none" : settings.clickBox == null ? "file" : java.util.Arrays.toString(settings.clickBox))
                + (settings.collisions ? "" : "/no-collision");
    }

    /**
     * The text to store with the block, or null when nothing differs from its file.
     */
    @Nullable
    String write() {
        if (isDefault()) {
            return null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        if (!this.commands.isEmpty()) {
            yaml.set("commands", new ArrayList<>(this.commands));
            yaml.set("command-source", this.asPlayer ? "PLAYER" : "CONSOLE");
        }
        if (!this.modelCommands) {
            yaml.set("model-commands", false);
        }
        if (!this.clickAnimation) {
            yaml.set("click-animation", false);
        }
        if (this.cooldown > 0) {
            yaml.set("cooldown", this.cooldown);
        }
        if (this.permission != null) {
            yaml.set("permission", this.permission);
        }
        if (this.noClickBox) {
            yaml.set("click-box", "none");
        } else if (this.clickBox != null) {
            yaml.set("click-box.width", this.clickBox[0]);
            yaml.set("click-box.height", this.clickBox[1]);
            yaml.set("click-box.y", this.clickBox[2]);
        }
        if (!this.collisions) {
            yaml.set("collisions", false);
        }
        if (!this.breakable) {
            yaml.set("breakable", false);
        }
        if (this.locked) {
            yaml.set("gizmo-admins-only", true);
        }
        if (!this.loops) {
            yaml.set("loops", false);
        }
        if (this.viewDistance >= 0) {
            yaml.set("view-distance", this.viewDistance);
        }
        if (this.note != null) {
            yaml.set("note", this.note);
        }
        return yaml.saveToString();
    }

    /**
     * Whether nothing differs from the block's file.
     */
    boolean isDefault() {
        return this.commands.isEmpty() && this.modelCommands && this.clickAnimation && this.cooldown == 0 && this.permission == null
                && this.clickBox == null && !this.noClickBox && this.collisions
                && this.breakable && !this.locked && this.loops && this.viewDistance < 0 && this.note == null;
    }

    BlockSettings copy() {
        BlockSettings copy = read(write());
        return copy == null ? new BlockSettings() : copy;
    }

    /**
     * Whether a click on the block is handled here rather than as its file says alone.
     */
    public boolean handlesClicks() {
        return !this.commands.isEmpty() || !this.modelCommands || !this.clickAnimation || this.cooldown > 0 || this.permission != null;
    }

    public boolean runsModelCommands() {
        return this.modelCommands;
    }

    public boolean playsClickAnimation() {
        return this.clickAnimation;
    }

    public boolean isBreakable() {
        return this.breakable;
    }

    /**
     * Whether a player may click the block.
     */
    public boolean allows(Player player) {
        return this.permission == null || player.hasPermission(this.permission);
    }

    /**
     * Whether a player's click on the block may do something now, and if so counts it. A long wait is stored on the
     * player and told to him; a short one only stops double clicks, silently.
     *
     * @param block where the block stands, which the cooldown belongs to
     */
    public boolean passCooldown(Player player, org.bukkit.Location block) {
        if (this.cooldown <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        String id = blockId(block);
        if (this.cooldown >= LONG_COOLDOWN) {
            NamespacedKey key = new NamespacedKey(BlockDisplayCreator.getInstance(), "cooldown/placed/" + id);
            PersistentDataContainer data = player.getPersistentDataContainer();
            Long last = data.get(key, PersistentDataType.LONG);
            if (last != null && last <= now && now - last < this.cooldown) {
                me.general_breddok.blockdisplaycreator.custom.InteractionHandlerDta.tellWait(player, this.cooldown - (now - last), now);
                return false;
            }
            data.set(key, PersistentDataType.LONG, now);
            return true;
        }
        Map<UUID, Long> clicks = LAST_CLICKS.computeIfAbsent(id, k -> new HashMap<>());
        Long last = clicks.get(player.getUniqueId());
        if (last != null && now - last < this.cooldown) {
            return false;
        }
        clicks.put(player.getUniqueId(), now);
        return true;
    }

    /**
     * Forgets the short cooldowns of a player who left.
     */
    public static void forget(UUID player) {
        LAST_CLICKS.values().forEach(clicks -> clicks.remove(player));
        LAST_CLICKS.values().removeIf(Map::isEmpty);
    }

    private static String blockId(org.bukkit.Location block) {
        String world = block.getWorld() == null ? "world" : block.getWorld().getName();
        return (world + "/" + block.getBlockX() + "_" + block.getBlockY() + "_" + block.getBlockZ())
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._/-]", "_");
    }

    /**
     * Runs the commands of a click, as the console or as the player.
     */
    public void run(Player player, UniversalPlaceholder<?>... placeholders) {
        if (this.commands.isEmpty()) {
            return;
        }
        List<CommandLine> lines = new ArrayList<>(this.commands.size());
        for (String command : this.commands) {
            try {
                lines.add(new MCCommandLine(command));
            } catch (RuntimeException e) {
                BlockDisplayCreator.getInstance().getLogger().warning("Click command '" + command + "' of a placed custom block is not a command: " + e.getMessage());
            }
        }
        new CommandBundleDta(lines, this.asPlayer ? CommandBundle.CommandSource.PLAYER : CommandBundle.CommandSource.CONSOLE, new ArrayList<>())
                .execute(player, placeholders);
    }

    /**
     * Whether a text can be run as a command of a click: refused texts would be dropped at the click.
     */
    static boolean isCommand(String text) {
        try {
            new MCCommandLine(text);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Nullable
    private static String blankToNull(@Nullable String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
