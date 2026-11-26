package me.general_breddok.blockdisplaycreator.render;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * An animation of a custom block shown through packets, written in the {@code animations} section of its file.
 * <pre>
 * animations:
 *   lid:
 *     trigger: click          # loop, click, place, command (/bdc animation play, for some players or all) or approach
 *     radius: 4               # approach only: blocks from the block within which a player sets it off, for him alone
 *     cooldown: 30s           # approach only: time before it plays again for the same player at the same block
 *     interaction: main       # click only: the interaction that starts it, any when absent
 *     duration: 40            # ticks; a loop starts over after them. By def the end of the last frame
 *     frames:
 *       - at: 0               # tick the frame starts at
 *         over: 10            # ticks the parts take to get to it
 *         parts: [3, 4, "6-9"]  # display entities moved, in the order of the summon command (0 = its root). By def all
 *         move: [0, 0.5, 0]   # blocks, added to their resting place
 *         rotate: [0, 90, 0]  # degrees around X, Y and Z
 *         scale: 1.2          # or [x, y, z]
 *         pivot: [0, 1, 0]    # point the rotation and the scale are made around. By def the middle of the parts
 *         sound: minecraft:block.chest.open
 *       - at: 0
 *         over: 30
 *         root: [0, 0.06, 0]  # moves the whole model, one packet for all its parts (see root-motion in the file)
 *     optimise: false         # keeps every frame; by def the frames a client does not need are left out
 *     sound-range: 3          # blocks from which its sounds are heard; by def those of the game
 *     distance: 16            # loop only: plays for the players within this many blocks of the block, the others
 *                             # see its parts at rest. By def for all who see the block
 *     sounds:                 # sounds of their own, for a tune: a sound of the game, its tick, its pitch and volume
 *       - at: 12
 *         sound: minecraft:block.note_block.harp
 *         pitch: 1.2
 *       - at: 30
 *         over: 10            # no move, rotate nor scale: back to rest
 * </pre>
 * A frame may also give one part an absolute {@code transformation} of 16 numbers, as bdengine exports them.
 * <p>
 * Every pose is worked out when the file is read: playing an animation only sends them, and the clients do the
 * motion in between.
 */
final class DisplayAnimation {

    enum Trigger {
        LOOP, CLICK, PLACE,
        /**
         * Only played by {@code /bdc animation play}: for chosen players, the others keep seeing the block at rest.
         */
        COMMAND,
        /**
         * Played for a player alone when he comes within {@link #radius} of the block, at most once per
         * {@link #cooldown} for each player and block.
         */
        APPROACH
    }

    /**
     * Blocks from the block within which a player sets off an {@code approach} animation, by default.
     */
    static final double APPROACH_RADIUS = 4;

    final String name;
    final Trigger trigger;
    /**
     * The interaction a click has to be made on, or null for any.
     */
    final String interaction;
    final int duration;
    final Frame[] frames;
    /**
     * For each tick of the animation, the indexes of the frames that start at it, or null.
     */
    final int[][] startingAt;
    /**
     * Sounds played at the block along the animation, apart from those of its frames.
     */
    final Note[] notes;
    /**
     * For each tick of the animation, the indexes of the notes played at it, or null.
     */
    final int[][] notesAt;
    /**
     * The display entities some frame of the animation moves, in increasing order.
     */
    final int[] touched;
    /**
     * Whether the plugin may leave out the frames a client does not need ({@code optimise: false} in the file keeps
     * them all).
     */
    final boolean optimisable;
    /**
     * How far, in blocks, players hear its sounds, 0 for the distance of the game.
     */
    final double soundRange;
    /**
     * An {@code approach} animation: how close to the block, in blocks, a player comes to set it off.
     */
    final double radius;
    /**
     * An {@code approach} animation: milliseconds before it plays again for the same player at the same block.
     */
    final long cooldown;
    /**
     * A loop: how close to the block, in blocks, a player has to be for it to play for him ({@code distance}), 0 for
     * every player who sees the block. The others see its parts at rest and are sent none of its frames.
     */
    final double distance;
    /**
     * Whether a frame of it moves the whole model ({@code root}).
     */
    final boolean rooted;
    /**
     * For each tick of the animation, the index of the last frame moving the whole model that starts at it or before,
     * -1 for none. Null when it has none.
     */
    final int[] rootAt;
    /**
     * For each frame, the index of the last frame moving the whole model up to it (itself included), -1 for none.
     * Null when it has none.
     */
    final int[] rootBefore;

    /**
     * The most ticks a client takes to glide to a teleport: a frame moving the whole model over longer is cut into
     * pieces of this length at most, along the same line.
     */
    static final int MAX_ROOT_OVER = 59;

    /**
     * @param at      the tick it starts at
     * @param over    the ticks its parts take to get to their poses
     * @param parts   the display entities it moves, as indexes in the model
     * @param moving  for each part, the synced values that take it to its pose over the frame's time
     * @param jumping for each part, the synced values that put it in its pose at once
     * @param targets for each part, its pose, as the client is sent it (null when the server cannot split a matrix)
     * @param root    where it takes the whole model, in blocks from where the block stands, in the model's axes at its
     *                scale; null for a frame that leaves it where it is. A frame moving the whole model moves no part
     */
    record Frame(int at, int over, int[] parts, List<?>[] moving, List<?>[] jumping, Transformation[] targets, String sound, float volume, float pitch,
                 Vector3f root) {
    }

    /**
     * Where a frame puts a part: what is sent to take it there over time or at once, and the pose itself.
     */
    private record Pose(List<?> moving, List<?> jumping, Transformation target) {
    }

    /**
     * A sound of the game played at a tick of the animation.
     */
    record Note(int at, String sound, float volume, float pitch) {
    }

    private DisplayAnimation(String name, Trigger trigger, String interaction, int duration, Frame[] frames, Note[] notes, boolean optimisable,
                             double soundRange, double radius, long cooldown, double distance) {
        this.distance = distance;
        this.name = name;
        this.optimisable = optimisable;
        this.soundRange = soundRange;
        this.radius = radius;
        this.cooldown = cooldown;
        this.trigger = trigger;
        this.interaction = interaction;
        this.duration = duration;
        this.frames = frames;
        this.startingAt = new int[duration + 1][];
        this.notes = notes;
        this.notesAt = new int[duration + 1][];
        for (int i = 0; i < notes.length; i++) {
            int[] playing = this.notesAt[notes[i].at()];
            int[] extended = playing == null ? new int[1] : java.util.Arrays.copyOf(playing, playing.length + 1);
            extended[extended.length - 1] = i;
            this.notesAt[notes[i].at()] = extended;
        }
        TreeSet<Integer> touched = new TreeSet<>();
        boolean rooted = false;
        for (int i = 0; i < frames.length; i++) {
            int[] starting = this.startingAt[frames[i].at()];
            int[] extended = starting == null ? new int[1] : java.util.Arrays.copyOf(starting, starting.length + 1);
            extended[extended.length - 1] = i;
            this.startingAt[frames[i].at()] = extended;
            for (int part : frames[i].parts()) {
                touched.add(part);
            }
            rooted |= frames[i].root() != null;
        }
        this.touched = touched.stream().mapToInt(Integer::intValue).toArray();
        this.rooted = rooted;
        if (rooted) {
            this.rootBefore = new int[frames.length];
            int last = -1;
            for (int i = 0; i < frames.length; i++) {
                if (frames[i].root() != null) {
                    last = i;
                }
                this.rootBefore[i] = last;
            }
            this.rootAt = new int[duration + 1];
            int frame = -1;
            for (int tick = 0; tick <= duration; tick++) {
                while (frame + 1 < frames.length && frames[frame + 1].at() <= tick) {
                    frame++;
                }
                this.rootAt[tick] = frame < 0 ? -1 : this.rootBefore[frame];
            }
        } else {
            this.rootBefore = null;
            this.rootAt = null;
        }
    }

    /**
     * Where the frames of the animation played up to a tick of it have taken the whole model, or null when none of
     * them moved it.
     *
     * @param tick   for a loop, its tick since the server started (a round that has turned before ends where the
     *               last one left it); for another, -1 to go by {@code lastFrame}
     * @param lastFrame for another animation, the last frame played, -1 for none
     */
    Vector3f rootAfter(int tick, int lastFrame) {
        if (!this.rooted) {
            return null;
        }
        int frame;
        if (this.trigger == Trigger.LOOP && tick >= 0) {
            frame = this.rootAt[tick % this.duration];
            if (frame < 0 && tick >= this.duration) {
                frame = this.rootBefore[this.frames.length - 1];
            }
        } else {
            frame = lastFrame < 0 ? -1 : this.rootBefore[Math.min(lastFrame, this.frames.length - 1)];
        }
        return frame < 0 ? null : this.frames[frame].root();
    }

    /**
     * Reads the animations of a custom block.
     *
     * @param section  its {@code animations} section, or null
     * @param elements the display entities of its model
     * @param problems receives what could not be read, in words for the server owner
     */
    static List<DisplayAnimation> read(ConfigurationSection section, List<DisplayModel.Element> elements, NmsBridge nms, List<String> problems) {
        List<DisplayAnimation> animations = new ArrayList<>();
        if (section == null) {
            return animations;
        }
        for (String name : section.getKeys(false)) {
            ConfigurationSection animation = section.getConfigurationSection(name);
            if (animation == null) {
                continue;
            }
            try {
                animations.add(read(name, animation, elements, nms));
            } catch (RuntimeException e) {
                // whatever is wrong with an animation, the block is still shown, without it
                problems.add("animation " + name + ": " + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString()));
            }
        }
        return animations;
    }

    private static DisplayAnimation read(String name, ConfigurationSection section, List<DisplayModel.Element> elements, NmsBridge nms) {
        Trigger trigger;
        try {
            trigger = Trigger.valueOf(section.getString("trigger", "loop").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("trigger must be loop, click, place, command or approach");
        }
        double radius = 0;
        long cooldown = 0;
        if (trigger == Trigger.APPROACH) {
            radius = number(section.get("radius"), APPROACH_RADIUS).doubleValue();
            if (!(radius > 0) || Double.isInfinite(radius)) {
                throw new IllegalArgumentException("radius must be a number of blocks above 0, not " + section.get("radius"));
            }
            cooldown = cooldown(section.get("cooldown"));
        }
        List<Map<?, ?>> entries = section.getMapList("frames");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("it has no frames");
        }
        // moves, pivots and whole matrices are written for the model at size 1: they follow its scale
        float scale = me.general_breddok.blockdisplaycreator.file.config.loader.CustomBlockYamlFile.modelScale(
                section.getRoot() == null ? null : section.getRoot().get("scale"));
        List<Frame> frames = new ArrayList<>();
        int end = 1;
        for (Map<?, ?> entry : entries) {
            int at = Math.max(0, number(entry.get("at"), 0).intValue());
            int over = Math.max(0, number(entry.get("over"), 0).intValue());
            end = Math.max(end, at + over);
            Object sound = entry.get("sound");
            String soundName = sound == null ? null : sound.toString();
            float volume = number(entry.get("volume"), 1f).floatValue();
            float pitch = number(entry.get("pitch"), 1f).floatValue();

            // a frame with a root moves the whole model, and its parts only when it names them or how they move
            boolean movesParts = entry.get("root") == null || entry.containsKey("parts") || entry.containsKey("move") || entry.containsKey("rotate")
                    || entry.containsKey("scale") || entry.containsKey("transformation");
            if (entry.get("root") != null) {
                Vector3f root = vector(entry.get("root"), "root").mul(scale);
                frames.add(new Frame(at, over, new int[0], new List<?>[0], new List<?>[0], new Transformation[0],
                        movesParts ? null : soundName, volume, pitch, root));
            }
            if (!movesParts) {
                continue;
            }
            int[] parts = parts(entry.get("parts"), elements.size());
            List<?>[] moving = new List<?>[parts.length];
            List<?>[] jumping = new List<?>[parts.length];
            Transformation[] targets = new Transformation[parts.length];
            Vector3f pivot = entry.get("pivot") == null ? centre(parts, elements) : vector(entry.get("pivot"), "pivot").mul(scale);
            for (int i = 0; i < parts.length; i++) {
                Pose pose = pose(entry, pivot, elements.get(parts[i]), parts.length, over, scale, nms);
                moving[i] = pose.moving();
                jumping[i] = pose.jumping();
                targets[i] = pose.target();
            }
            frames.add(new Frame(at, over, parts, moving, jumping, targets, soundName, volume, pitch, null));
        }
        frames.sort(Comparator.comparingInt(Frame::at));
        boolean loop = trigger == Trigger.LOOP;
        frames = withShortRootFrames(frames, loop);
        List<Note> notes = new ArrayList<>();
        for (Map<?, ?> entry : section.getMapList("sounds")) {
            if (entry.get("sound") == null) {
                throw new IllegalArgumentException("a sound has no sound key");
            }
            int at = Math.max(0, number(entry.get("at"), 0).intValue());
            notes.add(new Note(at, entry.get("sound").toString(), number(entry.get("volume"), 1f).floatValue(), number(entry.get("pitch"), 1f).floatValue()));
            end = Math.max(end, at + 1);
        }
        int duration = Math.max(section.getInt("duration", end), frames.get(frames.size() - 1).at() + 1);
        for (Note note : notes) {
            duration = Math.max(duration, note.at() + 1);
        }
        return new DisplayAnimation(name, trigger, section.getString("interaction"), duration, frames.toArray(Frame[]::new), notes.toArray(Note[]::new),
                section.getBoolean("optimise", true), soundRange(section.get("sound-range")), radius, cooldown, loopDistance(trigger, section.get("distance")));
    }

    /**
     * The frames, those that move the whole model over more than {@link #MAX_ROOT_OVER} ticks cut into pieces along the
     * same line: a client glides a teleport over 59 ticks at most. Each piece starts where the one before arrives, from
     * where the frame before left the model (in a loop, the last one of the round before; else where the block stands).
     */
    private static List<Frame> withShortRootFrames(List<Frame> frames, boolean loop) {
        boolean any = false;
        for (Frame frame : frames) {
            any |= frame.root() != null && frame.over() > MAX_ROOT_OVER;
        }
        if (!any) {
            return frames;
        }
        Vector3f from = new Vector3f();
        if (loop) {
            for (Frame frame : frames) {
                if (frame.root() != null) {
                    from = frame.root();
                }
            }
        }
        List<Frame> out = new ArrayList<>(frames.size() + 4);
        for (Frame frame : frames) {
            if (frame.root() == null) {
                out.add(frame);
                continue;
            }
            int pieces = (frame.over() + MAX_ROOT_OVER - 1) / MAX_ROOT_OVER;
            for (int piece = 1; piece < pieces; piece++) {
                int start = frame.over() * (piece - 1) / pieces;
                int stop = frame.over() * piece / pieces;
                Vector3f at = new Vector3f(from).lerp(frame.root(), stop / (float) frame.over());
                out.add(new Frame(frame.at() + start, stop - start, new int[0], new List<?>[0], new List<?>[0], new Transformation[0],
                        piece == 1 ? frame.sound() : null, frame.volume(), frame.pitch(), at));
            }
            int start = pieces > 1 ? frame.over() * (pieces - 1) / pieces : 0;
            out.add(new Frame(frame.at() + start, frame.over() - start, new int[0], new List<?>[0], new List<?>[0], new Transformation[0],
                    pieces > 1 ? null : frame.sound(), frame.volume(), frame.pitch(), frame.root()));
            from = frame.root();
        }
        out.sort(Comparator.comparingInt(Frame::at));
        return out;
    }

    /**
     * The {@code cooldown} of an {@code approach} animation in milliseconds, written as for a click box: 30s, 2m,
     * 1h30m, 500ms, or a plain number of milliseconds. 0 when absent.
     */
    private static long cooldown(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            if (number.longValue() < 0) {
                throw new IllegalArgumentException("cooldown must not be below 0, not " + value);
            }
            return number.longValue();
        }
        long millis = me.general_breddok.blockdisplaycreator.file.config.loader.CustomBlockYamlFile.parseDuration(value.toString());
        if (millis < 0) {
            throw new IllegalArgumentException("cooldown '" + value + "' is not a duration (examples: 30s, 2m, 1h30m, 500ms)");
        }
        return millis;
    }

    /**
     * The {@code distance} of a loop, in blocks: 0 when absent, and for an animation that is no loop.
     */
    private static double loopDistance(Trigger trigger, Object value) {
        if (value == null || trigger != Trigger.LOOP) {
            return 0;
        }
        double distance = number(value, 0).doubleValue();
        if (!(distance > 0) || Double.isInfinite(distance)) {
            throw new IllegalArgumentException("distance must be a number of blocks above 0, not " + value);
        }
        return distance;
    }

    private static double soundRange(Object value) {
        double range = number(value, 0).doubleValue();
        if (range < 0) {
            throw new IllegalArgumentException("sound-range must be a number of blocks, not " + value);
        }
        return range;
    }

    /**
     * The same animation with only some of its frames, at new times (see {@link AnimationOptimizer}): the parts keep
     * their poses, a frame that now takes another time is sent that time.
     */
    DisplayAnimation with(List<AnimationOptimizer.Kept> kept, NmsBridge nms) {
        Frame[] out = new Frame[kept.size()];
        for (int i = 0; i < out.length; i++) {
            AnimationOptimizer.Kept k = kept.get(i);
            Frame frame = this.frames[k.frame()];
            int[] parts = k.parts() == null ? frame.parts() : k.parts();
            List<?>[] moving = new List<?>[parts.length];
            List<?>[] jumping = new List<?>[parts.length];
            Transformation[] targets = new Transformation[parts.length];
            for (int p = 0; p < parts.length; p++) {
                int j = java.util.Arrays.binarySearch(frame.parts(), parts[p]);
                Transformation target = frame.targets()[j];
                moving[p] = k.over() == frame.over() ? frame.moving()[j]
                        : nms.poseValues(target.getTranslation(), target.getLeftRotation(), target.getScale(), target.getRightRotation(), k.over());
                jumping[p] = frame.jumping()[j];
                targets[p] = target;
            }
            out[i] = new Frame(k.at(), k.over(), parts, moving, jumping, targets, frame.sound(), frame.volume(), frame.pitch(), frame.root());
        }
        java.util.Arrays.sort(out, Comparator.comparingInt(Frame::at));
        return new DisplayAnimation(this.name, this.trigger, this.interaction, this.duration, out, this.notes, this.optimisable, this.soundRange,
                this.radius, this.cooldown, this.distance);
    }

    /**
     * The same animation without the frames that move the whole model, on a server that cannot show them.
     */
    DisplayAnimation withoutRoot() {
        if (!this.rooted) {
            return this;
        }
        Frame[] kept = java.util.Arrays.stream(this.frames).filter(frame -> frame.root() == null).toArray(Frame[]::new);
        if (kept.length == 0) {
            // a frame that moves nothing holds the place of the animation
            kept = new Frame[]{new Frame(0, 0, new int[0], new List<?>[0], new List<?>[0], new Transformation[0], null, 1f, 1f, null)};
        }
        return new DisplayAnimation(this.name, this.trigger, this.interaction, this.duration, kept, this.notes, this.optimisable, this.soundRange,
                this.radius, this.cooldown, this.distance);
    }

    /**
     * Whether a frame of the animation moves one of these parts (in increasing order).
     */
    boolean moves(int[] parts) {
        int i = 0;
        int j = 0;
        while (i < this.touched.length && j < parts.length) {
            if (this.touched[i] == parts[j]) {
                return true;
            }
            if (this.touched[i] < parts[j]) {
                i++;
            } else {
                j++;
            }
        }
        return false;
    }

    /**
     * Where a frame puts a display entity: its resting transformation, moved, turned and scaled as the frame says.
     * <p>
     * A frame that keeps the proportions of the entity (a move, a rotation, one scale for the three axes) gives its
     * translation, rotations and scale directly. Stretching it along an axis, or giving a whole matrix, has no such
     * answer: the server splits the matrix, as it does for the matrix of a summon command.
     *
     * @return the synced values that take the entity there over the frame's time, those that jump there, and the pose
     */
    private static Pose pose(Map<?, ?> frame, Vector3f pivot, DisplayModel.Element element, int partCount, int over, float modelScale, NmsBridge nms) {
        Transformation rest = element.transformation();
        Object absolute = frame.get("transformation");
        Object scale = frame.get("scale");
        Object rotate = frame.get("rotate");
        Vector3f move = frame.get("move") == null ? new Vector3f() : vector(frame.get("move"), "move").mul(modelScale);
        Quaternionf turn = new Quaternionf();
        if (rotate != null) {
            Vector3f degrees = vector(rotate, "rotate");
            turn.rotateXYZ((float) Math.toRadians(degrees.x), (float) Math.toRadians(degrees.y), (float) Math.toRadians(degrees.z));
        }

        if (absolute == null && (scale == null || scale instanceof Number)) {
            float factor = scale == null ? 1f : ((Number) scale).floatValue();
            Vector3f translation = new Vector3f(rest.getTranslation());
            if (rotate != null || scale != null) {
                translation.sub(pivot).mul(factor).rotate(turn).add(pivot);
            }
            translation.add(move);
            Quaternionf leftRotation = new Quaternionf(turn).mul(rest.getLeftRotation());
            Vector3f scaled = new Vector3f(rest.getScale()).mul(factor);
            Quaternionf rightRotation = new Quaternionf(rest.getRightRotation());
            return new Pose(nms.poseValues(translation, leftRotation, scaled, rightRotation, over),
                    nms.poseValues(translation, leftRotation, scaled, rightRotation, 0),
                    new Transformation(new Vector3f(translation), new Quaternionf(leftRotation), new Vector3f(scaled), new Quaternionf(rightRotation)));
        }

        Matrix4f pose = new Matrix4f();
        if (absolute != null) {
            if (partCount != 1) {
                throw new IllegalArgumentException("a frame with a transformation moves one part");
            }
            if (!(absolute instanceof List<?> numbers) || numbers.size() != 16) {
                throw new IllegalArgumentException("a transformation is 16 numbers");
            }
            // written row by row, as in a summon command
            for (int row = 0; row < 4; row++) {
                for (int column = 0; column < 4; column++) {
                    pose.set(column, row, number(numbers.get(row * 4 + column), 0).floatValue());
                }
            }
            // the matrix of a part in the model at size 1, as in the summon command
            pose.scaleLocal(modelScale);
        } else {
            pose.translate(move).translate(pivot).rotate(turn).scale(vector(scale, "scale"))
                    .translate(-pivot.x, -pivot.y, -pivot.z).mul(resting(rest));
        }
        return new Pose(nms.poseValues(pose, over), nms.poseValues(pose, 0), nms.split(pose));
    }

    private static Matrix4f resting(Transformation transformation) {
        return new Matrix4f()
                .translate(transformation.getTranslation())
                .rotate(transformation.getLeftRotation())
                .scale(transformation.getScale())
                .rotate(transformation.getRightRotation());
    }

    /**
     * The middle of some display entities at rest: of the box around their centres, which are the middle of the
     * unit cube for a block display and the origin for the others. Entities that show nothing only count when
     * there is no other.
     */
    private static Vector3f centre(int[] parts, List<DisplayModel.Element> elements) {
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (boolean drawnOnly : new boolean[]{true, false}) {
            for (int part : parts) {
                DisplayModel.Element element = elements.get(part);
                if (drawnOnly && !element.drawn()) {
                    continue;
                }
                Vector3f middle = element.block() ? new Vector3f(0.5f, 0.5f, 0.5f) : new Vector3f();
                resting(element.transformation()).transformPosition(middle);
                min.min(middle);
                max.max(middle);
            }
            if (min.x <= max.x) {
                break;
            }
        }
        return min.add(max).mul(0.5f);
    }

    /**
     * The parts a {@code parts} key names, in increasing order: numbers and ranges like 3-7, all of them when absent.
     */
    static int[] parts(Object value, int count) {
        TreeSet<Integer> parts = new TreeSet<>();
        if (value == null) {
            for (int i = 0; i < count; i++) {
                parts.add(i);
            }
        } else {
            for (Object entry : value instanceof List<?> list ? list : List.of(value)) {
                String text = entry.toString().trim();
                int dash = text.indexOf('-', 1);
                try {
                    int first = Integer.parseInt(dash < 0 ? text : text.substring(0, dash).trim());
                    int last = dash < 0 ? first : Integer.parseInt(text.substring(dash + 1).trim());
                    for (int i = first; i <= last; i++) {
                        parts.add(i);
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("parts are numbers or ranges like 3-7, not '" + text + "'");
                }
            }
        }
        if (parts.isEmpty() || parts.first() < 0 || parts.last() >= count) {
            throw new IllegalArgumentException("parts go from 0 to " + (count - 1));
        }
        return parts.stream().mapToInt(Integer::intValue).toArray();
    }

    static Vector3f vector(Object value, String name) {
        if (value instanceof List<?> list && list.size() == 3) {
            return new Vector3f(number(list.get(0), 0).floatValue(), number(list.get(1), 0).floatValue(), number(list.get(2), 0).floatValue());
        }
        throw new IllegalArgumentException(name + " is three numbers: [x, y, z]");
    }

    static Number number(Object value, Number byDefault) {
        if (value == null) {
            return byDefault;
        }
        if (value instanceof Number number) {
            return number;
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + value + "' is not a number");
        }
    }
}
