package me.general_breddok.blockdisplaycreator.render;

import com.jeff_media.customblockdata.CustomBlockData;
import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.common.BDCDependentPluginsManager;
import me.general_breddok.blockdisplaycreator.custom.block.AbstractCustomBlock;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockPermissions;
import me.general_breddok.blockdisplaycreator.world.guard.WGRegionAccessChecker;
import me.general_breddok.blockdisplaycreator.world.guard.WGRegionFlags;
import me.general_breddok.blockdisplaycreator.world.skyblock.superior.SSBIslandAccessChecker;
import me.general_breddok.blockdisplaycreator.world.skyblock.superior.SSBIslandPrivileges;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockKey;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Lets players move custom blocks shown through packets where they may build: shift + right click on a block shows
 * or hides its {@link Gizmo}, and while it is shown nothing else the player clicks has an effect in the world.
 * Where the player may place and break custom blocks (WorldGuard regions, island privileges, the block's own
 * permissions, operators' bypass) he may move them, within that same place.
 */
public final class GizmoManager implements Listener {

    /**
     * The permission to move custom blocks: everyone has it, wherever the usual checks let them build.
     */
    public static final String PERMISSION = "bdc.gizmo";
    /**
     * The permission to set things on one placed block alone, from the admin button of the gizmo (see
     * {@link AdminMenu}), and to move the blocks an administrator locked.
     */
    public static final String ADMIN_PERMISSION = "bdc.gizmo.admin";

    private final BlockDisplayCreator plugin;
    private final DisplayRenderer renderer;
    private final NmsBridge nms;
    private final Map<UUID, Gizmo> sessions = new HashMap<>();
    private final Map<UUID, Integer> toggled = new HashMap<>();
    /**
     * The players whose shift key is down, as the server was told by their clients: the click on an entity carries
     * its own idea of it, which a client that does not send it leaves out.
     */
    private final java.util.Set<UUID> sneaking = new java.util.HashSet<>();
    private int tick;
    private final AdminMenu adminMenu;

    GizmoManager(BlockDisplayCreator plugin, DisplayRenderer renderer, NmsBridge nms) {
        this.plugin = plugin;
        this.renderer = renderer;
        this.nms = nms;
        this.adminMenu = new AdminMenu(plugin, renderer, this);
        plugin.getServer().getPluginManager().registerEvents(this.adminMenu, plugin);
        listenToUnknownEntities();
    }

    /**
     * The panel of what is set on one block alone, opened by the admin button of the gizmo or by {@code /bdc settings}.
     */
    public AdminMenu adminMenu() {
        return this.adminMenu;
    }

    /**
     * Opens the admin panel of a block for a player whose gizmo is on it.
     */
    void openAdmin(Player player, PlacedDisplay placed) {
        if (player.hasPermission(ADMIN_PERMISSION)) {
            this.adminMenu.open(player, placed);
        }
    }

    /**
     * Brings the gizmos open on a block to where it now stands, after the admin panel changed it.
     */
    void follow(PlacedDisplay placed) {
        for (Gizmo gizmo : this.sessions.values()) {
            if (gizmo.target() == placed) {
                gizmo.follow();
            }
        }
    }

    /**
     * A number of config.yml, or a default when it is missing or not a number.
     */
    double setting(String path, double fallback) {
        Object value = this.plugin.getYamlConfiguration().get(path);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Whether the player has a gizmo open: the clicks that would break or use custom blocks are then his tool's.
     */
    public boolean isEditing(Player player) {
        return this.sessions.containsKey(player.getUniqueId());
    }

    // ---------- Opening and closing ----------

    private void toggle(Player player, PlacedDisplay placed) {
        this.toggled.put(player.getUniqueId(), this.tick);
        Gizmo current = this.sessions.remove(player.getUniqueId());
        if (current != null) {
            current.close();
            if (current.target() == placed) {
                say(player, "gizmo.closed");
                return;
            }
        }
        if (placed.model == null || placed.model.elements.isEmpty()) {
            return;
        }
        try {
            Gizmo gizmo = new Gizmo(this, this.renderer, this.nms, player, placed);
            this.sessions.put(player.getUniqueId(), gizmo);
            gizmo.status();
            boolean admin = player.hasPermission(ADMIN_PERMISSION);
            say(player, "gizmo.opened");
            say(player, admin ? "gizmo.help-admin" : "gizmo.help",
                    "%min%", Math.round(setting(admin ? "gizmo.admin-size-min" : "gizmo.size-min", admin ? 0.1 : 0.75) * 100),
                    "%max%", Math.round(setting(admin ? "gizmo.admin-size-max" : "gizmo.size-max", admin ? 10 : 1.25) * 100));
        } catch (RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Could not open the gizmo of custom block " + placed.modelName, e);
            say(player, "gizmo.failed");
        }
    }

    /**
     * Sends a text of messages.yml (nothing for an empty one), placeholders replaced: pairs of placeholder and value.
     */
    void say(Player player, String path, Object... pairs) {
        me.general_breddok.blockdisplaycreator.util.ChatUtil.sendMessage(player, Texts.get(path, pairs));
    }

    /**
     * Whether the player may move this block, or leave it at this place: where he may place and break custom blocks.
     */
    boolean mayEditAt(Player player, PlacedDisplay placed, Location where) {
        BDCDependentPluginsManager dependent = this.plugin.getDependentPluginsManager();
        if (dependent.isWorldGuardAvailable()
                && !(WGRegionAccessChecker.checkRegionAccess(where, WGRegionFlags.PLACE_CB, player)
                && WGRegionAccessChecker.checkRegionAccess(where, WGRegionFlags.BREAK_CB, player))) {
            return false;
        }
        if (dependent.isSuperiorSkyblockAvailable()
                && !(SSBIslandAccessChecker.checkIslandAccess(where, SSBIslandPrivileges.PLACE_CB, player)
                && SSBIslandAccessChecker.checkIslandAccess(where, SSBIslandPrivileges.BREAK_CB, player))) {
            return false;
        }
        AbstractCustomBlock definition = this.plugin.getCustomBlockService().getStorage().getAbstractCustomBlock(placed.modelName);
        CustomBlockPermissions permissions = definition == null ? null : definition.getPermissions();
        return permissions == null || (permissions.hasPermissions(player, CustomBlockPermissions.Type.PLACE)
                && permissions.hasPermissions(player, CustomBlockPermissions.Type.BREAK));
    }

    /**
     * Whether a click on this block opens (or closes) the player's gizmo: his gizmo is already on it, or he may edit it.
     * Otherwise the click is left to the usual handling, so that shift + click on a decoration out of his reach still
     * plays its animation.
     */
    private boolean opens(Player player, PlacedDisplay placed) {
        Gizmo current = this.sessions.get(player.getUniqueId());
        if (current != null && current.target() == placed) {
            return true;
        }
        // a block with a visible-if that keeps it from him cannot be selected
        if (!this.renderer.sees(player, placed) || !mayEditAt(player, placed, placed.base())) {
            return false;
        }
        if (placed.settings != null && placed.settings.locked && !player.hasPermission(ADMIN_PERMISSION)) {
            // an administrator keeps this one where he put it: the click is left to the usual handling
            say(player, "gizmo.locked");
            return false;
        }
        return true;
    }

    private void end(Player player) {
        Gizmo gizmo = this.sessions.remove(player.getUniqueId());
        if (gizmo != null) {
            gizmo.close();
        }
    }

    /**
     * Closes the gizmos pointed at a block that is no longer shown: broken, unloaded, or read again.
     */
    void forget(PlacedDisplay placed) {
        for (UUID uuid : new ArrayList<>(this.sessions.keySet())) {
            Gizmo gizmo = this.sessions.get(uuid);
            if (gizmo.target() == placed) {
                this.sessions.remove(uuid);
                gizmo.abandon();
            }
        }
    }

    void tick() {
        this.tick++;
        if (this.sessions.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Gizmo> entry : new ArrayList<>(this.sessions.entrySet())) {
            Gizmo gizmo = entry.getValue();
            String closing;
            try {
                closing = gizmo.tick(this.tick);
            } catch (RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "The gizmo of " + entry.getKey() + " failed", e);
                closing = "failed";
            }
            if (closing != null) {
                this.sessions.remove(entry.getKey());
                gizmo.close();
                Player player = this.plugin.getServer().getPlayer(entry.getKey());
                if (player != null && closing.equals("far")) {
                    say(player, "gizmo.too-far");
                }
            }
        }
    }

    // ---------- What is clicked ----------

    private PlacedDisplay placedOf(Entity entity) {
        if (!(entity instanceof Interaction) && !(entity instanceof Shulker)) {
            return null;
        }
        CustomBlockKey.DataHolder holder = CustomBlockKey.holder(entity);
        Location at = holder.getLocation();
        if (holder.getName() == null || at == null) {
            return null;
        }
        return this.renderer.find(entity.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    private boolean justToggled(Player player) {
        Integer at = this.toggled.get(player.getUniqueId());
        return at != null && this.tick - at <= 2;
    }

    private boolean mayEdit(Player player) {
        return (player.isSneaking() || this.sneaking.contains(player.getUniqueId())) && player.hasPermission(PERMISSION);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onToggleSneak(org.bukkit.event.player.PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            this.sneaking.add(event.getPlayer().getUniqueId());
        } else {
            this.sneaking.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        boolean editing = isEditing(player);
        if (event.getHand() != EquipmentSlot.HAND) {
            // the click of the other hand comes with the same click
            if (editing || justToggled(player)) {
                event.setCancelled(true);
            }
            return;
        }
        if (editing) {
            event.setCancelled(true);
            rightClick(player, placedOf(event.getRightClicked()));
            return;
        }
        if (mayEdit(player)) {
            PlacedDisplay placed = placedOf(event.getRightClicked());
            if (placed != null && opens(player, placed)) {
                event.setCancelled(true);
                toggle(player, placed);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        boolean editing = isEditing(player);
        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR;
        if (!editing && !justToggled(player) && !(rightClick && mayEdit(player))) {
            return;
        }
        Block block = event.getClickedBlock();
        if (editing) {
            // the click box around his eyes usually takes his clicks: these come when it missed them
            event.setCancelled(true);
            if ((event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) && event.getHand() == EquipmentSlot.HAND) {
                rightClick(player, block == null ? null : placedAt(block));
            }
            return;
        }
        if (rightClick && event.getHand() == EquipmentSlot.HAND && mayEdit(player)) {
            ItemStack held = event.getItem();
            boolean emptyHand = held == null || held.getType().isAir();
            PlacedDisplay placed = null;
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null && emptyHand) {
                placed = placedAt(block);
            }
            // a decoration without a click box: the one whose drawing the line of sight goes through before the block
            // clicked (a click in the air only reaches the server with something in hand)
            if (placed == null && (emptyHand || event.getAction() == Action.RIGHT_CLICK_AIR)) {
                placed = aimedDrawn(player, block == null ? Double.MAX_VALUE : distanceToBlock(player), false);
            }
            if (placed != null && opens(player, placed)) {
                event.setCancelled(true);
                toggle(player, placed);
                return;
            }
        }
        if (justToggled(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * The placed block whose central block this is, if it is shown through packets.
     */
    private PlacedDisplay placedAt(Block block) {
        if (!new CustomBlockData(block, this.plugin).has(CustomBlockKey.NAME)) {
            return null;
        }
        return this.renderer.find(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    /**
     * A right click while the gizmo is open. With shift held, it closes the gizmo wherever the player aims, or moves
     * it to the decoration he aims at; without, the part he aims at is put back as it was placed.
     *
     * @param clicked the decoration the click reached, when it reached one
     */
    private void rightClick(Player player, PlacedDisplay clicked) {
        Gizmo gizmo = this.sessions.get(player.getUniqueId());
        if (gizmo == null || justToggled(player)) {
            return;
        }
        if (player.isSneaking() || this.sneaking.contains(player.getUniqueId())) {
            PlacedDisplay other = clicked != null ? clicked : aimed(player);
            if (other == null) {
                other = aimedDrawn(player, distanceToBlock(player), false);
            }
            toggle(player, other != null && other != gizmo.target() && player.hasPermission(PERMISSION) && opens(player, other) ? other : gizmo.target());
            return;
        }
        gizmo.rightClick(this.tick);
    }

    /**
     * The block a player works on: the decoration he aims at (its click box, its central block, or what it draws),
     * else the one his gizmo is open on, or null.
     */
    @org.jetbrains.annotations.Nullable
    PlacedDisplay targetOf(Player player) {
        PlacedDisplay placed = aimed(player);
        if (placed == null) {
            org.bukkit.block.Block block = player.getTargetBlockExact(6);
            if (block != null) {
                placed = placedAt(block);
            }
        }
        if (placed == null) {
            // what it draws, even where its click box does not reach (one drawn above it, or none)
            placed = aimedDrawn(player, distanceToBlock(player), true);
        }
        Gizmo gizmo = this.sessions.get(player.getUniqueId());
        return placed == null && gizmo != null ? gizmo.target() : placed;
    }

    /**
     * The decoration whose click box the player aims at, within his reach.
     */
    private PlacedDisplay aimed(Player player) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.RayTraceResult hit = player.getWorld().rayTraceEntities(eye, eye.getDirection(), 6, 0,
                entity -> entity instanceof Interaction && placedOf(entity) != null);
        return hit == null || hit.getHitEntity() == null ? null : placedOf(hit.getHitEntity());
    }

    /**
     * How far a decoration without a click box can be picked by what it draws.
     */
    private static final double DRAWN_REACH = 8;

    /**
     * The decoration without a click box whose drawing the player aims at: the nearest one his line of sight enters,
     * closer than a distance (the block he clicked) and within {@link #DRAWN_REACH}.
     *
     * @param any whether a decoration with a click box counts too
     */
    private PlacedDisplay aimedDrawn(Player player, double before, boolean any) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector look = eye.getDirection();
        org.joml.Vector3d from = new org.joml.Vector3d(eye.getX(), eye.getY(), eye.getZ());
        org.joml.Vector3d direction = new org.joml.Vector3d(look.getX(), look.getY(), look.getZ()).normalize();
        PlacedDisplay best = null;
        double nearest = Math.min(before, DRAWN_REACH);
        for (PlacedDisplay placed : this.renderer.shownTo(player)) {
            if (placed.model == null || !any && placed.clickable() || placed.world != player.getWorld()) {
                continue;
            }
            double at = placed.reach(from, direction);
            if (at >= 0 && at < nearest) {
                nearest = at;
                best = placed;
            }
        }
        return best;
    }

    /**
     * How far the player's line of sight goes before it meets a block, within {@link #DRAWN_REACH}.
     */
    private static double distanceToBlock(Player player) {
        org.bukkit.util.RayTraceResult hit = player.rayTraceBlocks(DRAWN_REACH);
        return hit == null ? Double.MAX_VALUE : hit.getHitPosition().distance(player.getEyeLocation().toVector());
    }

    /**
     * Takes the clicks on the entities of the gizmo, which exist on its player's client only. The event is Paper's:
     * on a server without it, a right click with an empty hand aiming at nothing does not reach the gizmo.
     */
    private void listenToUnknownEntities() {
        Class<? extends org.bukkit.event.Event> type;
        java.lang.reflect.Method entityId;
        java.lang.reflect.Method attack;
        try {
            type = Class.forName("com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent").asSubclass(org.bukkit.event.Event.class);
            entityId = type.getMethod("getEntityId");
            attack = type.getMethod("isAttack");
        } catch (ReflectiveOperationException e) {
            return;
        }
        this.plugin.getServer().getPluginManager().registerEvent(type, this, EventPriority.NORMAL, (listener, event) -> {
            if (this.sessions.isEmpty() || !type.isInstance(event)) {
                return;
            }
            Player player = ((org.bukkit.event.player.PlayerEvent) event).getPlayer();
            Gizmo gizmo = this.sessions.get(player.getUniqueId());
            try {
                // a left click is the arm swing that comes with it
                if (gizmo != null && !(boolean) attack.invoke(event) && gizmo.owns((int) entityId.invoke(event))) {
                    rightClick(player, null);
                }
            } catch (ReflectiveOperationException e) {
                this.plugin.getLogger().log(Level.WARNING, "Could not read a click on the gizmo", e);
            }
        }, this.plugin, false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwing(PlayerAnimationEvent event) {
        if (this.sessions.isEmpty() || event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Gizmo gizmo = this.sessions.get(event.getPlayer().getUniqueId());
        if (gizmo != null) {
            gizmo.click(this.tick);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onScroll(PlayerItemHeldEvent event) {
        if (this.sessions.isEmpty()) {
            return;
        }
        Gizmo gizmo = this.sessions.get(event.getPlayer().getUniqueId());
        if (gizmo == null || !gizmo.isDragging()) {
            return;
        }
        int difference = event.getNewSlot() - event.getPreviousSlot();
        event.setCancelled(true);
        if (difference == 1 || difference == -8) {
            gizmo.scroll(true);
        } else if (difference == -1 || difference == 8) {
            gizmo.scroll(false);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Gizmo gizmo = this.sessions.get(event.getPlayer().getUniqueId());
        if (gizmo != null) {
            event.setCancelled(true);
            if (event.getPlayer().isSneaking() || this.sneaking.contains(event.getPlayer().getUniqueId())) {
                gizmo.toggleSnapping();
            } else {
                gizmo.toggleSpace();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isEditing(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (isEditing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (isEditing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        end(event.getPlayer());
        this.toggled.remove(event.getPlayer().getUniqueId());
        this.sneaking.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        end(event.getPlayer());
    }
}
