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
import org.bukkit.event.player.PlayerDropItemEvent;
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

    GizmoManager(BlockDisplayCreator plugin, DisplayRenderer renderer, NmsBridge nms) {
        this.plugin = plugin;
        this.renderer = renderer;
        this.nms = nms;
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
                say(player, 'e', "Gizmo &efermé&7.");
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
            say(player, 'e', "Gizmo &eactivé&7 : vise une pièce, &eclic gauche&7 pour la prendre, de nouveau pour la poser.");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7Molette &8: &epas &8· &7Shift+F &8: &eaimantation &8· &7F &8: &eaxes &8· &7Q &8: &eannuler &8· &7cube blanc &8: &eéchelle (75 à 125 %) &8· &7Shift + clic droit &8: &efermer"));
        } catch (RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Could not open the gizmo of custom block " + placed.modelName, e);
            say(player, 'c', "Le gizmo &cn'a pas pu s'ouvrir&7.");
        }
    }

    /**
     * Sends a message in the house style: a prefix coloured by the kind of message (a success, e information,
     * 6 warning, c error), grey text, the words that matter in the colour.
     */
    void say(Player player, char kind, String text) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8&l« &" + kind + "&lSH &8&l» &7" + text));
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
        return (current != null && current.target() == placed) || mayEditAt(player, placed, placed.base());
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
                    say(player, '6', "Gizmo &6fermé&7 : tu t'es &6trop éloigné&7 du modèle.");
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
        if (mayEdit(player)) {
            PlacedDisplay placed = placedOf(event.getRightClicked());
            if (placed != null && opens(player, placed)) {
                event.setCancelled(true);
                toggle(player, placed);
                return;
            }
        }
        if (editing) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        boolean editing = isEditing(player);
        if (!editing && !justToggled(player) && !(event.getAction() == Action.RIGHT_CLICK_BLOCK && mayEdit(player))) {
            return;
        }
        Block block = event.getClickedBlock();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getHand() == EquipmentSlot.HAND && block != null && mayEdit(player)) {
            ItemStack held = event.getItem();
            boolean empty = held == null || held.getType().isAir();
            if ((editing || empty) && new CustomBlockData(block, this.plugin).has(CustomBlockKey.NAME)) {
                PlacedDisplay placed = this.renderer.find(block.getWorld(), block.getX(), block.getY(), block.getZ());
                if (placed != null && opens(player, placed)) {
                    event.setCancelled(true);
                    toggle(player, placed);
                    return;
                }
            }
        }
        if (editing || justToggled(player)) {
            event.setCancelled(true);
        }
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
    public void onDrop(PlayerDropItemEvent event) {
        Gizmo gizmo = this.sessions.get(event.getPlayer().getUniqueId());
        if (gizmo != null) {
            event.setCancelled(true);
            gizmo.undo();
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
