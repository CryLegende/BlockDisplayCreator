package me.general_breddok.blockdisplaycreator.listener.packet;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPickItemFromBlock;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPickItemFromEntity;
import com.jeff_media.customblockdata.CustomBlockData;
import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlock;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockKey;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockService;
import me.general_breddok.blockdisplaycreator.permission.DefaultPermissions;
import me.general_breddok.blockdisplaycreator.render.DisplayRenderer;
import me.general_breddok.blockdisplaycreator.util.EntityUtil;
import me.general_breddok.blockdisplaycreator.util.ItemUtil;
import me.general_breddok.blockdisplaycreator.util.LocationUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

public final class PacketReceiveListener {

    public static PacketListener get() {
        return new PacketListener() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                PacketTypeCommon packetType = event.getPacketType();

                CustomBlockService customBlockService = BlockDisplayCreator.getInstance().getCustomBlockService();

                if (packetType == PacketType.Play.Client.PICK_ITEM_FROM_ENTITY) {

                    WrapperPlayClientPickItemFromEntity pickItem = new WrapperPlayClientPickItemFromEntity(event);
                    Player player = event.getPlayer();

                    Entity entity = EntityUtil.getNearEntity(player.getLocation(), pickItem.getEntityId());

                    if (entity == null) {
                        return;
                    }

                    handleSync(event, () -> customBlockService.getCustomBlock(entity), entity.getPersistentDataContainer(),
                            () -> distance(player, entity.getBoundingBox()));

                } else if (packetType == PacketType.Play.Client.PICK_ITEM_FROM_BLOCK) {

                    WrapperPlayClientPickItemFromBlock pickItemFromBlock = new WrapperPlayClientPickItemFromBlock(event);
                    Player player = event.getPlayer();

                    Location blockLocation = LocationUtil.toLocation(pickItemFromBlock.getBlockPos(), player.getWorld());

                    handleSync(event, () -> customBlockService.getCustomBlock(blockLocation), new CustomBlockData(blockLocation.getBlock(), BlockDisplayCreator.getInstance()),
                            () -> distance(player, blockLocation.getBlock().getBoundingBox()));
                }
            }

            /**
             * @param distance how far from the player's eyes what he picked is: an administrator aiming at a model in
             *                 front of it takes that model's item instead
             */
            private void handleSync(PacketReceiveEvent event, Supplier<CustomBlock> blockSupplier, PersistentDataContainer blockContainer, DoubleSupplier distance) {
                if (blockContainer.has(CustomBlockKey.NAME, PersistentDataType.STRING)) {

                    event.setCancelled(true);
                    Bukkit.getScheduler().runTask(BlockDisplayCreator.getInstance(), () -> {
                        Player player = event.getPlayer();
                        DisplayRenderer renderer = BlockDisplayCreator.getInstance().getDisplayRenderer();
                        if (renderer != null && renderer.gizmos() != null && renderer.gizmos().pickAimed(player, distance.getAsDouble())) {
                            return;
                        }
                        CustomBlock block = blockSupplier.get();
                        selectCustomBlockItem(player, block);
                    });
                }
            }
        };
    }

    /**
     * How far from a player's eyes his line of sight enters a box, or how far its middle is when it misses it.
     */
    private static double distance(Player player, BoundingBox box) {
        Location eye = player.getEyeLocation();
        RayTraceResult hit = box.rayTrace(eye.toVector(), eye.getDirection(), 64);
        return hit != null ? hit.getHitPosition().distance(eye.toVector()) : box.getCenter().distance(eye.toVector());
    }

    private static void selectCustomBlockItem(Player player, CustomBlock block) {
        ItemStack blockItem = block.getItem();

        ItemUtil.selectItem(player, ItemUtil.customBlockItem(blockItem.getType(), block.getName()), blockItem, DefaultPermissions.BDC.QUICKSELECT_CREATIVE);
    }
}
