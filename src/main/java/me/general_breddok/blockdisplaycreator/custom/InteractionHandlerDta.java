package me.general_breddok.blockdisplaycreator.custom;

import lombok.*;
import lombok.experimental.FieldDefaults;
import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.common.Cooldown;
import me.general_breddok.blockdisplaycreator.common.DeepCloneable;
import me.general_breddok.blockdisplaycreator.placeholder.universal.UniversalPlaceholder;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Runs the click commands of a click box, at most once per cooldown for each player.
 * <p>
 * A cooldown of a minute or more is a waiting time the player must see and that must outlive a restart:
 * the time of the last click is kept in the player's own data (one key per block and click box, the
 * {@code _2}, {@code _3} copies of a long box share their box's key), and a click during the wait tells
 * the player how long is left. A shorter cooldown only stops double clicks: kept in memory, silent.
 */
@Getter
@Setter
@EqualsAndHashCode
@FieldDefaults(level = AccessLevel.PRIVATE)
public class InteractionHandlerDta implements InteractionHandler, DeepCloneable<InteractionHandlerDta> {
    /** From this cooldown on (milliseconds), the wait is stored on the player and shown to them. */
    public static final long LONG_COOLDOWN = 60_000L;
    /** Least time between two "come back in" messages to the same player. */
    private static final long MESSAGE_GAP = 1_000L;
    private static final Map<UUID, Long> LAST_MESSAGE = new HashMap<>();

    CommandBundle commandBundle;
    Cooldown<UUID> cooldown;
    @Nullable
    NamespacedKey cooldownKey;

    public InteractionHandlerDta(CommandBundle commandBundle, Cooldown<UUID> cooldown, @Nullable NamespacedKey cooldownKey) {
        this.commandBundle = commandBundle;
        this.cooldown = cooldown;
        this.cooldownKey = cooldownKey;
    }

    /**
     * @param cooldown   milliseconds between two clicks of the same player
     * @param cooldownId block and click box the cooldown belongs to, used to store a long cooldown on the player
     */
    public InteractionHandlerDta(CommandBundle commandBundle, long cooldown, @Nullable String cooldownId) {
        this(commandBundle, new Cooldown<>(cooldown), cooldown >= LONG_COOLDOWN ? cooldownKey(cooldownId) : null);
    }

    public InteractionHandlerDta(CommandBundle commandBundle, int cooldown) {
        this(commandBundle, cooldown, null);
    }

    public InteractionHandlerDta(CommandBundle commandBundle) {
        this(commandBundle, 0);
    }

    @Override
    public void handleClick(@NotNull Interaction interaction, @NotNull Player player, UniversalPlaceholder<?>... placeholders) {
        if (cooldownKey != null) {
            if (!passLongCooldown(player)) {
                return;
            }
        } else if (!cooldown.check(player.getUniqueId())) {
            return;
        }

        if (commandBundle == null) {
            return;
        }

        commandBundle.execute(player, placeholders);
    }

    private boolean passLongCooldown(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        long now = System.currentTimeMillis();
        Long last = data.get(cooldownKey, PersistentDataType.LONG);
        long wait = cooldown.getCooldownValue();

        if (last != null && last <= now && now - last < wait) {
            tellWait(player, wait - (now - last), now);
            return false;
        }

        data.set(cooldownKey, PersistentDataType.LONG, now);
        return true;
    }

    /**
     * Tells a player how long he still has to wait before a click does something ({@code cooldown.wait} in
     * messages.yml), at most once a second.
     */
    public static void tellWait(Player player, long remaining, long now) {
        Long lastMessage = LAST_MESSAGE.get(player.getUniqueId());
        if (lastMessage != null && now - lastMessage < MESSAGE_GAP) {
            return;
        }
        LAST_MESSAGE.put(player.getUniqueId(), now);
        me.general_breddok.blockdisplaycreator.util.ChatUtil.sendMessage(player,
                me.general_breddok.blockdisplaycreator.file.config.value.StringMessagesValue.COOLDOWN_WAIT.replace("%time%", formatWait(remaining)));
    }

    /** {@code 23 h 12 min}, {@code 5 h}, {@code 12 min}, {@code 45 s}; always rounded up, never « 0 ». */
    static String formatWait(long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = (seconds + 59) / 60;
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
    }

    /** Key in the player's data for a block's click box: lower case, {@code interaction_2} counts as {@code interaction}. */
    @Nullable
    static NamespacedKey cooldownKey(@Nullable String cooldownId) {
        if (cooldownId == null || cooldownId.isEmpty()) {
            return null;
        }
        String id = cooldownId.toLowerCase(Locale.ROOT).replaceAll("_\\d+$", "").replaceAll("[^a-z0-9._/-]", "_");
        return new NamespacedKey(BlockDisplayCreator.getInstance(), "cooldown/" + id);
    }

    /** Forgets the messages sent to a player who left. */
    public static void forget(UUID player) {
        LAST_MESSAGE.remove(player);
    }

    @Override
    public long getCooldown() {
        return cooldown.getCooldownValue();
    }

    @Override
    public InteractionHandlerDta clone() {
        return new InteractionHandlerDta(this.commandBundle, this.cooldown, this.cooldownKey);
    }
}
