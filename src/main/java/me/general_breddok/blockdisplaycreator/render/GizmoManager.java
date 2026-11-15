package me.general_breddok.blockdisplaycreator.render;

import com.jeff_media.customblockdata.CustomBlockData;
import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
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
 * Lets operators move custom blocks shown through packets: shift + right click on a block shows or hides its
 * {@link Gizmo}, and while it is shown nothing else the operator clicks has an effect in the world.
 */
public final class GizmoManager implements Listener {

    /**
     * The permission to move custom blocks; operators have it.
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
                return;
            }
        }
        if (placed.model == null || placed.model.elements.isEmpty()) {
            return;
        }
        try {
            Gizmo gizmo = new Gizmo(this.renderer, this.nms, player, placed);
            this.sessions.put(player.getUniqueId(), gizmo);
            gizmo.status();
            player.sendMessage(ChatColor.GOLD + "Gizmo on: aim at a part (it lights up), left click to take it, left click again to drop it. "
                    + "Shift + right click the decoration to close. Scroll changes the step while a part is held, Shift + F (or Q) turns snapping on or off, F switches local and world axes.");
        } catch (RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Could not open the gizmo of custom block " + placed.modelName, e);
            player.sendMessage(ChatColor.RED + "The gizmo could not be opened, see the console.");
        }
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
            boolean keep;
            try {
                keep = gizmo.tick(this.tick);
            } catch (RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "The gizmo of " + entry.getKey() + " failed", e);
                keep = false;
            }
            if (!keep) {
                this.sessions.remove(entry.getKey());
                gizmo.close();
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
            if (placed != null) {
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
        if (!editing && !justToggled(player) && !(mayEdit(player) && event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Block block = event.getClickedBlock();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getHand() == EquipmentSlot.HAND && block != null && mayEdit(player)) {
            ItemStack held = event.getItem();
            boolean empty = held == null || held.getType().isAir();
            if ((editing || empty) && new CustomBlockData(block, this.plugin).has(CustomBlockKey.NAME)) {
                PlacedDisplay placed = this.renderer.find(block.getWorld(), block.getX(), block.getY(), block.getZ());
                if (placed != null) {
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
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Gizmo gizmo = this.sessions.get(event.getPlayer().getUniqueId());
        if (gizmo != null) {
            gizmo.click(this.tick);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onScroll(PlayerItemHeldEvent event) {
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
            gizmo.toggleSnapping();
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
