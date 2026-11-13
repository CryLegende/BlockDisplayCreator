package me.general_breddok.blockdisplaycreator.listener.block;

import com.jeff_media.customblockdata.CustomBlockData;
import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockKey;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;

/**
 * Lets a custom block whose central block cannot be mined, a barrier for a full block, be broken by a left click
 * on that block.
 * <p>
 * Such a custom block needs no interaction around it to be broken: the faces of its central block stay free, and
 * other blocks can be placed against them. The break goes through a regular block break, so that protection
 * plugins have their say.
 */
public class BlockDamageListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        Block block = event.getBlock();
        if (block.getType().getHardness() >= 0) {
            return;
        }
        if (!new CustomBlockData(block, BlockDisplayCreator.getInstance()).has(CustomBlockKey.NAME)) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().breakBlock(block);
    }
}
