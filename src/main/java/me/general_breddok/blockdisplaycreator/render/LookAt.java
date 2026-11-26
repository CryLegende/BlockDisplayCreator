package me.general_breddok.blockdisplaycreator.render;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Parts of a model that turn towards each player standing close by, written in the {@code look-at} section of its
 * file: the head of a character, its hat. Every player sees them turned towards himself, by packets of his own; the
 * others see them as they are.
 * <pre>
 * look-at:
 *   parts: "3-5"         # display entities that turn together, as in a frame
 *   pivot: [0, 1.4, 0]   # point they turn around (the neck), in blocks from the block base, model at size 1.
 *                        # By def the middle of the bottom of the parts
 *   radius: 6            # blocks: the players whose eyes are closer than this to the pivot are looked at
 *   yaw: 70              # degrees the parts turn at most to the left or to the right
 *   pitch: 30            # degrees they turn at most up or down
 *   over: 4              # ticks a turn takes; also the fewest ticks between two turns for one player
 *   step: 1.5            # degrees the direction of a player has to change by before the parts turn again
 *   facing: 0            # which way the parts look at rest, in degrees around the vertical axis of the model from
 *                        # its front, the side that faces whoever placed the block. 180 for parts made looking back
 *   loop: true           # the loops keep moving the parts while they look at a player, turned towards him. By def
 *                        # false: he sees them at rest, turned to him
 * </pre>
 * At rest, before any turn, the parts are where the model puts them: a player looked at sees them at rest, turned
 * towards him, while the loops of the block keep moving its other parts. The poses the turns go through are worked
 * out from the rest pose of each part, as a frame turning them around the pivot would be. With {@code loop: true}
 * they are worked out from the pose the loops give the parts instead, and each frame of a loop that moves them goes to
 * him turned the same way.
 * <p>
 * An animation with {@code look-at: true} (any trigger, played for everyone or for him alone) does the same: each of
 * its frames moving the parts reaches him turned towards him (a blink, a breath, a look that drifts), and the turns
 * start from where it has them. So does by default one that only shows, hides or sizes them. Any other animation
 * moving them goes first: they leave him for its time and look at him again once it ends.
 */
final class LookAt {

    static final double RADIUS = 6;
    static final float YAW = 70;
    static final float PITCH = 30;
    static final int OVER = 4;
    static final float STEP = 1.5f;

    /**
     * The parts that turn, in increasing order.
     */
    final int[] parts;
    /**
     * The point they turn around, in the model (at its {@code scale}).
     */
    final Vector3f pivot;
    final double radius;
    /**
     * Most degrees the parts turn, left or right, then up or down.
     */
    final float maxYaw;
    final float maxPitch;
    final int over;
    final float step;
    /**
     * Which way the parts look at rest: degrees around the vertical axis of the model from its front (+Z).
     */
    final float facing;
    /**
     * Whether the loops keep moving the parts for a player they look at ({@code loop: true}): each frame of a loop is
     * sent to him turned towards him, and a turn starts from where the loops have the parts. Else he sees them at rest,
     * turned to him.
     */
    final boolean keepsLoop;

    private LookAt(int[] parts, Vector3f pivot, double radius, float maxYaw, float maxPitch, int over, float step, float facing, boolean keepsLoop) {
        this.keepsLoop = keepsLoop;
        this.parts = parts;
        this.pivot = pivot;
        this.radius = radius;
        this.maxYaw = maxYaw;
        this.maxPitch = maxPitch;
        this.over = over;
        this.step = step;
        this.facing = facing;
    }

    /**
     * Reads the {@code look-at} section of the file of a block.
     *
     * @param elements the display entities of its model
     * @throws IllegalArgumentException with what is wrong in it, in words for the server owner
     */
    static LookAt read(ConfigurationSection section, List<DisplayModel.Element> elements) {
        if (!section.contains("parts")) {
            throw new IllegalArgumentException("parts is missing: the numbers of the parts that turn, as in a frame");
        }
        int[] parts = DisplayAnimation.parts(section.get("parts"), elements.size());
        // a pivot is written for the model at size 1, as those of the frames: it follows its scale
        float scale = me.general_breddok.blockdisplaycreator.file.config.loader.CustomBlockYamlFile.modelScale(
                section.getRoot() == null ? null : section.getRoot().get("scale"));
        Vector3f pivot = section.get("pivot") == null ? neck(parts, elements) : DisplayAnimation.vector(section.get("pivot"), "pivot").mul(scale);
        double radius = DisplayAnimation.number(section.get("radius"), RADIUS).doubleValue();
        if (!(radius > 0) || Double.isInfinite(radius)) {
            throw new IllegalArgumentException("radius must be a number of blocks above 0, not " + section.get("radius"));
        }
        float yaw = angle(section, "yaw", YAW, 180);
        float pitch = angle(section, "pitch", PITCH, 90);
        int over = DisplayAnimation.number(section.get("over"), OVER).intValue();
        if (over < 1 || over > 100) {
            throw new IllegalArgumentException("over must be a number of ticks from 1 to 100, not " + section.get("over"));
        }
        float step = DisplayAnimation.number(section.get("step"), STEP).floatValue();
        if (!(step > 0) || step > 45) {
            throw new IllegalArgumentException("step must be a number of degrees above 0, 45 at most, not " + section.get("step"));
        }
        float facing = DisplayAnimation.number(section.get("facing"), 0).floatValue();
        if (!Float.isFinite(facing)) {
            throw new IllegalArgumentException("facing must be a number of degrees, not " + section.get("facing"));
        }
        Object loop = section.get("loop");
        if (loop != null && !(loop instanceof Boolean)) {
            throw new IllegalArgumentException("loop must be true or false, not " + loop);
        }
        return new LookAt(parts, pivot, radius, yaw, pitch, over, step, facing, Boolean.TRUE.equals(loop));
    }

    private static float angle(ConfigurationSection section, String key, float byDefault, float most) {
        float angle = DisplayAnimation.number(section.get(key), byDefault).floatValue();
        if (!(angle >= 0) || angle > most) {
            throw new IllegalArgumentException(key + " must be a number of degrees from 0 to " + (int) most + ", not " + section.get(key));
        }
        return angle;
    }

    /**
     * The middle of the bottom of what the parts draw at rest: where a head meets the neck.
     */
    private static Vector3f neck(int[] parts, List<DisplayModel.Element> elements) {
        float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        Vector3f corner = new Vector3f();
        for (boolean drawnOnly : new boolean[]{true, false}) {
            for (int part : parts) {
                DisplayModel.Element element = elements.get(part);
                if (drawnOnly && !element.drawn()) {
                    continue;
                }
                Transformation rest = element.transformation();
                Matrix4f matrix = new Matrix4f().translate(rest.getTranslation()).rotate(rest.getLeftRotation())
                        .scale(rest.getScale()).rotate(rest.getRightRotation());
                float[] shape = element.shape() != null ? element.shape() : element.block() ? new float[]{0, 0, 0, 1, 1, 1}
                        : new float[]{-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f};
                for (int i = 0; i < 8; i++) {
                    matrix.transformPosition(corner.set((i & 1) == 0 ? shape[0] : shape[3], (i & 2) == 0 ? shape[1] : shape[4], (i & 4) == 0 ? shape[2] : shape[5]));
                    box[0] = Math.min(box[0], corner.x);
                    box[1] = Math.min(box[1], corner.y);
                    box[2] = Math.min(box[2], corner.z);
                    box[3] = Math.max(box[3], corner.x);
                    box[4] = Math.max(box[4], corner.y);
                    box[5] = Math.max(box[5], corner.z);
                }
            }
            if (box[0] <= box[3]) {
                break;
            }
        }
        return new Vector3f((box[0] + box[3]) / 2, box[1], (box[2] + box[5]) / 2);
    }

    /**
     * Whether a part turns.
     */
    boolean turns(int part) {
        return java.util.Arrays.binarySearch(this.parts, part) >= 0;
    }

    /**
     * The rotation that turns the parts by a yaw (to the left of the model for a positive one, seen from above,
     * looking along its facing) and a pitch (up for a positive one), in degrees: around the vertical axis of the model,
     * then around the axis across the facing.
     */
    Quaternionf turn(float yaw, float pitch) {
        return new Quaternionf().rotationY((float) Math.toRadians(this.facing + yaw)).rotateX((float) Math.toRadians(-pitch))
                .rotateY((float) Math.toRadians(-this.facing));
    }

    /**
     * Where a turn puts a part: its rest pose turned around the pivot.
     */
    Transformation turned(Transformation rest, Quaternionf turn) {
        Vector3f translation = new Vector3f(rest.getTranslation()).sub(this.pivot).rotate(turn).add(this.pivot);
        return new Transformation(translation, new Quaternionf(turn).mul(rest.getLeftRotation()), new Vector3f(rest.getScale()),
                new Quaternionf(rest.getRightRotation()));
    }
}
