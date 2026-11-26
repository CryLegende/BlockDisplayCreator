package me.general_breddok.blockdisplaycreator.render;

import org.bukkit.util.Transformation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Takes out of an animation the frames a client does not need: a client moves a part from one pose to the next by
 * itself, so a frame whose pose the motion between its neighbours already passes by (within a tolerance, on the
 * corners of the part) is left out, and the next frame kept leaves when the dropped one left and arrives when it
 * arrived. Parts that show nothing (an empty block display, an item display without item) get no frame at all.
 * <p>
 * The motion is replayed as a client of Minecraft 26 draws it: the translation and the scale in a straight line, each
 * rotation by slerp the shortest way, from the pose of the last frame drawn (half a tick before the frame arrives, on
 * average); two frames for a part in the same tick only count for the last one, as a client reads the synced data of
 * an entity once a tick.
 * <p>
 * Left as they are: the parts of a frame that carries a sound, parts moved by frames of different sets of parts,
 * parts whose frame starts before the previous one arrived, and parts a loop and another animation of the model both
 * move (they mix while the other one plays). At the end the whole animation is replayed, three turns for a loop,
 * every half tick, against the original: a part further than the tolerance keeps its frames.
 * <p>
 * Pure computation on the poses the server already worked out: it may run on any thread.
 */
final class AnimationOptimizer {

    /**
     * Changes whenever the result of the same input may change: cached results of another version are worked out again.
     */
    static final int VERSION = 1;
    /**
     * How far a kept pose may be from the original motion, in blocks of the world: a quarter of a pixel of a block.
     */
    static final double TOLERANCE = 1.0 / 64;
    /**
     * The most ticks between two frames of a part: a player arriving is sent the pose a frame heads for.
     */
    private static final int MAX_GAP = 30;
    /**
     * How long before the tick a frame arrives a client takes the pose it starts from, on average.
     */
    private static final double LAG = 0.5;
    private static final double STEP = 0.5;
    /**
     * Below this size, in blocks, a part shows nothing: only where it stands counts.
     */
    private static final double INVISIBLE = 0.004;

    /**
     * A frame kept: the index of the original frame (in the order of the animation), when it starts, how long it
     * takes, and the parts it still moves (null for the same as before).
     */
    record Kept(int frame, int at, int over, int[] parts) {
    }

    private AnimationOptimizer() {
    }

    // ---------- What to keep ----------

    /**
     * The parts of a model that a loop and another of its animations both move.
     */
    static Set<Integer> mixed(List<DisplayAnimation> animations) {
        Set<Integer> mixed = new HashSet<>();
        for (DisplayAnimation a : animations) {
            for (DisplayAnimation b : animations) {
                if (a != b && (a.trigger == DisplayAnimation.Trigger.LOOP || b.trigger == DisplayAnimation.Trigger.LOOP)) {
                    for (int part : a.touched) {
                        if (Arrays.binarySearch(b.touched, part) >= 0) {
                            mixed.add(part);
                        }
                    }
                }
            }
        }
        return mixed;
    }

    /**
     * The frames of an animation worth sending, or null when it is better left as it is.
     */
    static List<Kept> optimise(DisplayAnimation animation, List<DisplayModel.Element> elements, Set<Integer> mixed) {
        DisplayAnimation.Frame[] frames = animation.frames;
        for (DisplayAnimation.Frame frame : frames) {
            for (Transformation target : frame.targets()) {
                if (target == null) {
                    // the server could not tell how it splits a matrix: the motion cannot be replayed
                    return null;
                }
            }
        }
        int loop = animation.trigger == DisplayAnimation.Trigger.LOOP ? animation.duration : 0;

        // the parts left as they are, the others by the set of parts their frames move
        Map<Integer, Set<String>> setsOf = new HashMap<>();
        Set<Integer> blocked = new HashSet<>(mixed);
        for (DisplayAnimation.Frame frame : frames) {
            String key = Arrays.toString(frame.parts());
            for (int part : frame.parts()) {
                setsOf.computeIfAbsent(part, k -> new HashSet<>()).add(key);
                if (frame.sound() != null) {
                    blocked.add(part);
                }
            }
        }
        setsOf.forEach((part, sets) -> {
            if (sets.size() > 1) {
                blocked.add(part);
            }
        });
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < frames.length; i++) {
            // a frame moving the whole model is one teleport: kept as it is
            boolean free = frames[i].root() == null;
            for (int part : frames[i].parts()) {
                free &= !blocked.contains(part);
            }
            if (free) {
                groups.computeIfAbsent(Arrays.toString(frames[i].parts()), k -> new ArrayList<>()).add(i);
            }
        }

        Map<Integer, Kept> kept = new HashMap<>();
        boolean changed = false;
        for (List<Integer> group : groups.values()) {
            int[] parts = frames[group.get(0)].parts();
            int[] shown = Arrays.stream(parts).filter(part -> elements.get(part).drawn()).toArray();
            if (shown.length == 0) {
                // it shows nothing: nothing to send
                changed = true;
                continue;
            }
            int[] partsKept = shown.length < parts.length ? shown : null;
            changed |= partsKept != null;
            // frames of the same set in the same tick: a client only applies the last one
            List<Integer> entries = new ArrayList<>();
            for (int i = 0; i < group.size(); i++) {
                if (i + 1 == group.size() || frames[group.get(i + 1)].at() != frames[group.get(i)].at()) {
                    entries.add(group.get(i));
                }
            }
            changed |= entries.size() < group.size();
            Track track = new Track(frames, entries, shown, elements, loop);
            List<Kept> result = null;
            if (entries.size() >= 3 && track.contiguous()) {
                double tolerance = TOLERANCE;
                for (int attempt = 0; attempt < 4 && result == null; attempt++, tolerance *= 0.6) {
                    List<Kept> candidate = track.greedy(tolerance, partsKept);
                    if (track.worst(candidate) <= TOLERANCE) {
                        result = candidate;
                    }
                }
            }
            if (result == null) {
                result = new ArrayList<>();
                for (int entry : entries) {
                    result.add(new Kept(entry, frames[entry].at(), frames[entry].over(), partsKept));
                }
            }
            if (result.size() < entries.size()) {
                changed = true;
            }
            for (Kept k : result) {
                changed |= k.at() != frames[k.frame()].at() || k.over() != frames[k.frame()].over();
                kept.put(k.frame(), k);
            }
        }
        if (!changed) {
            return null;
        }
        List<Kept> out = new ArrayList<>();
        for (int i = 0; i < frames.length; i++) {
            boolean free = frames[i].root() == null;
            for (int part : frames[i].parts()) {
                free &= !blocked.contains(part);
            }
            if (!free) {
                out.add(new Kept(i, frames[i].at(), frames[i].over(), null));
            } else if (kept.containsKey(i)) {
                out.add(kept.get(i));
            }
        }
        // the plugin sorts frames by their start, keeping their order at the same tick
        out.sort((a, b) -> a.at() != b.at() ? Integer.compare(a.at(), b.at()) : Integer.compare(a.frame(), b.frame()));
        return out;
    }

    /**
     * How many synced data packets an animation sends a viewer in one turn: one per part of each frame.
     */
    static int updates(DisplayAnimation animation) {
        int count = 0;
        for (DisplayAnimation.Frame frame : animation.frames) {
            count += frame.parts().length;
        }
        return count;
    }

    /**
     * A fingerprint of what the optimisation of an animation depends on: the same fingerprint, the same result.
     */
    static String fingerprint(DisplayAnimation animation, List<DisplayModel.Element> elements, Set<Integer> mixed) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeDouble(TOLERANCE);
            out.writeUTF(animation.trigger.name());
            out.writeInt(animation.duration);
            for (DisplayAnimation.Frame frame : animation.frames) {
                out.writeInt(frame.at());
                out.writeInt(frame.over());
                out.writeBoolean(frame.sound() != null);
                if (frame.root() != null) {
                    // only then: the animations without root motion keep the fingerprint (and the cache) they had
                    out.writeUTF("root");
                    out.writeFloat(frame.root().x);
                    out.writeFloat(frame.root().y);
                    out.writeFloat(frame.root().z);
                }
                for (int i = 0; i < frame.parts().length; i++) {
                    out.writeInt(frame.parts()[i]);
                    write(out, frame.targets()[i]);
                }
            }
            for (int part : animation.touched) {
                DisplayModel.Element element = elements.get(part);
                out.writeInt(part);
                out.writeBoolean(element.drawn());
                out.writeBoolean(element.block());
                write(out, element.transformation());
                float[] shape = element.shape();
                out.writeInt(shape == null ? 0 : shape.length);
                if (shape != null) {
                    for (float v : shape) {
                        out.writeFloat(v);
                    }
                }
                out.writeBoolean(mixed.contains(part));
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()), 0, 16);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(DataOutputStream out, Transformation transformation) throws IOException {
        if (transformation == null) {
            out.writeBoolean(false);
            return;
        }
        out.writeBoolean(true);
        out.writeFloat(transformation.getTranslation().x);
        out.writeFloat(transformation.getTranslation().y);
        out.writeFloat(transformation.getTranslation().z);
        out.writeFloat(transformation.getLeftRotation().x);
        out.writeFloat(transformation.getLeftRotation().y);
        out.writeFloat(transformation.getLeftRotation().z);
        out.writeFloat(transformation.getLeftRotation().w);
        out.writeFloat(transformation.getScale().x);
        out.writeFloat(transformation.getScale().y);
        out.writeFloat(transformation.getScale().z);
        out.writeFloat(transformation.getRightRotation().x);
        out.writeFloat(transformation.getRightRotation().y);
        out.writeFloat(transformation.getRightRotation().z);
        out.writeFloat(transformation.getRightRotation().w);
    }

    // ---------- One set of parts moved together ----------

    /**
     * The frames of a set of parts that always move together, and how a client draws them.
     */
    private static final class Track {
        final DisplayAnimation.Frame[] frames;
        final List<Integer> entries;
        final int[] parts;
        final double[][][] corners;
        final double[][] rest;
        final int loop;
        /**
         * For each entry, for each part, the pose it heads for.
         */
        final double[][][] targets;

        Track(DisplayAnimation.Frame[] frames, List<Integer> entries, int[] parts, List<DisplayModel.Element> elements, int loop) {
            this.frames = frames;
            this.entries = entries;
            this.parts = parts;
            this.loop = loop;
            this.corners = new double[parts.length][][];
            this.rest = new double[parts.length][];
            for (int p = 0; p < parts.length; p++) {
                DisplayModel.Element element = elements.get(parts[p]);
                float[] shape = element.shape();
                if (shape == null || shape.length < 6) {
                    shape = element.block() ? new float[]{0, 0, 0, 1, 1, 1} : new float[]{-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f};
                }
                this.corners[p] = new double[8][];
                for (int c = 0; c < 8; c++) {
                    this.corners[p][c] = new double[]{(c & 1) == 0 ? shape[0] : shape[3], (c & 2) == 0 ? shape[1] : shape[4], (c & 4) == 0 ? shape[2] : shape[5]};
                }
                this.rest[p] = pose(element.transformation());
            }
            this.targets = new double[entries.size()][parts.length][];
            for (int e = 0; e < entries.size(); e++) {
                DisplayAnimation.Frame frame = frames[entries.get(e)];
                for (int p = 0; p < parts.length; p++) {
                    this.targets[e][p] = pose(frame.targets()[indexOf(frame.parts(), parts[p])]);
                }
            }
        }

        int at(int entry) {
            return this.frames[this.entries.get(entry)].at();
        }

        int over(int entry) {
            return this.frames[this.entries.get(entry)].over();
        }

        /**
         * Whether each frame starts once the one before has arrived.
         */
        boolean contiguous() {
            int n = this.entries.size();
            for (int e = 0; e + 1 < n; e++) {
                if (at(e + 1) < at(e) + over(e) || at(e + 1) == at(e)) {
                    return false;
                }
            }
            return this.loop == 0 || n < 2 || at(0) + this.loop >= at(n - 1) + over(n - 1);
        }

        /**
         * The original frames as updates (at, over, entry), over two turns for a loop.
         */
        private int[][] unrolled() {
            int n = this.entries.size();
            int turns = this.loop > 0 ? 2 : 1;
            int[][] out = new int[n * turns][];
            for (int t = 0; t < turns; t++) {
                for (int e = 0; e < n; e++) {
                    out[t * n + e] = new int[]{at(e) + t * this.loop, over(e), e};
                }
            }
            return out;
        }

        /**
         * Keeps a frame only where the client's own motion from the last kept one strays further than the tolerance.
         */
        List<Kept> greedy(double tolerance, int[] partsKept) {
            int n = this.entries.size();
            int[][] events = unrolled();
            double end = this.loop > 0 ? 3.0 * this.loop : maxArrival() + 2;
            double[] times = grid(0, end);
            double[][][] reference = reference(times);
            // the client's state when each original frame arrives: from where, to where, since when, how long
            List<double[][]> starts = new ArrayList<>();
            double[][] from = copy(this.rest);
            double[][] to = copy(this.rest);
            double since = 0;
            int length = 0;
            for (int[] event : events) {
                double[][] start = new double[this.parts.length][];
                for (int p = 0; p < this.parts.length; p++) {
                    start[p] = resume(from[p], to[p], since, length, event[0]);
                }
                from = start;
                to = this.targets[event[2]];
                since = event[0];
                length = event[1];
                starts.add(start);
            }
            int first = this.loop > 0 ? n - 1 : 0;
            int last = this.loop > 0 ? 2 * n - 1 : n - 1;
            // the segment being drawn: start poses, target poses, start tick, length
            double[][] segFrom = starts.get(first);
            double[][] segTo = this.targets[events[first][2]];
            double segSince = events[first][0];
            int segLength = events[first][1];

            List<Kept> kept = new ArrayList<>();
            if (this.loop == 0) {
                kept.add(new Kept(this.entries.get(0), at(0), over(0), partsKept));
            }
            int i = first;
            while (i < last) {
                int leave = events[i + 1][0];
                int best = i + 1;
                double[][] bestFrom = null;
                for (int j = i + 1; j <= last; j++) {
                    int arrive = events[j][0] + events[j][1];
                    if (j > i + 1 && arrive - leave > MAX_GAP) {
                        break;
                    }
                    double[][] start = new double[this.parts.length][];
                    for (int p = 0; p < this.parts.length; p++) {
                        start[p] = resume(segFrom[p], segTo[p], segSince, segLength, leave);
                    }
                    if (j > i + 1 && !follows(start, this.targets[events[j][2]], leave, arrive, times, reference, tolerance)) {
                        break;
                    }
                    best = j;
                    bestFrom = start;
                }
                int arrive = events[best][0] + events[best][1];
                int entry = events[best][2];
                kept.add(new Kept(this.entries.get(entry), this.loop > 0 ? Math.floorMod(leave, this.loop) : leave, arrive - leave, partsKept));
                segFrom = bestFrom;
                segTo = this.targets[entry];
                segSince = leave;
                segLength = arrive - leave;
                i = best;
            }
            return kept;
        }

        private int maxArrival() {
            int max = 0;
            for (int e = 0; e < this.entries.size(); e++) {
                max = Math.max(max, at(e) + over(e));
            }
            return max;
        }

        /**
         * Whether going from some poses to others between two ticks stays close to the original motion.
         */
        private boolean follows(double[][] start, double[][] target, int leave, int arrive, double[] times, double[][][] reference, double tolerance) {
            int from = (int) Math.round(leave / STEP);
            int to = (int) Math.round(arrive / STEP);
            for (int k = from; k <= to && k < times.length; k++) {
                double progress = arrive > leave ? (times[k] - leave) / (arrive - leave) : 1;
                for (int p = 0; p < this.parts.length; p++) {
                    if (gap(interpolate(start[p], target[p], progress), reference[p][k], this.corners[p]) > tolerance) {
                        return false;
                    }
                }
            }
            return true;
        }

        /**
         * How a client draws the original frames, part by part, at the given times.
         */
        private double[][][] reference(double[] times) {
            List<int[]> updates = new ArrayList<>();
            for (int e = 0; e < this.entries.size(); e++) {
                updates.add(new int[]{at(e), over(e), e});
            }
            return draw(updates, times);
        }

        private double[][][] draw(List<int[]> updates, double[] times) {
            double[][][] out = new double[this.parts.length][][];
            for (int p = 0; p < this.parts.length; p++) {
                final int part = p;
                List<double[]> poses = new ArrayList<>();
                List<int[]> timing = new ArrayList<>();
                for (int[] u : updates) {
                    timing.add(new int[]{u[0], u[1]});
                    poses.add(this.targets[u[2]][part]);
                }
                out[p] = trajectory(timing, poses, this.rest[p], times, this.loop);
            }
            return out;
        }

        /**
         * The largest distance between the original motion and the kept frames, over three turns of a loop.
         */
        double worst(List<Kept> kept) {
            double end = this.loop > 0 ? 3.0 * this.loop : Math.max(maxArrival(), kept.stream().mapToInt(k -> k.at() + k.over()).max().orElse(0)) + 2;
            double[] times = grid(0, end);
            double[][][] original = reference(times);
            List<int[]> updates = new ArrayList<>();
            for (Kept k : kept) {
                updates.add(new int[]{k.at(), k.over(), this.entries.indexOf(k.frame())});
            }
            double[][][] now = draw(updates, times);
            int from = this.loop > 0 ? (int) Math.round(this.loop / STEP) : 0;
            double worst = 0;
            for (int p = 0; p < this.parts.length; p++) {
                for (int k = from; k < times.length; k++) {
                    worst = Math.max(worst, gap(original[p][k], now[p][k], this.corners[p]));
                }
            }
            return worst;
        }
    }

    // ---------- The client ----------

    /**
     * Where a client draws a part at the given times (in increasing order), from the frames it is sent: at, over and
     * the pose of each, repeated every {@code loop} ticks for a loop (times then run over three turns).
     */
    static double[][] trajectory(List<int[]> timing, List<double[]> poses, double[] rest, double[] times, int loop) {
        Integer[] order = new Integer[timing.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        // stable: at the same tick, the order of the frames
        Arrays.sort(order, (a, b) -> Integer.compare(timing.get(a)[0], timing.get(b)[0]));
        int turns = loop > 0 ? 3 : 1;
        int n = order.length;
        double[][] out = new double[times.length][];
        double[] from = rest;
        double[] to = rest;
        double since = 0;
        int length = 0;
        int next = 0;
        for (int k = 0; k < times.length; k++) {
            double t = times[k];
            while (next < n * turns && eventAt(timing, order, next, loop) <= t) {
                // frames of the same tick: the client keeps the last values
                while (next + 1 < n * turns && eventAt(timing, order, next + 1, loop) == eventAt(timing, order, next, loop)) {
                    next++;
                }
                int at = eventAt(timing, order, next, loop);
                int index = order[next % n];
                double[] start = resume(from, to, since, length, at);
                from = start;
                to = poses.get(index);
                since = at;
                length = timing.get(index)[1];
                next++;
            }
            out[k] = interpolate(from, to, length <= 0 ? 1 : (t - since) / length);
        }
        return out;
    }

    private static int eventAt(List<int[]> timing, Integer[] order, int i, int loop) {
        int n = order.length;
        return timing.get(order[i % n])[0] + (loop > 0 ? (i / n) * loop : 0);
    }

    /**
     * The pose a client starts from when a new one arrives at a tick.
     */
    static double[] resume(double[] from, double[] to, double since, int length, double at) {
        return interpolate(from, to, length <= 0 ? 1 : (at - since - LAG) / length);
    }

    /**
     * {@code Transformation.slerp} of Minecraft: translation and scale in a straight line, rotations by slerp.
     */
    static double[] interpolate(double[] a, double[] b, double t) {
        if (t <= 0) {
            return a;
        }
        if (t >= 1) {
            return b;
        }
        double[] out = new double[14];
        for (int i = 0; i < 3; i++) {
            out[i] = a[i] + (b[i] - a[i]) * t;
            out[7 + i] = a[7 + i] + (b[7 + i] - a[7 + i]) * t;
        }
        slerp(a, b, 3, t, out);
        slerp(a, b, 10, t, out);
        return out;
    }

    /**
     * {@code Quaternionf.slerp} of JOML, the shortest way, not normalised.
     */
    private static void slerp(double[] a, double[] b, int o, double t, double[] out) {
        double cos = a[o] * b[o] + a[o + 1] * b[o + 1] + a[o + 2] * b[o + 2] + a[o + 3] * b[o + 3];
        double absCos = Math.abs(cos);
        double s0;
        double s1;
        if (1 - absCos > 1.0E-6) {
            double sinSqr = 1 - absCos * absCos;
            double sinInv = 1 / Math.sqrt(sinSqr);
            double omega = Math.atan2(sinSqr * sinInv, absCos);
            s0 = Math.sin((1 - t) * omega) * sinInv;
            s1 = Math.sin(t * omega) * sinInv;
        } else {
            s0 = 1 - t;
            s1 = t;
        }
        if (cos < 0) {
            s1 = -s1;
        }
        for (int i = 0; i < 4; i++) {
            out[o + i] = s0 * a[o + i] + s1 * b[o + i];
        }
    }

    /**
     * The largest distance between the corners of a part in two poses, in blocks. A part that shows nothing in both
     * only counts by where it stands: it comes back from there.
     */
    static double gap(double[] p, double[] q, double[][] corners) {
        double[] mp = matrix(p);
        double[] mq = matrix(q);
        double worst = 0;
        double sizeP = 0;
        double sizeQ = 0;
        double[] firstP = apply(mp, corners[0]);
        double[] firstQ = apply(mq, corners[0]);
        for (double[] corner : corners) {
            double[] a = apply(mp, corner);
            double[] b = apply(mq, corner);
            worst = Math.max(worst, distance(a, b));
            sizeP = Math.max(sizeP, distance(a, firstP));
            sizeQ = Math.max(sizeQ, distance(b, firstQ));
        }
        if (sizeP < INVISIBLE && sizeQ < INVISIBLE) {
            return Math.sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]) + (p[2] - q[2]) * (p[2] - q[2]));
        }
        return worst;
    }

    /**
     * Translation · left rotation · scale · right rotation, as 3 rows of 4.
     */
    private static double[] matrix(double[] p) {
        double[] l = rotation(p, 3);
        double[] r = rotation(p, 10);
        double[] m = new double[12];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double v = 0;
                for (int k = 0; k < 3; k++) {
                    v += l[i * 3 + k] * p[7 + k] * r[k * 3 + j];
                }
                m[i * 4 + j] = v;
            }
            m[i * 4 + 3] = p[i];
        }
        return m;
    }

    private static double[] rotation(double[] p, int o) {
        double x = p[o];
        double y = p[o + 1];
        double z = p[o + 2];
        double w = p[o + 3];
        return new double[]{
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)};
    }

    private static double[] apply(double[] m, double[] c) {
        return new double[]{
                m[0] * c[0] + m[1] * c[1] + m[2] * c[2] + m[3],
                m[4] * c[0] + m[5] * c[1] + m[6] * c[2] + m[7],
                m[8] * c[0] + m[9] * c[1] + m[10] * c[2] + m[11]};
    }

    private static double distance(double[] a, double[] b) {
        double x = a[0] - b[0];
        double y = a[1] - b[1];
        double z = a[2] - b[2];
        return Math.sqrt(x * x + y * y + z * z);
    }

    static double[] pose(Transformation t) {
        return new double[]{
                t.getTranslation().x, t.getTranslation().y, t.getTranslation().z,
                t.getLeftRotation().x, t.getLeftRotation().y, t.getLeftRotation().z, t.getLeftRotation().w,
                t.getScale().x, t.getScale().y, t.getScale().z,
                t.getRightRotation().x, t.getRightRotation().y, t.getRightRotation().z, t.getRightRotation().w};
    }

    private static double[] grid(double from, double to) {
        int n = (int) Math.round((to - from) / STEP);
        double[] out = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            out[i] = from + i * STEP;
        }
        return out;
    }

    private static double[][] copy(double[][] poses) {
        return poses.clone();
    }

    private static int indexOf(int[] array, int value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return i;
            }
        }
        return -1;
    }

    // ---------- Cache ----------

    /**
     * A result in a line of text: frame:at:over[:part,part…] separated by spaces, or "=" for nothing to change.
     */
    static String encode(List<Kept> kept) {
        if (kept == null) {
            return "=";
        }
        StringBuilder out = new StringBuilder();
        for (Kept k : kept) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(k.frame()).append(':').append(k.at()).append(':').append(k.over());
            if (k.parts() != null) {
                out.append(':');
                for (int i = 0; i < k.parts().length; i++) {
                    out.append(i == 0 ? "" : ",").append(k.parts()[i]);
                }
            }
        }
        return out.toString();
    }

    static List<Kept> decode(String text) {
        if (text == null || text.equals("=")) {
            return null;
        }
        List<Kept> out = new ArrayList<>();
        for (String item : text.trim().split(" ")) {
            String[] f = item.split(":");
            int[] parts = null;
            if (f.length > 3) {
                parts = Arrays.stream(f[3].split(",")).mapToInt(Integer::parseInt).toArray();
            }
            out.add(new Kept(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), parts));
        }
        return out;
    }
}
