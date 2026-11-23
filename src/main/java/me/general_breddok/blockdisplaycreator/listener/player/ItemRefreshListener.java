package me.general_breddok.blockdisplaycreator.listener.player;

import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.custom.block.AbstractCustomBlock;
import me.general_breddok.blockdisplaycreator.custom.block.BDCCustomBlockConfigStorage;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockKey;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockStorage;
import me.general_breddok.blockdisplaycreator.file.config.value.BooleanConfigValue;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Objects;

/**
 * Custom block items follow their file: an item given before its file changed gets the name and lore of the file
 * back when its owner joins, and when a player opens a container block holding it. Items in menus of other plugins
 * (inventories with no block) are left alone, and so is a file whose name or lore holds a placeholder.
 */
public class ItemRefreshListener implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!BooleanConfigValue.ITEMS_FOLLOW_FILES) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(BlockDisplayCreator.getInstance(), () -> {
            if (player.isOnline()) {
                refresh(player.getInventory());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!BooleanConfigValue.ITEMS_FOLLOW_FILES) {
            return;
        }
        Inventory top = event.getInventory();
        if (top.getLocation() != null) {
            refresh(top);
        }
        refresh(event.getPlayer().getInventory());
    }

    private static void refresh(Inventory inventory) {
        CustomBlockStorage storage = BlockDisplayCreator.getInstance().getCustomBlockService().getStorage();
        if (!(storage instanceof BDCCustomBlockConfigStorage configStorage)) {
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            String name = CustomBlockKey.holder(meta).getName();
            if (name == null) {
                continue;
            }
            AbstractCustomBlock block = configStorage.peek(name);
            ItemMeta file = block == null || block.getItem() == null ? null : block.getItem().getItemMeta();
            if (file == null || hasPlaceholder(file)) {
                continue;
            }
            String fileName = file.hasDisplayName() ? file.getDisplayName() : null;
            List<String> fileLore = file.hasLore() ? file.getLore() : null;
            String itemName = meta.hasDisplayName() ? meta.getDisplayName() : null;
            List<String> itemLore = meta.hasLore() ? meta.getLore() : null;
            if (Objects.equals(fileName, itemName) && Objects.equals(fileLore, itemLore)) {
                continue;
            }
            meta.setDisplayName(fileName);
            meta.setLore(fileLore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }
    }

    private static boolean hasPlaceholder(ItemMeta meta) {
        if (meta.hasDisplayName() && meta.getDisplayName().indexOf('%') >= 0) {
            return true;
        }
        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                if (line.indexOf('%') >= 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
