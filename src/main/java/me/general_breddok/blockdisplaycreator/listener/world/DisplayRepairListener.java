package me.general_breddok.blockdisplaycreator.listener.world;

import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.commandparser.CommandLine;
import me.general_breddok.blockdisplaycreator.commandparser.LegacyDisplayNbt;
import me.general_breddok.blockdisplaycreator.custom.AutomaticCommandDisplaySummoner;
import com.jeff_media.customblockdata.CustomBlockData;
import me.general_breddok.blockdisplaycreator.custom.block.AbstractCustomBlock;
import me.general_breddok.blockdisplaycreator.custom.block.BDCCustomBlockService;
import java.util.concurrent.atomic.AtomicInteger;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Repairs custom blocks placed while their summon command was still written for an older Minecraft version.
 * <p>
 * On Minecraft 26 the old {@code block_state} form is dropped at summon, so every block display of such a block
 * was saved holding air, and its text displays show their JSON source. When the entities of a chunk load, each
 * custom block found is compared with its definition, passenger by passenger, and the missing blocks and texts are
 * put back. A block that is already complete costs one pass over its passengers and changes nothing.
 */
public class DisplayRepairListener implements Listener {

    private final BlockDisplayCreator plugin;
    private final Map<String, List<List<LegacyDisplayNbt.Passenger>>> models = new ConcurrentHashMap<>();
    private final Set<String> unknownBlocks = ConcurrentHashMap.newKeySet();
    private final Map<String, String> signatures = new ConcurrentHashMap<>();
    private final AtomicInteger refreshed = new AtomicInteger();
    private final boolean repairDisplays;

    public DisplayRepairListener(BlockDisplayCreator plugin) {
        this.plugin = plugin;
        this.repairDisplays = LegacyDisplayNbt.serverNeedsModernNbt();
    }

    /**
     * Repairs the custom blocks whose entities were loaded before this listener existed.
     */
    public void repairLoaded() {
        for (World world : Bukkit.getWorlds()) {
            repairAll(world.getEntitiesByClass(Display.class));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        repairAll(event.getEntities());
    }

    private void repairAll(Iterable<? extends Entity> entities) {
        for (Entity entity : entities) {
            if (entity instanceof Display root && root.getVehicle() == null && !root.getPassengers().isEmpty()) {
                try {
                    repair(root);
                } catch (RuntimeException e) {
                    this.plugin.getLogger().log(Level.WARNING, "Could not check the custom block at " + describe(root), e);
                }
            }
        }
    }

    private void repair(Display root) {
        String name = root.getPersistentDataContainer().get(CustomBlockKey.NAME, PersistentDataType.STRING);
        if (name == null) {
            return;
        }
        checkHitbox(root, name);
        if (!this.repairDisplays) {
            return;
        }
        List<Entity> passengers = root.getPassengers();
        for (List<LegacyDisplayNbt.Passenger> expected : this.models.computeIfAbsent(name, this::model)) {
            if (!matches(expected, passengers)) {
                continue;
            }
            int repaired = 0;
            for (int i = 0; i < expected.size(); i++) {
                Entity passenger = passengers.get(i);
                if (passenger instanceof BlockDisplay display) {
                    repaired += repairBlock(display, expected.get(i).block()) ? 1 : 0;
                } else if (passenger instanceof TextDisplay display) {
                    repaired += repairText(display) ? 1 : 0;
                }
            }
            if (repaired > 0) {
                this.plugin.getLogger().info("Repaired " + repaired + " display(s) of the custom block " + name
                        + " at " + describe(root));
            }
            return;
        }
    }

    /**
     * Compares the interactions and collisions the block was placed with to the ones its file describes now, and
     * has them replaced when they differ. Entities are still being added to the world while a chunk loads: the
     * replacement waits for the next tick.
     */
    private void checkHitbox(Display root, String name) {
        Location location = CustomBlockKey.holder(root).getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        String expected = this.signatures.computeIfAbsent(name, this::signature);
        if (expected.isEmpty()) {
            return;
        }
        CustomBlockData data = new CustomBlockData(location.getBlock(), this.plugin);
        if (!data.has(CustomBlockKey.NAME) || expected.equals(data.get(CustomBlockKey.HITBOX, PersistentDataType.STRING))) {
            return;
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)
                    || !(this.plugin.getCustomBlockService() instanceof BDCCustomBlockService service)) {
                return;
            }
            try {
                if (service.refreshHitbox(location) && this.refreshed.getAndIncrement() == 0) {
                    // one line for a whole batch of chunks rather than one per block
                    Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.plugin.getLogger().info(
                            "Refreshed the hitbox of " + this.refreshed.getAndSet(0) + " placed custom block(s) from their files"), 200L);
                }
            } catch (RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "Could not refresh the hitbox of the custom block at " + describe(root), e);
            }
        });
    }

    private String signature(String name) {
        AbstractCustomBlock definition = this.plugin.getCustomBlockService().getStorage().getAbstractCustomBlock(name);
        return definition == null ? "" : BDCCustomBlockService.hitboxSignature(definition);
    }

    /**
     * Forgets the parsed models: called when the custom block files are read again.
     */
    public void invalidate() {
        this.models.clear();
        this.signatures.clear();
    }

    /**
     * The passengers each summon command of a custom block spawns. Read once per block name: the storage hands
     * out a full copy of the definition at every lookup.
     */
    private List<List<LegacyDisplayNbt.Passenger>> model(String name) {
        AbstractCustomBlock definition = this.plugin.getCustomBlockService().getStorage().getAbstractCustomBlock(name);
        List<List<LegacyDisplayNbt.Passenger>> model = new ArrayList<>();
        if (definition != null && definition.getDisplaySummoner() instanceof AutomaticCommandDisplaySummoner summoner
                && summoner.getCommands() != null) {
            for (CommandLine command : summoner.getCommands()) {
                model.add(LegacyDisplayNbt.passengers(command.toString()));
            }
        }
        return model;
    }

    /**
     * A model made of several summon commands has several roots: the command of a root is the one listing the same
     * kinds of displays in the same order.
     */
    private boolean matches(List<LegacyDisplayNbt.Passenger> expected, List<Entity> passengers) {
        if (expected.isEmpty() || expected.size() != passengers.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            String kind = expected.get(i).kind();
            Entity passenger = passengers.get(i);
            boolean same = kind != null && switch (kind) {
                case "block" -> passenger instanceof BlockDisplay;
                case "text" -> passenger instanceof TextDisplay;
                default -> passenger instanceof org.bukkit.entity.ItemDisplay;
            };
            if (!same) {
                return false;
            }
        }
        return true;
    }

    private boolean repairBlock(BlockDisplay display, String block) {
        if (block == null || !display.getBlock().getMaterial().isAir()) {
            return false;
        }
        BlockData data;
        try {
            data = Bukkit.createBlockData(block);
        } catch (IllegalArgumentException e) {
            if (this.unknownBlocks.add(block)) {
                this.plugin.getLogger().warning("Block '" + block + "' of a custom block is not known to this server: its display stays empty");
            }
            return false;
        }
        if (data.getMaterial().isAir()) {
            return false;
        }
        display.setBlock(data);
        return true;
    }

    /**
     * A text display summoned with the old form holds its JSON source as plain text: the component it describes
     * is parsed and set through the server's own Adventure classes, which keeps the font and the colours.
     */
    private boolean repairText(TextDisplay display) {
        String raw = display.getText();
        if (raw == null) {
            return false;
        }
        raw = raw.trim();
        if (!(raw.startsWith("[{\"") && raw.endsWith("]")) && !(raw.startsWith("{\"") && raw.endsWith("}"))) {
            return false;
        }
        try {
            ClassLoader server = Bukkit.class.getClassLoader();
            Class<?> componentClass = server.loadClass("net.kyori.adventure.text.Component");
            Class<?> serializerClass = server.loadClass("net.kyori.adventure.text.serializer.gson.GsonComponentSerializer");
            Object serializer = serializerClass.getMethod("gson").invoke(null);
            Method deserialize = server.loadClass("net.kyori.adventure.text.serializer.ComponentSerializer")
                    .getMethod("deserialize", Object.class);
            Object component = deserialize.invoke(serializer, raw);
            TextDisplay.class.getMethod("text", componentClass).invoke(display, component);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private String describe(Entity entity) {
        Location location = entity.getLocation();
        return location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ() + " in " + entity.getWorld().getName();
    }
}
