package me.general_breddok.blockdisplaycreator.listener.entity;

import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.common.BDCDependentPluginsManager;
import me.general_breddok.blockdisplaycreator.custom.block.BDCCustomBlockConfigStorage;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlock;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockKey;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockPermissions;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockService;
import me.general_breddok.blockdisplaycreator.custom.block.option.CustomBlockBreakOption;
import me.general_breddok.blockdisplaycreator.custom.block.option.CustomBlockOption;
import me.general_breddok.blockdisplaycreator.file.config.loader.CustomBlockConfigurationFile;
import me.general_breddok.blockdisplaycreator.file.config.value.BooleanConfigValue;
import me.general_breddok.blockdisplaycreator.file.config.value.StringMessagesValue;
import me.general_breddok.blockdisplaycreator.permission.DefaultPermissions;
import me.general_breddok.blockdisplaycreator.service.ServiceManager;
import me.general_breddok.blockdisplaycreator.util.ChatUtil;
import me.general_breddok.blockdisplaycreator.world.guard.WGRegionAccessChecker;
import me.general_breddok.blockdisplaycreator.world.guard.WGRegionFlags;
import me.general_breddok.blockdisplaycreator.world.skyblock.superior.SSBIslandAccessChecker;
import me.general_breddok.blockdisplaycreator.world.skyblock.superior.SSBIslandPrivileges;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;


public class EntityDamageByEntityListener implements Listener {

    private final ServiceManager<String, CustomBlockService> serviceManager;

    public EntityDamageByEntityListener(ServiceManager<String, CustomBlockService> serviceManager) {
        this.serviceManager = serviceManager;
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player))
            return;

        // an operator who has a gizmo open takes hold of its parts with his clicks: nothing is broken
        me.general_breddok.blockdisplaycreator.render.DisplayRenderer renderer = BlockDisplayCreator.getInstance().getDisplayRenderer();
        if (renderer != null && renderer.gizmos() != null && renderer.gizmos().isEditing(player)) {
            event.setCancelled(true);
            return;
        }

        if (player.getGameMode() == GameMode.ADVENTURE)
            return;

        Entity entity = event.getEntity();

        if (!(entity instanceof Interaction) && !(entity instanceof Shulker))
            return;

        String blockName = CustomBlockKey.holder(entity).getName();

        if (blockName == null)
            return;

        event.setCancelled(true);

        // a block whose display.visible-if keeps it from this player cannot be broken by him either
        if (renderer != null && renderer.hides(player, CustomBlockKey.holder(entity).getLocation()))
            return;

        // a file with "breakable: false" (a quest character, a decor players rely on) is never broken by a hit, not
        // even by an operator: /bdc custom-block break <x> <y> <z> removes it. Those allowed to break are told so.
        // the same for one placed block an administrator made unbreakable from its gizmo
        if (!breakable(blockName) || (renderer != null && renderer.protects(CustomBlockKey.holder(entity).getLocation()))) {
            if (BooleanConfigValue.BLOCKS_DESTRUCTION || player.hasPermission(DefaultPermissions.BDC.CustomBlock.BLOCKS_DESTRUCTION)) {
                Location at = CustomBlockKey.holder(entity).getLocation();
                if (at != null && at.getWorld() != null) {
                    ChatUtil.sendMessage(player, StringMessagesValue.PROTECTED_BREAK
                            .replace("%x%", String.valueOf(at.getBlockX())).replace("%y%", String.valueOf(at.getBlockY()))
                            .replace("%z%", String.valueOf(at.getBlockZ())).replace("%world%", at.getWorld().getName()));
                }
            }
            return;
        }

        if (!BooleanConfigValue.BLOCKS_DESTRUCTION) {
            if (!player.hasPermission(DefaultPermissions.BDC.CustomBlock.BLOCKS_DESTRUCTION)) {
                ChatUtil.sendMessage(player, StringMessagesValue.PERMISSION_DENIED_BREAK);
                return;
            }
        }

        CustomBlockService customBlockService = CustomBlockService.getService(serviceManager, entity);

        CustomBlock customBlock = customBlockService.getCustomBlock(entity);

        if (customBlock == null) {
            ChatUtil.sendMessage(player, StringMessagesValue.CUSTOM_BLOCK_NOT_FOUND.replace("%customblock_name%", blockName));
            return;
        }

        if (!checkAccess(player, customBlock)) {
            return;
        }

        CustomBlockOption[] options = new CustomBlockOption[5];

        if (!(player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)) {
            options[0] = CustomBlockBreakOption.DROP_ITEM;
        }

        try {
            customBlockService.breakBlock(customBlock, player, options);
        } catch (IllegalArgumentException ignore) {
        }
    }

    /**
     * Whether a hit may break the placed blocks of this file: false only when it says {@code breakable: false}.
     */
    public static boolean breakable(String name) {
        if (BlockDisplayCreator.getInstance().getCustomBlockService().getStorage() instanceof BDCCustomBlockConfigStorage storage
                && storage.getCustomBlockRepository() != null) {
            CustomBlockConfigurationFile file = storage.getCustomBlockRepository().getFile(name);
            return file == null || !Boolean.FALSE.equals(file.get("breakable"));
        }
        return true;
    }

    private static boolean checkAccess(Player player, CustomBlock customBlock) {
        BDCDependentPluginsManager dependentPluginManager = BlockDisplayCreator.getInstance().getDependentPluginsManager();
        Location customBlockLocation = customBlock.getLocation();

        if (dependentPluginManager.isWorldGuardAvailable()) {
            if (!WGRegionAccessChecker.checkRegionAccess(customBlockLocation, WGRegionFlags.BREAK_CB, player)) {
                ChatUtil.sendMessage(player, StringMessagesValue.REGION_DENIED_BREAK);
                return false;
            }
        }

        if (dependentPluginManager.isSuperiorSkyblockAvailable()) {
            if (!SSBIslandAccessChecker.checkIslandAccess(customBlockLocation, SSBIslandPrivileges.BREAK_CB, player)) {
                ChatUtil.sendMessage(player, StringMessagesValue.ISLAND_DENIED_BREAK);
                return false;
            }
        }

        CustomBlockPermissions permissions = customBlock.getPermissions();

        if (permissions != null) {
            if (!permissions.hasPermissions(player, CustomBlockPermissions.Type.BREAK)) {
                ChatUtil.sendMessage(player, StringMessagesValue.PERMISSION_DENIED_BREAK);
                return false;
            }
        }
        return true;
    }
}
