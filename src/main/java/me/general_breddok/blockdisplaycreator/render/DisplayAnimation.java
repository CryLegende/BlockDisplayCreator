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
 *     trigger: click          # loop, click or place
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
        LOOP, CLICK, PLACE
    }

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
     * @param at      the tick it starts at
     * @param parts   the display entities it moves, as indexes in the model
     * @param moving  for each part, the synced values that take it to its pose over the frame's time
     * @param jumping for each part, the synced values that put it in its pose at once
     */
    record Frame(int at, int[] parts, List<?>[] moving, List<?>[] jumping, String sound, float volume, float pitch) {
    }

    private DisplayAnimation(String name, Trigger trigger, String interaction, int duration, Frame[] frames) {
        this.name = name;
        this.trigger = trigger;
        this.interaction = interaction;
        this.duration = duration;
        this.frames = frames;
        this.startingAt = new int[duration + 1][];
        for (int i = 0; i < frames.length; i++) {
            int[] starting = this.startingAt[frames[i].at()];
            int[] extended = starting == null ? new int[1] : java.util.Arrays.copyOf(starting, starting.length + 1);
            extended[extended.length - 1] = i;
            this.startingAt[frames[i].at()] = extended;
        }
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
            throw new IllegalArgumentException("trigger must be loop, click or place");
        }
        List<Map<?, ?>> entries = section.getMapList("frames");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("it has no frames");
        }
        List<Frame> frames = new ArrayList<>();
        int end = 1;
        for (Map<?, ?> entry : entries) {
            int at = Math.max(0, number(entry.get("at"), 0).intValue());
            int over = Math.max(0, number(entry.get("over"), 0).intValue());
            int[] parts = parts(entry.get("parts"), elements.size());
            end = Math.max(end, at + over);

            List<?>[] moving = new List<?>[parts.length];
            List<?>[] jumping = new List<?>[parts.length];
            Vector3f pivot = entry.get("pivot") == null ? centre(parts, elements) : vector(entry.get("pivot"), "pivot");
            for (int i = 0; i < parts.length; i++) {
                List<?>[] values = pose(entry, pivot, elements.get(parts[i]), parts.length, over, nms);
                moving[i] = values[0];
                jumping[i] = values[1];
            }
            Object sound = entry.get("sound");
            frames.add(new Frame(at, parts, moving, jumping, sound == null ? null : sound.toString(),
                    number(entry.get("volume"), 1f).floatValue(), number(entry.get("pitch"), 1f).floatValue()));
        }
        frames.sort(Comparator.comparingInt(Frame::at));
        int duration = Math.max(section.getInt("duration", end), frames.get(frames.size() - 1).at() + 1);
        return new DisplayAnimation(name, trigger, section.getString("interaction"), duration, frames.toArray(Frame[]::new));
    }

    /**
     * Where a frame puts a display entity: its resting transformation, moved, turned and scaled as the frame says.
     * <p>
     * A frame that keeps the proportions of the entity (a move, a rotation, one scale for the three axes) gives its
     * translation, rotations and scale directly. Stretching it along an axis, or giving a whole matrix, has no such
     * answer: the server splits the matrix, as it does for the matrix of a summon command.
     *
     * @return the synced values that take the entity there over the frame's time, then those that jump there
     */
    private static List<?>[] pose(Map<?, ?> frame, Vector3f pivot, DisplayModel.Element element, int partCount, int over, NmsBridge nms) {
        Transformation rest = element.transformation();
        Object absolute = frame.get("transformation");
        Object scale = frame.get("scale");
        Object rotate = frame.get("rotate");
        Vector3f move = frame.get("move") == null ? new Vector3f() : vector(frame.get("move"), "move");
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
            return new List<?>[]{nms.poseValues(translation, leftRotation, scaled, rightRotation, over),
                    nms.poseValues(translation, leftRotation, scaled, rightRotation, 0)};
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
        } else {
            pose.translate(move).translate(pivot).rotate(turn).scale(vector(scale, "scale"))
                    .translate(-pivot.x, -pivot.y, -pivot.z).mul(resting(rest));
        }
        return new List<?>[]{nms.poseValues(pose, over), nms.poseValues(pose, 0)};
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

    private static int[] parts(Object value, int count) {
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

    private static Vector3f vector(Object value, String name) {
        if (value instanceof List<?> list && list.size() == 3) {
            return new Vector3f(number(list.get(0), 0).floatValue(), number(list.get(1), 0).floatValue(), number(list.get(2), 0).floatValue());
        }
        throw new IllegalArgumentException(name + " is three numbers: [x, y, z]");
    }

    private static Number number(Object value, Number byDefault) {
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
