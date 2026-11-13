package me.general_breddok.blockdisplaycreator.listener.skyblock.superior;

import com.bgsoftware.superiorskyblock.api.events.IslandChunkResetEvent;
import me.general_breddok.blockdisplaycreator.render.DisplayRenderer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * SuperiorSkyblock2 empties the chunks of a deleted island of their blocks and entities, not of the data plugins
 * keep in them: the custom blocks shown through packets are told apart, or they would outlive the island.
 */
public class SuperiorSkyblockChunkResetListener implements Listener {

    private final DisplayRenderer renderer;

    public SuperiorSkyblockChunkResetListener(DisplayRenderer renderer) {
        this.renderer = renderer;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onIslandChunkReset(IslandChunkResetEvent event) {
        this.renderer.resetChunk(event.getWorld(), event.getChunkX(), event.getChunkZ());
    }
}
