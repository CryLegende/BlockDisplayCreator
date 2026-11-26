package me.general_breddok.blockdisplaycreator.render;

import me.general_breddok.blockdisplaycreator.custom.block.AbstractCustomBlock;
import me.general_breddok.blockdisplaycreator.custom.block.BDCCustomBlockService;
import me.general_breddok.blockdisplaycreator.entity.GroupSummoner;
import me.general_breddok.blockdisplaycreator.entity.display.TranslationVectorAdjustable;
import me.general_breddok.blockdisplaycreator.rotation.DirectedVector;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Transformation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the clients need to draw a custom block, read once from its file: the display entities its summon commands
 * create, each with its type and synced data exactly as the server would send them.
 */
final class DisplayModel {

    /**
     * How far above the build limit the model is summoned for the time of the reading, out of everyone's sight.
     */
    private static final int READING_HEIGHT = 1000;

    final String name;
    final short sidesCount;
    final float translationYaw;
    final float translationPitch;
    final String hitboxSignature;
    /**
     * Whether its file gives it a click box. One without is picked by what it draws when an operator moves it.
     */
    final boolean clickable;
    final List<Element> elements;
    final List<DisplayAnimation> animations;
    /**
     * Whether one of its animations plays in a loop.
     */
    final boolean loops;
    /**
     * For each entity, whether a loop moves it.
     */
    final boolean[] looped;
    /**
     * Whether one of its entities stands lower than the block when it stands on its block (see {@link Element#sink()}).
     */
    final boolean sinks;
    /**
     * How far, in blocks, players are sent the model: its file's {@code display.view-distance}, 0 for the distance
     * the world tracks display entities at.
     */
    final int viewDistance;
    /**
     * How many blocks above, and below, the block players are sent the model: its file's {@code display.view-above}
     * and {@code display.view-below}, else its view distance, -1 without either (no limit).
     */
    final int viewAbove;
    final int viewBelow;
    /**
     * Its file's {@code display.visible-if}: the conditions a player must meet to be sent the model, empty for a model
     * everyone is sent.
     */
    final List<Condition> visibleIf;
    /**
     * For each entity, the fields of its transformation some pose of it changes ({@link #TRANSLATION},
     * {@link #SCALE}, {@link #LEFT_ROTATION}, {@link #RIGHT_ROTATION}), 0 for an entity that never leaves its rest.
     * <p>
     * A field no pose changes keeps, on every client, the value the entity was sent with: the frames leave it out (see
     * {@link #varying(List, List)}).
     */
    final byte[] varying;
    /**
     * Its file's {@code look-at}: parts turned towards each player close by, or null.
     */
    final LookAt lookAt;
    /**
     * The indexes of its {@code approach} animations.
     */
    final int[] approaches;
    /**
     * For each animation, whether it moves one of the parts of {@link #lookAt}: unless it keeps them looking at the
     * player ({@link #composesLook}), it then goes first, and the parts look at him again once it ends. Null without a
     * {@code look-at}.
     */
    final boolean[] movesLook;
    /**
     * For each animation moving parts of {@link #lookAt}, whether they keep looking at a player while it plays (see
     * {@link #composesLook(List, LookAt, List)}): a player they look at gets each frame moving them turned towards him,
     * and a turn starts from where its frames have them. Worked out on the animations as written. Null without a
     * {@code look-at}.
     */
    final boolean[] composesLook;
    /**
     * The loops (bit i for the animation i) that keep the parts of the {@code look-at} looking at a player, and those
     * that move them without: a player they look at gets none of the frames of the latter for them.
     */
    final long lookingLoops;
    final long unlookedLoops;
    /**
     * Whether a frame of some animation reaches the parts of the {@code look-at} of a player they look at: a turn then
     * starts from where the frames have them, else from their rest.
     */
    final boolean turnsFromFrames;
    /**
     * How much higher than the model its carrier stands, in blocks at the model's size, 0 for most models: as much
     * deeper as its {@code root} frames take it under its floor (a crouch before a jump), with a little more. The
     * parts riding the carrier are lowered as much in it, so that they are drawn where they would be: a client lights
     * an entity by the block it stands in, and those standing in the ground would be drawn black.
     */
    final float lift;
    /**
     * How many chunks away from the block a player may be and still be within the radius of its {@code look-at} or of
     * one of its {@code approach} animations, with a chunk to spare for the players moving between two checks of
     * where they are.
     */
    final int attentionChunks;
    /**
     * When one of its animations moves the whole model ({@code root} frames): for each entity, whether it rides the
     * invisible carrier those frames teleport (all but those of {@code root-motion.exclude}). Null for the other
     * models, which are shown without a carrier.
     */
    final boolean[] carried;
    /**
     * The entity type of the carrier: an empty block display, which draws nothing.
     */
    final Object carrierType;
    /**
     * The glide of the carrier its clients are given when they first get it: the length of the first frame moving
     * the whole model, so that a loop of frames of the same length never sends it again.
     */
    final int carrierGlide;
    /**
     * Its file's {@code spin}: parts turning on and on through the yaw of their entities, or null.
     */
    final Spin spin;
    /**
     * Its loops that play only for the players close to the block ({@code distance}): bit i for the animation i (the
     * first 64 animations of the file).
     */
    final long distanceLoops;
    /**
     * Among them, those that move the whole model.
     */
    final long distanceRooted;
    /**
     * The farthest its frames take the whole model from where it stands (root), in its axes at size 1: how much further
     * than the radius of its {@code look-at} a player may stand from its pivot at rest and still be within it.
     */
    final float rootReach;

    /**
     * How many blocks further than its {@code distance} a player has to go before a loop stops playing for him: one
     * walking along the edge does not see it start and stop over and over.
     */
    static final double LOOP_LEEWAY = 2;

    static final int TRANSLATION = 1;
    static final int SCALE = 2;
    static final int LEFT_ROTATION = 4;
    static final int RIGHT_ROTATION = 8;
    static final int WHOLE_POSE = TRANSLATION | SCALE | LEFT_ROTATION | RIGHT_ROTATION;

    private DisplayModel(AbstractCustomBlock definition, List<Element> elements, List<DisplayAnimation> animations, int viewDistance,
                         int viewAbove, int viewBelow, List<Condition> visibleIf, byte[] varying, LookAt lookAt, boolean[] composesLook, boolean[] carried,
                         Object carrierType, float lift, Spin spin) {
        this.spin = spin;
        this.varying = varying;
        this.lookAt = lookAt;
        this.carried = carried;
        this.carrierType = carrierType;
        this.lift = carried == null ? 0f : lift;
        this.carrierGlide = carrierGlide(animations);
        this.approaches = approaches(animations);
        this.movesLook = movesLook(animations, lookAt);
        this.composesLook = composesLook;
        this.lookingLoops = lookLoops(animations, this.movesLook, composesLook, true);
        this.unlookedLoops = lookLoops(animations, this.movesLook, composesLook, false);
        boolean composes = false;
        for (int i = 0; composesLook != null && i < composesLook.length; i++) {
            composes |= composesLook[i];
        }
        this.turnsFromFrames = lookAt != null && (lookAt.keepsLoop || composes);
        this.attentionChunks = attentionChunks(animations, this.approaches, lookAt);
        this.distanceLoops = distanceLoops(animations, false);
        this.distanceRooted = distanceLoops(animations, true);
        this.rootReach = rootReach(animations);
        this.name = definition.getName();
        this.viewDistance = viewDistance;
        int everyWay = viewDistance > 0 ? viewDistance : -1;
        this.viewAbove = viewAbove < 0 ? everyWay : viewAbove;
        this.viewBelow = viewBelow < 0 ? everyWay : viewBelow;
        this.visibleIf = visibleIf;
        this.sidesCount = definition.getSidesCount();
        this.hitboxSignature = BDCCustomBlockService.hitboxSignature(definition);
        this.clickable = definition.getConfiguredInteractions().stream().anyMatch(interaction -> !BDCCustomBlockService.isUseless(interaction));
        this.elements = elements;
        this.animations = animations;
        this.loops = animations.stream().anyMatch(animation -> animation.trigger == DisplayAnimation.Trigger.LOOP);
        this.looped = looped(animations, elements.size());
        this.sinks = elements.stream().anyMatch(element -> element.sink() > 0);

        GroupSummoner<Display> summoner = definition.getDisplaySummoner();
        DirectedVector translation = summoner instanceof TranslationVectorAdjustable adjustable ? adjustable.getTranslation() : null;
        this.translationYaw = translation == null ? 0 : translation.getYaw();
        this.translationPitch = translation == null ? 0 : translation.getPitch();
    }

    /**
     * The same model with other animations (as many, in the same order): its entities do not change.
     */
    DisplayModel withAnimations(List<DisplayAnimation> animations) {
        return new DisplayModel(this, animations);
    }

    private DisplayModel(DisplayModel model, List<DisplayAnimation> animations) {
        this.name = model.name;
        this.sidesCount = model.sidesCount;
        this.translationYaw = model.translationYaw;
        this.translationPitch = model.translationPitch;
        this.hitboxSignature = model.hitboxSignature;
        this.clickable = model.clickable;
        this.elements = model.elements;
        this.animations = List.copyOf(animations);
        this.loops = model.loops;
        this.looped = looped(this.animations, model.elements.size());
        this.sinks = model.sinks;
        this.viewDistance = model.viewDistance;
        this.viewAbove = model.viewAbove;
        this.viewBelow = model.viewBelow;
        this.visibleIf = model.visibleIf;
        this.bounds = model.bounds;
        // worked out on the animations as written: lighter ones only leave out frames, never add a pose
        this.varying = model.varying;
        this.lookAt = model.lookAt;
        this.approaches = approaches(this.animations);
        this.movesLook = movesLook(this.animations, this.lookAt);
        // whether an animation keeps the parts looking stays as worked out on it as written: a lighter one may have left
        // out the frames that made it otherwise
        this.composesLook = model.composesLook;
        this.lookingLoops = model.lookingLoops;
        this.unlookedLoops = model.unlookedLoops;
        this.turnsFromFrames = model.turnsFromFrames;
        this.attentionChunks = attentionChunks(this.animations, this.approaches, this.lookAt);
        this.distanceLoops = distanceLoops(this.animations, false);
        this.distanceRooted = distanceLoops(this.animations, true);
        // frames moving the whole model are kept as they are by the lighter animations
        this.carried = model.carried;
        this.carrierType = model.carrierType;
        this.carrierGlide = model.carrierGlide;
        this.lift = model.lift;
        this.rootReach = model.rootReach;
        this.spin = model.spin;
    }

    private static float rootReach(List<DisplayAnimation> animations) {
        float reach = 0f;
        for (DisplayAnimation animation : animations) {
            for (DisplayAnimation.Frame frame : animation.frames) {
                if (frame.root() != null) {
                    reach = Math.max(reach, frame.root().length());
                }
            }
        }
        return reach;
    }

    private static int carrierGlide(List<DisplayAnimation> animations) {
        // the loops first: they play all the time
        for (boolean loops : new boolean[]{true, false}) {
            for (DisplayAnimation animation : animations) {
                if (animation.rooted && (animation.trigger == DisplayAnimation.Trigger.LOOP) == loops) {
                    for (DisplayAnimation.Frame frame : animation.frames) {
                        if (frame.root() != null) {
                            return frame.over();
                        }
                    }
                }
            }
        }
        return 0;
    }

    /**
     * Whether the model is shown on a carrier that its {@code root} frames move.
     */
    boolean hasCarrier() {
        return this.carried != null;
    }

    /**
     * Whether an entity of the model rides its carrier.
     */
    boolean carried(int part) {
        return this.carried != null && this.carried[part];
    }

    private static int attentionChunks(List<DisplayAnimation> animations, int[] approaches, LookAt lookAt) {
        double reach = 0;
        for (int index : approaches) {
            reach = Math.max(reach, animations.get(index).radius);
        }
        if (lookAt != null) {
            // the pivot may stand to the side of the block: as far as the model is wide, and some more for its size
            reach = Math.max(reach, lookAt.radius + 2 * Math.hypot(lookAt.pivot.x, lookAt.pivot.z));
        }
        for (DisplayAnimation animation : animations) {
            if (animation.distance > 0) {
                reach = Math.max(reach, animation.distance + LOOP_LEEWAY);
            }
        }
        return (int) Math.ceil((reach + 2) / 16) + 1;
    }

    private static long distanceLoops(List<DisplayAnimation> animations, boolean rootedOnly) {
        long loops = 0;
        for (int i = 0; i < animations.size() && i < 64; i++) {
            DisplayAnimation animation = animations.get(i);
            if (animation.distance > 0 && (!rootedOnly || animation.rooted)) {
                loops |= 1L << i;
            }
        }
        return loops;
    }

    private static boolean[] looped(List<DisplayAnimation> animations, int parts) {
        boolean[] looped = new boolean[parts];
        for (DisplayAnimation animation : animations) {
            if (animation.trigger == DisplayAnimation.Trigger.LOOP) {
                for (int part : animation.touched) {
                    looped[part] = true;
                }
            }
        }
        return looped;
    }

    private static int[] approaches(List<DisplayAnimation> animations) {
        return java.util.stream.IntStream.range(0, animations.size())
                .filter(i -> animations.get(i).trigger == DisplayAnimation.Trigger.APPROACH).toArray();
    }

    private static boolean[] movesLook(List<DisplayAnimation> animations, LookAt lookAt) {
        if (lookAt == null) {
            return null;
        }
        boolean[] moves = new boolean[animations.size()];
        for (int i = 0; i < moves.length; i++) {
            moves[i] = animations.get(i).moves(lookAt.parts);
        }
        return moves;
    }

    /**
     * For each animation moving parts of the {@code look-at}, whether they keep looking at the player while it plays:
     * its {@code look-at}, or else for one that only shows, hides or sizes them (a blink: see {@link #onlySizes}) and for
     * the loops when the {@code look-at} has {@code loop: true}. The others leave the player while they play: an
     * animation that turns those parts makes them look elsewhere (a scene: the head looks at a map), and a loop without
     * {@code loop: true} is not sent for them to a player they look at.
     *
     * @return null without a {@code look-at}
     */
    static boolean[] composesLook(List<DisplayAnimation> animations, LookAt lookAt, List<Element> elements) {
        if (lookAt == null) {
            return null;
        }
        boolean[] composes = new boolean[animations.size()];
        for (int i = 0; i < composes.length; i++) {
            DisplayAnimation animation = animations.get(i);
            if (!animation.moves(lookAt.parts)) {
                continue;
            }
            composes[i] = animation.lookAt != null ? animation.lookAt
                    : animation.trigger == DisplayAnimation.Trigger.LOOP && lookAt.keepsLoop || onlySizes(animation, lookAt, elements);
        }
        return composes;
    }

    /**
     * Whether the frames of an animation only show, hide or size the parts of the {@code look-at} they move: each pose
     * keeps the rotations of the part at rest, and its translation unless its size changes too (a part sized around a
     * point of its own moves with it). A frame taking a part back to rest counts too.
     */
    private static boolean onlySizes(DisplayAnimation animation, LookAt lookAt, List<Element> elements) {
        for (DisplayAnimation.Frame frame : animation.frames) {
            for (int i = 0; i < frame.parts().length; i++) {
                int part = frame.parts()[i];
                if (!lookAt.turns(part)) {
                    continue;
                }
                Transformation target = frame.targets()[i];
                if (target == null) {
                    return false;
                }
                Transformation rest = elements.get(part).transformation();
                if (!sameTurn(rest.getLeftRotation(), target.getLeftRotation()) || !sameTurn(rest.getRightRotation(), target.getRightRotation())) {
                    return false;
                }
                if (near(rest.getScale(), target.getScale(), 1.0E-5f) && !near(rest.getTranslation(), target.getTranslation(), 1.0E-4f)) {
                    // moved, not sized
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether two rotations turn the same way, to a fifth of a degree (a matrix the server split gives back a rotation
     * a little off, or its opposite quaternion).
     */
    private static boolean sameTurn(org.joml.Quaternionfc a, org.joml.Quaternionfc b) {
        double dot = a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w();
        double norms = Math.sqrt((a.lengthSquared()) * (b.lengthSquared()));
        return norms > 0 && Math.abs(dot) / norms > 1 - 2.0E-6;
    }

    private static boolean near(org.joml.Vector3fc a, org.joml.Vector3fc b, float most) {
        return Math.abs(a.x() - b.x()) <= most && Math.abs(a.y() - b.y()) <= most && Math.abs(a.z() - b.z()) <= most;
    }

    /**
     * The loops (bit i for the animation i, the first 64) moving parts of the {@code look-at} that keep them looking, or
     * those that do not.
     */
    private static long lookLoops(List<DisplayAnimation> animations, boolean[] moves, boolean[] composes, boolean looking) {
        long loops = 0;
        for (int i = 0; moves != null && composes != null && i < animations.size() && i < 64 && i < composes.length; i++) {
            if (animations.get(i).trigger == DisplayAnimation.Trigger.LOOP && moves[i] && composes[i] == looking) {
                loops |= 1L << i;
            }
        }
        return loops;
    }

    /**
     * How much higher than the model its carrier stands (see {@link #lift}): the deepest its {@code root} frames take it
     * under its floor, raised to the next 64th of a block with one more, 0 when none goes under it.
     */
    private static float lift(List<DisplayAnimation> animations) {
        float lowest = 0;
        for (DisplayAnimation animation : animations) {
            for (DisplayAnimation.Frame frame : animation.frames) {
                if (frame.root() != null) {
                    lowest = Math.min(lowest, frame.root().y);
                }
            }
        }
        return lowest < 0 ? (float) Math.ceil(-lowest * 64 + 1) / 64f : 0f;
    }

    /**
     * Whether players close by change what the model shows them: it has a {@code look-at}, an {@code approach}
     * animation or a loop with a {@code distance}.
     */
    boolean attentive() {
        return this.lookAt != null || this.approaches.length > 0 || this.distanceLoops != 0;
    }

    /**
     * One display entity of the model.
     *
     * @param type           its server-side entity type
     * @param data           its synced data, as a client receives it
     * @param yaw            the yaw it is summoned with
     * @param pitch          the pitch it is summoned with
     * @param transformation its resting transformation
     * @param block          whether it is a block display, drawn from its corner rather than around its origin
     * @param drawn          whether it shows something: the root of a bdengine model is an empty block display
     * @param shape          what it draws before its transformation, as a box (smallest x, y, z then largest), null
     *                       when that is not known
     * @param billboard      how it turns to face the player: {@link #BILLBOARD_FIXED}, {@link #BILLBOARD_VERTICAL} or
     *                       {@link #BILLBOARD_ALL}
     * @param upright        its synced data when the model stands on its block: with a culling box if it can have one
     * @param sink           how much lower than the model its entity stands when the model stands on its block, in
     *                       blocks of the model, its translation raised as much (0 for none): a client draws a culling box
     *                       from the entity up
     * @param body           what it really draws before its transformation, as a box (smallest x, y, z then largest):
     *                       a flat item (a sunflower, a stick) one pixel thick, a head half a block, where {@code shape}
     *                       takes a whole block for any item so that no culling box ever cuts one
     */
    record Element(Object type, List<?> data, float yaw, float pitch, Transformation transformation, boolean block, boolean drawn,
                   float[] shape, byte billboard, List<?> upright, float sink, float[] body) {
    }

    /**
     * Reads the model of a custom block by summoning it for an instant, far above the given chunk, which must be
     * loaded with its entities.
     *
     * @param animations   the {@code animations} section of its file, or null
     * @param viewDistance how far players are sent it, in blocks, 0 for the world's distance
     * @param viewAbove    how many blocks above it players are sent it, -1 for the view distance
     * @param viewBelow    how many blocks below it players are sent it, -1 for the view distance
     * @param visibleIf    the conditions a player must meet to be sent it, empty for none
     * @param problems     receives what could not be read of its animations
     * @return the model, without any element when the summon commands of the block create nothing
     */
    static DisplayModel read(NmsBridge nms, AbstractCustomBlock definition, List<String[]> summonTexts, World world, int chunkX, int chunkZ,
                             ConfigurationSection animations, ConfigurationSection lookAt, ConfigurationSection rootMotion, int viewDistance,
                             int viewAbove, int viewBelow, List<Condition> visibleIf, List<String> problems) {
        return read(nms, definition, summonTexts, world, chunkX, chunkZ, animations, lookAt, rootMotion, null, viewDistance, viewAbove, viewBelow,
                visibleIf, problems);
    }

    /**
     * @param spinSection the {@code spin} section of its file, or null
     */
    static DisplayModel read(NmsBridge nms, AbstractCustomBlock definition, List<String[]> summonTexts, World world, int chunkX, int chunkZ,
                             ConfigurationSection animations, ConfigurationSection lookAt, ConfigurationSection rootMotion,
                             ConfigurationSection spinSection, int viewDistance,
                             int viewAbove, int viewBelow, List<Condition> visibleIf, List<String> problems) {
        Location location = new Location(world, (chunkX << 4) + 8.5, world.getMaxHeight() + READING_HEIGHT, (chunkZ << 4) + 8.5);
        List<Element> elements = new ArrayList<>();

        Interaction sender = world.spawn(location, Interaction.class);
        List<Display> displays;
        try {
            // its summon commands run now, to their end, even from inside another command (a placement, a reload)
            displays = nms.outsideCommand(() -> summonTexts == null ? definition.spawnDisplay(location, sender, display -> true)
                    : summon(definition, summonTexts, location, sender));
        } finally {
            sender.remove();
        }

        try {
            // a model made of several summon commands has several roots, created in the order of the commands
            displays.stream()
                    .filter(display -> display.getVehicle() == null)
                    .sorted(Comparator.comparingInt(Entity::getEntityId))
                    .forEach(root -> collect(nms, root, elements));
        } finally {
            for (Display display : displays) {
                display.getPassengers().forEach(Entity::remove);
                display.remove();
            }
        }
        List<DisplayAnimation> read = elements.isEmpty() ? List.of() : List.copyOf(DisplayAnimation.read(animations, elements, nms, problems));
        LookAt look = null;
        if (lookAt != null && !elements.isEmpty()) {
            try {
                look = LookAt.read(lookAt, elements);
            } catch (RuntimeException e) {
                // the block is still shown, its parts do not turn
                problems.add("look-at: " + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString()));
            }
        }
        byte[] varying = varying(elements, read);
        if (look != null) {
            // a turn moves a part around the pivot: its translation and its left rotation change
            for (int part : look.parts) {
                varying[part] |= TRANSLATION | LEFT_ROTATION;
            }
        }
        boolean[] carried = null;
        Object carrierType = null;
        if (read.stream().anyMatch(animation -> animation.rooted)) {
            if (!nms.canCarry()) {
                problems.add("frames with a root are left out: this server cannot seat display entities on another one");
                read = read.stream().map(DisplayAnimation::withoutRoot).toList();
            } else {
                carried = new boolean[elements.size()];
                java.util.Arrays.fill(carried, true);
                Object exclude = rootMotion == null ? null : rootMotion.get("exclude");
                if (exclude != null) {
                    try {
                        for (int part : DisplayAnimation.parts(exclude, elements.size())) {
                            carried[part] = false;
                        }
                    } catch (IllegalArgumentException e) {
                        problems.add("root-motion.exclude: " + e.getMessage() + "; every part moves with the model");
                    }
                }
                carrierType = carrierType(nms, location);
            }
        } else if (rootMotion != null) {
            problems.add("root-motion has no use: no frame of its animations has a root");
        }
        boolean[] composes = composesLook(read, look, elements);
        float lift = carried == null ? 0f : lift(read);
        return new DisplayModel(definition, List.copyOf(withCullingBox(nms, elements, read, look, composes, carried, lift)), read, viewDistance, viewAbove,
                viewBelow, visibleIf, varying, look, composes, carried, carrierType, lift, spin(nms, spinSection, elements, problems));
    }

    /**
     * The {@code spin} of the file of a block, or null when it has none or it cannot be read (said in problems).
     */
    private static Spin spin(NmsBridge nms, ConfigurationSection section, List<Element> elements, List<String> problems) {
        if (section == null || elements.isEmpty()) {
            return null;
        }
        if (!nms.canSpin()) {
            problems.add("spin is left out: this server cannot turn display entities shown through packets");
            return null;
        }
        try {
            return Spin.read(section, elements);
        } catch (RuntimeException e) {
            // the block is still shown, its parts do not turn
            problems.add("spin: " + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString()));
            return null;
        }
    }

    /**
     * The entity type of an empty block display, read from one summoned for an instant.
     */
    private static Object carrierType(NmsBridge nms, Location location) {
        BlockDisplay display = location.getWorld().spawn(location, BlockDisplay.class, entity -> entity.setPersistent(false));
        try {
            return nms.type(nms.handle(display));
        } finally {
            display.remove();
        }
    }

    /**
     * Below this, two numbers of a pose are taken to be the same: a millionth of a block, of a scale or of a
     * component of a rotation, which no client can draw.
     */
    private static final float SAME = 1.0E-6f;

    /**
     * Which fields of its transformation each entity of a model changes, in any pose of any of its animations: a
     * field that keeps its resting value in all of them is the same in every pose packet the plugin sends the entity
     * (the size of a block and the raise of a lowered entity apply to every packet alike, and a change of size sends
     * every entity its whole rest pose again).
     * <p>
     * A client keeps the fields of a display entity it is not sent, and starts its interpolation again whenever it is
     * sent the start of one: the frames send the start, the duration and the changing fields only, and a part that
     * never changes gets no frame at all. A pose the server could not split marks every field.
     */
    static byte[] varying(List<Element> elements, List<DisplayAnimation> animations) {
        byte[] varying = new byte[elements.size()];
        for (DisplayAnimation animation : animations) {
            for (DisplayAnimation.Frame frame : animation.frames) {
                for (int i = 0; i < frame.parts().length; i++) {
                    int part = frame.parts()[i];
                    Transformation rest = elements.get(part).transformation();
                    Transformation target = frame.targets()[i];
                    if (target == null) {
                        varying[part] = WHOLE_POSE;
                        continue;
                    }
                    if (!same(rest.getTranslation(), target.getTranslation())) {
                        varying[part] |= TRANSLATION;
                    }
                    if (!same(rest.getScale(), target.getScale())) {
                        varying[part] |= SCALE;
                    }
                    if (!same(rest.getLeftRotation(), target.getLeftRotation())) {
                        varying[part] |= LEFT_ROTATION;
                    }
                    if (!same(rest.getRightRotation(), target.getRightRotation())) {
                        varying[part] |= RIGHT_ROTATION;
                    }
                }
            }
        }
        return varying;
    }

    private static boolean same(org.joml.Vector3fc a, org.joml.Vector3fc b) {
        return Math.abs(a.x() - b.x()) <= SAME && Math.abs(a.y() - b.y()) <= SAME && Math.abs(a.z() - b.z()) <= SAME;
    }

    private static boolean same(org.joml.Quaternionfc a, org.joml.Quaternionfc b) {
        return Math.abs(a.x() - b.x()) <= SAME && Math.abs(a.y() - b.y()) <= SAME && Math.abs(a.z() - b.z()) <= SAME
                && Math.abs(a.w() - b.w()) <= SAME;
    }

    /**
     * Whether only the players who meet its {@code display.visible-if} are sent the model.
     */
    boolean conditional() {
        return !this.visibleIf.isEmpty();
    }

    /**
     * How far what the model draws reaches at rest: its radius around the vertical axis, then its height above the
     * floor, in blocks.
     */
    float[] extent() {
        float radius = 0;
        float top = 0;
        org.joml.Vector3f corner = new org.joml.Vector3f();
        for (Element element : this.elements) {
            if (!element.drawn()) {
                continue;
            }
            Transformation rest = element.transformation();
            org.joml.Matrix4f matrix = new org.joml.Matrix4f().translate(rest.getTranslation()).rotate(rest.getLeftRotation())
                    .scale(rest.getScale()).rotate(rest.getRightRotation());
            float low = element.block() ? 0f : -0.5f;
            float high = element.block() ? 1f : 0.5f;
            for (int i = 0; i < 8; i++) {
                matrix.transformPosition(corner.set((i & 1) == 0 ? low : high, (i & 2) == 0 ? low : high, (i & 4) == 0 ? low : high));
                radius = Math.max(radius, (float) Math.sqrt(corner.x * corner.x + corner.z * corner.z));
                top = Math.max(top, corner.y);
            }
        }
        return new float[]{radius, top};
    }

    private float[] bounds;

    /**
     * The box that holds what the model draws at rest: its smallest corner, then its largest (x, y, z each), in
     * blocks around the entity. All zero for a model that draws nothing.
     * <p>
     * Each part counts for what it really draws (see {@link Element#body()}): a flat item stretched along its
     * thickness (a stem made of a sunflower stretched twelve times) still reaches only a pixel's width, where a whole
     * block would reach six blocks away.
     */
    float[] bounds() {
        if (this.bounds == null) {
            float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            org.joml.Vector3f corner = new org.joml.Vector3f();
            boolean any = false;
            for (Element element : this.elements) {
                if (!element.drawn()) {
                    continue;
                }
                Transformation rest = element.transformation();
                org.joml.Matrix4f matrix = new org.joml.Matrix4f().translate(rest.getTranslation()).rotate(rest.getLeftRotation())
                        .scale(rest.getScale()).rotate(rest.getRightRotation());
                float[] body = element.body();
                for (int i = 0; i < 8; i++) {
                    matrix.transformPosition(corner.set((i & 1) == 0 ? body[0] : body[3], (i & 2) == 0 ? body[1] : body[4], (i & 4) == 0 ? body[2] : body[5]));
                    box[0] = Math.min(box[0], corner.x);
                    box[1] = Math.min(box[1], corner.y);
                    box[2] = Math.min(box[2], corner.z);
                    box[3] = Math.max(box[3], corner.x);
                    box[4] = Math.max(box[4], corner.y);
                    box[5] = Math.max(box[5], corner.z);
                    any = true;
                }
            }
            this.bounds = any ? box : new float[6];
        }
        return this.bounds;
    }

    /**
     * The middle of {@link #bounds()}: where an operator's tool is shown and what the block turns around.
     */
    org.joml.Vector3d centre() {
        float[] b = bounds();
        return new org.joml.Vector3d((b[0] + b[3]) / 2.0, (b[1] + b[4]) / 2.0, (b[2] + b[5]) / 2.0);
    }

    /**
     * Steps a pose change is looked at in, on the way the clients draw it, when an animated model gets its box.
     */
    private static final int INTERPOLATION_STEPS = 8;

    /**
     * How far under its base a part may be lowered so that its box holds it: past that, it stays drawn at all times.
     */
    private static final float MAX_SINK = 32f;

    /**
     * A display entity without a culling box is drawn whenever it is in range, even behind the player. Each entity
     * of a model standing on its block gets its own box: a cylinder around the vertical axis of the model, from its
     * base to the top of what the entity draws, with some room, holding it at rest, in every pose of its animations
     * and on the way between them, as the clients draw it.
     * <p>
     * A client draws a box from the entity up only. A part that goes further under the base than the half block of
     * room clients add is lowered by its {@link Element#sink() sink}: its entity stands that much lower and its
     * translation is raised as much, so that it is drawn at the same place (see {@link PlacedDisplay}).
     * <p>
     * Texts are boxed by the size their text can take at most, parts turning on themselves to face the player
     * (billboard {@code vertical}) by their cylinder, which they do not leave; parts facing the player on every axis
     * by the sphere they turn in. An entity whose pose cannot be followed keeps being drawn at all times.
     *
     * @param composes for each animation, whether it keeps the parts of the {@code look-at} looking at the player: their
     *                 poses in it are turned too
     * @param lift     how much higher the carrier stands than the model: the parts riding it are drawn that much lower
     *                 than their entity
     */
    private static List<Element> withCullingBox(NmsBridge nms, List<Element> elements, List<DisplayAnimation> animations, LookAt lookAt,
                                                boolean[] composes, boolean[] carried, float lift) {
        List<Element> boxed = new ArrayList<>(elements.size());
        for (int part = 0; part < elements.size(); part++) {
            Element element = elements.get(part);
            if (!element.drawn() || element.shape() == null) {
                boxed.add(element);
                continue;
            }
            // radius around the vertical axis, height of the top, height of the bottom
            float[] reach = {0, -Float.MAX_VALUE, Float.MAX_VALUE};
            boolean followed = true;
            reach(element, element.transformation(), reach);
            if (lookAt != null && lookAt.turns(part)) {
                // the turns towards the players: as far as they go each way, and on the way
                List<Transformation> bases = new ArrayList<>();
                bases.add(element.transformation());
                // turned from each pose the animations that keep it looking give it too
                for (int a = 0; composes != null && a < animations.size(); a++) {
                    if (!composes[a]) {
                        continue;
                    }
                    for (DisplayAnimation.Frame frame : animations.get(a).frames) {
                        int i = java.util.Arrays.binarySearch(frame.parts(), part);
                        if (i >= 0 && frame.targets()[i] != null) {
                            bases.add(frame.targets()[i]);
                        }
                    }
                }
                for (Transformation base : bases) {
                    for (int yaw = -2; yaw <= 2; yaw++) {
                        for (int pitch = -2; pitch <= 2; pitch++) {
                            reach(element, lookAt.turned(base, lookAt.turn(lookAt.maxYaw * yaw / 2f, lookAt.maxPitch * pitch / 2f)), reach);
                        }
                    }
                }
            }
            for (DisplayAnimation animation : animations) {
                // the poses the part goes through, in their order: rest, those of its frames, rest again
                List<Transformation> path = new ArrayList<>();
                path.add(element.transformation());
                for (DisplayAnimation.Frame frame : animation.frames) {
                    for (int i = 0; i < frame.parts().length; i++) {
                        if (frame.parts()[i] == part) {
                            if (frame.targets()[i] == null) {
                                followed = false;
                            }
                            path.add(frame.targets()[i]);
                        }
                    }
                }
                if (!followed) {
                    break;
                }
                if (path.size() == 1) {
                    continue;
                }
                path.add(element.transformation());
                if (animation.trigger == DisplayAnimation.Trigger.LOOP) {
                    // a new round starts from where the last one left the part
                    path.add(path.get(path.size() - 2));
                    path.add(path.get(1));
                }
                for (int i = 1; i < path.size(); i++) {
                    for (int step = 1; step <= INTERPOLATION_STEPS; step++) {
                        reach(element, between(path.get(i - 1), path.get(i), step / (float) INTERPOLATION_STEPS), reach);
                    }
                }
            }
            // a part riding the carrier stands where it stands: it cannot be lowered, it is drawn at all times; it is
            // drawn as much lower than its entity as the carrier stands higher than the model
            boolean riding = carried != null && carried[part];
            if (riding && lift > 0) {
                reach[1] -= lift;
                reach[2] -= lift;
            }
            float sink = reach[2] < -0.5f ? (float) Math.ceil((-reach[2] - 0.5f) * 16f + 1f) / 16f : 0f;
            float top = reach[1] + sink;
            if (!followed || sink > MAX_SINK || top <= 0 || !nms.canAnimate() && sink > 0 || riding && sink > 0) {
                boxed.add(element);
                continue;
            }
            // the box starts at the entity: what little goes under it is covered by the room given all around
            float width = reach[0] * 2.2f + 1.5f;
            float height = top * 1.1f + 0.75f;
            boxed.add(new Element(element.type(), element.data(), element.yaw(), element.pitch(), element.transformation(), element.block(),
                    element.drawn(), element.shape(), element.billboard(), nms.withCullingBox(element.data(), width, height), sink, element.body()));
        }
        return boxed;
    }

    /**
     * Widens a reach (radius around the vertical axis, height of the top, height of the bottom) to hold what an
     * element draws in a pose.
     */
    private static void reach(Element element, Transformation pose, float[] reach) {
        org.joml.Matrix4f matrix = new org.joml.Matrix4f().translate(pose.getTranslation()).rotate(pose.getLeftRotation())
                .scale(pose.getScale()).rotate(pose.getRightRotation());
        float[] shape = element.shape();
        org.joml.Vector3f corner = new org.joml.Vector3f();
        for (int i = 0; i < 8; i++) {
            matrix.transformPosition(corner.set((i & 1) == 0 ? shape[0] : shape[3], (i & 2) == 0 ? shape[1] : shape[4], (i & 4) == 0 ? shape[2] : shape[5]));
            if (element.billboard() == BILLBOARD_ALL) {
                // turned on every axis around the entity: anywhere on the sphere it draws at
                float distance = corner.length();
                reach[0] = Math.max(reach[0], distance);
                reach[1] = Math.max(reach[1], distance);
                reach[2] = Math.min(reach[2], -distance);
            } else {
                // fixed, or turned around the vertical axis only, which keeps it in the same cylinder
                reach[0] = Math.max(reach[0], (float) Math.sqrt(corner.x * corner.x + corner.z * corner.z));
                reach[1] = Math.max(reach[1], corner.y);
                reach[2] = Math.min(reach[2], corner.y);
            }
        }
    }

    static final byte BILLBOARD_FIXED = 0;
    static final byte BILLBOARD_VERTICAL = 1;
    static final byte BILLBOARD_ALL = 2;

    /**
     * Width of a character of text at most, in pixels of a text display (bold, and the wide signs of the fallback
     * font, included).
     */
    private static final int CHARACTER_WIDTH = 10;

    /**
     * What a display entity draws around its origin before its transformation, as a box (smallest x, y, z then
     * largest x, y, z), in blocks.
     * <p>
     * A text display draws its text flat, centred on its origin, from the origin up, at 40 pixels a block: lines of
     * 10 pixels, as wide as their text up to the line width, past which a line wraps. The size is taken at its
     * largest: every character as wide as the widest, a wrapped line counted twice.
     */
    private static float[] shape(Display display) {
        if (display instanceof BlockDisplay) {
            return new float[]{0, 0, 0, 1, 1, 1};
        }
        if (!(display instanceof org.bukkit.entity.TextDisplay text)) {
            return new float[]{-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f};
        }
        String legacy = text.getText();
        String plain = legacy == null ? "" : org.bukkit.ChatColor.stripColor(legacy);
        int lineWidth = Math.max(1, text.getLineWidth());
        int widest = 0;
        int lines = 0;
        for (String line : plain.split("\n", -1)) {
            int width = line.codePointCount(0, line.length()) * CHARACTER_WIDTH;
            if (width <= lineWidth) {
                lines++;
                widest = Math.max(widest, width);
            } else {
                lines += 2 * (int) Math.ceil(width / (double) lineWidth);
                widest = lineWidth;
            }
        }
        float halfWidth = (widest / 2f + 4) / 40f;
        float height = (lines * 10 + 4) / 40f;
        return new float[]{-halfWidth, -0.1f, -0.1f, halfWidth, height, 0.1f};
    }

    /**
     * A pose on the way from one to another, as a client draws it: translation and scale in a straight line, the
     * rotations along the shortest arc.
     */
    static Transformation between(Transformation from, Transformation to, float progress) {
        return new Transformation(
                new org.joml.Vector3f(from.getTranslation()).lerp(to.getTranslation(), progress),
                new org.joml.Quaternionf(from.getLeftRotation()).slerp(to.getLeftRotation(), progress),
                new org.joml.Vector3f(from.getScale()).lerp(to.getScale(), progress),
                new org.joml.Quaternionf(from.getRightRotation()).slerp(to.getRightRotation(), progress));
    }

    /**
     * Summons a model from the text of its commands, as its summoner does, without building that text again.
     */
    private static List<Display> summon(AbstractCustomBlock definition, List<String[]> summonTexts, Location location, Interaction sender) {
        World world = location.getWorld();
        Boolean feedback = world.getGameRuleValue(org.bukkit.GameRule.SEND_COMMAND_FEEDBACK);
        Boolean logged = world.getGameRuleValue(org.bukkit.GameRule.LOG_ADMIN_COMMANDS);
        sender.addAttachment(me.general_breddok.blockdisplaycreator.BlockDisplayCreator.getInstance(),
                me.general_breddok.blockdisplaycreator.permission.DefaultPermissions.Vanilla.Command.SUMMON, true);
        world.setGameRule(org.bukkit.GameRule.SEND_COMMAND_FEEDBACK, false);
        world.setGameRule(org.bukkit.GameRule.LOG_ADMIN_COMMANDS, false);
        try {
            String at = " " + location.getX() + " " + location.getY() + " " + location.getZ() + " ";
            for (String[] text : summonTexts) {
                org.bukkit.Bukkit.dispatchCommand(sender, text[0] + at + text[1]);
            }
        } finally {
            world.setGameRule(org.bukkit.GameRule.SEND_COMMAND_FEEDBACK, feedback == null || feedback);
            world.setGameRule(org.bukkit.GameRule.LOG_ADMIN_COMMANDS, logged == null || logged);
        }
        DirectedVector translation = definition.getDisplaySummoner() instanceof TranslationVectorAdjustable adjustable ? adjustable.getTranslation() : null;
        List<Display> displays = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(location, 0.01, 0.01, 0.01, entity -> entity instanceof Display)) {
            Display display = (Display) entity;
            if (translation != null) {
                display.setTransformation(new me.general_breddok.blockdisplaycreator.world.TransformationBuilder(display).addTranslation(translation.clone()).build());
            }
            displays.add(display);
        }
        return displays;
    }

    /**
     * Items whose model is not a flat sprite although they are neither a block nor a solid one.
     */
    private static final java.util.Set<org.bukkit.Material> SOLID_ITEMS = java.util.EnumSet.of(
            org.bukkit.Material.SHIELD, org.bukkit.Material.TRIDENT, org.bukkit.Material.SPYGLASS, org.bukkit.Material.CONDUIT);

    private static final float PIXEL = 1f / 16f;

    /**
     * What a display entity really draws before its transformation (see {@link Element#body()}): a block display its
     * block; an item display a head half a block wide hanging from its origin, a flat item (an item that is no block,
     * like a stick, or a block one walks through, like a flower, a torch, a rail) a sprite one pixel thick, another
     * item a whole block (also one with a model from a resource pack, given by a custom model data); a text what
     * {@link #shape(Display)} gives it.
     */
    private static float[] body(Display display) {
        if (!(display instanceof ItemDisplay item)) {
            return shape(display);
        }
        org.bukkit.inventory.ItemStack stack = item.getItemStack();
        if (stack == null || stack.getType().isAir()) {
            return shape(display);
        }
        org.bukkit.Material material = stack.getType();
        String name = material.name();
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasCustomModelData()) {
            return shape(display);
        }
        if (name.endsWith("_HEAD") || name.endsWith("_SKULL")) {
            return new float[]{-0.25f, -0.5f, -0.25f, 0.25f, 0f, 0.25f};
        }
        if (material.isBlock() && material.isSolid() || SOLID_ITEMS.contains(material)) {
            return shape(display);
        }
        return new float[]{-0.5f, -0.5f, -PIXEL / 2, 0.5f, 0.5f, PIXEL / 2};
    }

    private static boolean isDrawn(Display display) {
        if (display instanceof BlockDisplay block) {
            return !block.getBlock().getMaterial().isAir();
        }
        if (display instanceof ItemDisplay item) {
            return item.getItemStack() != null && !item.getItemStack().getType().isAir();
        }
        return true;
    }

    private static void collect(NmsBridge nms, Display display, List<Element> elements) {
        Object handle = nms.handle(display);
        Location location = display.getLocation();
        byte billboard = switch (display.getBillboard()) {
            case FIXED -> BILLBOARD_FIXED;
            case VERTICAL -> BILLBOARD_VERTICAL;
            default -> BILLBOARD_ALL;
        };
        elements.add(new Element(nms.type(handle), nms.syncedValues(handle), location.getYaw(), location.getPitch(), display.getTransformation(),
                display instanceof BlockDisplay, isDrawn(display), shape(display), billboard, null, 0f, body(display)));
        for (Entity passenger : display.getPassengers()) {
            if (passenger instanceof Display child) {
                collect(nms, child, elements);
            }
        }
    }
}
