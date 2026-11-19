package me.general_breddok.blockdisplaycreator.render;

import com.jeff_media.customblockdata.CustomBlockData;
import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.custom.block.AbstractCustomBlock;
import me.general_breddok.blockdisplaycreator.custom.block.BDCCustomBlockConfigStorage;
import me.general_breddok.blockdisplaycreator.file.config.loader.CustomBlockConfigurationFile;
import me.general_breddok.blockdisplaycreator.custom.block.BDCCustomBlockService;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockKey;
import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockRotation;
import me.general_breddok.blockdisplaycreator.data.manager.PersistentDataTypes;
import me.general_breddok.blockdisplaycreator.file.config.value.BooleanConfigValue;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Shows the display entities of placed custom blocks to the players around them without the entities existing on
 * the server.
 * <p>
 * A custom block keeps its data in its chunk, with its interaction and collision entities. Its model, often dozens
 * of display entities, never changes once placed: instead of being stored, ticked and tracked by the server, it is
 * read once from the block's file and sent to each player who comes close, then removed when they leave.
 * <p>
 * Blocks placed with real display entities are converted when their chunk loads: the entities are removed and the
 * block is shown from its file. With {@code packet-display: false} the conversion runs the other way.
 */
public final class DisplayRenderer implements Listener {

    /**
     * Ticks between two checks of where the players are.
     */
    private static final int VIEWER_PERIOD = 5;
    /**
     * Time the chunks waiting to be read may take in one tick.
     */
    private static final long SCAN_BUDGET_NANOS = 8_000_000L;
    /**
     * Ticks before a model that could not be read is read again.
     */
    private static final int MODEL_RETRY_PERIOD = 600;
    /**
     * Ticks a display entity takes to glide to a position it is teleported to while an operator moves it.
     */
    private static final int GLIDE_TICKS = 2;
    private static final String REGISTRY_FILE = "packet-display-chunks.txt";
    private static final String RESET_MARK = ";reset";

    private final BlockDisplayCreator plugin;
    private final NmsBridge nms;
    /**
     * What operators move custom blocks with, or null on a server that cannot teleport display entities.
     */
    private GizmoManager gizmos;

    private final Map<UUID, Map<Long, List<PlacedDisplay>>> index = new ConcurrentHashMap<>();
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Map<String, DisplayModel> models = new HashMap<>();
    /**
     * The blocks whose model could not be read, with the tick from which it is tried again: a file may be missing
     * for a while, or the summon refused where it was first tried.
     */
    private final Map<String, Integer> unavailableModels = new HashMap<>();
    private final Set<String> reportedModels = new HashSet<>();
    private final Set<ChunkTask> retryChunks = new HashSet<>();
    private final Map<String, Integer> ranges = new HashMap<>();
    /**
     * The longest {@code display.view-distance} of the models read, in blocks: how far around them players look for
     * blocks to be sent.
     */
    private int farthest;
    private final Queue<ChunkTask> tasks = new ConcurrentLinkedQueue<>();
    /**
     * The blocks someone sees whose model has an animation playing in a loop.
     */
    private final Set<PlacedDisplay> looping = new HashSet<>();
    /**
     * The blocks with an animation started by a click or their placement that has not ended.
     */
    private final Set<PlacedDisplay> playing = new HashSet<>();
    /**
     * By player, the animations {@code /bdc animation play} plays for them alone.
     */
    private final Map<UUID, List<PrivatePlay>> privatePlays = new HashMap<>();
    /**
     * The chunks holding custom blocks shown through packets, kept on disk: a chunk wiped while it is not loaded
     * (value true) is cleaned of them when it next loads.
     */
    private final Map<String, Boolean> registry = new ConcurrentHashMap<>();
    private volatile boolean registryDirty;

    private int tick;
    private int converted;
    private int moved;
    private int restored;
    private int hitboxes;
    private boolean summaryScheduled;

    /**
     * A player and the custom blocks their client currently draws.
     */
    static final class Viewer {
        final Player player;
        final Set<PlacedDisplay> shown = new HashSet<>();
        UUID world;
        int chunkX;
        int chunkZ;
        boolean stale = true;

        Viewer(Player player) {
            this.player = player;
        }
    }

    private record ChunkTask(UUID world, int x, int z, boolean unload) {
    }

    /**
     * An animation of a block played for one player: the others keep seeing the block as it is.
     */
    private static final class PrivatePlay {
        final PlacedDisplay placed;
        final int animation;
        final int startedAt;
        /**
         * The last frame played, -1 before the first.
         */
        int lastFrame = -1;

        PrivatePlay(PlacedDisplay placed, int animation, int startedAt) {
            this.placed = placed;
            this.animation = animation;
            this.startedAt = startedAt;
        }
    }

    /**
     * What became of a request to play an animation.
     */
    public enum PlayResult {
        PLAYED,
        /**
         * No custom block shown through packets at that position.
         */
        NO_BLOCK,
        /**
         * Its model has no animation of that name.
         */
        NO_ANIMATION,
        /**
         * The animation plays in a loop, for everyone, all the time.
         */
        LOOPING
    }

    private DisplayRenderer(BlockDisplayCreator plugin, NmsBridge nms) {
        this.plugin = plugin;
        this.nms = nms;
        loadRegistry();
    }

    /**
     * @return the renderer, or null on a server that does not expose what it needs: custom blocks then keep their
     * real display entities
     */
    @Nullable
    public static DisplayRenderer create(BlockDisplayCreator plugin) {
        try {
            DisplayRenderer renderer = new DisplayRenderer(plugin, NmsBridge.create());
            Bukkit.getPluginManager().registerEvents(renderer, plugin);
            Bukkit.getScheduler().runTaskTimer(plugin, renderer::tick, 1L, 1L);
            if (renderer.nms.canMove()) {
                renderer.gizmos = new GizmoManager(plugin, renderer, renderer.nms);
                Bukkit.getPluginManager().registerEvents(renderer.gizmos, plugin);
                Bukkit.getScheduler().runTaskTimer(plugin, renderer.gizmos::tick, 1L, 1L);
            } else {
                plugin.getLogger().warning("Operators cannot move custom blocks on this server: " + renderer.nms.movesFailure());
            }
            return renderer;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Custom blocks keep their real display entities on this server: " + e);
            return null;
        }
    }

    /**
     * Whether custom blocks are shown through packets rather than with real display entities.
     */
    public boolean isActive() {
        return BooleanConfigValue.PACKET_DISPLAY;
    }

    /**
     * What operators move custom blocks with, or null where display entities cannot be teleported.
     */
    @Nullable
    public GizmoManager gizmos() {
        return this.gizmos;
    }

    /**
     * Whether a block of this definition is shown through packets when placed now.
     */
    public boolean renders(AbstractCustomBlock definition) {
        return isActive() && !"item".equals(definition.getSaveSystem());
    }

    // ---------- Placing and breaking ----------

    /**
     * Makes sure the model of a custom block about to be placed can be shown.
     *
     * @param location where the block is placed, in a loaded chunk
     * @return false when its summon commands create no display entity
     */
    public synchronized boolean prepare(String name, Location location) {
        // a placement is worth a new reading, whenever the last one failed
        if (this.unavailableModels.getOrDefault(name, 0) != Integer.MAX_VALUE) {
            this.unavailableModels.remove(name);
        }
        return model(name, location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4) != null;
    }

    /**
     * Starts showing a custom block that was just placed, and stores how with its data.
     */
    public synchronized void placed(String name, Location location, CustomBlockRotation rotation, CustomBlockData data) {
        World world = location.getWorld();
        DisplayModel model = model(name, world, location.getBlockX() >> 4, location.getBlockZ() >> 4);
        if (model == null) {
            return;
        }
        PlacedDisplay placed = new PlacedDisplay(world, location.getBlockX(), location.getBlockY(), location.getBlockZ(), name, rotation);
        placed.baseX = location.getX();
        placed.baseY = location.getY();
        placed.baseZ = location.getZ();
        String placement = placed.placement();
        if (placement != null) {
            data.set(CustomBlockKey.RENDER, PersistentDataType.STRING, placement);
        }
        removed(location.getBlock());
        placed.bind(model, this.nms);
        add(placed);
        start(placed, DisplayAnimation.Trigger.PLACE, null);
    }

    /**
     * Plays the click animations of a custom block whose interaction was just clicked.
     *
     * @param interaction the name of the interaction, as written in the file of the block
     */
    public synchronized void clicked(Block block, String interaction) {
        Map<Long, List<PlacedDisplay>> chunks = this.index.get(block.getWorld().getUID());
        List<PlacedDisplay> list = chunks == null ? null : chunks.get(chunkKey(block.getX() >> 4, block.getZ() >> 4));
        if (list == null) {
            return;
        }
        for (PlacedDisplay placed : list) {
            if (placed.x == block.getX() && placed.y == block.getY() && placed.z == block.getZ()) {
                start(placed, DisplayAnimation.Trigger.CLICK, interaction);
                return;
            }
        }
    }

    // ---------- Animations played by a command ----------

    /**
     * Plays an animation of the custom block of a block from its start, whatever starts it in its file.
     *
     * @param players the players it is played for: the others keep seeing the block as it is, and once it ends these
     *                players see it as the others do. Null plays it for everyone who sees the block and those who
     *                come to see it while it plays, as a click does
     */
    public synchronized PlayResult play(Block block, String name, @Nullable java.util.Collection<? extends Player> players) {
        PlacedDisplay placed = find(block.getWorld(), block.getX(), block.getY(), block.getZ());
        if (placed == null || placed.model == null) {
            return PlayResult.NO_BLOCK;
        }
        int index = animationIndex(placed.model, name);
        if (index < 0) {
            return PlayResult.NO_ANIMATION;
        }
        if (placed.model.animations.get(index).trigger == DisplayAnimation.Trigger.LOOP) {
            return PlayResult.LOOPING;
        }
        if (players == null) {
            // its first frame goes out with the next tick, over what it was playing
            placed.startedAt[index] = this.tick + 1;
            this.playing.add(placed);
            return PlayResult.PLAYED;
        }
        for (Player player : players) {
            UUID id = player.getUniqueId();
            List<PrivatePlay> plays = this.privatePlays.computeIfAbsent(id, k -> new ArrayList<>(1));
            for (Iterator<PrivatePlay> iterator = plays.iterator(); iterator.hasNext(); ) {
                PrivatePlay old = iterator.next();
                if (old.placed == placed) {
                    // started again: the parts the other animation moved go back to rest, the frames take the rest
                    iterator.remove();
                    Viewer viewer = this.viewers.get(id);
                    if (old.animation != index && viewer != null && viewer.player == player && viewer.shown.contains(placed)) {
                        this.nms.send(player, placed.restPacket(this.nms, old.animation));
                    }
                }
            }
            plays.add(new PrivatePlay(placed, index, this.tick + 1));
            placed.privateFor.add(id);
        }
        return PlayResult.PLAYED;
    }

    /**
     * Stops the animations a command plays on the custom block of a block.
     *
     * @param players the players whose own animations are stopped: they see the block as the others do. Null stops
     *                those played for everyone, and the block goes back to rest
     * @return the number of animations stopped, -1 when there is no custom block shown through packets there
     */
    public synchronized int stop(Block block, @Nullable java.util.Collection<? extends Player> players) {
        PlacedDisplay placed = find(block.getWorld(), block.getX(), block.getY(), block.getZ());
        if (placed == null || placed.model == null) {
            return -1;
        }
        int stopped = 0;
        if (players == null) {
            List<DisplayAnimation> animations = placed.model.animations;
            for (int i = 0; i < animations.size(); i++) {
                if (animations.get(i).trigger == DisplayAnimation.Trigger.LOOP || placed.lastFrame[i] < 0 && placed.startedAt[i] < 0) {
                    continue;
                }
                if (placed.startedAt[i] >= 0) {
                    stopped++;
                }
                placed.startedAt[i] = -1;
                placed.lastFrame[i] = -1;
                Object packet = placed.restPacket(this.nms, i);
                for (Viewer viewer : placed.viewers) {
                    if (!placed.privateFor.contains(viewer.player.getUniqueId())) {
                        this.nms.send(viewer.player, packet);
                    }
                }
            }
            return stopped;
        }
        for (Player player : players) {
            List<PrivatePlay> plays = this.privatePlays.get(player.getUniqueId());
            if (plays == null) {
                continue;
            }
            for (Iterator<PrivatePlay> iterator = plays.iterator(); iterator.hasNext(); ) {
                PrivatePlay play = iterator.next();
                if (play.placed == placed) {
                    iterator.remove();
                    end(player.getUniqueId(), play);
                    stopped++;
                }
            }
            if (plays.isEmpty()) {
                this.privatePlays.remove(player.getUniqueId());
            }
        }
        return stopped;
    }

    /**
     * The name of the custom block of a block and the animations of its model, or null when there is no custom
     * block shown through packets there.
     */
    @Nullable
    public synchronized Map.Entry<String, List<String>> animations(Block block) {
        PlacedDisplay placed = find(block.getWorld(), block.getX(), block.getY(), block.getZ());
        if (placed == null || placed.model == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (DisplayAnimation animation : placed.model.animations) {
            names.add(animation.name + " (" + animation.trigger.name().toLowerCase(java.util.Locale.ROOT) + ", " + animation.duration + " ticks)");
        }
        return Map.entry(placed.modelName, names);
    }

    /**
     * The names of the animations a command can play, among the models read so far.
     */
    public synchronized Set<String> playableAnimations() {
        Set<String> names = new java.util.TreeSet<>();
        for (DisplayModel model : this.models.values()) {
            for (DisplayAnimation animation : model.animations) {
                if (animation.trigger != DisplayAnimation.Trigger.LOOP) {
                    names.add(animation.name);
                }
            }
        }
        return names;
    }

    private static int animationIndex(DisplayModel model, String name) {
        for (int i = 0; i < model.animations.size(); i++) {
            if (model.animations.get(i).name.equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Ends an animation played for a player: they see the block as the others do again.
     */
    private void end(UUID id, PrivatePlay play) {
        PlacedDisplay placed = play.placed;
        placed.privateFor.remove(id);
        Viewer viewer = this.viewers.get(id);
        if (viewer != null && viewer.player.isOnline() && viewer.shown.contains(placed)) {
            this.nms.send(viewer.player, placed.restPacket(this.nms, play.animation));
            catchUp(viewer, placed);
        }
    }

    /**
     * Drops the animations played for players on a block that is no longer shown.
     */
    private void dropPrivatePlays(PlacedDisplay placed) {
        if (placed.privateFor.isEmpty()) {
            return;
        }
        for (UUID id : placed.privateFor) {
            List<PrivatePlay> plays = this.privatePlays.get(id);
            if (plays != null) {
                plays.removeIf(play -> play.placed == placed);
                if (plays.isEmpty()) {
                    this.privatePlays.remove(id);
                }
            }
        }
        placed.privateFor.clear();
    }

    /**
     * Starts the animations of a block that answer a trigger, unless they are already playing.
     */
    private void start(PlacedDisplay placed, DisplayAnimation.Trigger trigger, String interaction) {
        List<DisplayAnimation> animations = placed.model.animations;
        for (int i = 0; i < animations.size(); i++) {
            DisplayAnimation animation = animations.get(i);
            if (animation.trigger == trigger && placed.startedAt[i] < 0
                    && (animation.interaction == null || animation.interaction.equalsIgnoreCase(interaction))) {
                // its first frame goes out with the next tick
                placed.startedAt[i] = this.tick + 1;
                this.playing.add(placed);
            }
        }
    }

    /**
     * Stops showing the custom block of a block, if any.
     */
    public synchronized void removed(Block block) {
        Map<Long, List<PlacedDisplay>> chunks = this.index.get(block.getWorld().getUID());
        if (chunks == null) {
            return;
        }
        long key = chunkKey(block.getX() >> 4, block.getZ() >> 4);
        List<PlacedDisplay> list = chunks.get(key);
        if (list == null) {
            return;
        }
        for (Iterator<PlacedDisplay> iterator = list.iterator(); iterator.hasNext(); ) {
            PlacedDisplay placed = iterator.next();
            if (placed.x == block.getX() && placed.y == block.getY() && placed.z == block.getZ()) {
                iterator.remove();
                hide(placed);
            }
        }
        if (list.isEmpty()) {
            chunks.remove(key);
            unregister(block.getWorld(), block.getX() >> 4, block.getZ() >> 4);
        }
    }

    /**
     * Reads again how the custom block of a block is shown, after its data changed.
     */
    public synchronized void refresh(Block block) {
        removed(block);
        Chunk chunk = block.getChunk();
        if (chunk.isEntitiesLoaded()) {
            Map<Long, List<PlacedDisplay>> chunks = chunks(block.getWorld());
            long key = chunkKey(chunk.getX(), chunk.getZ());
            List<PlacedDisplay> list = chunks.get(key);
            if (list == null) {
                list = new ArrayList<>();
            }
            read(block, chunk, list);
            if (!list.isEmpty()) {
                chunks.put(key, list);
                register(block.getWorld(), chunk.getX(), chunk.getZ());
            }
        }
    }

    /**
     * How many display entities the custom blocks of a chunk show: what they would weigh as real entities.
     */
    public synchronized int elementsIn(Chunk chunk) {
        Map<Long, List<PlacedDisplay>> chunks = this.index.get(chunk.getWorld().getUID());
        List<PlacedDisplay> list = chunks == null ? null : chunks.get(chunkKey(chunk.getX(), chunk.getZ()));
        if (list == null) {
            return 0;
        }
        int count = 0;
        for (PlacedDisplay placed : list) {
            count += placed.entityIds.length;
        }
        return count;
    }

    // ---------- Moving blocks (operators) ----------

    /**
     * The placed block of a block position, if it is shown through packets.
     */
    @Nullable
    synchronized PlacedDisplay find(World world, int x, int y, int z) {
        Map<Long, List<PlacedDisplay>> chunks = this.index.get(world.getUID());
        List<PlacedDisplay> list = chunks == null ? null : chunks.get(chunkKey(x >> 4, z >> 4));
        if (list != null) {
            for (PlacedDisplay placed : list) {
                if (placed.x == x && placed.y == y && placed.z == z) {
                    return placed;
                }
            }
        }
        return null;
    }

    /**
     * The packet that makes the display entities of a block glide to the next position they are teleported to.
     */
    private Object glidePacket(PlacedDisplay placed) {
        List<?> values = this.nms.teleportDurationValues(GLIDE_TICKS);
        List<Object> packets = new ArrayList<>(placed.entityIds.length);
        for (int id : placed.entityIds) {
            packets.add(this.nms.dataPacket(id, values));
        }
        return this.nms.bundle(packets);
    }

    /**
     * Makes the clients that see a block glide, rather than jump, to the positions it is moved to.
     */
    synchronized void beginShift(PlacedDisplay placed) {
        if (placed.gliding) {
            return;
        }
        placed.gliding = true;
        if (!placed.viewers.isEmpty()) {
            Object packet = glidePacket(placed);
            for (Viewer viewer : placed.viewers) {
                this.nms.send(viewer.player, packet);
            }
        }
    }

    /**
     * Moves a block to where its shift puts it, for everyone who sees it.
     */
    synchronized void shift(PlacedDisplay placed, double x, double y, double z, double yaw, double pitch) {
        shift(placed, x, y, z, yaw, pitch, placed.scale());
    }

    /**
     * Moves a block to where its shift puts it, and gives it the size it asks, for everyone who sees it.
     */
    synchronized void shift(PlacedDisplay placed, double x, double y, double z, double yaw, double pitch, double scale) {
        boolean resized = Math.abs(scale - placed.scale()) > 1.0E-9;
        placed.shift(x, y, z, yaw, pitch, scale);
        if (placed.viewers.isEmpty()) {
            return;
        }
        List<DisplayModel.Element> elements = placed.model.elements;
        List<Object> packets = new ArrayList<>(elements.size() * 2);
        for (int i = 0; i < elements.size(); i++) {
            double[] pose = placed.pose(elements.get(i).pitch());
            packets.add(this.nms.teleportPacket(placed.entityIds[i], pose[0], pose[1], pose[2], (float) pose[3], (float) pose[4]));
            if (resized && this.nms.canAnimate()) {
                // each part goes to its resting pose, multiplied by the new size; a loop corrects it at its next frame
                org.bukkit.util.Transformation rest = elements.get(i).transformation();
                float k = (float) scale;
                packets.add(this.nms.dataPacket(placed.entityIds[i], this.nms.poseValues(new org.joml.Vector3f(rest.getTranslation()).mul(k),
                        new org.joml.Quaternionf(rest.getLeftRotation()), new org.joml.Vector3f(rest.getScale()).mul(k),
                        new org.joml.Quaternionf(rest.getRightRotation()), GLIDE_TICKS)));
            }
        }
        Object packet = this.nms.bundle(packets);
        for (Viewer viewer : placed.viewers) {
            this.nms.send(viewer.player, packet);
        }
    }

    /**
     * Stores where an operator left a block, and takes its interactions and collisions there.
     */
    synchronized void commitShift(PlacedDisplay placed) {
        placed.gliding = false;
        Block block = placed.world.getBlockAt(placed.x, placed.y, placed.z);
        CustomBlockData data = new CustomBlockData(block, this.plugin);
        String text = placed.shiftText();
        if (text == null) {
            data.remove(CustomBlockKey.SHIFT);
        } else {
            data.set(CustomBlockKey.SHIFT, PersistentDataType.STRING, text);
        }
        layHitbox(placed, data);
    }

    /**
     * Takes the interactions and collisions of a block where its shift puts its model, shaped as the model now lies.
     */
    private void layHitbox(PlacedDisplay placed, CustomBlockData data) {
        if (this.plugin.getCustomBlockService() instanceof BDCCustomBlockService service
                && service.layHitbox(data, placed.base(), placed.placedPose(placed.model.elements.get(0).pitch()), placed.hitboxShift, placed.shift)) {
            System.arraycopy(placed.shift, 0, placed.hitboxShift, 0, 6);
        }
    }

    /**
     * Where the item of a broken block appears: in the middle of the model, where an operator may have moved it.
     *
     * @return null when the block was not moved
     */
    @Nullable
    public synchronized Location dropPoint(Location block) {
        PlacedDisplay placed = block.getWorld() == null ? null : find(block.getWorld(), block.getBlockX(), block.getBlockY(), block.getBlockZ());
        if (placed == null || !placed.isShifted()) {
            return null;
        }
        float pitch = placed.model.elements.get(0).pitch();
        org.joml.Vector3d pivot = placed.pivot(pitch);
        return new Location(placed.world, pivot.x, placed.pose(pitch)[1] + 0.25, pivot.z);
    }

    // ---------- Reload, reset, shutdown ----------

    /**
     * Shows every custom block again from its file: called once the files have been read, at start and at reload.
     */
    public synchronized void reload() {
        this.models.clear();
        this.unavailableModels.clear();
        this.reportedModels.clear();
        this.retryChunks.clear();
        this.ranges.clear();
        this.farthest = 0;
        this.playing.clear();
        // the blocks are read again and get new entity ids: what was played for players is dropped
        for (List<PrivatePlay> plays : this.privatePlays.values()) {
            plays.forEach(play -> play.placed.privateFor.clear());
        }
        this.privatePlays.clear();

        if (!isActive()) {
            // every block shown through packets is read again, and given real entities
            for (Map<Long, List<PlacedDisplay>> chunks : this.index.values()) {
                chunks.values().forEach(list -> list.forEach(this::hide));
            }
            this.index.clear();
        }
        for (Map<Long, List<PlacedDisplay>> chunks : this.index.values()) {
            for (Iterator<List<PlacedDisplay>> lists = chunks.values().iterator(); lists.hasNext(); ) {
                List<PlacedDisplay> list = lists.next();
                for (Iterator<PlacedDisplay> iterator = list.iterator(); iterator.hasNext(); ) {
                    PlacedDisplay placed = iterator.next();
                    hide(placed);
                    DisplayModel model = model(placed.modelName, placed.world, placed.chunkX(), placed.chunkZ());
                    if (model == null) {
                        iterator.remove();
                    } else {
                        refreshHitbox(placed, model, new CustomBlockData(placed.world.getBlockAt(placed.x, placed.y, placed.z), this.plugin));
                        placed.bind(model, this.nms);
                        show(placed);
                    }
                }
                if (list.isEmpty()) {
                    lists.remove();
                }
            }
        }
        // blocks whose file was missing, and blocks to convert
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                queue(chunk);
            }
        }
    }

    /**
     * Forgets the custom blocks of a chunk whose content is being wiped, an island being deleted for instance: their
     * data would otherwise outlive them and show them again. A chunk that is not loaded is cleaned when it next
     * loads.
     */
    public synchronized void resetChunk(World world, int chunkX, int chunkZ) {
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            wipe(world.getChunkAt(chunkX, chunkZ));
        } else if (this.registry.replace(registryKey(world, chunkX, chunkZ), Boolean.FALSE, Boolean.TRUE)) {
            this.registryDirty = true;
        }
    }

    /**
     * Removes the display entities from the clients: the plugin is being disabled.
     */
    public synchronized void shutdown() {
        for (Map<Long, List<PlacedDisplay>> chunks : this.index.values()) {
            for (List<PlacedDisplay> list : chunks.values()) {
                list.forEach(this::hide);
            }
        }
        this.index.clear();
        this.viewers.clear();
        this.looping.clear();
        this.playing.clear();
        this.privatePlays.clear();
        if (this.registryDirty) {
            this.registryDirty = false;
            saveRegistry();
        }
    }

    // ---------- Events ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        queue(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        queue(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        Map<Long, List<PlacedDisplay>> chunks = this.index.get(chunk.getWorld().getUID());
        if (chunks != null && chunks.containsKey(chunkKey(chunk.getX(), chunk.getZ()))) {
            this.tasks.add(new ChunkTask(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ(), true));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        synchronized (this) {
            Map<Long, List<PlacedDisplay>> chunks = this.index.remove(event.getWorld().getUID());
            if (chunks != null) {
                chunks.values().forEach(list -> list.forEach(this::hide));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        synchronized (this) {
            UUID id = event.getPlayer().getUniqueId();
            Viewer viewer = this.viewers.remove(id);
            if (viewer != null) {
                forget(viewer);
            }
            List<PrivatePlay> plays = this.privatePlays.remove(id);
            if (plays != null) {
                plays.forEach(play -> play.placed.privateFor.remove(id));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        clientCleared(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        clientCleared(event.getPlayer());
    }

    /**
     * A client that changes world or respawns drops every entity it knew: what it was shown has to be sent again.
     */
    private synchronized void clientCleared(Player player) {
        Viewer viewer = this.viewers.get(player.getUniqueId());
        if (viewer != null) {
            forget(viewer);
        }
    }

    /**
     * Chunks hold the data of other plugins too, and most hold none: only those with data are worth reading.
     */
    private void queue(Chunk chunk) {
        World world = chunk.getWorld();
        if (chunk.getPersistentDataContainer().isEmpty() && !Boolean.TRUE.equals(this.registry.get(registryKey(world, chunk.getX(), chunk.getZ())))) {
            return;
        }
        this.tasks.add(new ChunkTask(world.getUID(), chunk.getX(), chunk.getZ(), false));
    }

    // ---------- Every tick ----------

    private synchronized void tick() {
        long deadline = System.nanoTime() + SCAN_BUDGET_NANOS;
        ChunkTask task;
        while ((task = this.tasks.poll()) != null) {
            World world = Bukkit.getWorld(task.world());
            if (world != null) {
                try {
                    if (task.unload()) {
                        unload(world, task.x(), task.z());
                    } else if (!scan(world, task.x(), task.z(), deadline)) {
                        // out of time in the middle of the chunk: the rest of its blocks wait for the next tick
                        this.tasks.add(task);
                    }
                } catch (RuntimeException e) {
                    this.plugin.getLogger().log(Level.WARNING, "Could not read the custom blocks of chunk " + task.x() + " " + task.z() + " in " + world.getName(), e);
                }
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }

        this.tick++;
        animate();
        if (this.tick % MODEL_RETRY_PERIOD == 0 && !this.retryChunks.isEmpty()) {
            this.tasks.addAll(this.retryChunks);
            this.retryChunks.clear();
        }
        if (this.tick % VIEWER_PERIOD == 0) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                update(player);
            }
        }
        if (this.registryDirty) {
            this.registryDirty = false;
            saveRegistry();
        }
    }

    private void unload(World world, int chunkX, int chunkZ) {
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            // loaded again since: its blocks are the same
            return;
        }
        Map<Long, List<PlacedDisplay>> chunks = this.index.get(world.getUID());
        List<PlacedDisplay> list = chunks == null ? null : chunks.remove(chunkKey(chunkX, chunkZ));
        if (list != null) {
            list.forEach(this::hide);
        }
    }

    /**
     * Reads the custom blocks of a loaded chunk. It waits for the entities of the chunk: the blocks placed with real
     * display entities are recognised by them.
     * <p>
     * Reading a model for the first time and converting a block take time: a chunk holding many of them is read
     * over several ticks.
     *
     * @param deadline the time after which the reading stops, as given by {@link System#nanoTime()}
     * @return false when blocks of the chunk are left to read
     */
    private boolean scan(World world, int chunkX, int chunkZ, long deadline) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return true;
        }
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (!chunk.isEntitiesLoaded()) {
            return true;
        }
        if (Boolean.TRUE.equals(this.registry.get(registryKey(world, chunkX, chunkZ)))) {
            wipe(chunk);
            return true;
        }
        Set<Block> blocks = CustomBlockData.getBlocksWithCustomData(this.plugin, chunk);
        if (blocks.isEmpty()) {
            return true;
        }
        Map<Long, List<PlacedDisplay>> chunks = chunks(world);
        long key = chunkKey(chunkX, chunkZ);
        List<PlacedDisplay> list = chunks.get(key);
        if (list == null) {
            list = new ArrayList<>();
        }
        boolean complete = true;
        for (Block block : blocks) {
            if (!isIndexed(list, block)) {
                int shown = list.size();
                read(block, chunk, list);
                // only a block that got shown counts: the ones left as they are cost nothing and are met again
                if (list.size() > shown && System.nanoTime() > deadline) {
                    complete = false;
                    break;
                }
            }
        }
        if (!list.isEmpty()) {
            chunks.put(key, list);
            register(world, chunkX, chunkZ);
        }
        return complete;
    }

    private static boolean isIndexed(List<PlacedDisplay> list, Block block) {
        for (PlacedDisplay placed : list) {
            if (placed.x == block.getX() && placed.y == block.getY() && placed.z == block.getZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads one custom block of a chunk whose entities are loaded, converts it if it is not shown the way the
     * configuration asks, and starts showing it.
     */
    private void read(Block block, Chunk chunk, List<PlacedDisplay> list) {
        CustomBlockData data = new CustomBlockData(block, this.plugin);
        String name = data.get(CustomBlockKey.NAME, PersistentDataType.STRING);
        UUID[] displays = data.get(CustomBlockKey.DISPLAY_UUID, PersistentDataTypes.UUID_ARRAY);
        CustomBlockRotation rotation = data.get(CustomBlockKey.BLOCK_ROTATION, PersistentDataTypes.CUSTOM_BLOCK_ROTATION);
        if (name == null || displays == null || rotation == null || data.has(CustomBlockKey.DISPLAY_SPAWN_COMMAND)) {
            return;
        }
        boolean real = displays.length > 0;
        if (real && !isActive()) {
            return;
        }
        DisplayModel model = model(name, chunk.getWorld(), chunk.getX(), chunk.getZ());
        if (model == null) {
            if (this.unavailableModels.getOrDefault(name, Integer.MAX_VALUE) != Integer.MAX_VALUE) {
                this.retryChunks.add(new ChunkTask(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ(), false));
            }
            return;
        }
        PlacedDisplay placed = new PlacedDisplay(block.getWorld(), block.getX(), block.getY(), block.getZ(), name, rotation);
        placed.model = model;
        placed.placement(data.get(CustomBlockKey.RENDER, PersistentDataType.STRING));

        if (real) {
            if (!convert(placed, data, displays)) {
                return;
            }
        } else if (!isActive()) {
            restore(placed, data);
            return;
        }

        placed.shiftText(data.get(CustomBlockKey.SHIFT, PersistentDataType.STRING));
        refreshHitbox(placed, model, data);

        placed.bind(model, this.nms);
        list.add(placed);
        show(placed);
    }

    /**
     * Replaces the interactions and collisions of a placed block when its file no longer describes the ones it has:
     * a click box changed by hand, or a new scale.
     */
    private void refreshHitbox(PlacedDisplay placed, DisplayModel model, CustomBlockData data) {
        if (!model.hitboxSignature.equals(data.get(CustomBlockKey.HITBOX, PersistentDataType.STRING))
                && this.plugin.getCustomBlockService() instanceof BDCCustomBlockService service
                && service.refreshHitbox(placed.base())) {
            // the new entities stand around the placement: they go where the block was moved
            placed.resetHitboxShift();
            if (placed.isShifted()) {
                layHitbox(placed, data);
            }
            this.hitboxes++;
            scheduleSummary();
        } else if (placed.isShifted() && !BDCCustomBlockService.HITBOX_LAYOUT_VERSION.equals(data.get(CustomBlockKey.HITBOX_LAYOUT, PersistentDataType.STRING))) {
            // moved before click boxes followed a tipped model: they are laid out again where the model lies
            layHitbox(placed, data);
        }
    }

    /**
     * Replaces the real display entities of a placed custom block by its model. Nothing happens unless every one of
     * them is loaded: a block that lost part of its entities stays as it is.
     */
    private boolean convert(PlacedDisplay placed, CustomBlockData data, UUID[] displays) {
        List<Display> roots = new ArrayList<>(displays.length);
        for (UUID uuid : displays) {
            if (!(Bukkit.getEntity(uuid) instanceof Display display) || !display.isValid()) {
                return false;
            }
            roots.add(display);
        }
        Display first = roots.get(0);

        Location stored = CustomBlockKey.holder(first).getLocation();
        if (stored != null && stored.getBlockX() == placed.x && stored.getBlockY() == placed.y && stored.getBlockZ() == placed.z) {
            placed.baseX = stored.getX();
            placed.baseY = stored.getY();
            placed.baseZ = stored.getZ();
        }
        // entities that are not where the rotation of the block puts them keep their place
        Location actual = first.getLocation();
        double[] expected = placed.pose(placed.model.elements.get(0).pitch());
        if (Math.abs(actual.getX() - expected[0]) > 1.0E-3 || Math.abs(actual.getY() - expected[1]) > 1.0E-3 || Math.abs(actual.getZ() - expected[2]) > 1.0E-3
                || angleDistance(actual.getYaw(), (float) expected[3]) > 0.1f || angleDistance(actual.getPitch(), (float) expected[4]) > 0.1f) {
            placed.fixedPose = new double[]{actual.getX(), actual.getY(), actual.getZ(), actual.getYaw(), actual.getPitch()};
            this.moved++;
        }

        for (Display root : roots) {
            removeWithPassengers(root);
        }
        data.set(CustomBlockKey.DISPLAY_UUID, PersistentDataTypes.UUID_ARRAY, new UUID[0]);
        String placement = placed.placement();
        if (placement != null) {
            data.set(CustomBlockKey.RENDER, PersistentDataType.STRING, placement);
        }
        this.converted++;
        scheduleSummary();
        return true;
    }

    /**
     * Gives a custom block shown through packets its real display entities back.
     */
    private void restore(PlacedDisplay placed, CustomBlockData data) {
        if (placed.world.getBlockAt(placed.x, placed.y, placed.z).getChunk().isEntitiesLoaded()
                && this.plugin.getCustomBlockService() instanceof BDCCustomBlockService service) {
            AbstractCustomBlock definition = service.getStorage().getAbstractCustomBlock(placed.modelName);
            if (definition == null) {
                return;
            }
            List<UUID> vehicles = new ArrayList<>();
            if (service.summonDisplays(definition, placed.base(), placed.rotation, vehicles).isEmpty()) {
                return;
            }
            data.set(CustomBlockKey.DISPLAY_UUID, PersistentDataTypes.UUID_ARRAY, vehicles.toArray(UUID[]::new));
            data.remove(CustomBlockKey.RENDER);
            this.restored++;
            scheduleSummary();
        }
    }

    private static void removeWithPassengers(Entity entity) {
        for (Entity passenger : entity.getPassengers()) {
            removeWithPassengers(passenger);
        }
        entity.remove();
    }

    private static float angleDistance(float a, float b) {
        float distance = Math.abs(a - b) % 360f;
        return distance > 180f ? 360f - distance : distance;
    }

    /**
     * Removes the custom blocks of a chunk, data included.
     */
    private void wipe(Chunk chunk) {
        Map<Long, List<PlacedDisplay>> chunks = this.index.get(chunk.getWorld().getUID());
        List<PlacedDisplay> list = chunks == null ? null : chunks.remove(chunkKey(chunk.getX(), chunk.getZ()));
        if (list != null) {
            list.forEach(this::hide);
        }
        unregister(chunk.getWorld(), chunk.getX(), chunk.getZ());
        for (Block block : CustomBlockData.getBlocksWithCustomData(this.plugin, chunk)) {
            CustomBlockData data = new CustomBlockData(block, this.plugin);
            if (data.has(CustomBlockKey.NAME)) {
                data.clear();
            }
        }
    }

    // ---------- Models ----------

    /**
     * The model of a custom block, read from its file the first time it is needed.
     *
     * @return null when the block has no file, keeps its display entities in its item, or summons nothing
     */
    @Nullable
    private DisplayModel model(String name, World world, int chunkX, int chunkZ) {
        DisplayModel model = this.models.get(name);
        if (model != null) {
            return model;
        }
        if (world == null || this.tick < this.unavailableModels.getOrDefault(name, 0)) {
            return null;
        }
        // the definition held by the storage and the text of its commands: a copy would be written out as text again
        BDCCustomBlockConfigStorage storage = this.plugin.getCustomBlockService().getStorage() instanceof BDCCustomBlockConfigStorage config ? config : null;
        AbstractCustomBlock definition = storage != null ? storage.peek(name) : this.plugin.getCustomBlockService().getStorage().getAbstractCustomBlock(name);
        List<String[]> summonTexts = storage == null ? null : storage.getSummonTexts().get(name);
        if (definition == null) {
            this.unavailableModels.put(name, this.tick + MODEL_RETRY_PERIOD);
            return null;
        }
        if ("item".equals(definition.getSaveSystem())) {
            // its display entities are those of the item it was placed with
            this.unavailableModels.put(name, Integer.MAX_VALUE);
            return null;
        }
        List<String> problems = new ArrayList<>();
        try {
            model = DisplayModel.read(this.nms, definition, summonTexts, world, chunkX, chunkZ, animations(name), viewDistance(name), problems);
        } catch (RuntimeException e) {
            if (this.reportedModels.add(name)) {
                this.plugin.getLogger().log(Level.WARNING, "Could not read the display entities of the custom block " + name, e);
            }
            this.unavailableModels.put(name, this.tick + MODEL_RETRY_PERIOD);
            return null;
        }
        if (model.elements.isEmpty()) {
            if (this.reportedModels.add(name)) {
                this.plugin.getLogger().warning("The summon commands of the custom block " + name + " created no display entity in " + world.getName());
            }
            this.unavailableModels.put(name, this.tick + MODEL_RETRY_PERIOD);
            return null;
        }
        for (String problem : problems) {
            this.plugin.getLogger().warning("Custom block " + name + ", " + problem);
        }
        this.unavailableModels.remove(name);
        this.models.put(name, model);
        // players moving look that much further for blocks; the blocks of this model are shown as they are added
        this.farthest = Math.max(this.farthest, model.viewDistance);
        return model;
    }

    /**
     * The {@code display.view-distance} of the file of a custom block, in blocks, or 0 when it has none.
     */
    private int viewDistance(String name) {
        if (this.plugin.getCustomBlockService().getStorage() instanceof BDCCustomBlockConfigStorage storage
                && storage.getCustomBlockRepository() != null) {
            CustomBlockConfigurationFile file = storage.getCustomBlockRepository().getFile(name);
            if (file != null) {
                try {
                    return Math.round(me.general_breddok.blockdisplaycreator.file.config.loader.CustomBlockYamlFile.viewDistance(file.get("display.view-distance")));
                } catch (IllegalArgumentException e) {
                    // the block itself does not load with it
                    return 0;
                }
            }
        }
        return 0;
    }

    /**
     * The {@code animations} section of the file of a custom block, if it has one and this server can play it.
     */
    @Nullable
    private ConfigurationSection animations(String name) {
        if (this.nms.canAnimate()
                && this.plugin.getCustomBlockService().getStorage() instanceof BDCCustomBlockConfigStorage storage
                && storage.getCustomBlockRepository() != null) {
            CustomBlockConfigurationFile file = storage.getCustomBlockRepository().getFile(name);
            if (file != null && file.get("animations") instanceof ConfigurationSection section) {
                return section;
            }
        }
        return null;
    }

    // ---------- Who sees what ----------

    private void add(PlacedDisplay placed) {
        chunks(placed.world).computeIfAbsent(chunkKey(placed.chunkX(), placed.chunkZ()), k -> new ArrayList<>()).add(placed);
        register(placed.world, placed.chunkX(), placed.chunkZ());
        show(placed);
    }

    private Map<Long, List<PlacedDisplay>> chunks(World world) {
        return this.index.computeIfAbsent(world.getUID(), k -> new ConcurrentHashMap<>());
    }

    /**
     * Sends a custom block to the players already close enough to see it.
     */
    private void show(PlacedDisplay placed) {
        UUID world = placed.world.getUID();
        for (Viewer viewer : this.viewers.values()) {
            if (!viewer.stale && world.equals(viewer.world)
                    && inRange(placed.chunkX() - viewer.chunkX, placed.chunkZ() - viewer.chunkZ, radius(placed))
                    && viewer.shown.add(placed)) {
                attach(viewer, placed);
            }
        }
    }

    /**
     * Sends a custom block to a player, where its animations have taken it.
     */
    private void attach(Viewer viewer, PlacedDisplay placed) {
        placed.viewers.add(viewer);
        this.nms.send(viewer.player, placed.spawnPacket(this.nms));
        if (placed.gliding) {
            this.nms.send(viewer.player, glidePacket(placed));
        }
        PrivatePlay own = placed.privateFor.isEmpty() ? null : privatePlay(viewer.player.getUniqueId(), placed);
        if (own != null) {
            // where the animation played for this player has taken the block
            if (own.lastFrame >= 0) {
                this.nms.send(viewer.player, placed.catchUpPacket(this.nms, own.animation, own.lastFrame));
            }
        } else {
            catchUp(viewer, placed);
        }
        if (placed.model.loops) {
            this.looping.add(placed);
        }
    }

    @Nullable
    private PrivatePlay privatePlay(UUID id, PlacedDisplay placed) {
        List<PrivatePlay> plays = this.privatePlays.get(id);
        if (plays != null) {
            for (PrivatePlay play : plays) {
                if (play.placed == placed) {
                    return play;
                }
            }
        }
        return null;
    }

    /**
     * Sends a player who sees a block where the animations everyone sees have taken it.
     */
    private void catchUp(Viewer viewer, PlacedDisplay placed) {
        List<DisplayAnimation> animations = placed.model.animations;
        for (int i = 0; i < animations.size(); i++) {
            DisplayAnimation animation = animations.get(i);
            int last = placed.lastFrame[i];
            if (animation.trigger == DisplayAnimation.Trigger.LOOP) {
                // the frames already started in this turn of the loop, or all of them when it has turned before
                int phase = this.tick % animation.duration;
                last = -1;
                while (last + 1 < animation.frames.length && animation.frames[last + 1].at() <= phase) {
                    last++;
                }
                if (last < 0 && this.tick >= animation.duration) {
                    last = animation.frames.length - 1;
                }
            }
            if (last >= 0) {
                this.nms.send(viewer.player, placed.catchUpPacket(this.nms, i, last));
            }
        }
    }

    /**
     * Plays the frames due at this tick: those of the loops of the blocks someone sees, all in step, those of the
     * animations a click, a placement or a command started for everyone, and those commands play for some players.
     */
    private void animate() {
        if (!this.privatePlays.isEmpty()) {
            animatePrivate();
        }
        for (PlacedDisplay placed : this.looping) {
            List<DisplayAnimation> animations = placed.model.animations;
            for (int i = 0; i < animations.size(); i++) {
                DisplayAnimation animation = animations.get(i);
                if (animation.trigger == DisplayAnimation.Trigger.LOOP) {
                    play(placed, i, animation, animation.startingAt[this.tick % animation.duration]);
                    sound(placed, animation, this.tick % animation.duration);
                }
            }
        }
        for (Iterator<PlacedDisplay> iterator = this.playing.iterator(); iterator.hasNext(); ) {
            PlacedDisplay placed = iterator.next();
            List<DisplayAnimation> animations = placed.model.animations;
            boolean active = false;
            for (int i = 0; i < animations.size() && i < placed.startedAt.length; i++) {
                if (placed.startedAt[i] < 0) {
                    continue;
                }
                DisplayAnimation animation = animations.get(i);
                int elapsed = this.tick - placed.startedAt[i];
                if (elapsed >= 0 && elapsed <= animation.duration) {
                    play(placed, i, animation, animation.startingAt[elapsed]);
                    sound(placed, animation, elapsed);
                }
                if (elapsed >= animation.duration) {
                    placed.startedAt[i] = -1;
                } else {
                    active = true;
                }
            }
            if (!active) {
                iterator.remove();
            }
        }
    }

    /**
     * The frames and sounds due at this tick of the animations played for single players: only they get them.
     */
    private void animatePrivate() {
        for (Iterator<Map.Entry<UUID, List<PrivatePlay>>> entries = this.privatePlays.entrySet().iterator(); entries.hasNext(); ) {
            Map.Entry<UUID, List<PrivatePlay>> entry = entries.next();
            Viewer viewer = this.viewers.get(entry.getKey());
            Player player = viewer != null ? viewer.player : Bukkit.getPlayer(entry.getKey());
            for (Iterator<PrivatePlay> iterator = entry.getValue().iterator(); iterator.hasNext(); ) {
                PrivatePlay play = iterator.next();
                DisplayAnimation animation = play.placed.model.animations.get(play.animation);
                int elapsed = this.tick - play.startedAt;
                if (elapsed >= 0 && elapsed < animation.duration) {
                    boolean sees = viewer != null && viewer.shown.contains(play.placed);
                    // sounds come from the block: heard in its world only
                    boolean hears = player != null && player.getWorld() == play.placed.world;
                    int[] starting = animation.startingAt[elapsed];
                    if (starting != null) {
                        for (int frame : starting) {
                            if (sees) {
                                this.nms.send(viewer.player, play.placed.framePacket(this.nms, play.animation, frame));
                            }
                            DisplayAnimation.Frame played = animation.frames[frame];
                            if (hears && played.sound() != null) {
                                player.playSound(play.placed.base(), played.sound(), played.volume(), played.pitch());
                            }
                            play.lastFrame = frame;
                        }
                    }
                    int[] due = animation.notesAt[elapsed];
                    if (hears && due != null) {
                        for (int note : due) {
                            DisplayAnimation.Note played = animation.notes[note];
                            player.playSound(play.placed.base(), played.sound(), played.volume(), played.pitch());
                        }
                    }
                }
                if (elapsed >= animation.duration) {
                    iterator.remove();
                    end(entry.getKey(), play);
                }
            }
            if (entry.getValue().isEmpty()) {
                entries.remove();
            }
        }
    }

    private void sound(PlacedDisplay placed, DisplayAnimation animation, int at) {
        int[] due = animation.notesAt[at];
        if (due == null) {
            return;
        }
        Location where = placed.base();
        for (int note : due) {
            DisplayAnimation.Note played = animation.notes[note];
            placed.world.playSound(where, played.sound(), played.volume(), played.pitch());
        }
    }

    private void play(PlacedDisplay placed, int index, DisplayAnimation animation, int[] starting) {
        if (starting == null) {
            return;
        }
        for (int frame : starting) {
            if (!placed.viewers.isEmpty()) {
                Object packet = placed.framePacket(this.nms, index, frame);
                for (Viewer viewer : placed.viewers) {
                    // a player watching an animation played for them alone gets the others' frames once it ends
                    if (placed.privateFor.isEmpty() || !placed.privateFor.contains(viewer.player.getUniqueId())) {
                        this.nms.send(viewer.player, packet);
                    }
                }
            }
            DisplayAnimation.Frame played = animation.frames[frame];
            if (played.sound() != null) {
                placed.world.playSound(placed.base(), played.sound(), played.volume(), played.pitch());
            }
            placed.lastFrame[index] = frame;
        }
    }

    private void hide(PlacedDisplay placed) {
        if (this.gizmos != null) {
            this.gizmos.forget(placed);
        }
        this.looping.remove(placed);
        dropPrivatePlays(placed);
        if (placed.viewers.isEmpty()) {
            return;
        }
        Object packet = placed.removePacket(this.nms);
        for (Viewer viewer : placed.viewers) {
            viewer.shown.remove(placed);
            if (viewer.player.isOnline()) {
                this.nms.send(viewer.player, packet);
            }
        }
        placed.viewers.clear();
    }

    /**
     * Drops what a client was shown without telling it: it has already forgotten it, or is gone.
     */
    private void forget(Viewer viewer) {
        for (PlacedDisplay placed : viewer.shown) {
            placed.viewers.remove(viewer);
            if (placed.viewers.isEmpty()) {
                this.looping.remove(placed);
            }
        }
        viewer.shown.clear();
        viewer.stale = true;
    }

    /**
     * Brings what a player is shown in line with the chunk they stand in.
     */
    private void update(Player player) {
        Viewer viewer = this.viewers.get(player.getUniqueId());
        if (viewer == null || viewer.player != player) {
            if (viewer != null) {
                forget(viewer);
            }
            viewer = new Viewer(player);
            this.viewers.put(player.getUniqueId(), viewer);
        }
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (!world.getUID().equals(viewer.world)) {
            forget(viewer);
        } else if (!viewer.stale && chunkX == viewer.chunkX && chunkZ == viewer.chunkZ) {
            return;
        }
        viewer.world = world.getUID();
        viewer.chunkX = chunkX;
        viewer.chunkZ = chunkZ;
        viewer.stale = false;
        // the chunks to look in: those of the model seen from the furthest away
        int radius = Math.max(radius(world), radius(world, this.farthest));

        List<PlacedDisplay> leaving = null;
        for (Iterator<PlacedDisplay> iterator = viewer.shown.iterator(); iterator.hasNext(); ) {
            PlacedDisplay placed = iterator.next();
            if (!inRange(placed.chunkX() - chunkX, placed.chunkZ() - chunkZ, radius(placed))) {
                iterator.remove();
                placed.viewers.remove(viewer);
                if (placed.viewers.isEmpty()) {
                    this.looping.remove(placed);
                }
                if (leaving == null) {
                    leaving = new ArrayList<>();
                }
                leaving.add(placed);
            }
        }
        if (leaving != null) {
            int count = 0;
            for (PlacedDisplay placed : leaving) {
                count += placed.entityIds.length;
            }
            int[] ids = new int[count];
            int offset = 0;
            for (PlacedDisplay placed : leaving) {
                System.arraycopy(placed.entityIds, 0, ids, offset, placed.entityIds.length);
                offset += placed.entityIds.length;
            }
            this.nms.send(player, this.nms.removePacket(ids));
        }

        Map<Long, List<PlacedDisplay>> chunks = this.index.get(world.getUID());
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        int side = 2 * radius + 1;
        if (chunks.size() < side * side) {
            for (List<PlacedDisplay> list : chunks.values()) {
                PlacedDisplay any = list.isEmpty() ? null : list.get(0);
                if (any != null && inRange(any.chunkX() - chunkX, any.chunkZ() - chunkZ, radius)) {
                    showAll(viewer, list);
                }
            }
        } else {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (inRange(dx, dz, radius)) {
                        List<PlacedDisplay> list = chunks.get(chunkKey(chunkX + dx, chunkZ + dz));
                        if (list != null) {
                            showAll(viewer, list);
                        }
                    }
                }
            }
        }
    }

    private void showAll(Viewer viewer, List<PlacedDisplay> list) {
        for (PlacedDisplay placed : list) {
            if (!inRange(placed.chunkX() - viewer.chunkX, placed.chunkZ() - viewer.chunkZ, radius(placed)) || viewer.shown.contains(placed)) {
                continue;
            }
            if (!chunkSent(viewer.player, placed.chunkX(), placed.chunkZ())) {
                // the terrain first: a player who just arrived gets the decorations of a chunk once he has the chunk
                viewer.stale = true;
                continue;
            }
            viewer.shown.add(placed);
            attach(viewer, placed);
        }
    }

    // Paper only: whether the server has sent a chunk to the player yet
    private static final java.lang.reflect.Method CHUNK_SENT = chunkSentMethod();

    private static java.lang.reflect.Method chunkSentMethod() {
        try {
            return Player.class.getMethod("isChunkSent", long.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static boolean chunkSent(Player player, int chunkX, int chunkZ) {
        if (CHUNK_SENT == null) {
            return true;
        }
        try {
            return (boolean) CHUNK_SENT.invoke(player, (long) chunkX & 0xffffffffL | ((long) chunkZ & 0xffffffffL) << 32);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    private static boolean inRange(int dx, int dz, int radius) {
        return dx * dx + dz * dz <= radius * radius + radius;
    }

    /**
     * How many chunks away a custom block is shown: as far as its model asks, or as the world tracks display
     * entities, within the chunks the server sends.
     */
    private int radius(PlacedDisplay placed) {
        return placed.model == null || placed.model.viewDistance <= 0 ? radius(placed.world) : radius(placed.world, placed.model.viewDistance);
    }

    private static int radius(World world, int blocks) {
        return blocks <= 0 ? 0 : Math.max(1, Math.min((blocks + 15) >> 4, world.getViewDistance()));
    }

    /**
     * How many chunks away a custom block is shown: the distance the server tracks display entities at in this
     * world, within the chunks it sends.
     */
    private int radius(World world) {
        Integer range = this.ranges.get(world.getName());
        if (range == null) {
            YamlConfiguration spigot = Bukkit.spigot().getConfig();
            int blocks = spigot.getInt("world-settings." + world.getName() + ".entity-tracking-range.display",
                    spigot.getInt("world-settings.default.entity-tracking-range.display", 128));
            range = Math.max(1, Math.min((blocks + 15) >> 4, world.getViewDistance()));
            this.ranges.put(world.getName(), range);
        }
        return range;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    // ---------- Bookkeeping ----------

    private static String registryKey(World world, int chunkX, int chunkZ) {
        return world.getName() + ";" + chunkX + ";" + chunkZ;
    }

    private void register(World world, int chunkX, int chunkZ) {
        if (this.registry.putIfAbsent(registryKey(world, chunkX, chunkZ), Boolean.FALSE) == null) {
            this.registryDirty = true;
        }
    }

    private void unregister(World world, int chunkX, int chunkZ) {
        if (this.registry.remove(registryKey(world, chunkX, chunkZ)) != null) {
            this.registryDirty = true;
        }
    }

    private Path registryPath() {
        return this.plugin.getDataFolder().toPath().resolve(REGISTRY_FILE);
    }

    private void loadRegistry() {
        Path path = registryPath();
        if (Files.isRegularFile(path)) {
            try {
                for (String line : Files.readAllLines(path)) {
                    line = line.trim();
                    if (line.endsWith(RESET_MARK)) {
                        this.registry.put(line.substring(0, line.length() - RESET_MARK.length()), Boolean.TRUE);
                    } else if (!line.isEmpty()) {
                        this.registry.put(line, Boolean.FALSE);
                    }
                }
            } catch (IOException e) {
                this.plugin.getLogger().log(Level.WARNING, "Could not read " + REGISTRY_FILE, e);
            }
        }
    }

    private void saveRegistry() {
        Path path = registryPath();
        List<String> lines = new ArrayList<>(this.registry.size());
        this.registry.forEach((chunk, reset) -> lines.add(reset ? chunk + RESET_MARK : chunk));
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(REGISTRY_FILE + ".tmp");
            Files.write(temporary, lines);
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Could not write " + REGISTRY_FILE, e);
        }
    }

    /**
     * One line for a whole batch of chunks rather than one per block.
     */
    private void scheduleSummary() {
        if (this.summaryScheduled) {
            return;
        }
        this.summaryScheduled = true;
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            synchronized (this) {
                this.summaryScheduled = false;
                if (this.converted > 0) {
                    this.plugin.getLogger().info("Converted " + this.converted + " placed custom block(s) to packet display"
                            + (this.moved > 0 ? " (" + this.moved + " kept the place of their entities)" : ""));
                }
                if (this.restored > 0) {
                    this.plugin.getLogger().info("Gave " + this.restored + " placed custom block(s) their display entities back");
                }
                if (this.hitboxes > 0) {
                    this.plugin.getLogger().info("Refreshed the hitbox of " + this.hitboxes + " placed custom block(s) from their files");
                }
                this.converted = 0;
                this.moved = 0;
                this.restored = 0;
                this.hitboxes = 0;
            }
        }, 200L);
    }
}
