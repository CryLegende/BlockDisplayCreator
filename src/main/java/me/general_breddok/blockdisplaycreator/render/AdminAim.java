package me.general_breddok.blockdisplaycreator.render;

import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlock;
import me.general_breddok.blockdisplaycreator.listener.entity.EntityDamageByEntityListener;
import me.general_breddok.blockdisplaycreator.permission.DefaultPermissions;
import me.general_breddok.blockdisplaycreator.util.ItemUtil;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * What an administrator does to a placed model by aiming at what it draws, click box or not: a swing breaks it as a hit
 * on its click box would (same drops, sounds and protections), a middle click takes its item. A model counts when his
 * line of sight meets one of its parts within reach, closer than the block or the entity the crosshair is on.
 * <p>
 * The server is never told of a swing in the air at a model: a swing alone is taken for a left click when no right
 * click (of a block, an item or an entity) and no hit on an entity came with it. Nothing runs on any tick; a swing or a
 * click of a player without the permission costs a lookup (the permission is asked once a second for each player), and
 * an administrator's swing or pick looks only at the models he is sent close to him.
 * Its options are in config.yml ({@code admin-aim}).
 */
final class AdminAim implements Listener {

    /**
     * Ticks between two reads of the options in config.yml.
     */
    private static final int OPTIONS_PERIOD = 20;
    /**
     * Ticks a refusal to break a protected block is not told again for the same block (a button held on it).
     */
    private static final int REFUSAL_QUIET = 20;
    /**
     * Ticks a player is taken to keep, or to lack, the permission once it was checked: the swings and clicks of the
     * other players then cost a lookup.
     */
    private static final int PERMISSION_PERIOD = 20;
    private static final int LONG_AGO = Integer.MIN_VALUE / 2;

    private final BlockDisplayCreator plugin;
    private final DisplayRenderer renderer;
    private final GizmoManager gizmos;
    private final Map<UUID, Clicks> clicks = new ConcurrentHashMap<>();

    private volatile int optionsAt = LONG_AGO;
    private volatile String permission = GizmoManager.ADMIN_PERMISSION;
    private volatile double reach = 5;
    private volatile boolean breaks = true;
    private volatile boolean picks = true;
    private volatile int breakDelay = 5;
    private volatile Set<GameMode> breakModes = EnumSet.of(GameMode.SURVIVAL, GameMode.CREATIVE);

    /**
     * Whether a player has the permission, and what the client of an administrator did lately, by tick.
     */
    private static final class Clicks {
        /**
         * Whether he has the permission, and the tick it was checked at.
         */
        volatile boolean admin;
        volatile int checkedAt = LONG_AGO;
        /**
         * A right click: on a block, with an item in the air, on an entity. The client swings after some of them.
         */
        volatile int used = LONG_AGO;
        /**
         * A hit on an entity, known or not to the server: the swing that comes with it is that hit's.
         */
        volatile int attacked = LONG_AGO;
        /**
         * A left click on a block, already decided: the block was hit, or the model in front of it.
         */
        volatile int decided = LONG_AGO;
        /**
         * The last model broken (or refused) by aiming.
         */
        volatile int broke = LONG_AGO;
        volatile int refusedAt = LONG_AGO;
        volatile PlacedDisplay refused;
    }

    AdminAim(BlockDisplayCreator plugin, DisplayRenderer renderer, GizmoManager gizmos) {
        this.plugin = plugin;
        this.renderer = renderer;
        this.gizmos = gizmos;
        listen("io.papermc.paper.event.player.PrePlayerAttackEntityEvent", (player, event) -> {
            Clicks of = of(player);
            if (of != null) {
                of.attacked = this.gizmos.now();
            }
        });
        Method attack = method("com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent", "isAttack");
        if (attack != null) {
            listen("com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent", (player, event) -> {
                Clicks of = of(player);
                if (of != null) {
                    boolean hit;
                    try {
                        hit = (boolean) attack.invoke(event);
                    } catch (ReflectiveOperationException e) {
                        hit = true;
                    }
                    if (hit) {
                        of.attacked = this.gizmos.now();
                    } else {
                        of.used = this.gizmos.now();
                    }
                }
            });
        }
        listenToPicks();
    }

    // ---------- Options ----------

    /**
     * Reads the options again once a second: an event only compares two numbers.
     */
    private void refresh() {
        int now = this.gizmos.now();
        if (now - this.optionsAt < OPTIONS_PERIOD && now >= this.optionsAt) {
            return;
        }
        this.optionsAt = now;
        String node = text("admin-aim.permission");
        this.permission = node == null || node.isBlank() ? GizmoManager.ADMIN_PERMISSION : node.trim();
        this.reach = Math.max(0.5, Math.min(32, this.gizmos.setting("admin-aim.reach", 5)));
        this.breaks = flag("admin-aim.break", true);
        this.picks = flag("admin-aim.pick", true);
        this.breakDelay = (int) Math.max(1, Math.round(this.gizmos.setting("admin-aim.break-delay", 5)));
        Object listed = this.plugin.getYamlConfiguration().get("admin-aim.break-game-modes");
        java.util.List<?> names = listed instanceof java.util.List<?> list ? list : listed == null ? java.util.List.of("SURVIVAL", "CREATIVE") : java.util.List.of(listed);
        Set<GameMode> modes = EnumSet.noneOf(GameMode.class);
        for (Object mode : names) {
            try {
                modes.add(GameMode.valueOf(String.valueOf(mode).trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // an unknown game mode is left out
            }
        }
        this.breakModes = modes;
    }

    @Nullable
    private String text(String path) {
        Object value = this.plugin.getYamlConfiguration().get(path);
        return value == null ? null : String.valueOf(value);
    }

    private boolean flag(String path, boolean fallback) {
        Object value = this.plugin.getYamlConfiguration().get(path);
        return value instanceof Boolean on ? on : value == null ? fallback : Boolean.parseBoolean(String.valueOf(value).trim());
    }

    /**
     * What an administrator's client did lately, null for a player without the permission. The permission is asked
     * at most once a second for each player.
     */
    @Nullable
    private Clicks of(Player player) {
        refresh();
        Clicks of = this.clicks.computeIfAbsent(player.getUniqueId(), id -> new Clicks());
        int now = this.gizmos.now();
        if (now - of.checkedAt >= PERMISSION_PERIOD || now < of.checkedAt) {
            of.admin = player.hasPermission(this.permission);
            of.checkedAt = now;
        }
        return of.admin ? of : null;
    }

    /**
     * Whether a swing of this player may break a model: an administrator in a game mode it works in, whose gizmo is
     * not open (its clicks are the tool's).
     */
    private boolean mayBreak(Player player) {
        refresh();
        return this.breaks && this.breakModes.contains(player.getGameMode()) && of(player) != null
                && !this.gizmos.isEditing(player) && !this.gizmos.justToggled(player);
    }

    private boolean mayPick(Player player) {
        refresh();
        return this.picks && player.getGameMode() != GameMode.SPECTATOR && of(player) != null && !this.gizmos.isEditing(player);
    }

    // ---------- What the client did ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) {
            Clicks of = of(event.getPlayer());
            if (of != null) {
                of.used = this.gizmos.now();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseEntity(PlayerInteractEntityEvent event) {
        Clicks of = of(event.getPlayer());
        if (of != null) {
            of.used = this.gizmos.now();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseEntityAt(PlayerInteractAtEntityEvent event) {
        Clicks of = of(event.getPlayer());
        if (of != null) {
            of.used = this.gizmos.now();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            Clicks of = of(player);
            if (of != null) {
                of.attacked = this.gizmos.now();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.clicks.remove(event.getPlayer().getUniqueId());
    }

    // ---------- Breaking ----------

    /**
     * A left click on a block: when a model stands in front of the block, the model is hit and the block is not.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLeftClickBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!mayBreak(player)) {
            return;
        }
        Clicks of = of(player);
        if (of == null) {
            return;
        }
        int now = this.gizmos.now();
        // the swing that comes with this click is this click's
        of.decided = now;
        DisplayRenderer.Aimed aimed = this.renderer.aimed(player, this.reach);
        if (aimed == null || aimed.distance() >= blockDistance(player, event.getClickedBlock())) {
            return;
        }
        event.setCancelled(true);
        if (now - of.broke >= this.breakDelay) {
            breakAimed(player, aimed.placed(), of, now);
        }
    }

    /**
     * A swing that came alone: a left click in the air, as far as the server knows, at the model aimed at if any.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        if (!mayBreak(player)) {
            return;
        }
        Clicks of = of(player);
        if (of == null) {
            return;
        }
        int now = this.gizmos.now();
        // a right click (the client swings after some), a hit on an entity, a click on a block: not a swing in the air
        if (now - of.used <= 1 || now - of.attacked <= 1 || now - of.decided <= 1 || now - of.broke < this.breakDelay) {
            return;
        }
        DisplayRenderer.Aimed aimed = this.renderer.aimed(player, this.reach);
        if (aimed == null || somethingBefore(player, aimed.distance())) {
            return;
        }
        breakAimed(player, aimed.placed(), of, now);
    }

    private void breakAimed(Player player, PlacedDisplay placed, Clicks of, int now) {
        of.broke = now;
        // a button held on a protected model is told so once a second
        boolean tell = !(of.refused == placed && now - of.refusedAt < REFUSAL_QUIET);
        boolean broken = EntityDamageByEntityListener.breakByHit(player, placed.modelName, placed.base(), this.plugin.getCustomBlockService(), tell);
        if (!broken) {
            of.refused = placed;
            of.refusedAt = now;
        }
    }

    /**
     * Whether a block, or a living entity (a player, a mob, the collision of another model), stands on the line of sight
     * before a distance: the click is theirs.
     */
    private static boolean somethingBefore(Player player, double distance) {
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection();
        if (player.getWorld().rayTraceBlocks(eye, look, distance, FluidCollisionMode.NEVER, false) != null) {
            return true;
        }
        return player.getWorld().rayTraceEntities(eye, look, distance, 0, found -> found != player && found instanceof LivingEntity
                && !(found instanceof Player other && other.getGameMode() == GameMode.SPECTATOR)) != null;
    }

    /**
     * How far from the eyes the line of sight enters a block.
     */
    private static double blockDistance(Player player, Block block) {
        Location eye = player.getEyeLocation();
        Vector from = eye.toVector();
        RayTraceResult hit = block.getBoundingBox().rayTrace(from, eye.getDirection(), 64);
        if (hit != null) {
            return hit.getHitPosition().distance(from);
        }
        return Math.max(0, from.distance(block.getLocation().add(0.5, 0.5, 0.5).toVector()) - 0.5);
    }

    /**
     * How far from the eyes the line of sight enters an entity.
     */
    private static double entityDistance(Player player, Entity entity) {
        Location eye = player.getEyeLocation();
        Vector from = eye.toVector();
        RayTraceResult hit = entity.getBoundingBox().rayTrace(from, eye.getDirection(), 64);
        return hit != null ? hit.getHitPosition().distance(from) : from.distance(entity.getBoundingBox().getCenter());
    }

    // ---------- The item ----------

    /**
     * A middle click whose block or entity stands behind the model aimed at takes the model's item instead.
     *
     * @param beyond how far from the eyes the block or entity the client picked is
     * @return whether the pick was the model's
     */
    boolean pickAimed(Player player, double beyond) {
        if (!mayPick(player)) {
            return false;
        }
        DisplayRenderer.Aimed aimed = this.renderer.aimed(player, Math.min(this.reach, beyond));
        if (aimed == null || aimed.distance() >= beyond) {
            return false;
        }
        copy(player, aimed.placed(), false);
        return true;
    }

    /**
     * Puts the item of a placed block in the player's hand: the one he has, else, in creative mode, a new one (as a
     * middle click on its click box does).
     *
     * @param give whether one is given in any game mode when he has none (the copy button of the gizmo)
     * @return whether he holds it
     */
    boolean copy(Player player, PlacedDisplay placed, boolean give) {
        CustomBlock block = this.plugin.getCustomBlockService().getCustomBlock(placed.base());
        ItemStack item = block == null ? null : block.getItem();
        if (item == null || item.getType().isAir()) {
            return false;
        }
        item = item.clone();
        item.setAmount(1);
        java.util.function.Predicate<ItemStack> same = ItemUtil.customBlockItem(item.getType(), block.getName());
        if (ItemUtil.selectItem(player, same, item, DefaultPermissions.BDC.QUICKSELECT_CREATIVE)) {
            return true;
        }
        if (!give) {
            return false;
        }
        ItemUtil.distributeItem(player, item);
        ItemUtil.selectItem(player, same, null, null);
        return true;
    }

    /**
     * Paper's pick events: a middle click on a block or an entity the server knows (those of custom blocks are taken
     * earlier, from their packets).
     */
    private void listenToPicks() {
        Class<? extends Event> type;
        Class<?> blockPick;
        Class<?> entityPick;
        Method block;
        Method entity;
        try {
            type = Class.forName("io.papermc.paper.event.player.PlayerPickItemEvent").asSubclass(Event.class);
            blockPick = Class.forName("io.papermc.paper.event.player.PlayerPickBlockEvent");
            entityPick = Class.forName("io.papermc.paper.event.player.PlayerPickEntityEvent");
            block = blockPick.getMethod("getBlock");
            entity = entityPick.getMethod("getEntity");
        } catch (ReflectiveOperationException e) {
            return;
        }
        this.plugin.getServer().getPluginManager().registerEvent(type, this, EventPriority.LOWEST, (listener, event) -> {
            if (!type.isInstance(event) || !(event instanceof PlayerEvent playerEvent) || ((Cancellable) event).isCancelled()) {
                return;
            }
            Player player = playerEvent.getPlayer();
            if (!mayPick(player)) {
                return;
            }
            try {
                double beyond;
                if (blockPick.isInstance(event)) {
                    beyond = blockDistance(player, (Block) block.invoke(event));
                } else if (entityPick.isInstance(event)) {
                    beyond = entityDistance(player, (Entity) entity.invoke(event));
                } else {
                    return;
                }
                if (pickAimed(player, beyond)) {
                    ((Cancellable) event).setCancelled(true);
                }
            } catch (ReflectiveOperationException e) {
                this.plugin.getLogger().log(Level.WARNING, "Could not read a middle click", e);
            }
        }, this.plugin, false);
    }

    // ---------- Paper's events, when there ----------

    @Nullable
    private static Method method(String type, String name) {
        try {
            return Class.forName(type).getMethod(name);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Listens to an event of Paper by its name, at the monitor priority, when the server has it.
     */
    private void listen(String name, BiConsumer<Player, Event> action) {
        Class<? extends Event> type;
        try {
            type = Class.forName(name).asSubclass(Event.class);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return;
        }
        this.plugin.getServer().getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
            if (type.isInstance(event) && event instanceof PlayerEvent playerEvent) {
                action.accept(playerEvent.getPlayer(), event);
            }
        }, this.plugin, false);
    }
}
