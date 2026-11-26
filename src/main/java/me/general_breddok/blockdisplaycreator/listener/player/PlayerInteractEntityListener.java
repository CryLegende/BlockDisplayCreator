package me.general_breddok.blockdisplaycreator.listener.player;

import me.general_breddok.blockdisplaycreator.render.DisplayRenderer;
import com.google.common.base.Preconditions;
import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.common.BDCDependentPluginsManager;
import me.general_breddok.blockdisplaycreator.custom.ConfiguredInteraction;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlock;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockKey;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockPermissions;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockService;
import me.general_breddok.blockdisplaycreator.event.custom.block.CustomBlockInteractEvent;
import me.general_breddok.blockdisplaycreator.file.config.value.BooleanConfigValue;
import me.general_breddok.blockdisplaycreator.file.config.value.StringMessagesValue;
import me.general_breddok.blockdisplaycreator.permission.DefaultPermissions;
import me.general_breddok.blockdisplaycreator.placeholder.universal.CustomBlockPlaceholder;
import me.general_breddok.blockdisplaycreator.placeholder.universal.InteractionPlaceholder;
import me.general_breddok.blockdisplaycreator.service.ServiceManager;
import me.general_breddok.blockdisplaycreator.util.ChatUtil;
import me.general_breddok.blockdisplaycreator.util.EventUtil;
import me.general_breddok.blockdisplaycreator.world.guard.WGRegionAccessChecker;
import me.general_breddok.blockdisplaycreator.world.guard.WGRegionFlags;
import me.general_breddok.blockdisplaycreator.world.skyblock.superior.SSBIslandAccessChecker;
import me.general_breddok.blockdisplaycreator.world.skyblock.superior.SSBIslandPrivileges;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.function.Predicate;

public class PlayerInteractEntityListener implements Listener {

    private final ServiceManager<String, CustomBlockService> serviceManager;

    public PlayerInteractEntityListener(ServiceManager<String, CustomBlockService> serviceManager) {
        this.serviceManager = serviceManager;
    }

    /*
     * LOW: a right click on the click box of a custom block is decided here, before the generic protections
     * (WorldGuard reads such a click as its "interact" flag at NORMAL, so a spawn region denying interact
     * would swallow every click). The custom block's own rules apply instead: the interact-cb region flag,
     * the INTERACT_CB island privilege and the permissions of the block file. Allowed or refused, the click
     * is cancelled, so no other protection acts on (or answers) a click on a custom block.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlayerInteractAtEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();

        Interaction interaction;
        if (event.getRightClicked() instanceof Interaction clickBox) {
            interaction = clickBox;
        } else if (event.getRightClicked() instanceof Shulker collision && CustomBlockKey.holder(collision).getName() != null) {
            // the collision of a custom block (an invisible shulker) often stands in front of its click box, on a big
            // model above all: a right click on it is the click of the block, on its nearest click box. The client sends
            // a click on a shulker for each hand: only the main hand one counts, or the block would be clicked twice
            event.setCancelled(true);
            if (hand != EquipmentSlot.HAND)
                return;
            interaction = clickBoxOf(collision);
            if (interaction == null)
                return;
        } else {
            return;
        }

        String blockName = CustomBlockKey.holder(interaction).getName();

        if (blockName == null)
            return;

        // a block whose display.visible-if keeps it from this player: he does not see it, his click does nothing
        DisplayRenderer renderer = BlockDisplayCreator.getInstance().getDisplayRenderer();
        if (renderer != null && renderer.hides(player, CustomBlockKey.holder(interaction).getLocation())) {
            event.setCancelled(true);
            return;
        }

        // an animation a command plays for this player alone still runs on this block (a flight, a reveal, the click
        // animation itself): his clicks on it wait for its end instead of cutting it short and running their commands
        if (renderer != null && renderer.playsFor(player, CustomBlockKey.holder(interaction).getLocation())) {
            event.setCancelled(true);
            return;
        }

        if (!BooleanConfigValue.BLOCKS_INTERACTION) {
            if (!player.hasPermission(DefaultPermissions.BDC.CustomBlock.BLOCKS_INTERACTION)) {
                event.setCancelled(true);
                ChatUtil.sendMessage(player, StringMessagesValue.PERMISSION_DENIED_INTERACT);
                return;
            }
        }

        CustomBlockService customBlockService = CustomBlockService.getService(serviceManager, interaction);

        CustomBlock customBlock = customBlockService.getCustomBlock(interaction);

        if (customBlock == null) {
            ChatUtil.sendMessage(player, StringMessagesValue.CUSTOM_BLOCK_NOT_FOUND.replace("%customblock_name%", blockName));
            return;
        }

        if (!checkAccess(customBlock, player)) {
            event.setCancelled(true);
            return;
        }

        ConfiguredInteraction configuredInteraction = customBlock.getConfiguredInteraction(interaction);

        // what an administrator set on this block alone: its own commands, who may click, how often
        me.general_breddok.blockdisplaycreator.render.BlockSettings settings = renderer == null ? null : renderer.settingsAt(customBlock.getLocation());
        if (settings != null && settings.handlesClicks()) {
            event.setCancelled(true);
            if (hand != EquipmentSlot.HAND) {
                return;
            }
            if (!settings.allows(player)) {
                ChatUtil.sendMessage(player, StringMessagesValue.PLACED_DENIED_INTERACT);
                return;
            }
            if (configuredInteraction != null && !EventUtil.call(new CustomBlockInteractEvent(customBlock, player, interaction, configuredInteraction, hand))) {
                return;
            }
            if (!settings.passCooldown(player, customBlock.getLocation())) {
                return;
            }
            if (configuredInteraction != null && settings.runsModelCommands()) {
                customBlock.handleClick(configuredInteraction, interaction, player, new CustomBlockPlaceholder(customBlock), new InteractionPlaceholder(interaction));
            }
            settings.run(player, new CustomBlockPlaceholder(customBlock), new InteractionPlaceholder(interaction));
            if (settings.playsClickAnimation()) {
                // with the player: a greeting his coming close set off ends, as on a block without settings
                renderer.clicked(customBlock.getLocation().getBlock(), configuredInteraction == null ? null : configuredInteraction.getIdentifier(), player);
            }
            return;
        }

        if (configuredInteraction == null) {
            return;
        }

        CustomBlockInteractEvent customBlockInteractEvent = new CustomBlockInteractEvent(customBlock, player, interaction, configuredInteraction, hand);

        if (!EventUtil.call(customBlockInteractEvent)) {
            return;
        }

        event.setCancelled(true);

        customBlock.handleClick(configuredInteraction, interaction, player, new CustomBlockPlaceholder(customBlock), new InteractionPlaceholder(interaction));

        if (renderer != null) {
            renderer.clicked(customBlock.getLocation().getBlock(), configuredInteraction.getIdentifier(), player);
        }


        /*
        RayTraceResult rayTraceResult = world.rayTraceEntities(
                eyeLocation,
                direction,
                6,
                entity -> !entity.getUniqueId().equals(player.getUniqueId())
        );

        ChatUtil.sendMessage(player, "Is rayTraceResult null? - " + (rayTraceResult == null));

        if (rayTraceResult == null) {
            return;
        }

        Vector hitPosition = rayTraceResult.getHitPosition();

        BlockFace blockFace = determineEntityFace(interaction, hitPosition);

        ChatUtil.sendMessage(player, "&1hitPosition is " + hitPosition);
        ChatUtil.sendMessage(player, "&2blockFace is " + blockFace);
        ChatUtil.sendMessage(player, "&3hitEntity is " + rayTraceResult.getHitEntity().getType());
        ChatUtil.sendMessage(player, "&4BoundingBox: " + interaction.getBoundingBox());


        PlayerInteractEvent playerInteractEvent = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItem(hand), world.getBlockAt(hitPosition.getBlockX(), hitPosition.getBlockY(), hitPosition.getBlockZ()), blockFace, hand);
        EventUtil.call(playerInteractEvent);*/
    }

    /**
     * The click box of the custom block a collision belongs to that is nearest to it, or null when it has none loaded.
     */
    @Nullable
    private Interaction clickBoxOf(Shulker collision) {
        CustomBlock customBlock = CustomBlockService.getService(serviceManager, collision).getCustomBlock(collision);
        if (customBlock == null || customBlock.getInteractions() == null)
            return null;
        Vector at = collision.getBoundingBox().getCenter();
        Interaction nearest = null;
        double best = Double.MAX_VALUE;
        for (Interaction candidate : customBlock.getInteractions()) {
            if (candidate == null || !candidate.isValid())
                continue;
            double distance = candidate.getBoundingBox().getCenter().distanceSquared(at);
            if (distance < best) {
                best = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private static boolean checkAccess(CustomBlock customBlock, Player player) {
        BDCDependentPluginsManager dependentPluginManager = BlockDisplayCreator.getInstance().getDependentPluginsManager();
        Location customBlockLocation = customBlock.getLocation();

        if (dependentPluginManager.isWorldGuardAvailable()) {
            if (!WGRegionAccessChecker.checkRegionAccess(customBlockLocation, WGRegionFlags.INTERACT_CB, player)) {
                ChatUtil.sendPlaceholderMessage(player, StringMessagesValue.REGION_DENIED_INTERACT);
                return false;
            }
        }

        if (dependentPluginManager.isSuperiorSkyblockAvailable()) {
            if (!SSBIslandAccessChecker.checkIslandAccess(customBlockLocation, SSBIslandPrivileges.INTERACT_CB, player)) {
                ChatUtil.sendPlaceholderMessage(player, StringMessagesValue.ISLAND_DENIED_INTERACT);
                return false;
            }
        }


        CustomBlockPermissions permissions = customBlock.getPermissions();

        if (permissions != null && !permissions.hasPermissions(player, CustomBlockPermissions.Type.INTERACT)) {
            ChatUtil.sendPlaceholderMessage(player, StringMessagesValue.PERMISSION_DENIED_INTERACT);
            return false;
        }

        return true;
    }

    public BlockFace determineEntityFace(Interaction entity, Vector hitPos) {
        BoundingBox box = entity.getBoundingBox();

        double dx = hitPos.getX() - box.getCenterX();
        double dy = hitPos.getY() - box.getCenterY();
        double dz = hitPos.getZ() - box.getCenterZ();

        double absDx = Math.abs(dx);
        double absDy = Math.abs(dy);
        double absDz = Math.abs(dz);

        if (absDy > absDx && absDy > absDz) {
            return dy > 0 ? BlockFace.UP : BlockFace.DOWN;
        } else if (absDx > absDz) {
            return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        } else {
            return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
        }
    }

    private RayTraceResult rayTraceEntities(World world, Location start, Vector direction, double maxDistance, double raySize, Predicate<Entity> filter) {
        Preconditions.checkArgument(start != null, "Location start cannot be null");
        Preconditions.checkArgument(world.equals(start.getWorld()), "Location start cannot be in a different world");
        start.checkFinite();

        Preconditions.checkArgument(direction != null, "Vector direction cannot be null");
        direction.checkFinite();

        Preconditions.checkArgument(direction.lengthSquared() > 0, "Direction's magnitude (%s) need to be greater than 0", direction.lengthSquared());

        if (maxDistance < 0.0D) {
            return null;
        }

        Vector startPos = start.toVector();
        Vector dir = direction.clone().normalize().multiply(maxDistance);
        BoundingBox aabb = BoundingBox.of(startPos, startPos).expandDirectional(dir).expand(raySize);
        Collection<Entity> entities = world.getNearbyEntities(aabb, filter);

        Entity nearestHitEntity = null;
        RayTraceResult nearestHitResult = null;
        double nearestDistanceSq = Double.MAX_VALUE;

        ChatUtil.log("1");
        for (Entity entity : entities) {
            BoundingBox boundingBox = entity.getBoundingBox().expand(raySize);
            RayTraceResult hitResult = boundingBox.rayTrace(startPos, direction, maxDistance);
            ChatUtil.log("2");
            if (hitResult != null) {
                double distanceSq = startPos.distanceSquared(hitResult.getHitPosition());
                ChatUtil.log("3");
                if (distanceSq < nearestDistanceSq) {
                    ChatUtil.log("4");
                    nearestHitEntity = entity;
                    nearestHitResult = hitResult;
                    nearestDistanceSq = distanceSq;
                }
            }
        }

        return (nearestHitEntity == null) ? null : new RayTraceResult(nearestHitResult.getHitPosition(), nearestHitEntity, nearestHitResult.getHitBlockFace());
    }
}
