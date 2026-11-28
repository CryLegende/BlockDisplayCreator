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
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
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
import java.util.LinkedHashMap;
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
     * How many blocks up or down a player goes before what they are shown is worked out again.
     */
    private static final int HEIGHT_STEP = 4;
    private static final int HEIGHT_MARGIN = 2;
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
    /**
     * Ticks the parts of an animation played for one player take to glide back to rest when another one played for him
     * replaces it: a reaction cutting a scene short starts from there, without a jump.
     */
    private static final int REPLACE_GLIDE_TICKS = 3;
    /**
     * Ticks at least a part takes to glide back into a loop after an animation played for one player.
     */
    private static final int REJOIN_TICKS = 8;
    /**
     * Ticks between two reads of whether a player who does not move is dead and of his world (see readPlace).
     */
    private static final int FULL_READ_TICKS = 20;
    /**
     * Ticks between two checks of the {@code display.visible-if} of the blocks around each player.
     */
    private static final int VISIBILITY_PERIOD = 20;
    private static final String VISIBLE_IF = "display.visible-if";
    private static final String REGISTRY_FILE = "packet-display-chunks.txt";
    private static final String OPTIMISED_FILE = "animation-cache.txt";
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
     * What {@link AnimationOptimizer} keeps of each animation, by block and animation: the fingerprint of the
     * animation it was worked out for, a tab, then the frames kept. Saved in {@link #OPTIMISED_FILE}.
     */
    private final Map<String, String> optimised = new ConcurrentHashMap<>();
    private volatile boolean optimisedDirty;
    /**
     * Models whose animations are being worked out, off the main thread, one at a time.
     */
    private final Set<String> optimising = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService optimiser = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BlockDisplayCreator animation optimiser");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private int lightened;
    private long updatesBefore;
    private long updatesAfter;
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
     * The blocks someone sees whose model turns parts on and on ({@code spin}).
     */
    private final Set<PlacedDisplay> spinning = new HashSet<>();
    /**
     * The blocks with an animation started by a click or their placement that has not ended.
     */
    private final Set<PlacedDisplay> playing = new HashSet<>();
    /**
     * By player, the animations {@code /bdc animation play} plays for them alone.
     */
    private final Map<UUID, List<PrivatePlay>> privatePlays = new HashMap<>();
    /**
     * The blocks someone sees whose model has a {@code look-at} or an {@code approach} animation: the players who see
     * them are checked every tick. A block nobody sees any more leaves the list at the next tick.
     */
    private final List<PlacedDisplay> attentive = new ArrayList<>();
    /**
     * How many blocks further than its radius a player has to go before a {@code look-at} or an {@code approach}
     * lets him go: one standing on the edge is not let go and taken back over and over.
     */
    private static final double LEEWAY = 0.5;
    /**
     * Where a player's position is read into on a server where it cannot be read directly.
     */
    private final Location here = new Location(null, 0, 0, 0);
    /**
     * Where a player's place is read into for what he is shown (not shared with the other reads, done meanwhile).
     */
    private final Location placeHere = new Location(null, 0, 0, 0);
    private final org.joml.Vector3d direction = new org.joml.Vector3d();
    private final double[] centre = new double[3];
    /**
     * The chunks holding custom blocks shown through packets, kept on disk: a chunk wiped while it is not loaded
     * (value true) is cleaned of them when it next loads.
     */
    private final Map<String, Boolean> registry = new ConcurrentHashMap<>();
    private volatile boolean registryDirty;
    /**
     * Whether a model read since the last reload has a {@code display.visible-if}: only then are the blocks around
     * the players checked every second.
     */
    private volatile boolean anyConditional;
    private final Set<String> reportedConditions = new HashSet<>();

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
        /**
         * The blocks in range whose model has a {@code display.visible-if}, shown or not: their conditions are
         * checked again every second.
         */
        final Set<PlacedDisplay> guarded = new HashSet<>();
        /**
         * Among them, those not sent because their conditions are false for this player.
         */
        final Set<PlacedDisplay> withheld = new HashSet<>();
        /**
         * Per world and block, the entity ids this client was last sent for it and not told to remove. Kept across
         * world changes: should a client still draw an older showing of a block, it is removed before the block is
         * sent again with other ids, instead of staying on screen as a frozen copy without click box.
         */
        final Map<UUID, Map<Long, int[]>> sent = new HashMap<>();
        /**
         * The same for the models shown for other plugins, by model: they have no block of their own, and one may
         * stand at the position of a placed block.
         */
        final Map<PlacedDisplay, int[]> sentModels = new HashMap<>();
        UUID world;
        int chunkX;
        int chunkZ;
        /**
         * The height, in blocks, the player was at the last time what they are shown was worked out.
         */
        int y;
        boolean stale = true;
        /**
         * The blocks with a {@code look-at} or an {@code approach} animation this player is close to, and what he
         * was shown of them. Null until he comes close to one.
         */
        Map<PlacedDisplay, Nearby> near;
        /**
         * Where the player stood at the tick {@link #readAt}, read once a tick for all the blocks close to him: how many
         * times his place changed since he came, the object the server keeps his position in (null where it cannot be
         * read), his x, y, z, and the height of his eyes.
         */
        int readAt = -1;
        int moves;
        Object position;
        double x;
        double y0;
        double z;
        double eyes;
        /**
         * Whether he is dead and the world he is in, read again with his coordinates (see readPlace) at the tick
         * {@code fullReadAt}.
         */
        boolean dead;
        World world0;
        int fullReadAt = -1;
        /**
         * The block {@link #near(PlacedDisplay)} was last asked about, and its answer: a player close to one block
         * finds it without the map.
         */
        private PlacedDisplay lastPlaced;
        private Nearby lastNear;

        Viewer(Player player) {
            this.player = player;
        }

        @Nullable
        Nearby near(PlacedDisplay placed) {
            if (placed == this.lastPlaced) {
                return this.lastNear;
            }
            Nearby near = this.near == null ? null : this.near.get(placed);
            this.lastPlaced = placed;
            this.lastNear = near;
            return near;
        }

        /**
         * What he was shown close by is forgotten for every block, as he is read afresh (a respawn, a world change).
         */
        void forgetAllNear() {
            this.near = null;
            this.lastPlaced = null;
            this.lastNear = null;
            this.position = null;
            this.fullReadAt = -1;
        }

        /**
         * Whether the parts of the {@code look-at} of a block are turned towards this player on his client: he then
         * gets none of their frames.
         */
        boolean lookedAtBy(PlacedDisplay placed) {
            Nearby near = near(placed);
            return near != null && near.looking;
        }

        /**
         * The player no longer sees the block: what he was shown of it close by is forgotten (his client forgot it, or
         * is told to).
         */
        void forgetNear(PlacedDisplay placed) {
            if (this.near != null) {
                this.near.remove(placed);
            }
            if (placed == this.lastPlaced) {
                this.lastNear = null;
            }
        }
    }

    /**
     * What a player close to a block with a {@code look-at} or an {@code approach} animation was shown of it.
     */
    static final class Nearby {
        /**
         * Within the radius of the {@code look-at}.
         */
        boolean gazing;
        /**
         * His client shows the parts of the {@code look-at} turned towards him.
         */
        boolean looking;
        /**
         * The turn last sent, in degrees.
         */
        float yaw;
        float pitch;
        /**
         * The tick it was sent at.
         */
        int sentAt = Integer.MIN_VALUE / 2;
        /**
         * When the parts a frame takes longer than a turn to move get the rest of their way, turned towards him (a turn
         * leaves them where their frame has them by its end), -1 for never.
         */
        int resumeAt = -1;
        /**
         * Where he stood (how many times his place had changed) and how high his eyes were the last time nothing was
         * left to send him: while they stay the same, nothing is worked out again.
         */
        int settledMoves = -1;
        double settledEyes = Double.NaN;
        /**
         * For each {@code approach} animation of the model (bit i for the i-th), whether he is within its radius.
         */
        int approached;
        /**
         * For each loop of the model with a {@code distance} (bit i for the animation i), whether he is within it: he
         * gets its frames.
         */
        long loopsIn;

        boolean idle() {
            return !this.gazing && !this.looking && this.approached == 0 && this.loopsIn == 0;
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
         * Whether the player set it off by coming close ({@code approach}): his clicks on the block are not held back,
         * and end it.
         */
        final boolean approach;
        /**
         * The last frame played, -1 before the first.
         */
        int lastFrame = -1;
        /**
         * Whether it moved the whole model for him (root frames), where to, and the glide his client has for the
         * teleports of the carrier (-1 when not known).
         */
        boolean rootMoved;
        org.joml.Vector3f rootTarget;
        int carrierGlide = -1;
        /**
         * For each part of the model's {@code look-at}, where his client had it when the animation started, unturned
         * (null for its rest): the turns towards him start from there for the parts it has not moved yet. Null for all
         * at rest.
         */
        org.bukkit.util.Transformation[] lookFrom;

        PrivatePlay(PlacedDisplay placed, int animation, int startedAt, boolean approach) {
            this.placed = placed;
            this.animation = animation;
            this.startedAt = startedAt;
            this.approach = approach;
        }
    }

    /**
     * What became of a request to play an animation.
     */
    public enum PlayResult {
        PLAYED,
        /**
         * No custom block shown through packets at that position, or a model shown for another plugin that is no
         * longer shown.
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
        loadOptimised();
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
        guardHitbox(placed, data);
        add(placed);
        start(placed, DisplayAnimation.Trigger.PLACE, null);
    }

    /**
     * Plays the click animations of a custom block whose interaction was just clicked.
     *
     * @param interaction the name of the interaction, as written in the file of the block
     */
    public synchronized void clicked(Block block, String interaction) {
        clicked(block, interaction, null);
    }

    /**
     * Plays the click animations of a custom block whose interaction was just clicked.
     *
     * @param interaction the name of the interaction, as written in the file of the block
     * @param player      who clicked: an animation his approach set off on the block for him ends, its parts gliding
     *                    back, so that he sees the click animations as the others do
     */
    public synchronized void clicked(Block block, String interaction, @Nullable Player player) {
        Map<Long, List<PlacedDisplay>> chunks = this.index.get(block.getWorld().getUID());
        List<PlacedDisplay> list = chunks == null ? null : chunks.get(chunkKey(block.getX() >> 4, block.getZ() >> 4));
        if (list == null) {
            return;
        }
        for (PlacedDisplay placed : list) {
            if (placed.x == block.getX() && placed.y == block.getY() && placed.z == block.getZ()) {
                if (player != null && placed.privateFor.contains(player.getUniqueId())) {
                    List<PrivatePlay> plays = this.privatePlays.get(player.getUniqueId());
                    for (Iterator<PrivatePlay> iterator = plays == null ? null : plays.iterator(); iterator != null && iterator.hasNext(); ) {
                        PrivatePlay play = iterator.next();
                        if (play.placed == placed && play.approach) {
                            iterator.remove();
                            end(player.getUniqueId(), play, true);
                        }
                    }
                    if (plays != null && plays.isEmpty()) {
                        this.privatePlays.remove(player.getUniqueId());
                    }
                }
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
        return playOn(find(block.getWorld(), block.getX(), block.getY(), block.getZ()), name, players);
    }

    private PlayResult playOn(@Nullable PlacedDisplay placed, String name, @Nullable java.util.Collection<? extends Player> players) {
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
            placed.publicStamp++;
            this.playing.add(placed);
            return PlayResult.PLAYED;
        }
        for (Player player : players) {
            startPrivate(player, placed, index, false);
        }
        return PlayResult.PLAYED;
    }

    /**
     * Starts an animation of a block for a player alone, from its first frame at the next tick: one this block was
     * playing for him stops.
     *
     * @param approach whether his coming close set it off
     */
    private void startPrivate(Player player, PlacedDisplay placed, int index, boolean approach) {
        UUID id = player.getUniqueId();
        List<PrivatePlay> plays = this.privatePlays.computeIfAbsent(id, k -> new ArrayList<>(1));
        PrivatePlay play = new PrivatePlay(placed, index, this.tick + 1, approach);
        play.carrierGlide = placed.carrierGlide;
        Viewer viewer = this.viewers.get(id);
        boolean seen = viewer != null && viewer.player == player && viewer.shown.contains(placed);
        if (placed.carrierId >= 0) {
            // he gets none of the others' frames from now on: the whole model stays for him where they have it now
            play.rootTarget = placed.rootTarget(this.tick, viewer == null || viewer.player != player ? 0L : skipLoops(viewer, placed));
        }
        LookAt lookAt = placed.model.lookAt;
        boolean replaced = false;
        for (Iterator<PrivatePlay> iterator = plays.iterator(); iterator.hasNext(); ) {
            PrivatePlay old = iterator.next();
            if (old.placed == placed) {
                // started again: the parts the other animation moved go back to rest, the frames take the rest
                iterator.remove();
                replaced = true;
                if (lookAt != null) {
                    // the parts of the look-at are where the other one has them
                    play.lookFrom = placed.privatePoses(old.animation, old.lastFrame, this.tick - old.startedAt, old.lookFrom, 0).targets();
                }
                if (old.animation != index && seen) {
                    // gliding: the new one's first frames take the parts they move from wherever they are by then; those
                    // turned towards him go back to rest turned so
                    boolean turned = lookAt != null && viewer.lookedAtBy(placed);
                    Object rest = placed.restPacket(this.nms, old.animation, REPLACE_GLIDE_TICKS, turned);
                    if (rest != null) {
                        this.nms.send(player, rest);
                    }
                    if (lookAt != null) {
                        boolean moved = false;
                        for (int p = 0; p < lookAt.parts.length; p++) {
                            if (java.util.Arrays.binarySearch(placed.model.animations.get(old.animation).touched, lookAt.parts[p]) >= 0) {
                                play.lookFrom[p] = null;
                                moved = true;
                            }
                        }
                        if (turned && moved) {
                            Nearby near = viewer.near(placed);
                            this.nms.send(player, placed.turnPacket(this.nms, near.yaw, near.pitch, REPLACE_GLIDE_TICKS,
                                    placed.privatePoses(index, -1, 0, play.lookFrom, REPLACE_GLIDE_TICKS)));
                        }
                    }
                }
                // the whole model stays where the other one took it, and goes back to the others once this one ends
                play.rootMoved = old.rootMoved;
                play.rootTarget = old.rootTarget;
                play.carrierGlide = old.carrierGlide;
            }
        }
        if (lookAt != null && seen && !replaced) {
            // where his client has the parts of the look-at: turned from where the frames that keep them looking have
            // them, or where all the frames he gets have them
            long skip = skipLoops(viewer, placed);
            play.lookFrom = viewer.lookedAtBy(placed) ? placed.model.turnsFromFrames
                    ? placed.publicPoses(lookAt.parts, this.tick, 0, skip | placed.model.unlookedLoops).targets() : null
                    : placed.publicPoses(lookAt.parts, this.tick, 0, skip).targets();
        }
        plays.add(play);
        placed.privateFor.add(id);
        // whether it takes the parts of the look-at from him is worked out again (see suspends)
        placed.privateStamp++;
    }

    /**
     * Stops the animations a command plays on the custom block of a block.
     *
     * @param players the players whose own animations are stopped: they see the block as the others do. Null stops
     *                those played for everyone, and the block goes back to rest
     * @return the number of animations stopped, -1 when there is no custom block shown through packets there
     */
    public synchronized int stop(Block block, @Nullable java.util.Collection<? extends Player> players) {
        return stopOn(find(block.getWorld(), block.getX(), block.getY(), block.getZ()), players);
    }

    private int stopOn(@Nullable PlacedDisplay placed, @Nullable java.util.Collection<? extends Player> players) {
        if (placed == null || placed.model == null) {
            return -1;
        }
        int stopped = 0;
        if (players == null) {
            List<DisplayAnimation> animations = placed.model.animations;
            boolean rooted = false;
            // the parts they moved that a loop moves too: where the loop has them, at once, rather than at rest
            boolean[] fromLoops = null;
            for (int i = 0; i < animations.size(); i++) {
                if (animations.get(i).trigger == DisplayAnimation.Trigger.LOOP || placed.lastFrame[i] < 0 && placed.startedAt[i] < 0) {
                    continue;
                }
                if (placed.startedAt[i] >= 0) {
                    stopped++;
                }
                for (int part : animations.get(i).touched) {
                    if (placed.model.looped[part]) {
                        if (fromLoops == null) {
                            fromLoops = new boolean[placed.entityIds.length];
                            java.util.Arrays.fill(fromLoops, true);
                        }
                        fromLoops[part] = false;
                    }
                }
                rooted |= animations.get(i).rooted && placed.lastFrame[i] >= 0;
                placed.startedAt[i] = -1;
                placed.publicStamp++;
                placed.lastFrame[i] = -1;
                Object packet = placed.restPacket(this.nms, i);
                for (Viewer viewer : placed.viewers) {
                    if (placed.privateFor.contains(viewer.player.getUniqueId())) {
                        continue;
                    }
                    if (placed.model.lookAt != null && viewer.lookedAtBy(placed) && placed.model.movesLook[i]) {
                        // the parts turned towards him go back at once where the others' frames now have them, turned so
                        Object rest = placed.restPacket(this.nms, i, 0, true);
                        if (rest != null) {
                            this.nms.send(viewer.player, rest);
                        }
                        Nearby near = viewer.near(placed);
                        this.nms.send(viewer.player, placed.turnPacket(this.nms, near.yaw, near.pitch, 0, this.tick, skipLoops(viewer, placed)));
                        continue;
                    }
                    this.nms.send(viewer.player, packet);
                }
            }
            if (fromLoops != null && placed.loops()) {
                for (Viewer viewer : placed.viewers) {
                    if (placed.privateFor.contains(viewer.player.getUniqueId())) {
                        continue;
                    }
                    boolean unturned = placed.model.lookAt != null && viewer.lookedAtBy(placed);
                    for (int i = 0; i < animations.size(); i++) {
                        DisplayAnimation loop = animations.get(i);
                        if (loop.trigger != DisplayAnimation.Trigger.LOOP || loop.distance > 0 && !plays(viewer, placed, i)) {
                            continue;
                        }
                        Object packet = placed.rejoinPacket(this.nms, i, this.tick % loop.duration, this.tick >= loop.duration, 0, unturned, fromLoops);
                        if (packet != null) {
                            this.nms.send(viewer.player, packet);
                        }
                    }
                }
            }
            if (rooted && placed.model.hasCarrier() && !placed.viewers.isEmpty()) {
                // the whole model goes back at once to where the loops have it, as the parts do
                carrierToLoops(placed);
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
        for (int i = 0; i < placed.model.animations.size(); i++) {
            DisplayAnimation animation = placed.model.animations.get(i);
            // with a look-at, for one moving its parts: whether they keep looking at the player while it plays, leave him
            // for its time, or (a loop) stay at rest for him
            String look = placed.model.lookAt == null || !placed.model.movesLook[i] ? ""
                    : placed.model.composesLook[i] ? ", keeps the look"
                    : animation.trigger == DisplayAnimation.Trigger.LOOP ? ", look parts at rest" : ", stops the look";
            names.add(animation.name + " (" + animation.trigger.name().toLowerCase(java.util.Locale.ROOT) + ", " + animation.duration + " ticks"
                    + (animation.eased > 0 ? ", eased: " + animation.eased + " more frames" : "") + look + ")");
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
        end(id, play, false);
    }

    /**
     * Ends an animation played for a player: they see the block as the others do again.
     *
     * @param glide whether the parts it moved glide back (cut short by a click) rather than being put back at once
     */
    private void end(UUID id, PrivatePlay play, boolean glide) {
        PlacedDisplay placed = play.placed;
        placed.privateFor.remove(id);
        placed.privateStamp++;
        Viewer viewer = this.viewers.get(id);
        if (viewer != null && viewer.player.isOnline() && viewer.shown.contains(placed)) {
            if (placed.model.conditional() && !visibleTo(viewer.player, placed)) {
                // its conditions turned false while it played (the click that started it marked it found): it
                // goes now, from where the animation left it
                withhold(viewer, placed);
                return;
            }
            LookAt lookAt = placed.model.lookAt;
            // the parts turned towards him (the animation kept them looking at him) stay so, and go where the others'
            // frames have them, turned
            boolean turned = lookAt != null && viewer.lookedAtBy(placed);
            int[] touched = placed.model.animations.get(play.animation).touched;
            if (glide) {
                int[] parts = turned ? java.util.Arrays.stream(touched).filter(part -> !lookAt.turns(part)).toArray() : touched;
                if (parts.length > 0) {
                    this.nms.send(viewer.player, placed.publicPacket(this.nms, parts, this.tick, REJOIN_TICKS, skipLoops(viewer, placed)));
                }
            } else {
                Object rest = placed.restPacket(this.nms, play.animation, 0, turned);
                if (rest != null) {
                    this.nms.send(viewer.player, rest);
                }
            }
            if (turned && (placed.model.movesLook[play.animation] || placed.model.lookingLoops != 0)) {
                PlacedDisplay.PublicPoses after = placed.lookPoses(this.tick, REJOIN_TICKS, skipLoops(viewer, placed));
                if (glide || !placed.samePlace(privateLook(play, 0), after)) {
                    // nothing is sent when the animation left them where the others' frames have them (back at rest)
                    Nearby near = viewer.near(placed);
                    this.nms.send(viewer.player, placed.turnPacket(this.nms, near.yaw, near.pitch, REJOIN_TICKS, after));
                }
            }
            catchUp(viewer, placed, true);
            if (play.rootMoved && placed.model.hasCarrier()) {
                rejoinCarrier(viewer, placed, play);
            }
        }
    }

    /**
     * Puts the carrier of a block at once where the frames each player who sees it gets have it (those of the loops
     * with a distance only within it); the players watching an animation played for them alone keep theirs.
     */
    private void carrierToLoops(PlacedDisplay placed) {
        carrierToLoops(placed, 0);
    }

    /**
     * @param ticks the time it takes to get there, 0 for at once
     */
    private void carrierToLoops(PlacedDisplay placed, int ticks) {
        Object shared = null;
        for (Viewer viewer : placed.viewers) {
            if (placed.privateFor.contains(viewer.player.getUniqueId())) {
                continue;
            }
            long skip = skipLoops(viewer, placed) & placed.model.distanceRooted;
            Object packet = skip == 0 && shared != null ? shared
                    : placed.carrierMove(this.nms, placed.rootTarget(this.tick, skip), ticks, placed.carrierGlide, placed.carrierGlide);
            if (skip == 0) {
                shared = packet;
            }
            this.nms.send(viewer.player, packet);
        }
    }

    /**
     * Takes the whole model back, gliding, to where the others see it, for a player an animation that moved it was
     * played for, and gives his client the glide the others have.
     */
    private void rejoinCarrier(Viewer viewer, PlacedDisplay placed, PrivatePlay play) {
        org.joml.Vector3f target = placed.rootTarget(this.tick, skipLoops(viewer, placed));
        boolean there = java.util.Objects.equals(target, play.rootTarget) || target == null && play.rootTarget != null && play.rootTarget.lengthSquared() < 1.0E-12f;
        if (!there) {
            this.nms.send(viewer.player, placed.carrierMove(this.nms, target, REJOIN_TICKS, play.carrierGlide, placed.carrierGlide));
        } else if (placed.carrierGlide >= 0 && play.carrierGlide != placed.carrierGlide) {
            this.nms.send(viewer.player, this.nms.dataPacket(placed.carrierId, this.nms.teleportDurationValues(placed.carrierGlide)));
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
        placed.privateStamp++;
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
                placed.publicStamp++;
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

    // ---------- Models shown for other plugins ----------

    /**
     * A model shown for another plugin: nothing of it is stored in the world. Given by
     * {@link #show(org.bukkit.plugin.Plugin, String, Location, float)}.
     */
    public static final class ShownModel {
        private final org.bukkit.plugin.Plugin owner;
        private final String model;
        private final Location location;
        /**
         * What draws it, null once it is no longer shown.
         */
        PlacedDisplay placed;

        private ShownModel(org.bukkit.plugin.Plugin owner, String model, Location location) {
            this.owner = owner;
            this.model = model;
            this.location = location;
        }

        /**
         * The name of its custom block file.
         */
        public String model() {
            return this.model;
        }

        /**
         * Where it was shown: the point it stands on, as given, with its yaw. A copy.
         */
        public Location location() {
            return this.location.clone();
        }

        /**
         * The plugin it was shown for.
         */
        public org.bukkit.plugin.Plugin owner() {
            return this.owner;
        }

        @Override
        public String toString() {
            return "ShownModel[" + this.model + " for " + this.owner.getName() + " at " + this.location.getX() + " " + this.location.getY() + " "
                    + this.location.getZ() + " in " + (this.location.getWorld() == null ? "?" : this.location.getWorld().getName()) + "]";
        }
    }

    /**
     * The models shown for other plugins, by world and chunk. Kept apart from the placed blocks: nothing that finds,
     * breaks, reads, wipes or counts the blocks of a position or a chunk reaches them, and they leave nothing in the
     * chunk nor in {@link #REGISTRY_FILE}.
     */
    private final Map<UUID, Map<Long, List<PlacedDisplay>>> shownModels = new ConcurrentHashMap<>();

    /**
     * Shows the model of a custom block file for another plugin, as a block of that file placed there would be shown,
     * without anything being written in the world: no block, no chunk data, no entity on the server.
     * <p>
     * Players are sent it as they are a placed block: within the {@code display.view-distance}, {@code view-above}
     * and {@code view-below} of its file once they have its chunk, with its loops (and their {@code distance}), its
     * {@code look-at}, its {@code approach} animations, its {@code root} frames and its {@code display.visible-if}; a
     * player arriving gets it where its animations have taken it. Its {@code place} animations do not play. None of
     * the click boxes and collisions of its file is created and this plugin handles no click on it: the other plugin
     * puts its own click box if it wants one. Breaking, the gizmo, the settings of a block and whatever else is done to
     * placed blocks leave it alone, a block placed at the same position included, and it leaves them alone.
     * <p>
     * It stays shown until {@link #hide(ShownModel)} or until this plugin lets it go: its chunk or its world unloads,
     * its file is gone or draws nothing after a reload, packet display is turned off, this plugin or its owner is
     * disabled. {@link #shows(ShownModel)} then turns false; the owner shows it again when it wants it back.
     * <p>
     * Main thread only, as every method of the renderer.
     *
     * @param owner the plugin it is shown for: its models are taken away when it is disabled
     * @param model the name of the custom block file
     * @param where the point it stands on (its feet), as it is rather than the corner or the middle of a block, and
     *              its yaw, which turns it as it turns an entity: any angle, the {@code sides-count} of the file does
     *              not apply. Its chunk must be loaded
     * @param size  what the size of the model is multiplied by, around that point: 1 for the size of its file
     * @return what was shown, or null when the file is unknown, draws nothing or keeps its model in its item, the
     * chunk is not loaded, packet display is off, or the owner is not enabled; null too the first time a model is
     * asked for from inside a command (one of the owner's commands, or an event a command fires): it is read from its
     * file at the next tick, and shown when asked again
     * @throws IllegalArgumentException when the size is not a positive number or the location has no world
     */
    @Nullable
    public synchronized ShownModel show(org.bukkit.plugin.Plugin owner, String model, Location where, float size) {
        java.util.Objects.requireNonNull(owner, "owner");
        java.util.Objects.requireNonNull(model, "model");
        World world = where.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("The location to show " + model + " at has no world");
        }
        if (!(size > 0) || Float.isInfinite(size)) {
            throw new IllegalArgumentException("The size of " + model + " must be a positive number, not " + size);
        }
        int chunkX = where.getBlockX() >> 4;
        int chunkZ = where.getBlockZ() >> 4;
        if (!isActive() || !owner.isEnabled() || !this.plugin.isEnabled() || !world.isChunkLoaded(chunkX, chunkZ)) {
            return null;
        }
        DisplayModel read = modelFor(model, world.getChunkAt(chunkX, chunkZ));
        if (read == null) {
            return null;
        }
        PlacedDisplay placed = new PlacedDisplay(world, where.getBlockX(), where.getBlockY(), where.getBlockZ(), model,
                new me.general_breddok.blockdisplaycreator.custom.block.BDCCustomBlockRotation(org.bukkit.block.BlockFace.UP, where.getYaw()));
        placed.baseX = where.getX();
        placed.baseY = where.getY();
        placed.baseZ = where.getZ();
        // its size is applied as an operator's resize is, around the point it stands on
        placed.shift[5] = size;
        ShownModel shown = new ShownModel(owner, model, where.clone());
        shown.placed = placed;
        placed.shownFor = shown;
        placed.bind(read, this.nms);
        this.shownModels.computeIfAbsent(world.getUID(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey(chunkX, chunkZ), k -> new ArrayList<>(1)).add(placed);
        showModel(placed);
        return shown;
    }

    /**
     * Takes a model shown for another plugin away from every player. Nothing happens when it is no longer shown.
     */
    public synchronized void hide(ShownModel shown) {
        PlacedDisplay placed = shown.placed;
        if (placed == null) {
            return;
        }
        Map<Long, List<PlacedDisplay>> chunks = this.shownModels.get(placed.world.getUID());
        long key = chunkKey(placed.chunkX(), placed.chunkZ());
        List<PlacedDisplay> list = chunks == null ? null : chunks.get(key);
        if (list != null) {
            list.remove(placed);
            if (list.isEmpty()) {
                chunks.remove(key);
            }
        }
        drop(placed);
    }

    /**
     * Whether a model shown for another plugin is still shown: false once hidden, or let go by this plugin (see
     * {@link #show(org.bukkit.plugin.Plugin, String, Location, float)}).
     */
    public synchronized boolean shows(ShownModel shown) {
        return shown != null && shown.placed != null;
    }

    /**
     * Plays an animation of a model shown for another plugin from its start, whatever starts it in its file, as
     * {@link #play(Block, String, java.util.Collection)} does on a placed block.
     *
     * @param players the players it is played for, the only ones who see it and hear its sounds: the others keep
     *                seeing the model as it is, and once it ends these players see it as the others do. Null plays it
     *                for everyone who sees the model and those who come to see it while it plays
     * @return {@link PlayResult#NO_BLOCK} when the model is no longer shown
     */
    public synchronized PlayResult play(ShownModel shown, String animation, @Nullable java.util.Collection<? extends Player> players) {
        return playOn(shown.placed, animation, players);
    }

    /**
     * Stops the animations played on a model shown for another plugin, as {@link #stop(Block, java.util.Collection)}
     * does on a placed block.
     *
     * @param players the players whose own animations are stopped: they see the model as the others do. Null stops
     *                those played for everyone, and the model goes back to rest
     * @return the number of animations stopped, -1 when the model is no longer shown
     */
    public synchronized int stop(ShownModel shown, @Nullable java.util.Collection<? extends Player> players) {
        return stopOn(shown.placed, players);
    }

    /**
     * Whether a model of that name can be shown: its file is known, draws something and does not keep its model in
     * its item, and packet display is on. Read from the file the first time it is asked, as a placement would.
     */
    public synchronized boolean knows(String model) {
        if (!isActive()) {
            return false;
        }
        if (modelFor(model, null) != null) {
            return true;
        }
        if (this.unavailableModels.containsKey(model)) {
            // its last reading found no file, or nothing drawn
            return false;
        }
        // not read yet (asked from inside a command, or no chunk loaded): its file says
        AbstractCustomBlock definition = definition(model);
        return definition != null && !"item".equals(definition.getSaveSystem());
    }

    /**
     * The names of all the animations of a model, whatever starts them, in the order of its file; empty for an unknown
     * model.
     */
    public synchronized Set<String> animations(String model) {
        Set<String> names = new java.util.LinkedHashSet<>();
        DisplayModel read = isActive() ? modelFor(model, null) : null;
        if (read != null) {
            for (DisplayAnimation animation : read.animations) {
                names.add(animation.name);
            }
        } else if (isActive() && !this.unavailableModels.containsKey(model)) {
            // not read yet: its file says
            ConfigurationSection section = animationSection(model);
            if (section != null) {
                names.addAll(section.getKeys(false));
            }
        }
        return java.util.Collections.unmodifiableSet(names);
    }

    /**
     * How long an animation of a model lasts, in ticks: the time after which one played for a player alone ends, and
     * a loop starts over. Another plugin asks it to know when a scene it played is over.
     *
     * @param model     the name of the custom block file
     * @param animation the name of the animation, whatever case it is written in
     * @return its length in ticks, -1 when the model or the animation is unknown (or the model cannot be read now, see
     * {@link #show(org.bukkit.plugin.Plugin, String, Location, float)})
     */
    public synchronized int duration(String model, String animation) {
        DisplayModel read = isActive() && model != null && animation != null ? modelFor(model, null) : null;
        if (read == null) {
            return -1;
        }
        int index = animationIndex(read, animation);
        return index < 0 ? -1 : read.animations.get(index).duration;
    }

    /**
     * The box that holds what a model draws at rest: {minX, minY, minZ, maxX, maxY, maxZ}, in blocks around the point
     * it is shown at, in the model's own axes (before the yaw it is shown with turns it) and at the size of its file
     * (to be multiplied by the size it is shown at). Null for an unknown model, or one not read yet (see
     * {@link #show(org.bukkit.plugin.Plugin, String, Location, float)}).
     */
    public synchronized double @Nullable [] bounds(String model) {
        DisplayModel read = isActive() ? modelFor(model, null) : null;
        if (read == null) {
            return null;
        }
        float[] box = read.bounds();
        double[] bounds = new double[6];
        for (int i = 0; i < 6; i++) {
            bounds[i] = box[i];
        }
        return bounds;
    }

    /**
     * The model of a file, for a model shown for another plugin: read now when it has not been, in a chunk whose
     * entities are loaded (the given one when it can).
     * <p>
     * A model is read by running its summon commands. Inside a command (another plugin's command, or an event it
     * fires), those would only run once that command ends: nothing would be read, and the entities they summon would
     * stay in the world. It is then read at the next tick, and null is given until then.
     *
     * @return null when it cannot be read, or not now
     */
    @Nullable
    private DisplayModel modelFor(String name, @Nullable Chunk preferred) {
        DisplayModel model = this.models.get(name);
        if (model != null) {
            return model;
        }
        if (this.nms.inCommand() && !this.nms.canRunCommandsNow()) {
            // a server where the command running cannot be set aside: read at the next tick
            readSoon(name);
            return null;
        }
        Chunk chunk = preferred != null && preferred.isEntitiesLoaded() ? preferred : someLoadedChunk();
        return chunk == null ? null : model(name, chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    /**
     * The models asked for from inside a command, read at the next tick.
     */
    private final Set<String> readingSoon = new HashSet<>();

    private void readSoon(String name) {
        if (!this.readingSoon.add(name) || !this.plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            synchronized (this) {
                this.readingSoon.remove(name);
                if (isActive() && !this.nms.inCommand()) {
                    modelFor(name, null);
                }
            }
        });
    }

    /**
     * A chunk whose entities are loaded, to read a model in: where a player stands, else the spawn of a world, else
     * any.
     */
    @Nullable
    private Chunk someLoadedChunk() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location at = player.getLocation(this.here);
            World world = at.getWorld();
            if (world != null && world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                Chunk chunk = world.getChunkAt(at.getBlockX() >> 4, at.getBlockZ() >> 4);
                if (chunk.isEntitiesLoaded()) {
                    return chunk;
                }
            }
        }
        for (World world : Bukkit.getWorlds()) {
            Location spawn = world.getSpawnLocation();
            if (world.isChunkLoaded(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4)) {
                Chunk chunk = world.getChunkAt(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4);
                if (chunk.isEntitiesLoaded()) {
                    return chunk;
                }
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                if (chunk.isEntitiesLoaded()) {
                    return chunk;
                }
            }
        }
        return null;
    }

    @Nullable
    private AbstractCustomBlock definition(String name) {
        return this.plugin.getCustomBlockService().getStorage() instanceof BDCCustomBlockConfigStorage storage ? storage.peek(name)
                : this.plugin.getCustomBlockService().getStorage().getAbstractCustomBlock(name);
    }

    /**
     * Sends a model shown for another plugin to the players already close enough to see it who have its chunk; the
     * others get it at their next look at where they are.
     */
    private void showModel(PlacedDisplay placed) {
        UUID world = placed.world.getUID();
        for (Viewer viewer : this.viewers.values()) {
            if (!viewer.stale && world.equals(viewer.world)
                    && inRange(viewer, placed, 0) && !viewer.shown.contains(placed) && !viewer.withheld.contains(placed)) {
                if (chunkSent(viewer.player, placed.chunkX(), placed.chunkZ())) {
                    reach(viewer, placed);
                } else {
                    viewer.stale = true;
                }
            }
        }
    }

    /**
     * Sends a player the models shown for other plugins in the chunks around him, as {@link #update(Player)} does the
     * placed blocks.
     */
    private void showModelsAround(Viewer viewer, World world, int chunkX, int chunkZ, int radius) {
        Map<Long, List<PlacedDisplay>> chunks = this.shownModels.get(world.getUID());
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

    /**
     * Takes a model shown for another plugin away from its viewers, and from the clients that may still draw it
     * without being among them (a player who respawned or changed world, until the next look at where he is): no
     * frozen copy is left. It is then no longer shown; the caller has taken it out of {@link #shownModels}.
     */
    private void drop(PlacedDisplay placed) {
        hide(placed);
        // its sounds would go on without it
        this.playing.remove(placed);
        for (Viewer viewer : this.viewers.values()) {
            int[] ids = viewer.sentModels.remove(placed);
            if (ids != null && viewer.player.isOnline()) {
                this.nms.send(viewer.player, this.nms.removePacket(ids));
            }
        }
        if (placed.shownFor != null) {
            placed.shownFor.placed = null;
        }
    }

    /**
     * Lets go the models shown for other plugins in a chunk that unloaded.
     */
    private void dropModels(World world, int chunkX, int chunkZ) {
        Map<Long, List<PlacedDisplay>> chunks = this.shownModels.get(world.getUID());
        List<PlacedDisplay> list = chunks == null ? null : chunks.remove(chunkKey(chunkX, chunkZ));
        if (list != null) {
            list.forEach(this::drop);
        }
    }

    /**
     * Whether models are shown for other plugins in a chunk. Read on the thread of its world.
     */
    private boolean showsIn(Chunk chunk) {
        Map<Long, List<PlacedDisplay>> chunks = this.shownModels.get(chunk.getWorld().getUID());
        return chunks != null && chunks.containsKey(chunkKey(chunk.getX(), chunk.getZ()));
    }

    /**
     * Gives the models shown for other plugins their file again after a reload, of every file or of one: a model
     * whose file is gone or draws nothing any more is let go.
     *
     * @param name the file read again, null for all of them
     */
    private void reloadModels(@Nullable String name) {
        for (Map<Long, List<PlacedDisplay>> chunks : this.shownModels.values()) {
            for (Iterator<List<PlacedDisplay>> lists = chunks.values().iterator(); lists.hasNext(); ) {
                List<PlacedDisplay> list = lists.next();
                for (Iterator<PlacedDisplay> iterator = list.iterator(); iterator.hasNext(); ) {
                    PlacedDisplay placed = iterator.next();
                    if (name != null && !placed.modelName.equals(name)) {
                        continue;
                    }
                    hide(placed);
                    this.playing.remove(placed);
                    DisplayModel model = isActive() ? modelFor(placed.modelName, placed.world.getChunkAt(placed.chunkX(), placed.chunkZ())) : null;
                    if (model == null) {
                        iterator.remove();
                        drop(placed);
                    } else {
                        // new ids: a client still drawing the old ones is told to remove them when it gets the new
                        placed.bind(model, this.nms);
                        showModel(placed);
                    }
                }
                if (list.isEmpty()) {
                    lists.remove();
                }
            }
        }
    }

    /**
     * Takes away the models shown for a plugin being disabled.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(org.bukkit.event.server.PluginDisableEvent event) {
        synchronized (this) {
            for (Map<Long, List<PlacedDisplay>> chunks : this.shownModels.values()) {
                for (Iterator<List<PlacedDisplay>> lists = chunks.values().iterator(); lists.hasNext(); ) {
                    List<PlacedDisplay> list = lists.next();
                    for (Iterator<PlacedDisplay> iterator = list.iterator(); iterator.hasNext(); ) {
                        PlacedDisplay placed = iterator.next();
                        if (placed.shownFor != null && placed.shownFor.owner == event.getPlugin()) {
                            iterator.remove();
                            drop(placed);
                        }
                    }
                    if (list.isEmpty()) {
                        lists.remove();
                    }
                }
            }
        }
    }

    /**
     * How many models are shown for other plugins.
     */
    private int shownCount() {
        int count = 0;
        for (Map<Long, List<PlacedDisplay>> chunks : this.shownModels.values()) {
            for (List<PlacedDisplay> list : chunks.values()) {
                count += list.size();
            }
        }
        return count;
    }

    /**
     * The chunks of the placed blocks, then those of the models shown for other plugins: for what applies to every
     * model drawn.
     */
    private List<Map<Long, List<PlacedDisplay>>> allChunks() {
        List<Map<Long, List<PlacedDisplay>>> all = new ArrayList<>(this.index.values());
        all.addAll(this.shownModels.values());
        return all;
    }

    // ---------- Moving blocks (operators) ----------

    /**
     * The placed blocks a player is sent at the moment: the models shown for other plugins are theirs.
     */
    synchronized List<PlacedDisplay> shownTo(Player player) {
        Viewer viewer = this.viewers.get(player.getUniqueId());
        if (viewer == null) {
            return List.of();
        }
        List<PlacedDisplay> blocks = new ArrayList<>(viewer.shown.size());
        for (PlacedDisplay placed : viewer.shown) {
            if (placed.shownFor == null) {
                blocks.add(placed);
            }
        }
        return blocks;
    }

    /**
     * A placed block a line of sight meets, and how far from the eyes.
     */
    record Aimed(PlacedDisplay placed, double distance) {
    }

    /**
     * The placed block a player aims at by what it draws, the nearest his line of sight meets within a distance: only
     * among the blocks he is sent, and of those only the ones close enough for what they draw to reach that far.
     */
    @Nullable
    synchronized Aimed aimed(Player player, double within) {
        Viewer viewer = this.viewers.get(player.getUniqueId());
        if (viewer == null || viewer.player != player || viewer.shown.isEmpty()) {
            return null;
        }
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector look = eye.getDirection();
        org.joml.Vector3d from = new org.joml.Vector3d(eye.getX(), eye.getY(), eye.getZ());
        org.joml.Vector3d along = new org.joml.Vector3d(look.getX(), look.getY(), look.getZ()).normalize();
        World world = player.getWorld();
        PlacedDisplay best = null;
        double nearest = within;
        for (PlacedDisplay placed : viewer.shown) {
            if (placed.shownFor != null || placed.world != world || placed.model == null || placed.model.elements.isEmpty()) {
                continue;
            }
            // what it draws stands around where it was placed and moved: one too far away is not looked at further
            double reach = nearest + placed.model.boundsRadius() * placed.scale() + 1;
            double dx = placed.baseX + placed.shift[0] - from.x;
            double dy = placed.baseY + placed.shift[1] - from.y;
            double dz = placed.baseZ + placed.shift[2] - from.z;
            if (placed.fixedPose == null && dx * dx + dy * dy + dz * dz > reach * reach) {
                continue;
            }
            double at = placed.hit(from, along, nearest);
            if (at >= 0 && at < nearest) {
                nearest = at;
                best = placed;
            }
        }
        return best == null ? null : new Aimed(best, nearest);
    }

    /**
     * Where a click box drawn on a block stands in the world, as its hitbox will be summoned and laid out where its
     * model now is: for each box it becomes, x, y, z of the middle of its floor, then its width and height.
     *
     * @param box width, height, floor, x and z, at the size its file gives
     */
    synchronized List<double[]> clickBoxesFor(PlacedDisplay placed, double[] box) {
        return BDCCustomBlockService.customClickBoxes(placed.rotation, placed.model.sidesCount, placed.base(),
                placed.placedPose(placed.model.elements.get(0).pitch()), placed.shift, box);
    }

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
        List<Object> packets = new ArrayList<>(placed.allIds.length);
        for (int id : placed.allIds) {
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
        if (placed.carrierId >= 0) {
            // every client now has this glide for the carrier: the next frame moving the whole model gives its own
            placed.carrierGlide = GLIDE_TICKS;
            for (List<PrivatePlay> plays : this.privatePlays.values()) {
                for (PrivatePlay play : plays) {
                    if (play.placed == placed) {
                        play.carrierGlide = GLIDE_TICKS;
                    }
                }
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
        List<Object> packets = new ArrayList<>(elements.size() * 2 + 1);
        double[] carrier = null;
        boolean ownCarrier = placed.carrierId >= 0 && placed.model.distanceRooted != 0;
        if (placed.carrierId >= 0) {
            // the parts riding it follow; they are still teleported for the way they are turned
            carrier = placed.carrierAt(placed.rootTarget(this.tick));
            if (!ownCarrier) {
                packets.add(this.nms.teleportPacket(placed.carrierId, carrier[0], carrier[1], carrier[2], (float) carrier[3], (float) carrier[4]));
            }
        }
        for (int i = 0; i < elements.size(); i++) {
            double[] pose = placed.pose(elements.get(i).pitch());
            // a spinning part keeps turned as far as the spin has it
            float turned = (float) pose[3] + (placed.model.spin != null && placed.model.spin.turns(i) && placed.spins() ? placed.model.spin.angle(this.tick) : 0f);
            if (carrier != null && placed.model.carried(i)) {
                packets.add(this.nms.teleportPacket(placed.entityIds[i], carrier[0], carrier[1], carrier[2], turned, (float) pose[4]));
            } else {
                packets.add(this.nms.teleportPacket(placed.entityIds[i], pose[0], pose[1] - placed.sink(i), pose[2], turned, (float) pose[4]));
            }
            if ((resized || elements.get(i).sink() > 0) && this.nms.canAnimate()) {
                // each part goes to its resting pose, multiplied by the new size, raised as much as it stands lower
                // than the block; a loop corrects it at its next frame
                org.bukkit.util.Transformation rest = elements.get(i).transformation();
                packets.add(this.nms.dataPacket(placed.entityIds[i], placed.sent(this.nms, i, this.nms.poseValues(rest.getTranslation(),
                        rest.getLeftRotation(), rest.getScale(), rest.getRightRotation(), GLIDE_TICKS))));
            }
        }
        Object packet = this.nms.bundle(packets);
        boolean rested = (resized || placed.model.sinks) && this.nms.canAnimate();
        for (Viewer viewer : placed.viewers) {
            this.nms.send(viewer.player, packet);
            if (ownCarrier) {
                // a loop with a distance moving the whole model: where his loops have it
                double[] at = placed.carrierAt(placed.rootTarget(this.tick, skipLoops(viewer, placed)));
                this.nms.send(viewer.player, this.nms.teleportPacket(placed.carrierId, at[0], at[1], at[2], (float) at[3], (float) at[4]));
            }
            Nearby near = viewer.near(placed);
            if (near != null) {
                // the direction of the player from the block changed: worked out again at the next tick, and the
                // parts sent their rest pose again are turned back towards him
                near.settledMoves = -1;
                if (rested) {
                    near.looking = false;
                }
            }
        }
    }

    /**
     * Stores where an operator left a block, and takes its interactions and collisions there.
     */
    synchronized void commitShift(PlacedDisplay placed) {
        placed.gliding = false;
        if (placed.model.spin != null && !placed.viewers.isEmpty()) {
            // the operator's moves gave every entity their glide: the spinning parts get that of the spin back, and spin
            // again or no longer (a block put on its side does not spin)
            Object glide = placed.spinGlidePacket(this.nms);
            Object turn = placed.spinPacket(this.nms, placed.spins() ? placed.model.spin.angle(this.tick) : 0f);
            for (Viewer viewer : placed.viewers) {
                this.nms.send(viewer.player, glide);
                this.nms.send(viewer.player, turn);
            }
            if (placed.spins()) {
                this.spinning.add(placed);
            } else {
                this.spinning.remove(placed);
            }
        }
        Block block = placed.world.getBlockAt(placed.x, placed.y, placed.z);
        CustomBlockData data = new CustomBlockData(block, this.plugin);
        String text = placed.shiftText();
        if (text == null) {
            data.remove(CustomBlockKey.SHIFT);
        } else {
            data.set(CustomBlockKey.SHIFT, PersistentDataType.STRING, text);
        }
        layHitbox(placed, data);
        // its click boxes were summoned again
        guardHitbox(placed, data);
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

    // ---------- What an administrator sets on one block ----------

    /**
     * What an administrator set on the custom block of a block, null for one that follows its file (or that is not
     * shown through packets).
     */
    @Nullable
    public synchronized BlockSettings settingsAt(@Nullable Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        PlacedDisplay placed = find(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return placed == null ? null : placed.settings;
    }

    /**
     * Whether an administrator made the custom block of a block unbreakable by hits.
     */
    public synchronized boolean protects(@Nullable Location location) {
        BlockSettings settings = settingsAt(location);
        return settings != null && !settings.breakable;
    }

    /**
     * The blocks shown through packets in a world, within a distance of a point, that an administrator set something on.
     */
    synchronized List<PlacedDisplay> withSettings(World world, Location around, double distance) {
        List<PlacedDisplay> found = new ArrayList<>();
        Map<Long, List<PlacedDisplay>> chunks = this.index.get(world.getUID());
        if (chunks == null) {
            return found;
        }
        for (List<PlacedDisplay> list : chunks.values()) {
            for (PlacedDisplay placed : list) {
                if (placed.settings != null && around.distanceSquared(placed.base()) <= distance * distance) {
                    found.add(placed);
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(placed -> around.distanceSquared(placed.base())));
        return found;
    }

    /**
     * Gives a block what an administrator set on it: stored with the block, and shown at once to those who see it.
     *
     * @param settings null (or nothing set) for a block that follows its file again
     * @return false when its hitbox changed but could not be summoned again at once (it is at its next read)
     */
    synchronized boolean applySettings(PlacedDisplay placed, @Nullable BlockSettings settings) {
        if (settings != null && settings.isDefault()) {
            settings = null;
        }
        boolean loopedBefore = placed.loops();
        int distanceBefore = placed.viewDistance();
        int aboveBefore = placed.viewAbove();
        int belowBefore = placed.viewBelow();
        String hitboxBefore = BlockSettings.hitboxKey(placed.settings);
        placed.settings = settings;

        CustomBlockData data = new CustomBlockData(placed.world.getBlockAt(placed.x, placed.y, placed.z), this.plugin);
        String text = settings == null ? null : settings.write();
        if (text == null) {
            data.remove(CustomBlockKey.SETTINGS);
        } else {
            data.set(CustomBlockKey.SETTINGS, PersistentDataType.STRING, text);
        }

        if (loopedBefore != placed.loops()) {
            if (placed.loops()) {
                if (!placed.viewers.isEmpty()) {
                    this.looping.add(placed);
                    for (Viewer viewer : placed.viewers) {
                        // a player watching an animation played for him alone gets the loops once it ends
                        if (!placed.privateFor.contains(viewer.player.getUniqueId())) {
                            catchUp(viewer, placed);
                        }
                    }
                }
            } else {
                this.looping.remove(placed);
                this.spinning.remove(placed);
                List<DisplayAnimation> animations = placed.model.animations;
                // the parts an animation everyone sees plays stay with it
                boolean[] busy = this.playing.contains(placed) ? placed.busyParts(this.tick) : null;
                for (int i = 0; i < animations.size(); i++) {
                    if (animations.get(i).trigger != DisplayAnimation.Trigger.LOOP) {
                        continue;
                    }
                    Object packet = placed.restPacket(this.nms, i, 0, false, busy);
                    for (Viewer viewer : placed.viewers) {
                        if (packet != null && !placed.privateFor.contains(viewer.player.getUniqueId())) {
                            this.nms.send(viewer.player, packet);
                            Nearby near = viewer.near(placed);
                            if (near != null) {
                                // the parts turned towards him were put back at rest too: turned to him again
                                near.looking = false;
                            }
                        }
                    }
                }
            }
            if (placed.model.hasCarrier() && !placed.viewers.isEmpty()) {
                // the whole model at once where the frames now playing have it, as its parts
                carrierToLoops(placed);
            }
            if (placed.model.spin != null && !placed.viewers.isEmpty()) {
                // the spin stops and starts with the loops: back to rest, or on from where it is
                Object packet = placed.spinPacket(this.nms, placed.spins() ? placed.model.spin.angle(this.tick) : 0f);
                for (Viewer viewer : placed.viewers) {
                    this.nms.send(viewer.player, packet);
                }
                if (placed.spins()) {
                    this.spinning.add(placed);
                } else {
                    this.spinning.remove(placed);
                }
            }
        }

        if (distanceBefore != placed.viewDistance() || aboveBefore != placed.viewAbove() || belowBefore != placed.viewBelow()) {
            // players moving look that much further for blocks; everyone in the world works out again what they see
            this.farthest = Math.max(this.farthest, placed.viewDistance());
            for (Viewer viewer : this.viewers.values()) {
                if (placed.world.getUID().equals(viewer.world)) {
                    viewer.stale = true;
                }
            }
        }

        return hitboxBefore.equals(BlockSettings.hitboxKey(settings)) || rebuildHitbox(placed, data);
    }

    /**
     * Summons the click boxes and collisions of a block again after its hitbox settings changed, where its model
     * stands. When one of its entities is not loaded, they are summoned again the next time the block is read.
     *
     * @return whether they were summoned again at once
     */
    synchronized boolean rebuildHitbox(PlacedDisplay placed, CustomBlockData data) {
        if (!(this.plugin.getCustomBlockService() instanceof BDCCustomBlockService service) || !service.rebuildHitbox(placed.base())) {
            // the stored description no longer matches: the next read of the block summons them again
            data.remove(CustomBlockKey.HITBOX);
            return false;
        }
        placed.resetHitboxShift();
        if (placed.isShifted()) {
            layHitbox(placed, data);
        }
        guardHitbox(placed, data);
        return true;
    }

    /**
     * The click boxes and collisions a block has now, as boxes in the world: min x, y, z, max x, y, z, then 1 for a
     * collision and 0 for a click box. Empty for a block whose entities are not loaded.
     */
    synchronized List<double[]> hitboxOf(PlacedDisplay placed) {
        List<double[]> boxes = new ArrayList<>();
        CustomBlockData data = new CustomBlockData(placed.world.getBlockAt(placed.x, placed.y, placed.z), this.plugin);
        for (org.bukkit.NamespacedKey key : List.of(CustomBlockKey.INTERACTION_UUID, CustomBlockKey.COLLISION_UUID)) {
            UUID[] uuids = data.get(key, PersistentDataTypes.UUID_ARRAY);
            if (uuids == null) {
                continue;
            }
            for (UUID uuid : uuids) {
                Entity entity = Bukkit.getEntity(uuid);
                if (entity == null || !entity.isValid()) {
                    continue;
                }
                org.bukkit.util.BoundingBox box = entity.getBoundingBox();
                if (box.getVolume() <= 0) {
                    continue;
                }
                boxes.add(new double[]{box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ(),
                        key == CustomBlockKey.COLLISION_UUID ? 1 : 0});
            }
        }
        return boxes;
    }

    /**
     * Gives a block another size, keeping where it stands (see {@link PlacedDisplay#sizeAnchor()}), for everyone who
     * sees it, and stores it.
     */
    synchronized void resize(PlacedDisplay placed, double size) {
        double[] next = placed.shiftForSize(size);
        beginShift(placed);
        shift(placed, next[0], next[1], next[2], next[3], next[4], next[5]);
        commitShift(placed);
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
        this.anyConditional = false;
        this.reportedConditions.clear();
        warnWithoutPlaceholders();
        this.playing.clear();
        // the blocks are read again and get new entity ids: what was played for players is dropped
        for (List<PrivatePlay> plays : this.privatePlays.values()) {
            plays.forEach(play -> {
                play.placed.privateFor.clear();
                play.placed.privateStamp++;
            });
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
                        CustomBlockData data = new CustomBlockData(placed.world.getBlockAt(placed.x, placed.y, placed.z), this.plugin);
                        refreshHitbox(placed, model, data);
                        placed.bind(model, this.nms);
                        // a visible-if added, changed or removed applies to the blocks already placed
                        guardHitbox(placed, data);
                        show(placed);
                    }
                }
                if (list.isEmpty()) {
                    lists.remove();
                }
            }
        }
        // the models shown for other plugins take their new file, or go when it no longer draws anything
        reloadModels(null);
        // blocks whose file was missing, and blocks to convert
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                queue(chunk);
            }
        }
    }

    /**
     * Shows again from its file every placed block and every model shown for another plugin of one custom block,
     * once that file alone has been read again ({@code /bdc reload <block>}): the others are left as they are. A
     * block whose file is gone or draws nothing any more stops being shown, as at a whole reload.
     */
    public synchronized void reload(String name) {
        this.models.remove(name);
        this.unavailableModels.remove(name);
        this.reportedModels.remove(name);
        if (!isActive()) {
            return;
        }
        for (Map.Entry<UUID, Map<Long, List<PlacedDisplay>>> worlds : this.index.entrySet()) {
            for (Iterator<List<PlacedDisplay>> lists = worlds.getValue().values().iterator(); lists.hasNext(); ) {
                List<PlacedDisplay> list = lists.next();
                for (Iterator<PlacedDisplay> iterator = list.iterator(); iterator.hasNext(); ) {
                    PlacedDisplay placed = iterator.next();
                    if (!placed.modelName.equals(name)) {
                        continue;
                    }
                    hide(placed);
                    this.playing.remove(placed);
                    DisplayModel model = model(name, placed.world, placed.chunkX(), placed.chunkZ());
                    if (model == null) {
                        // read again once its file draws something (see read)
                        iterator.remove();
                        this.tasks.add(new ChunkTask(worlds.getKey(), placed.chunkX(), placed.chunkZ(), false));
                    } else {
                        CustomBlockData data = new CustomBlockData(placed.world.getBlockAt(placed.x, placed.y, placed.z), this.plugin);
                        refreshHitbox(placed, model, data);
                        placed.bind(model, this.nms);
                        guardHitbox(placed, data);
                        show(placed);
                    }
                }
                if (list.isEmpty()) {
                    lists.remove();
                }
            }
        }
        reloadModels(name);
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
        for (Map<Long, List<PlacedDisplay>> chunks : this.shownModels.values()) {
            chunks.values().forEach(list -> list.forEach(this::drop));
        }
        this.shownModels.clear();
        for (Map<Long, List<PlacedDisplay>> chunks : this.index.values()) {
            for (List<PlacedDisplay> list : chunks.values()) {
                list.forEach(this::hide);
            }
        }
        this.index.clear();
        this.viewers.clear();
        this.looping.clear();
        this.spinning.clear();
        this.playing.clear();
        this.privatePlays.clear();
        this.attentive.clear();
        if (this.registryDirty) {
            this.registryDirty = false;
            saveRegistry();
        }
        this.optimiser.shutdownNow();
        if (this.optimisedDirty) {
            this.optimisedDirty = false;
            saveOptimised(new LinkedHashMap<>(this.optimised));
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
        if (this.anyConditional) {
            guardLoaded(event.getEntities());
        }
    }

    /**
     * Hides at once the interactions and collisions of blocks with a {@code display.visible-if} that come back with
     * their chunk, before anyone can click them: they may stand in another chunk than their block, read later.
     */
    private synchronized void guardLoaded(List<Entity> entities) {
        for (Entity entity : entities) {
            if (!(entity instanceof Interaction) && !(entity instanceof Shulker)) {
                continue;
            }
            CustomBlockKey.DataHolder holder = CustomBlockKey.holder(entity);
            String name = holder.getName();
            DisplayModel model = name == null ? null : this.models.get(name);
            if (model == null || !model.conditional()) {
                continue;
            }
            Location at = holder.getLocation();
            PlacedDisplay placed = at == null ? null : find(entity.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
            for (Entity part : hitboxParts(entity)) {
                part.setVisibleByDefault(false);
                if (placed != null && this.plugin.isEnabled()) {
                    for (Viewer viewer : placed.viewers) {
                        viewer.player.showEntity(this.plugin, part);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        Map<Long, List<PlacedDisplay>> chunks = this.index.get(chunk.getWorld().getUID());
        if (chunks != null && chunks.containsKey(chunkKey(chunk.getX(), chunk.getZ())) || showsIn(chunk)) {
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
            Map<Long, List<PlacedDisplay>> models = this.shownModels.remove(event.getWorld().getUID());
            if (models != null) {
                models.values().forEach(list -> list.forEach(this::drop));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        me.general_breddok.blockdisplaycreator.custom.InteractionHandlerDta.forget(event.getPlayer().getUniqueId());
        synchronized (this) {
            UUID id = event.getPlayer().getUniqueId();
            Viewer viewer = this.viewers.remove(id);
            if (viewer != null) {
                forget(viewer);
            }
            List<PrivatePlay> plays = this.privatePlays.remove(id);
            if (plays != null) {
                plays.forEach(play -> {
                    play.placed.privateFor.remove(id);
                    play.placed.privateStamp++;
                });
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
        long begun = System.nanoTime();
        long deadline = begun + SCAN_BUDGET_NANOS;
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
        // before the frames: a frame that moves the parts turned towards a player goes to him from its first tick
        attend();
        long animating = System.nanoTime();
        animate();
        long animated = System.nanoTime();
        this.animateNanos += animated - animating;
        if (this.tick % MODEL_RETRY_PERIOD == 0 && !this.retryChunks.isEmpty()) {
            this.tasks.addAll(this.retryChunks);
            this.retryChunks.clear();
        }
        if (this.tick % VIEWER_PERIOD == 0) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                update(player);
            }
            this.placesNanos += System.nanoTime() - animated;
        }
        if (this.anyConditional && this.tick % VISIBILITY_PERIOD == 0) {
            recheck();
        }
        if (this.registryDirty) {
            this.registryDirty = false;
            saveRegistry();
        }
        long spent = System.nanoTime() - begun;
        this.tickNanos += spent;
        this.tickMost = Math.max(this.tickMost, spent);
        this.tickCount++;
    }

    private void unload(World world, int chunkX, int chunkZ) {
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            // loaded again since: its blocks are the same
            return;
        }
        dropModels(world, chunkX, chunkZ);
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
            // its real display entities are everyone's: so are its click boxes
            guardHitbox(placed, data);
            restore(placed, data);
            return;
        }

        placed.shiftText(data.get(CustomBlockKey.SHIFT, PersistentDataType.STRING));
        placed.settings = BlockSettings.read(data.get(CustomBlockKey.SETTINGS, PersistentDataType.STRING));
        if (placed.settings != null) {
            this.farthest = Math.max(this.farthest, placed.settings.viewDistance);
        }
        refreshHitbox(placed, model, data);

        placed.bind(model, this.nms);
        guardHitbox(placed, data);
        list.add(placed);
        show(placed);
    }

    /**
     * Replaces the interactions and collisions of a placed block when its file no longer describes the ones it has:
     * a click box changed by hand, or a new scale.
     */
    private void refreshHitbox(PlacedDisplay placed, DisplayModel model, CustomBlockData data) {
        if (this.plugin.getCustomBlockService() instanceof BDCCustomBlockService service
                && (!model.hitboxSignature.equals(data.get(CustomBlockKey.HITBOX, PersistentDataType.STRING)) || service.hasLostCollision(placed.base()))
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
            model = DisplayModel.read(this.nms, definition, summonTexts, world, chunkX, chunkZ, animationSection(name), lookAt(name), section(name, "root-motion"),
                    this.nms.canAnimate() ? section(name, "spin") : null,
                    viewDistance(name), viewHeight(name, "display.view-above"), viewHeight(name, "display.view-below"), visibleIf(name, problems), problems);
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
        model = lightened(name, model);
        this.models.put(name, model);
        // players moving look that much further for blocks; the blocks of this model are shown as they are added
        this.farthest = Math.max(this.farthest, model.viewDistance);
        if (model.conditional()) {
            this.anyConditional = true;
        }
        return model;
    }

    /**
     * Without PlaceholderAPI, the placeholders of a {@code display.visible-if} stay as written: said once, when the
     * files of the blocks have been read.
     */
    private void warnWithoutPlaceholders() {
        if (this.plugin.getDependentPluginsManager().isPlaceholderApiAvailable()
                || !(this.plugin.getCustomBlockService().getStorage() instanceof BDCCustomBlockConfigStorage storage)
                || storage.getCustomBlockRepository() == null) {
            return;
        }
        for (CustomBlockConfigurationFile file : storage.getCustomBlockRepository().getFiles()) {
            if (file.get(VISIBLE_IF) != null) {
                this.plugin.getLogger().warning("Custom block " + file.getName() + " has a " + VISIBLE_IF + ", but PlaceholderAPI is not installed: "
                        + "its placeholders stay as written, so its conditions are usually false");
                return;
            }
        }
    }

    /**
     * The {@code display.visible-if} of the file of a custom block: empty when it has none.
     *
     * @param problems receives the lines that are not conditions
     */
    private List<Condition> visibleIf(String name, List<String> problems) {
        CustomBlockConfigurationFile file = file(name);
        return file == null ? List.of() : Condition.readAll(file.get(VISIBLE_IF), VISIBLE_IF, problems);
    }

    @Nullable
    private CustomBlockConfigurationFile file(String name) {
        if (this.plugin.getCustomBlockService().getStorage() instanceof BDCCustomBlockConfigStorage storage
                && storage.getCustomBlockRepository() != null) {
            return storage.getCustomBlockRepository().getFile(name);
        }
        return null;
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
     * The {@code display.view-above} or {@code display.view-below} of the file of a custom block, in blocks, or -1 when
     * it has none.
     */
    private int viewHeight(String name, String path) {
        if (this.plugin.getCustomBlockService().getStorage() instanceof BDCCustomBlockConfigStorage storage
                && storage.getCustomBlockRepository() != null) {
            CustomBlockConfigurationFile file = storage.getCustomBlockRepository().getFile(name);
            if (file != null) {
                try {
                    return me.general_breddok.blockdisplaycreator.file.config.loader.CustomBlockYamlFile.viewHeight(file.get(path));
                } catch (IllegalArgumentException e) {
                    // the block itself does not load with it
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * The {@code animations} section of the file of a custom block, if it has one and this server can play it.
     */
    @Nullable
    private ConfigurationSection animationSection(String name) {
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

    /**
     * The {@code look-at} section of the file of a custom block, if it has one and this server can turn its parts.
     */
    @Nullable
    private ConfigurationSection lookAt(String name) {
        return this.nms.canAnimate() ? section(name, "look-at") : null;
    }

    /**
     * A section of the file of a custom block, if it has it.
     */
    @Nullable
    private ConfigurationSection section(String name, String path) {
        CustomBlockConfigurationFile file = file(name);
        return file != null && file.get(path) instanceof ConfigurationSection section ? section : null;
    }

    // ---------- Lighter animations ----------

    /**
     * The model with the frames of its animations a client does not need left out, as far as they are already worked
     * out. The others are worked out off the main thread; the blocks of the model then get them.
     */
    private DisplayModel lightened(String name, DisplayModel model) {
        if (!BooleanConfigValue.ANIMATION_OPTIMISATION || model.animations.isEmpty()) {
            return model;
        }
        Set<Integer> mixed = AnimationOptimizer.mixed(model.animations);
        List<DisplayAnimation> animations = new ArrayList<>(model.animations);
        Map<String, String> missing = new LinkedHashMap<>();
        boolean changed = false;
        for (int i = 0; i < animations.size(); i++) {
            DisplayAnimation animation = animations.get(i);
            if (!animation.optimisable) {
                continue;
            }
            String print = AnimationOptimizer.fingerprint(animation, model.elements, mixed);
            String cached = this.optimised.get(name + '\t' + animation.name);
            if (cached == null || !cached.startsWith(print + '\t')) {
                missing.put(animation.name, print);
                continue;
            }
            List<AnimationOptimizer.Kept> kept = AnimationOptimizer.decode(cached.substring(print.length() + 1));
            if (kept != null && !kept.isEmpty()) {
                animations.set(i, animation.with(kept, this.nms));
                changed = true;
            }
        }
        if (!missing.isEmpty() && this.optimising.add(name)) {
            DisplayModel original = model;
            this.optimiser.execute(() -> {
                Map<String, String> results = new LinkedHashMap<>();
                for (DisplayAnimation animation : original.animations) {
                    String print = missing.get(animation.name);
                    if (print == null) {
                        continue;
                    }
                    String kept;
                    try {
                        kept = AnimationOptimizer.encode(AnimationOptimizer.optimise(animation, original.elements, mixed));
                    } catch (RuntimeException e) {
                        this.plugin.getLogger().log(Level.WARNING, "Could not lighten the animation " + animation.name + " of the custom block " + name, e);
                        kept = "=";
                    }
                    results.put(name + '\t' + animation.name, print + '\t' + kept);
                }
                if (this.plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(this.plugin, () -> {
                        this.optimised.putAll(results);
                        this.optimisedDirty = true;
                        this.optimising.remove(name);
                        swapLightened(name, original, 0);
                    });
                }
            });
        }
        return changed ? model.withAnimations(animations) : model;
    }

    /**
     * Gives the blocks of a model the lightened animations just worked out. A block playing an animation started by
     * a click, its placement or a command waits for its end: its frames are counted by their place.
     */
    private synchronized void swapLightened(String name, DisplayModel original, int attempt) {
        DisplayModel current = this.models.get(name);
        if (current == null || current.elements != original.elements) {
            // read again since (reload): the next reading takes the cache
            return;
        }
        DisplayModel lighter = lightened(name, original);
        if (lighter == original || lighter == current) {
            return;
        }
        List<PlacedDisplay> blocks = new ArrayList<>();
        Set<PlacedDisplay> privately = new HashSet<>();
        this.privatePlays.values().forEach(plays -> plays.forEach(play -> privately.add(play.placed)));
        for (Map<Long, List<PlacedDisplay>> chunks : allChunks()) {
            for (List<PlacedDisplay> list : chunks.values()) {
                for (PlacedDisplay placed : list) {
                    if (placed.model != current) {
                        continue;
                    }
                    boolean busy = privately.contains(placed) || !placed.privateFor.isEmpty();
                    for (int i = 0; i < current.animations.size() && !busy; i++) {
                        busy = current.animations.get(i).trigger != DisplayAnimation.Trigger.LOOP && placed.startedAt[i] >= 0;
                    }
                    if (busy) {
                        if (attempt < 300) {
                            Bukkit.getScheduler().runTaskLater(this.plugin, () -> swapLightened(name, original, attempt + 1), 20L);
                        }
                        return;
                    }
                    blocks.add(placed);
                }
            }
        }
        for (PlacedDisplay placed : blocks) {
            placed.rebindAnimations(lighter);
        }
        this.models.put(name, lighter);
        countLightened(original, lighter);
    }

    private void countLightened(DisplayModel original, DisplayModel lighter) {
        long before = 0;
        long after = 0;
        for (int i = 0; i < original.animations.size(); i++) {
            before += AnimationOptimizer.updates(original.animations.get(i));
            after += AnimationOptimizer.updates(lighter.animations.get(i));
        }
        if (after < before) {
            this.lightened++;
            this.updatesBefore += before;
            this.updatesAfter += after;
            scheduleSummary();
        }
    }

    private Path optimisedPath() {
        return this.plugin.getDataFolder().toPath().resolve(OPTIMISED_FILE);
    }

    private void loadOptimised() {
        Path path = optimisedPath();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(path)) {
                String[] fields = line.split("\t", 4);
                if (fields.length == 4) {
                    this.optimised.put(fields[0] + '\t' + fields[1], fields[2] + '\t' + fields[3]);
                }
            }
        } catch (IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Could not read " + OPTIMISED_FILE, e);
        }
    }

    private void saveOptimised(Map<String, String> entries) {
        Path path = optimisedPath();
        List<String> lines = new ArrayList<>(entries.size() + 1);
        lines.add("# Animations lightened by BlockDisplayCreator: block, animation, fingerprint, frames kept (frame:at:over[:parts]). Worked out again when a file changes; safe to delete.");
        entries.forEach((key, value) -> lines.add(key + '\t' + value));
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(OPTIMISED_FILE + ".tmp");
            Files.write(temporary, lines);
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Could not write " + OPTIMISED_FILE, e);
        }
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
                    && inRange(viewer, placed, 0) && !viewer.shown.contains(placed) && !viewer.withheld.contains(placed)) {
                reach(viewer, placed);
            }
        }
    }

    /**
     * Sends a custom block that came in range to a player, unless its {@code display.visible-if} holds it back from
     * him: it is then sent once its conditions turn true.
     */
    private void reach(Viewer viewer, PlacedDisplay placed) {
        if (placed.model.conditional()) {
            viewer.guarded.add(placed);
            if (!visibleTo(viewer.player, placed)) {
                viewer.withheld.add(placed);
                hitbox(viewer.player, placed, false);
                return;
            }
        }
        viewer.shown.add(placed);
        attach(viewer, placed);
    }

    /**
     * Takes back a block with a {@code display.visible-if} from a player whose conditions turned false: his client
     * forgets it, he gets none of its frames and sounds, and can neither click it nor bump into it.
     */
    private void withhold(Viewer viewer, PlacedDisplay placed) {
        viewer.shown.remove(placed);
        placed.viewers.remove(viewer);
        viewer.forgetNear(placed);
        if (placed.viewers.isEmpty()) {
            this.looping.remove(placed);
            this.spinning.remove(placed);
        }
        viewer.withheld.add(placed);
        this.nms.send(viewer.player, placed.removePacket(this.nms));
        unsent(viewer, placed);
        hitbox(viewer.player, placed, false);
    }

    private static long blockKey(PlacedDisplay placed) {
        return ((long) placed.x & 0x3FFFFFFL) << 38 | ((long) placed.z & 0x3FFFFFFL) << 12 | ((long) placed.y & 0xFFFL);
    }

    /**
     * Notes the ids a block is sent to a client with; ids sent earlier for the same block, under another binding
     * (a reload, a chunk read again), are removed first: the client may still draw them.
     */
    private void sending(Viewer viewer, PlacedDisplay placed) {
        int[] before = placed.shownFor != null ? viewer.sentModels.put(placed, placed.allIds)
                : viewer.sent.computeIfAbsent(placed.world.getUID(), k -> new HashMap<>()).put(blockKey(placed), placed.allIds);
        if (before != null && before != placed.allIds) {
            this.nms.send(viewer.player, this.nms.removePacket(before));
        }
    }

    /**
     * The client was told to remove these ids.
     */
    private static void unsent(Viewer viewer, PlacedDisplay placed) {
        if (placed.shownFor != null) {
            viewer.sentModels.remove(placed, placed.allIds);
            return;
        }
        Map<Long, int[]> blocks = viewer.sent.get(placed.world.getUID());
        if (blocks != null) {
            blocks.remove(blockKey(placed), placed.allIds);
        }
    }

    /**
     * Checks again the conditions of the blocks with a {@code display.visible-if} around each player: a block appears
     * or disappears within a second of his quest moving on. A block he sees stays while an animation played for him
     * alone runs on it, and goes when it ends.
     */
    private void recheck() {
        for (Viewer viewer : this.viewers.values()) {
            if (viewer.guarded.isEmpty() || !viewer.player.isOnline()) {
                continue;
            }
            List<PlacedDisplay> changed = null;
            for (PlacedDisplay placed : viewer.guarded) {
                if (visibleTo(viewer.player, placed) != viewer.shown.contains(placed)) {
                    if (changed == null) {
                        changed = new ArrayList<>(2);
                    }
                    changed.add(placed);
                }
            }
            if (changed == null) {
                continue;
            }
            for (PlacedDisplay placed : changed) {
                if (viewer.withheld.remove(placed)) {
                    viewer.shown.add(placed);
                    attach(viewer, placed);
                } else if (!placed.privateFor.contains(viewer.player.getUniqueId())) {
                    withhold(viewer, placed);
                }
            }
        }
    }

    /**
     * Whether the conditions of the {@code display.visible-if} of a block are true for a player. Their {@code {x}}
     * {@code {y}} {@code {z}} and {@code {world}} are the block it was placed in, {@code %player%} is the player, then
     * PlaceholderAPI gives its placeholders.
     */
    private boolean visibleTo(Player player, PlacedDisplay placed) {
        try {
            return Condition.all(placed.model.visibleIf, text -> placeholders(player, placed, text));
        } catch (RuntimeException e) {
            if (this.reportedConditions.add(placed.modelName)) {
                this.plugin.getLogger().log(Level.WARNING, "Could not check the " + VISIBLE_IF + " of the custom block " + placed.modelName
                        + " for " + player.getName() + ": it is not shown", e);
            }
            return false;
        }
    }

    private String placeholders(Player player, PlacedDisplay placed, String text) {
        if (text.indexOf('{') >= 0) {
            text = text.replace("{x}", Integer.toString(placed.x))
                    .replace("{y}", Integer.toString(placed.y))
                    .replace("{z}", Integer.toString(placed.z))
                    .replace("{world}", placed.world.getName());
        }
        if (text.indexOf('%') >= 0) {
            text = text.replace("%player%", player.getName());
            if (text.indexOf('%') >= 0 && this.plugin.getDependentPluginsManager().isPlaceholderApiAvailable()) {
                text = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
            }
        }
        return text;
    }

    /**
     * Whether a player is sent a block: always, unless its model has a {@code display.visible-if}.
     */
    synchronized boolean sees(Player player, PlacedDisplay placed) {
        if (placed.model == null || !placed.model.conditional()) {
            return true;
        }
        Viewer viewer = this.viewers.get(player.getUniqueId());
        return viewer != null && viewer.player == player && viewer.shown.contains(placed);
    }

    /**
     * Whether the custom block placed at a location has a {@code display.visible-if} that keeps it from a player at
     * the moment: his clicks on it are ignored.
     *
     * @param location the location the block was placed at, as stored on its interactions
     */
    public synchronized boolean hides(Player player, @Nullable Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        PlacedDisplay placed = find(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        return placed != null && !sees(player, placed);
    }

    /**
     * Whether an animation played by a command for this player alone still runs on the custom block placed at a
     * location (a scene: a flight, a reveal, a click animation): his clicks on it wait for its end, or they would cut
     * it short (a new animation of the block replaces it) and run their commands over it.
     *
     * @param location the location the block was placed at, as stored on its interactions
     */
    public synchronized boolean playsFor(Player player, @Nullable Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        List<PrivatePlay> plays = this.privatePlays.get(player.getUniqueId());
        if (plays == null || plays.isEmpty()) {
            return false;
        }
        PlacedDisplay placed = find(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (placed == null) {
            return false;
        }
        for (PrivatePlay play : plays) {
            // a greeting his coming close set off does not hold his click back: the click ends it
            if (play.placed == placed && !play.approach) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hides the interactions and collisions of a block whose model has a {@code display.visible-if} from every player
     * but those it is sent to, or gives those of a block whose model no longer has one back to everyone. Called
     * whenever they may be new entities: when the block is placed or read, and when they are laid out again.
     */
    private void guardHitbox(PlacedDisplay placed, CustomBlockData data) {
        boolean conditional = isActive() && placed.model != null && placed.model.conditional();
        if (!conditional && placed.hitbox == null && !data.has(CustomBlockKey.HIDDEN_HITBOX)) {
            // the other blocks
            return;
        }
        List<UUID> uuids = new ArrayList<>();
        for (org.bukkit.NamespacedKey key : List.of(CustomBlockKey.INTERACTION_UUID, CustomBlockKey.COLLISION_UUID)) {
            UUID[] stored = data.get(key, PersistentDataTypes.UUID_ARRAY);
            if (stored != null) {
                uuids.addAll(List.of(stored));
            }
        }
        for (UUID uuid : uuids) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) {
                // hidden from all, or shown to all, whatever was shown to whom before
                for (Entity part : hitboxParts(entity)) {
                    part.setVisibleByDefault(!conditional);
                }
            }
        }
        if (conditional) {
            placed.hitbox = uuids.toArray(UUID[]::new);
            data.set(CustomBlockKey.HIDDEN_HITBOX, PersistentDataType.BYTE, (byte) 1);
            for (Viewer viewer : placed.viewers) {
                hitbox(viewer.player, placed, true);
            }
        } else {
            placed.hitbox = null;
            data.remove(CustomBlockKey.HIDDEN_HITBOX);
        }
    }

    /**
     * Shows the interactions and collisions of a block with a {@code display.visible-if} to a player, or hides them
     * from him: he can click it and bump into it only while he is sent it.
     */
    private void hitbox(Player player, PlacedDisplay placed, boolean visible) {
        if (placed.hitbox == null || !this.plugin.isEnabled()) {
            return;
        }
        for (UUID uuid : placed.hitbox) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity == null) {
                continue;
            }
            for (Entity part : hitboxParts(entity)) {
                if (visible) {
                    player.showEntity(this.plugin, part);
                } else {
                    player.hideEntity(this.plugin, part);
                }
            }
        }
    }

    /**
     * A hitbox entity of a block, with the empty interaction a collision rides.
     */
    private static List<Entity> hitboxParts(Entity entity) {
        Entity vehicle = entity instanceof Shulker ? entity.getVehicle() : null;
        return vehicle == null ? List.of(entity) : List.of(entity, vehicle);
    }

    /**
     * Plays a sound of an animation at a block: heard by everyone around it, or by the players it is sent to only when
     * its model has a {@code display.visible-if}, or when the animation has a {@code sound-range}: then by those of them
     * who stand within it.
     */
    private void playSound(PlacedDisplay placed, int index, DisplayAnimation animation, String sound, float volume, float pitch) {
        Location where = placed.base();
        double range = animation.soundRange;
        boolean near = animation.distance > 0;
        if (range <= 0 && !placed.model.conditional() && !near) {
            placed.world.playSound(where, sound, volume, pitch);
            return;
        }
        for (Viewer viewer : placed.viewers) {
            // a loop with a distance is heard by those it plays for
            if ((range <= 0 || placed.heardBy(viewer.player, range)) && (!near || plays(viewer, placed, index))) {
                viewer.player.playSound(where, sound, volume, pitch);
            }
        }
    }

    /**
     * Sends a custom block to a player, where its animations have taken it.
     */
    private void attach(Viewer viewer, PlacedDisplay placed) {
        placed.viewers.add(viewer);
        if (placed.hitbox != null) {
            hitbox(viewer.player, placed, true);
        }
        sending(viewer, placed);
        if (placed.model.distanceLoops != 0) {
            // the loops with a distance he is already within play for him from his first frame
            Location at = viewer.player.getLocation(this.here);
            double dx = at.getX() - placed.baseX - placed.shift[0];
            double dy = at.getY() - placed.baseY - placed.shift[1];
            double dz = at.getZ() - placed.baseZ - placed.shift[2];
            double distance = dx * dx + dy * dy + dz * dz;
            long in = 0;
            for (long loops = placed.model.distanceLoops; loops != 0; loops &= loops - 1) {
                int a = Long.numberOfTrailingZeros(loops);
                double reach = placed.model.animations.get(a).distance;
                if (distance <= reach * reach) {
                    in |= 1L << a;
                }
            }
            if (in != 0) {
                nearby(viewer, placed).loopsIn = in;
            }
        }
        PrivatePlay own = placed.privateFor.isEmpty() ? null : privatePlay(viewer.player.getUniqueId(), placed);
        boolean spins = placed.spins();
        if (placed.model.hasCarrier() || spins) {
            // the whole model where the frames he gets have taken it: those played for him alone, or those of everyone;
            // the spinning parts turned as far as the spin has them
            int glide = placed.carrierGlide >= 0 ? placed.carrierGlide : placed.model.carrierGlide;
            this.nms.send(viewer.player, placed.spawnPacket(this.nms, own != null && own.rootMoved ? own.rootTarget
                    : placed.rootTarget(this.tick, skipLoops(viewer, placed)), glide, spins ? placed.model.spin.angle(this.tick) : Float.NaN));
            if (spins) {
                this.spinning.add(placed);
            }
            if (own != null) {
                own.carrierGlide = glide;
            }
        } else {
            this.nms.send(viewer.player, placed.spawnPacket(this.nms));
        }
        if (placed.gliding) {
            this.nms.send(viewer.player, glidePacket(placed));
        }
        if (own != null) {
            // where the animation played for this player has taken the block; the parts it has not moved are at rest
            if (own.lastFrame >= 0) {
                this.nms.send(viewer.player, placed.catchUpPacket(this.nms, own.animation, own.lastFrame));
            }
            own.lookFrom = null;
        } else {
            catchUp(viewer, placed);
        }
        if (placed.loops()) {
            this.looping.add(placed);
        }
        if (!placed.attentive && placed.model.attentive()) {
            placed.attentive = true;
            this.attentive.add(placed);
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
        catchUp(viewer, placed, false);
    }

    /**
     * Sends a player who sees a block where the animations everyone sees have taken it.
     *
     * @param rejoin whether the player was watching an animation played for them alone: the loops take the parts back
     *               gliding (see {@link PlacedDisplay#rejoinPacket}) rather than in one jump
     */
    private void catchUp(Viewer viewer, PlacedDisplay placed, boolean rejoin) {
        List<DisplayAnimation> animations = placed.model.animations;
        // the parts turned towards him stay so
        boolean unturned = viewer.lookedAtBy(placed);
        // the parts an animation everyone sees moves while it plays have its pose, not that of the loops; once it has
        // ended, those a loop he gets moves have the loop's
        boolean[] busy = this.playing.contains(placed) ? placed.busyParts(this.tick) : null;
        boolean[] looped = null;
        if (placed.loops() && placed.model.loops) {
            for (int i = 0; i < animations.size(); i++) {
                DisplayAnimation loop = animations.get(i);
                if (loop.trigger == DisplayAnimation.Trigger.LOOP && (loop.distance <= 0 || plays(viewer, placed, i))) {
                    if (looped == null) {
                        looped = new boolean[placed.entityIds.length];
                    }
                    for (int part : loop.touched) {
                        looped[part] = true;
                    }
                }
            }
        }
        for (int i = 0; i < animations.size(); i++) {
            DisplayAnimation animation = animations.get(i);
            int last = placed.lastFrame[i];
            if (animation.trigger == DisplayAnimation.Trigger.LOOP && (!placed.loops() || animation.distance > 0 && !plays(viewer, placed, i))) {
                // an administrator stopped the loops of this block, or he is too far for this one: at rest
                continue;
            }
            if (animation.trigger == DisplayAnimation.Trigger.LOOP && rejoin) {
                Object packet = placed.rejoinPacket(this.nms, i, this.tick % animation.duration, this.tick >= animation.duration, REJOIN_TICKS, unturned, busy);
                if (packet != null) {
                    this.nms.send(viewer.player, packet);
                }
                continue;
            }
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
            boolean[] skip = animation.trigger == DisplayAnimation.Trigger.LOOP ? busy
                    : placed.startedAt[i] >= 0 && placed.startedAt[i] <= this.tick ? null : looped;
            Object packet = last < 0 ? null : placed.catchUpPacket(this.nms, i, last, unturned, skip);
            if (packet != null) {
                this.nms.send(viewer.player, packet);
            }
        }
    }

    // ---------- Players close by: look-at and approach ----------

    /**
     * Checks, for the blocks with a {@code look-at} or an {@code approach} animation, the players who see them: only
     * those, and nothing is sent to a player who does not move. A player who stands still costs a comparison, one who
     * moves outside every radius a distance; nothing is created either way.
     */
    private void attend() {
        if (this.attentive.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        for (int b = this.attentive.size() - 1; b >= 0; b--) {
            PlacedDisplay placed = this.attentive.get(b);
            if (placed.viewers.isEmpty() || placed.model == null || !placed.model.attentive()) {
                int last = this.attentive.size() - 1;
                this.attentive.set(b, this.attentive.get(last));
                this.attentive.remove(last);
                placed.attentive = false;
                continue;
            }
            this.attendPairs += placed.viewers.size();
            for (int v = 0; v < placed.viewers.size(); v++) {
                Viewer viewer = placed.viewers.get(v);
                try {
                    attend(placed, viewer);
                } catch (RuntimeException e) {
                    // said once a model: it would come back every tick
                    if (this.reportedModels.add("attend " + placed.modelName)) {
                        this.plugin.getLogger().log(Level.WARNING, "Could not turn the custom block " + placed.modelName + " towards " + viewer.player.getName(), e);
                    }
                    viewer.forgetNear(placed);
                }
            }
        }
        this.attendNanos += System.nanoTime() - start;
        this.attendTicks++;
    }

    /**
     * What the pass over the players close to blocks has cost since {@link #attendStats()} was last asked: its time,
     * the ticks it ran and the (player, block) pairs it looked at.
     */
    private long attendNanos;
    private long attendTicks;
    private long attendPairs;
    /**
     * What the whole work of a tick has cost since {@link #attendStats()} was last asked, and its longest tick.
     */
    private long tickNanos;
    private long tickMost;
    private long tickCount;
    /**
     * Of that work, the frames played (animations and spins) and the looks at where the players are, every few ticks.
     */
    private long animateNanos;
    private long placesNanos;

    /**
     * The cost of the pass over the players close to the blocks with a look-at, an approach animation or a loop with
     * a distance, since this was last asked (then counted afresh): microseconds a tick, pairs a tick, nanoseconds a
     * pair; then the whole work of the renderer a tick, and the models shown for other plugins; then, of that work,
     * the frames played, the looks at where the players are, and the packets sent, a tick.
     */
    public synchronized String attendStats() {
        long ticks = Math.max(1, this.attendTicks);
        long all = Math.max(1, this.tickCount);
        String text = String.format(java.util.Locale.ROOT, "%d ticks: %.1f µs a tick, %.1f (player, block) pairs a tick, %.0f ns a pair;"
                        + " all of the renderer %.1f µs a tick over %d ticks (longest %.1f); %d model(s) shown for other plugins;"
                        + " frames %.1f µs a tick, players' places %.1f µs a tick, %.1f packets a tick",
                this.attendTicks, this.attendNanos / 1000.0 / ticks, this.attendPairs / (double) ticks,
                this.attendPairs == 0 ? 0.0 : this.attendNanos / (double) this.attendPairs,
                this.tickNanos / 1000.0 / all, this.tickCount, this.tickMost / 1000.0, shownCount(),
                this.animateNanos / 1000.0 / all, this.placesNanos / 1000.0 / all, this.nms.takeSent() / (double) all);
        this.attendNanos = 0;
        this.attendTicks = 0;
        this.attendPairs = 0;
        this.tickNanos = 0;
        this.tickMost = 0;
        this.tickCount = 0;
        this.animateNanos = 0;
        this.placesNanos = 0;
        return text;
    }

    private void attend(PlacedDisplay placed, Viewer viewer) {
        DisplayModel model = placed.model;
        Nearby near = viewer.near(placed);
        if (near == null && (Math.abs(viewer.chunkX - placed.chunkX()) > model.attentionChunks || Math.abs(viewer.chunkZ - placed.chunkZ()) > model.attentionChunks)) {
            // far from every radius, by the chunk he was in at the last check of where the players are
            return;
        }
        Player player = viewer.player;
        if (viewer.readAt != this.tick) {
            readPlace(viewer);
        }
        if (viewer.dead || viewer.world0 != placed.world) {
            // a dead player's client forgets the block when he respawns; one changing world, as he arrives
            return;
        }
        int moves = viewer.moves;
        LookAt lookAt = model.lookAt;
        if (lookAt != null && near != null && near.looking && moves == near.settledMoves && viewer.eyes == near.settledEyes) {
            // he has not moved since he was last turned to: neither the radii he is within nor the way he is looked at
            // change; only an animation taking the parts from him, or the rest of a slow frame
            if (!suspends(placed, viewer)) {
                resume(placed, viewer, near);
                return;
            }
            near.looking = false;
        }
        double px = viewer.x;
        double py = viewer.y0;
        double pz = viewer.z;

        int[] approaches = model.approaches;
        if (model.distanceLoops != 0) {
            // from where the block stands, an operator's moves included
            double dx = px - placed.baseX - placed.shift[0];
            double dy = py - placed.baseY - placed.shift[1];
            double dz = pz - placed.baseZ - placed.shift[2];
            double distance = dx * dx + dy * dy + dz * dz;
            long loops = model.distanceLoops;
            while (loops != 0) {
                int a = Long.numberOfTrailingZeros(loops);
                loops &= loops - 1;
                long bit = 1L << a;
                boolean was = near != null && (near.loopsIn & bit) != 0;
                double reach = was ? model.animations.get(a).distance + DisplayModel.LOOP_LEEWAY : model.animations.get(a).distance;
                boolean in = distance <= reach * reach;
                if (in == was) {
                    continue;
                }
                if (near == null) {
                    near = nearby(viewer, placed);
                }
                if (in) {
                    near.loopsIn |= bit;
                } else {
                    near.loopsIn &= ~bit;
                }
                loopRange(viewer, placed, a, in, near);
            }
        }
        if (approaches.length > 0) {
            // from where the block stands, an operator's moves included
            double dx = px - placed.baseX - placed.shift[0];
            double dy = py - placed.baseY - placed.shift[1];
            double dz = pz - placed.baseZ - placed.shift[2];
            double distance = dx * dx + dy * dy + dz * dz;
            for (int a = 0; a < approaches.length && a < 31; a++) {
                double radius = model.animations.get(approaches[a]).radius;
                boolean was = near != null && (near.approached & 1 << a) != 0;
                double reach = was ? radius + LEEWAY : radius;
                boolean in = distance <= reach * reach;
                if (in == was) {
                    continue;
                }
                if (near == null) {
                    near = nearby(viewer, placed);
                }
                if (in) {
                    near.approached |= 1 << a;
                    approach(viewer, placed, approaches[a]);
                } else {
                    near.approached &= ~(1 << a);
                }
            }
        }

        if (lookAt != null) {
            double eyes = viewer.eyes;
            if (near != null && near.looking && suspends(placed, viewer)) {
                // an animation moving the parts goes first: from this tick on he gets its frames
                near.looking = false;
            }
            if (near != null && near.looking && moves == near.settledMoves && eyes == near.settledEyes) {
                // he has not moved since he was last turned to; a slow frame goes on once the turn is done
                resume(placed, viewer, near);
                return;
            }
            boolean was = near != null && near.gazing;
            double reach = was ? lookAt.radius + LEEWAY : lookAt.radius;
            double[] centre = placed.lookCentre();
            if (placed.model.hasCarrier()) {
                if (!was) {
                    // farther from the pivot at rest than the radius however far the frames take the whole model: he is
                    // not within it, and where they have it now need not be worked out (most players who see it)
                    double far = reach + placed.model.rootReach * placed.scale();
                    double sx = px - centre[0];
                    double sy = py + eyes - centre[1];
                    double sz = pz - centre[2];
                    if (sx * sx + sy * sy + sz * sz > far * far) {
                        if (near != null && near.idle()) {
                            viewer.forgetNear(placed);
                        }
                        return;
                    }
                }
                centre = placed.lookCentre(this.tick, skipLoops(viewer, placed), this.centre);
            }
            double dx = px - centre[0];
            double dy = py + eyes - centre[1];
            double dz = pz - centre[2];
            if (dx * dx + dy * dy + dz * dz > reach * reach) {
                if (was) {
                    near.gazing = false;
                    if (near.looking) {
                        // back, gliding, to where the others see the parts, or where an animation played for him alone
                        // has them
                        near.looking = false;
                        PrivatePlay own = placed.privateFor.isEmpty() ? null : privatePlay(player.getUniqueId(), placed);
                        this.nms.send(player, own != null ? placed.posesPacket(this.nms, lookAt.parts, privateLook(own, lookAt.over))
                                : placed.publicPacket(this.nms, lookAt.parts, this.tick, lookAt.over, skipLoops(viewer, placed)));
                    }
                }
            } else {
                if (near == null) {
                    near = nearby(viewer, placed);
                }
                near.gazing = true;
                gaze(placed, viewer, near, lookAt, moves, eyes, dx, dy, dz);
                resume(placed, viewer, near);
            }
        }
        if (near != null && near.idle()) {
            viewer.forgetNear(placed);
        }
    }

    /**
     * Reads, once a tick, what the pass over the blocks close to a player needs of him: where he stands (a change
     * counted, for the blocks to tell whether he moved since they last looked at him), how high his eyes are, whether
     * he is dead and his world. Only the object the server keeps his position in is looked at while it stays the same
     * (it makes a new one whenever he moves): the rest is read again when it changes, and once a second (a player
     * killed or sent to another world where he stood; a respawn or a world change reads him afresh anyway).
     */
    private void readPlace(Viewer viewer) {
        Player player = viewer.player;
        viewer.readAt = this.tick;
        Object position = this.nms.position(player);
        if (position == null || position != viewer.position || this.tick - viewer.fullReadAt >= FULL_READ_TICKS) {
            viewer.position = position;
            viewer.fullReadAt = this.tick;
            viewer.dead = player.isDead();
            viewer.world0 = player.getWorld();
            double x;
            double y;
            double z;
            if (position == null) {
                Location at = player.getLocation(this.here);
                x = at.getX();
                y = at.getY();
                z = at.getZ();
            } else {
                x = this.nms.coordinate(position, 0);
                y = this.nms.coordinate(position, 1);
                z = this.nms.coordinate(position, 2);
            }
            if (x != viewer.x || y != viewer.y0 || z != viewer.z) {
                viewer.x = x;
                viewer.y0 = y;
                viewer.z = z;
                viewer.moves++;
            }
        }
        viewer.eyes = player.getEyeHeight();
    }

    /**
     * The loops of a block (bit i for the animation i) a player is not sent: those with a {@code distance} he is not
     * within.
     */
    private static long skipLoops(Viewer viewer, PlacedDisplay placed) {
        long loops = placed.model.distanceLoops;
        if (loops == 0) {
            return 0L;
        }
        Nearby near = viewer.near(placed);
        return near == null ? loops : loops & ~near.loopsIn;
    }

    /**
     * Whether a player is sent the frames of an animation of a block: a loop with a {@code distance} only within it.
     */
    private static boolean plays(Viewer viewer, PlacedDisplay placed, int animation) {
        return animation >= 64 || (placed.model.distanceLoops & 1L << animation) == 0 || (skipLoops(viewer, placed) & 1L << animation) == 0;
    }

    /**
     * A player came within the {@code distance} of a loop of a block, or went further than it with some leeway: its
     * parts glide, once, from rest to where the loop has them (then he gets its frames), or back to rest (then he gets
     * none). The whole model glides with them when the loop moves it. A player watching an animation played for him
     * alone gets this once it ends; on a block whose loops an administrator stopped, nothing moves.
     */
    private void loopRange(Viewer viewer, PlacedDisplay placed, int index, boolean in, Nearby near) {
        if (!placed.loops() || !placed.privateFor.isEmpty() && placed.privateFor.contains(viewer.player.getUniqueId())) {
            return;
        }
        DisplayAnimation animation = placed.model.animations.get(index);
        LookAt lookAt = placed.model.lookAt;
        boolean unturned = viewer.lookedAtBy(placed);
        Object packet;
        if (in) {
            // the parts an animation everyone sees plays stay with it
            packet = placed.rejoinPacket(this.nms, index, this.tick % animation.duration, this.tick >= animation.duration, REJOIN_TICKS, unturned,
                    this.playing.contains(placed) ? placed.busyParts(this.tick) : null);
        } else {
            int[] parts = animation.touched;
            if (unturned) {
                parts = java.util.Arrays.stream(parts).filter(part -> !lookAt.turns(part)).toArray();
            }
            packet = parts.length == 0 ? null : placed.publicPacket(this.nms, parts, this.tick, REJOIN_TICKS, skipLoops(viewer, placed));
        }
        if (packet != null) {
            this.nms.send(viewer.player, packet);
        }
        if (animation.rooted && placed.carrierId >= 0) {
            this.nms.send(viewer.player, placed.carrierMove(this.nms, placed.rootTarget(this.tick, skipLoops(viewer, placed)), REJOIN_TICKS,
                    placed.carrierGlide, placed.carrierGlide));
        }
        if (unturned && placed.model.composesLook[index]) {
            // turned to him again from where this loop now has them
            near.looking = false;
        }
    }

    private static Nearby nearby(Viewer viewer, PlacedDisplay placed) {
        Nearby near = viewer.near(placed);
        if (near == null) {
            if (viewer.near == null) {
                viewer.near = new HashMap<>(4);
            }
            near = new Nearby();
            viewer.near.put(placed, near);
            viewer.lastPlaced = placed;
            viewer.lastNear = near;
        }
        return near;
    }

    /**
     * Turns the parts of the {@code look-at} of a block towards a player within its radius, when the direction he is
     * in changed by its {@code step} since they were last turned, at most once every {@code over} ticks. An animation
     * moving them (one everyone sees, other than a loop, or one played for him alone) goes first, unless it keeps them
     * looking at him ({@code look-at: true}, see {@link DisplayModel#composesLook}): they turn to him again once it
     * ends.
     *
     * @param dx where his eyes are from the pivot, in the world
     */
    private void gaze(PlacedDisplay placed, Viewer viewer, Nearby near, LookAt lookAt, int moves, double eyes, double dx, double dy, double dz) {
        if (suspends(placed, viewer)) {
            near.looking = false;
            return;
        }
        // the direction in the model, then from the way the parts face at rest
        org.joml.Vector3d d = placed.lookBack().transform(dx, dy, dz, this.direction);
        double facing = Math.toRadians(lookAt.facing);
        double cos = Math.cos(facing);
        double sin = Math.sin(facing);
        double x = d.x * cos - d.z * sin;
        double z = d.x * sin + d.z * cos;
        float yaw = (float) Math.toDegrees(Math.atan2(x, z));
        float pitch = (float) Math.toDegrees(Math.atan2(d.y, Math.sqrt(x * x + z * z)));
        yaw = Math.max(-lookAt.maxYaw, Math.min(lookAt.maxYaw, yaw));
        pitch = Math.max(-lookAt.maxPitch, Math.min(lookAt.maxPitch, pitch));
        if (near.looking && Math.abs(yaw - near.yaw) < lookAt.step && Math.abs(pitch - near.pitch) < lookAt.step) {
            near.settledMoves = moves;
            near.settledEyes = eyes;
            return;
        }
        if (this.tick - near.sentAt < lookAt.over) {
            // too soon after the last turn: looked at again at the next tick
            return;
        }
        // from where the frames that keep them looking have the parts (an animation played for him alone, the loops
        // with loop: true), else from their rest: facing him in the time of a turn, however slow a frame moving them
        PrivatePlay own = placed.privateFor.isEmpty() ? null : privatePlay(viewer.player.getUniqueId(), placed);
        PlacedDisplay.PublicPoses from = own != null ? privateLook(own, lookAt.over) : placed.lookPoses(this.tick, lookAt.over, skipLoops(viewer, placed));
        this.nms.send(viewer.player, placed.turnPacket(this.nms, yaw, pitch, lookAt.over, from, true));
        near.looking = true;
        near.yaw = yaw;
        near.pitch = pitch;
        near.sentAt = this.tick;
        near.settledMoves = moves;
        near.settledEyes = eyes;
        // a frame on its way for longer: the rest of it once the turn is done
        near.resumeAt = placed.lasting(from, lookAt.over) ? this.tick + lookAt.over : -1;
    }

    /**
     * Sends a player the rest of the way of the parts of the {@code look-at} a quick turn left short of the pose of
     * their frame (see {@link Nearby#resumeAt}), once the turn is done and no other has been sent since.
     */
    private void resume(PlacedDisplay placed, Viewer viewer, Nearby near) {
        if (near.resumeAt < 0 || this.tick < near.resumeAt) {
            return;
        }
        near.resumeAt = -1;
        if (!near.looking || near.sentAt == this.tick) {
            // left, or turned again at this tick: that turn has its own
            return;
        }
        PrivatePlay own = placed.privateFor.isEmpty() ? null : privatePlay(viewer.player.getUniqueId(), placed);
        PlacedDisplay.PublicPoses from = own != null ? privateLook(own, 0) : placed.lookPoses(this.tick, 0, skipLoops(viewer, placed));
        Object packet = from == null ? null : placed.followPacket(this.nms, near.yaw, near.pitch, from, placed.model.lookAt.over);
        if (packet != null) {
            this.nms.send(viewer.player, packet);
        }
    }

    /**
     * Where the parts of the {@code look-at} of a block are, unturned, for a player an animation is played for alone.
     */
    private PlacedDisplay.PublicPoses privateLook(PrivatePlay play, int minimum) {
        return play.placed.privatePoses(play.animation, play.lastFrame, this.tick - play.startedAt, play.lookFrom, minimum);
    }

    /**
     * Whether an animation that takes the parts of the {@code look-at} of a block away from a player plays for him: one
     * everyone sees, other than a loop, or one played for him alone, that moves them without keeping them looking at
     * him (see {@link DisplayModel#composesLook}).
     */
    private boolean suspends(PlacedDisplay placed, Viewer viewer) {
        if (placed.publicChecked != placed.publicStamp || this.tick >= placed.publicRecheckAt) {
            // the same for every player who sees the block: worked out again only when an animation everyone sees
            // starts or ends, or one waiting for its first tick gets there
            boolean[] moves = placed.model.movesLook;
            boolean[] keeps = placed.model.composesLook;
            placed.publicChecked = placed.publicStamp;
            placed.publicRecheckAt = Integer.MAX_VALUE;
            placed.suspending = false;
            for (int i = 0; i < moves.length && i < placed.startedAt.length; i++) {
                if (!moves[i] || keeps[i] || placed.startedAt[i] < 0) {
                    continue;
                }
                if (placed.startedAt[i] > this.tick) {
                    placed.publicRecheckAt = Math.min(placed.publicRecheckAt, placed.startedAt[i]);
                    continue;
                }
                placed.suspending = true;
                break;
            }
        }
        if (placed.suspending) {
            return true;
        }
        if (placed.privateChecked == placed.privateStamp && this.tick < placed.privateRecheckAt && !placed.privateSuspending) {
            // nothing played for one of them takes the parts: no player needs looking for
            return false;
        }
        boolean[] moves = placed.model.movesLook;
        boolean[] keeps = placed.model.composesLook;
        if (placed.privateChecked != placed.privateStamp || this.tick >= placed.privateRecheckAt) {
            // whether an animation played for one of them takes the parts from him (else no player needs looking for):
            // worked out again only when one starts or ends, or one waiting to start does
            placed.privateChecked = placed.privateStamp;
            placed.privateRecheckAt = Integer.MAX_VALUE;
            placed.privateSuspending = false;
            for (UUID id : placed.privateFor) {
                PrivatePlay play = privatePlay(id, placed);
                if (play == null || play.animation >= moves.length || !moves[play.animation] || keeps[play.animation]) {
                    continue;
                }
                if (play.startedAt > this.tick) {
                    placed.privateRecheckAt = Math.min(placed.privateRecheckAt, play.startedAt);
                    continue;
                }
                placed.privateSuspending = true;
                break;
            }
        }
        if (placed.privateSuspending && placed.privateFor.contains(viewer.player.getUniqueId())) {
            PrivatePlay play = privatePlay(viewer.player.getUniqueId(), placed);
            return play != null && play.startedAt <= this.tick && play.animation < moves.length && moves[play.animation] && !keeps[play.animation];
        }
        return false;
    }

    /**
     * A player came within the radius of an {@code approach} animation of a block: it plays for him alone, unless
     * it did less than its {@code cooldown} ago at this block, or another animation plays for him on it.
     */
    private void approach(Viewer viewer, PlacedDisplay placed, int animation) {
        UUID id = viewer.player.getUniqueId();
        if (!placed.privateFor.isEmpty() && placed.privateFor.contains(id)) {
            return;
        }
        if (placed.approach(id, animation, System.currentTimeMillis())) {
            startPrivate(viewer.player, placed, animation, true);
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
        for (PlacedDisplay placed : this.spinning) {
            Spin spin = placed.model.spin;
            if (spin != null && this.tick % spin.step == 0 && !placed.viewers.isEmpty()) {
                // where the spin has them in a step: the clients glide there meanwhile
                Object packet = placed.spinPacket(this.nms, spin.angle((long) this.tick + spin.step));
                for (Viewer viewer : placed.viewers) {
                    this.nms.send(viewer.player, packet);
                }
            }
        }
        for (PlacedDisplay placed : this.looping) {
            List<DisplayAnimation> animations = placed.model.animations;
            for (int i = 0; i < animations.size(); i++) {
                DisplayAnimation animation = animations.get(i);
                if (animation.trigger == DisplayAnimation.Trigger.LOOP) {
                    play(placed, i, animation, animation.startingAt[this.tick % animation.duration]);
                    sound(placed, animation, this.tick % animation.duration);
                    sparks(placed, i, animation, this.tick % animation.duration, null);
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
                    sparks(placed, i, animation, elapsed, null);
                }
                if (elapsed >= animation.duration) {
                    placed.startedAt[i] = -1;
                    placed.publicStamp++;
                    // the parts it took from the loops go back to them
                    rejoinLoops(placed, i);
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
     * An animation everyone sees (a click, a command played for all) has ended: the parts it moved that a loop moves
     * too, whose frames the loops kept from everyone while it played, glide back to where the loops have them (or with
     * their frame when it is still on its way), for each player who sees the block and gets its frames, as they do for a
     * player once an animation played for him alone ends. Those another one still plays stay with it; those turned
     * towards a player stay so. The whole model as well, when it and a loop both move it.
     */
    private void rejoinLoops(PlacedDisplay placed, int index) {
        if (!placed.loops() || placed.viewers.isEmpty()) {
            return;
        }
        if (placed.model.animations.get(index).rooted && placed.carrierId >= 0 && !placed.rootBusy(this.tick)
                && placed.model.animations.stream().anyMatch(loop -> loop.rooted && loop.trigger == DisplayAnimation.Trigger.LOOP)) {
            carrierToLoops(placed, REJOIN_TICKS);
        }
        boolean[] looped = placed.model.looped;
        boolean[] busy = placed.busyParts(this.tick);
        // the parts not given back: all but those it moved that a loop moves too, and no other animation plays
        boolean[] skip = null;
        for (int part : placed.model.animations.get(index).touched) {
            if (looped[part] && (busy == null || !busy[part])) {
                if (skip == null) {
                    skip = new boolean[placed.entityIds.length];
                    java.util.Arrays.fill(skip, true);
                }
                skip[part] = false;
            }
        }
        if (skip == null) {
            return;
        }
        LookAt lookAt = placed.model.lookAt;
        boolean look = false;
        for (int p = 0; lookAt != null && p < lookAt.parts.length; p++) {
            look |= !skip[lookAt.parts[p]];
        }
        List<DisplayAnimation> animations = placed.model.animations;
        for (Viewer viewer : placed.viewers) {
            if (!placed.privateFor.isEmpty() && placed.privateFor.contains(viewer.player.getUniqueId())) {
                // he gets the loops once the animation played for him ends
                continue;
            }
            Nearby near = lookAt == null ? null : viewer.near(placed);
            boolean looking = near != null && near.looking;
            // within the radius with his look taken by the animation: turned to him again at the next tick, from the loop
            boolean unturned = looking || near != null && near.gazing;
            for (int i = 0; i < animations.size(); i++) {
                DisplayAnimation loop = animations.get(i);
                if (loop.trigger != DisplayAnimation.Trigger.LOOP || loop.distance > 0 && !plays(viewer, placed, i)) {
                    continue;
                }
                Object packet = placed.rejoinPacket(this.nms, i, this.tick % loop.duration, this.tick >= loop.duration, REJOIN_TICKS, unturned, skip);
                if (packet != null) {
                    this.nms.send(viewer.player, packet);
                }
            }
            if (looking && look && placed.model.turnsFromFrames) {
                // turned towards him, where the loops that keep them looking have them now
                this.nms.send(viewer.player, placed.turnPacket(this.nms, near.yaw, near.pitch, REJOIN_TICKS, this.tick, skipLoops(viewer, placed)));
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
                // most ticks of an animation start no frame and play no sound nor particle: nothing to look up then
                if (elapsed >= 0 && elapsed < animation.duration
                        && (animation.startingAt[elapsed] != null || animation.sparksAt[elapsed] != null || animation.notesAt[elapsed] != null)) {
                    boolean sees = viewer != null && viewer.shown.contains(play.placed);
                    // sounds come from the block: heard in its world only, and by those it is sent to when it has a
                    // visible-if
                    boolean hears = player != null && player.getWorld() == play.placed.world && (sees || !play.placed.model.conditional())
                            && (animation.soundRange <= 0 || play.placed.heardBy(player, animation.soundRange));
                    int[] starting = animation.startingAt[elapsed];
                    // an animation that keeps the parts of the look-at looking at him: its frames reach them turned
                    Nearby turned = sees && play.placed.model.lookAt != null && play.placed.model.composesLook[play.animation] && viewer.lookedAtBy(play.placed)
                            ? viewer.near(play.placed) : null;
                    if (starting != null) {
                        for (int frame : starting) {
                            Object packet = sees ? play.placed.framePacket(this.nms, play.animation, frame, turned != null) : null;
                            if (packet != null) {
                                this.nms.send(viewer.player, packet);
                            }
                            if (turned != null) {
                                Object look = play.placed.turnedFramePacket(this.nms, play.animation, frame, turned.yaw, turned.pitch);
                                if (look != null) {
                                    this.nms.send(viewer.player, look);
                                }
                            }
                            DisplayAnimation.Frame played = animation.frames[frame];
                            if (played.root() != null && play.placed.carrierId >= 0) {
                                if (sees) {
                                    boolean withGlide = play.carrierGlide != played.over();
                                    this.nms.send(viewer.player, play.placed.rootFramePacket(this.nms, play.animation, frame, withGlide));
                                    play.carrierGlide = played.over();
                                }
                                play.rootMoved = true;
                                play.rootTarget = played.root();
                            }
                            if (hears && played.sound() != null) {
                                player.playSound(play.placed.base(), played.sound(), played.volume(), played.pitch());
                            }
                            play.lastFrame = frame;
                        }
                    }
                    if (sees && animation.sparksAt[elapsed] != null) {
                        sparks(play.placed, play.animation, animation, elapsed, viewer);
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

    /**
     * Shows the particles of an animation due at a tick of it: to a player it is played for alone, or to those who see
     * the block and get its frames (not those watching an animation of their own on it, nor those too far for a loop
     * with a distance). Where the model's point is, as the block is turned, moved and sized, with the whole model when
     * its frames have moved it.
     *
     * @param only the player it is played for alone, null for everyone
     */
    private void sparks(PlacedDisplay placed, int index, DisplayAnimation animation, int at, @Nullable Viewer only) {
        int[] due = animation.sparksAt[at];
        if (due == null || placed.viewers.isEmpty()) {
            return;
        }
        int most = (int) Math.max(0, Math.min(Integer.MAX_VALUE, me.general_breddok.blockdisplaycreator.file.config.value.LongConfigValue.ANIMATION_PARTICLES_MAX));
        for (int spark : due) {
            DisplayAnimation.Spark shown = animation.sparks[spark];
            if (only != null) {
                PrivatePlay play = privatePlay(only.player.getUniqueId(), placed);
                // where the whole model is for him: where his animation took it, or where it was when it started
                org.joml.Vector3f root = play != null && (play.rootMoved || play.rootTarget != null) ? play.rootTarget
                        : placed.rootTarget(this.tick, skipLoops(only, placed));
                spark(only, placed, shown, root, most);
                continue;
            }
            for (Viewer viewer : placed.viewers) {
                if (!placed.privateFor.isEmpty() && placed.privateFor.contains(viewer.player.getUniqueId())
                        || animation.distance > 0 && !plays(viewer, placed, index)) {
                    continue;
                }
                spark(viewer, placed, shown, placed.model.hasCarrier() ? placed.rootTarget(this.tick, skipLoops(viewer, placed)) : null, most);
            }
        }
    }

    private void spark(Viewer viewer, PlacedDisplay placed, DisplayAnimation.Spark spark, @Nullable org.joml.Vector3f root, int most) {
        double[] at = placed.modelPoint(spark.position(), root);
        double[] spread = placed.modelSpread(spark.offset());
        int count = Math.min(spark.count(), most);
        if (spark.data() == null) {
            viewer.player.spawnParticle(spark.particle(), at[0], at[1], at[2], count, spread[0], spread[1], spread[2], spark.speed());
        } else {
            viewer.player.spawnParticle(spark.particle(), at[0], at[1], at[2], count, spread[0], spread[1], spread[2], spark.speed(), spark.data());
        }
    }

    private void sound(PlacedDisplay placed, DisplayAnimation animation, int at) {
        int[] due = animation.notesAt[at];
        if (due == null) {
            return;
        }
        for (int note : due) {
            DisplayAnimation.Note played = animation.notes[note];
            playSound(placed, placed.model.animations.indexOf(animation), animation, played.sound(), played.volume(), played.pitch());
        }
    }

    private void play(PlacedDisplay placed, int index, DisplayAnimation animation, int[] starting) {
        if (starting == null) {
            return;
        }
        // a loop leaves to an animation everyone sees that plays (a click) the parts it moves: none of its frames go
        // for them until it ends, then they come back to the loop gliding (see rejoinLoops)
        boolean[] busy = animation.trigger == DisplayAnimation.Trigger.LOOP && this.playing.contains(placed) ? placed.busyParts(this.tick) : null;
        // the whole model too, when it moves it
        boolean rootHeld = busy != null && animation.rooted && placed.rootBusy(this.tick);
        for (int frame : starting) {
            Object packet = placed.viewers.isEmpty() ? null : placed.framePacket(this.nms, index, frame, false, busy);
            if (packet != null) {
                Object unturned = null;
                for (Viewer viewer : placed.viewers) {
                    // a player watching an animation played for them alone gets the others' frames once it ends
                    if (!placed.privateFor.isEmpty() && placed.privateFor.contains(viewer.player.getUniqueId())) {
                        continue;
                    }
                    if (animation.distance > 0 && !plays(viewer, placed, index)) {
                        // too far from the block for this loop
                        continue;
                    }
                    if (placed.model.lookAt != null && viewer.lookedAtBy(placed)) {
                        // the parts turned towards him stay so: he gets the frame of the others (an animation that moves
                        // them has made them turn back to it, see attend)
                        if (unturned == null) {
                            unturned = placed.framePacket(this.nms, index, frame, true, busy);
                            if (unturned == null) {
                                unturned = Boolean.FALSE;
                            }
                        }
                        if (unturned != Boolean.FALSE) {
                            this.nms.send(viewer.player, unturned);
                        }
                        if (placed.model.composesLook[index]) {
                            // the animation keeps them looking at him: the frame, turned towards him
                            Nearby near = viewer.near(placed);
                            Object turned = placed.turnedFramePacket(this.nms, index, frame, near.yaw, near.pitch, busy);
                            if (turned != null) {
                                this.nms.send(viewer.player, turned);
                            }
                        }
                        continue;
                    }
                    this.nms.send(viewer.player, packet);
                }
            }
            DisplayAnimation.Frame played = animation.frames[frame];
            if (played.root() != null && placed.carrierId >= 0 && !placed.viewers.isEmpty() && !rootHeld) {
                moveCarrier(placed, index, frame, played);
            }
            if (played.sound() != null) {
                playSound(placed, index, animation, played.sound(), played.volume(), played.pitch());
            }
            placed.lastFrame[index] = frame;
        }
    }

    /**
     * Sends the players who see a block the teleport of its carrier a frame moving the whole model makes, with the
     * glide of the frame only when they do not all have it already.
     */
    private void moveCarrier(PlacedDisplay placed, int index, int frame, DisplayAnimation.Frame played) {
        boolean withGlide = placed.carrierGlide != played.over();
        Object packet = placed.rootFramePacket(this.nms, index, frame, withGlide);
        boolean skipped = false;
        DisplayAnimation animation = placed.model.animations.get(index);
        for (Viewer viewer : placed.viewers) {
            if (!placed.privateFor.isEmpty() && placed.privateFor.contains(viewer.player.getUniqueId())
                    || animation.distance > 0 && !plays(viewer, placed, index)) {
                skipped = true;
                continue;
            }
            this.nms.send(viewer.player, packet);
        }
        if (withGlide) {
            // those left out keep the glide they had
            placed.carrierGlide = skipped ? -1 : played.over();
        }
    }

    private void hide(PlacedDisplay placed) {
        if (this.gizmos != null) {
            this.gizmos.forget(placed);
        }
        this.looping.remove(placed);
        this.spinning.remove(placed);
        dropPrivatePlays(placed);
        if (placed.model != null && placed.model.conditional()) {
            for (Viewer viewer : this.viewers.values()) {
                viewer.guarded.remove(placed);
                viewer.withheld.remove(placed);
            }
        }
        if (placed.viewers.isEmpty()) {
            return;
        }
        Object packet = placed.removePacket(this.nms);
        for (Viewer viewer : placed.viewers) {
            viewer.shown.remove(placed);
            viewer.forgetNear(placed);
            if (viewer.player.isOnline()) {
                this.nms.send(viewer.player, packet);
                unsent(viewer, placed);
                hitbox(viewer.player, placed, false);
            }
        }
        placed.viewers.clear();
    }

    /**
     * Drops what a client was shown without telling it: it has already forgotten it, or is gone.
     */
    private void forget(Viewer viewer) {
        boolean online = viewer.player.isOnline();
        for (PlacedDisplay placed : viewer.shown) {
            placed.viewers.remove(viewer);
            if (placed.viewers.isEmpty()) {
                this.looping.remove(placed);
                this.spinning.remove(placed);
            }
            if (placed.hitbox != null && online) {
                hitbox(viewer.player, placed, false);
            }
        }
        viewer.shown.clear();
        viewer.guarded.clear();
        viewer.withheld.clear();
        viewer.forgetAllNear();
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
        Location location = player.getLocation(this.placeHere);
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        int y = location.getBlockY();
        if (!world.getUID().equals(viewer.world)) {
            forget(viewer);
        } else if (!viewer.stale && chunkX == viewer.chunkX && chunkZ == viewer.chunkZ && Math.abs(y - viewer.y) < HEIGHT_STEP) {
            return;
        }
        viewer.world = world.getUID();
        viewer.chunkX = chunkX;
        viewer.chunkZ = chunkZ;
        viewer.y = y;
        viewer.stale = false;
        // the chunks to look in: those of the model seen from the furthest away
        int radius = Math.max(radius(world), radius(world, this.farthest));

        List<PlacedDisplay> leaving = null;
        for (Iterator<PlacedDisplay> iterator = viewer.shown.iterator(); iterator.hasNext(); ) {
            PlacedDisplay placed = iterator.next();
            if (!inRange(viewer, placed, HEIGHT_MARGIN)) {
                iterator.remove();
                placed.viewers.remove(viewer);
                viewer.forgetNear(placed);
                unsent(viewer, placed);
                if (placed.viewers.isEmpty()) {
                    this.looping.remove(placed);
                    this.spinning.remove(placed);
                }
                if (placed.model.conditional()) {
                    viewer.guarded.remove(placed);
                    hitbox(player, placed, false);
                }
                if (leaving == null) {
                    leaving = new ArrayList<>();
                }
                leaving.add(placed);
            }
        }
        if (!viewer.withheld.isEmpty()) {
            // the blocks held back from him that are now out of his range are no longer checked
            for (Iterator<PlacedDisplay> iterator = viewer.withheld.iterator(); iterator.hasNext(); ) {
                PlacedDisplay placed = iterator.next();
                if (!inRange(viewer, placed, HEIGHT_MARGIN)) {
                    iterator.remove();
                    viewer.guarded.remove(placed);
                }
            }
        }
        if (leaving != null) {
            int count = 0;
            for (PlacedDisplay placed : leaving) {
                count += placed.allIds.length;
            }
            int[] ids = new int[count];
            int offset = 0;
            for (PlacedDisplay placed : leaving) {
                System.arraycopy(placed.allIds, 0, ids, offset, placed.allIds.length);
                offset += placed.allIds.length;
            }
            this.nms.send(player, this.nms.removePacket(ids));
        }

        showModelsAround(viewer, world, chunkX, chunkZ, radius);
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
            if (!inRange(viewer, placed, 0) || viewer.shown.contains(placed) || viewer.withheld.contains(placed)) {
                continue;
            }
            if (!chunkSent(viewer.player, placed.chunkX(), placed.chunkZ())) {
                // the terrain first: a player who just arrived gets the decorations of a chunk once he has the chunk
                viewer.stale = true;
                continue;
            }
            reach(viewer, placed);
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
     * Whether a player is close enough to a custom block to be sent it: by the chunks between them and, when its
     * file sets a {@code display.view-distance}, {@code view-above} or {@code view-below}, by their difference in
     * height too. Clients draw a model with a view distance only within that many blocks in every direction: without
     * this, a block in a cave was sent, with every frame of its animations, to the players standing above it.
     *
     * @param margin how many more blocks of height a block already shown is kept for, so that a player moving around
     *               the limit does not get it again and again
     */
    private boolean inRange(Viewer viewer, PlacedDisplay placed, int margin) {
        if (!inRange(placed.chunkX() - viewer.chunkX, placed.chunkZ() - viewer.chunkZ, radius(placed))) {
            return false;
        }
        if (placed.model == null) {
            return true;
        }
        int height = viewer.y - placed.y;
        int limit = height >= 0 ? placed.viewAbove() : placed.viewBelow();
        return limit < 0 || Math.abs(height) <= limit + margin;
    }

    /**
     * How many chunks away a custom block is shown: as far as its model asks, or as the world tracks display
     * entities, within the chunks the server sends.
     */
    private int radius(PlacedDisplay placed) {
        return placed.model == null || placed.viewDistance() <= 0 ? radius(placed.world) : radius(placed.world, placed.viewDistance());
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

    /**
     * How far, in blocks, a block without a distance of its own is sent in a world.
     */
    int defaultViewBlocks(World world) {
        return radius(world) * 16;
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
                if (this.lightened > 0) {
                    this.plugin.getLogger().info("Lightened the animations of " + this.lightened + " custom block(s): " + this.updatesBefore
                            + " pose updates a turn down to " + this.updatesAfter + ", same motion");
                }
                if (this.optimisedDirty) {
                    this.optimisedDirty = false;
                    Map<String, String> copy = new LinkedHashMap<>(this.optimised);
                    Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> saveOptimised(copy));
                }
                this.lightened = 0;
                this.updatesBefore = 0;
                this.updatesAfter = 0;
                this.converted = 0;
                this.moved = 0;
                this.restored = 0;
                this.hitboxes = 0;
            }
        }, 200L);
    }
}
