package me.general_breddok.blockdisplaycreator.render;

import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes the starts and ends of hand-written frames smooth: a client goes from one pose to the next at a constant
 * speed, so a part that starts from stillness jumps to full speed, and one that stops does so at once.
 * <p>
 * For each part (and for the whole model, with {@code root} frames), a frame of at least {@link #MIN_TICKS} ticks
 * that starts from stillness (nothing moved the part before it in the animation, or it rested at least a tick, or the
 * frame before did not move it) eases in, one that ends in stillness (the animation ends, or the part then rests, or
 * the next frame does not move it) eases out, one that does both follows a smoothstep. It is replaced by three frames
 * along the same path (translation and scale in a straight line, rotations by slerp, as a client draws them), timed
 * on the curve. The others stay as written: frames in the middle of a motion (a windmill turning, a part moving
 * through consecutive frames), a frame cut short by the next or starting before the one before arrived, frames given
 * as 16 numbers (bdengine exports, already timed), and the frames of another animation than a loop on parts a loop
 * also moves (they start wherever the loop has them).
 * <p>
 * Pure computation on the poses the server worked out, once, when the file is read.
 */
final class Easing {

    /**
     * Frames shorter than this are left as they are: three steps of a tick or less would only add packets.
     */
    static final int MIN_TICKS = 6;
    /**
     * Below this, two numbers of a pose are taken to be the same.
     */
    private static final float SAME = 1.0E-5f;

    enum Kind {NONE, IN, OUT, BOTH}

    private Easing() {
    }

    /**
     * Where the curve of a kind of easing is at a fraction of the frame's time.
     */
    static double curve(Kind kind, double t) {
        return switch (kind) {
            case BOTH -> t * t * (3 - 2 * t);
            // the first half of a smoothstep, stretched over the frame: still at the start, faster at the end
            case IN -> 1.5 * t * t - 0.5 * t * t * t;
            case OUT -> 1 - curve(Kind.IN, 1 - t);
            default -> t;
        };
    }

    private static final Map<Long, int[]> KNOTS = new ConcurrentHashMap<>();

    /**
     * The ticks, within a frame of {@code ticks}, at which its three pieces start and end ({0, k1, k2, ticks}): those
     * whose straight pieces stay closest to the curve.
     */
    static int[] knots(Kind kind, int ticks) {
        return KNOTS.computeIfAbsent((long) ticks << 2 | kind.ordinal(), key -> {
            int n = Math.min(ticks, 120);
            int best1 = n / 3;
            int best2 = 2 * n / 3;
            double bestError = Double.MAX_VALUE;
            for (int k1 = 1; k1 < n - 1; k1++) {
                for (int k2 = k1 + 1; k2 < n; k2++) {
                    double error = 0;
                    int[] knot = {0, k1, k2, n};
                    for (int piece = 0; piece < 3 && error < bestError; piece++) {
                        double a = knot[piece] / (double) n;
                        double b = knot[piece + 1] / (double) n;
                        double ya = curve(kind, a);
                        double yb = curve(kind, b);
                        // the client draws every frame: every half tick is a sample
                        for (int step = 1; step < 2 * (knot[piece + 1] - knot[piece]); step++) {
                            double t = a + (b - a) * step / (2.0 * (knot[piece + 1] - knot[piece]));
                            error = Math.max(error, Math.abs(curve(kind, t) - (ya + (yb - ya) * (t - a) / (b - a))));
                        }
                    }
                    if (error < bestError) {
                        bestError = error;
                        best1 = k1;
                        best2 = k2;
                    }
                }
            }
            if (n == ticks) {
                return new int[]{0, best1, best2, ticks};
            }
            // a longer frame: the same fractions
            int k1 = Math.max(1, (int) Math.round(best1 * ticks / (double) n));
            int k2 = Math.min(ticks - 1, Math.max(k1 + 1, (int) Math.round(best2 * ticks / (double) n)));
            return new int[]{0, k1, k2, ticks};
        });
    }

    /**
     * One motion of a part (or of the whole model) in an animation: the frame, the place of the part in it, where it
     * starts from and where it goes.
     */
    private record Move(int frame, int slot, Object from, Object to) {
    }

    /**
     * The frames of an animation with the starts and ends of its motions eased, or the same list when nothing is.
     *
     * @param absolute  the frames given as 16 numbers, which are left as they are
     * @param mixed     the parts a loop and another animation of the model both move
     * @param rootMixed whether a loop and another animation both move the whole model
     */
    static List<DisplayAnimation.Frame> ease(List<DisplayAnimation.Frame> frames, boolean loop, int duration, List<DisplayModel.Element> elements,
                                             Set<DisplayAnimation.Frame> absolute, Set<Integer> mixed, boolean rootMixed, NmsBridge nms) {
        // for each frame, for each of its parts then its root, how it eases
        Kind[][] kinds = new Kind[frames.size()][];
        Object[][] starts = new Object[frames.size()][];
        for (int i = 0; i < frames.size(); i++) {
            kinds[i] = new Kind[frames.get(i).parts().length + 1];
            Arrays.fill(kinds[i], Kind.NONE);
            starts[i] = new Object[kinds[i].length];
        }
        boolean any = false;
        // the parts, then the whole model (channel -1)
        int[] channels = java.util.stream.IntStream.concat(
                frames.stream().flatMapToInt(frame -> Arrays.stream(frame.parts())).distinct().sorted(), java.util.stream.IntStream.of(-1)).toArray();
        for (int channel : channels) {
            if (channel >= 0 && !loop && mixed.contains(channel) || channel < 0 && (!loop && rootMixed)) {
                // it starts wherever a loop has it
                continue;
            }
            List<Move> moves = new ArrayList<>();
            Object rest = channel >= 0 ? elements.get(channel).transformation() : new Vector3f();
            for (int i = 0; i < frames.size(); i++) {
                DisplayAnimation.Frame frame = frames.get(i);
                if (channel < 0) {
                    if (frame.root() != null) {
                        moves.add(new Move(i, frame.parts().length, null, frame.root()));
                    }
                } else {
                    int slot = Arrays.binarySearch(frame.parts(), channel);
                    if (slot >= 0) {
                        moves.add(new Move(i, slot, null, frame.targets()[slot]));
                    }
                }
            }
            int count = moves.size();
            for (int m = 0; m < count; m++) {
                Move move = moves.get(m);
                DisplayAnimation.Frame frame = frames.get(move.frame());
                // where it starts from: the pose of the move before it, the last of the round in a loop, else its rest
                Object from = m > 0 ? moves.get(m - 1).to() : loop && count > 1 ? moves.get(count - 1).to() : rest;
                moves.set(m, new Move(move.frame(), move.slot(), from, move.to()));
            }
            for (int m = 0; m < count; m++) {
                Move move = moves.get(m);
                DisplayAnimation.Frame frame = frames.get(move.frame());
                if (frame.over() < MIN_TICKS || move.to() == null || move.from() == null || absolute.contains(frame) || same(move.from(), move.to())) {
                    continue;
                }
                // before it
                boolean still;
                if (m > 0 || loop && count > 1) {
                    Move before = moves.get(m > 0 ? m - 1 : count - 1);
                    DisplayAnimation.Frame previous = frames.get(before.frame());
                    int arrives = previous.at() + previous.over() - (m > 0 ? 0 : duration);
                    if (arrives > frame.at()) {
                        // it starts before the one before arrived: from a pose on the way
                        continue;
                    }
                    still = arrives < frame.at() || same(before.from(), before.to());
                } else {
                    still = true;
                }
                // after it
                boolean stops;
                if (m + 1 < count || loop && count > 1) {
                    Move after = moves.get(m + 1 < count ? m + 1 : 0);
                    DisplayAnimation.Frame next = frames.get(after.frame());
                    int leaves = next.at() + (m + 1 < count ? 0 : duration);
                    if (leaves < frame.at() + frame.over()) {
                        // the next one cuts it short
                        continue;
                    }
                    stops = leaves > frame.at() + frame.over() || same(after.from(), after.to());
                } else {
                    stops = true;
                }
                Kind kind = still && stops ? Kind.BOTH : still ? Kind.IN : stops ? Kind.OUT : Kind.NONE;
                if (kind != Kind.NONE) {
                    kinds[move.frame()][move.slot()] = kind;
                    starts[move.frame()][move.slot()] = move.from();
                    any = true;
                }
            }
        }
        if (!any) {
            return frames;
        }
        List<DisplayAnimation.Frame> out = new ArrayList<>(frames.size() + 8);
        for (int i = 0; i < frames.size(); i++) {
            out.addAll(split(frames.get(i), kinds[i], starts[i], nms));
        }
        out.sort(Comparator.comparingInt(DisplayAnimation.Frame::at));
        return out;
    }

    /**
     * A frame cut into the pieces of its easings: its parts that ease the same way share three frames, those that do
     * not keep it as it is. The sound goes with the first.
     */
    private static List<DisplayAnimation.Frame> split(DisplayAnimation.Frame frame, Kind[] kinds, Object[] starts, NmsBridge nms) {
        boolean eased = false;
        for (Kind kind : kinds) {
            eased |= kind != Kind.NONE;
        }
        if (!eased) {
            return List.of(frame);
        }
        List<DisplayAnimation.Frame> out = new ArrayList<>(7);
        String sound = frame.sound();
        int parts = frame.parts().length;
        for (Kind kind : Kind.values()) {
            List<Integer> slots = new ArrayList<>();
            for (int slot = 0; slot < parts; slot++) {
                if (kinds[slot] == kind) {
                    slots.add(slot);
                }
            }
            boolean root = frame.root() != null && kinds[parts] == kind;
            if (slots.isEmpty() && !root) {
                continue;
            }
            if (kind == Kind.NONE) {
                out.add(subset(frame, slots, frame.at(), frame.over(), root ? frame.root() : null, sound, null, 1, nms));
                sound = null;
                continue;
            }
            int[] knot = knots(kind, frame.over());
            for (int piece = 0; piece < 3; piece++) {
                double progress = curve(kind, knot[piece + 1] / (double) frame.over());
                Vector3f rootAt = root ? (piece == 2 ? frame.root() : new Vector3f((Vector3f) starts[parts]).lerp(frame.root(), (float) progress)) : null;
                out.add(subset(frame, slots, frame.at() + knot[piece], knot[piece + 1] - knot[piece], rootAt, sound, piece == 2 ? null : starts,
                        (float) progress, nms));
                sound = null;
            }
        }
        return out;
    }

    /**
     * Some parts of a frame, over another time, on the way to their poses (at {@code progress} from {@code starts}),
     * or at them when {@code starts} is null.
     */
    private static DisplayAnimation.Frame subset(DisplayAnimation.Frame frame, List<Integer> slots, int at, int over, Vector3f root, String sound,
                                                 Object[] starts, float progress, NmsBridge nms) {
        int[] parts = new int[slots.size()];
        List<?>[] moving = new List<?>[slots.size()];
        List<?>[] jumping = new List<?>[slots.size()];
        Transformation[] targets = new Transformation[slots.size()];
        for (int k = 0; k < parts.length; k++) {
            int slot = slots.get(k);
            parts[k] = frame.parts()[slot];
            Transformation target = frame.targets()[slot];
            if (starts != null) {
                target = between((Transformation) starts[slot], target, progress);
                moving[k] = nms.poseValues(target.getTranslation(), target.getLeftRotation(), target.getScale(), target.getRightRotation(), over);
                jumping[k] = nms.poseValues(target.getTranslation(), target.getLeftRotation(), target.getScale(), target.getRightRotation(), 0);
            } else {
                moving[k] = over == frame.over() ? frame.moving()[slot]
                        : nms.poseValues(target.getTranslation(), target.getLeftRotation(), target.getScale(), target.getRightRotation(), over);
                jumping[k] = frame.jumping()[slot];
            }
            targets[k] = target;
        }
        return new DisplayAnimation.Frame(at, over, parts, moving, jumping, targets, sound, frame.volume(), frame.pitch(), root);
    }

    /**
     * A pose on the way from one to another, as a client draws it: translation and scale in a straight line, the
     * rotations along the shortest arc.
     */
    static Transformation between(Transformation from, Transformation to, float progress) {
        return new Transformation(
                new Vector3f(from.getTranslation()).lerp(to.getTranslation(), progress),
                new Quaternionf(from.getLeftRotation()).slerp(to.getLeftRotation(), progress),
                new Vector3f(from.getScale()).lerp(to.getScale(), progress),
                new Quaternionf(from.getRightRotation()).slerp(to.getRightRotation(), progress));
    }

    /**
     * Whether two poses (or two places of the whole model) are the same. Worked out by hand: the JOML of the server is
     * not the one the plugin is built against, some of its methods have other signatures.
     */
    private static boolean same(Object a, Object b) {
        if (a instanceof Vector3f u && b instanceof Vector3f v) {
            return distance(u, v) <= SAME;
        }
        if (a instanceof Transformation s && b instanceof Transformation t) {
            return distance(s.getTranslation(), t.getTranslation()) <= SAME && distance(s.getScale(), t.getScale()) <= SAME
                    && sameRotation(s.getLeftRotation(), t.getLeftRotation()) && sameRotation(s.getRightRotation(), t.getRightRotation());
        }
        return false;
    }

    private static float distance(Vector3f a, Vector3f b) {
        float x = a.x - b.x;
        float y = a.y - b.y;
        float z = a.z - b.z;
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static boolean sameRotation(Quaternionf a, Quaternionf b) {
        double dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
        return Math.abs(Math.abs(dot) - 1) <= SAME;
    }
}
