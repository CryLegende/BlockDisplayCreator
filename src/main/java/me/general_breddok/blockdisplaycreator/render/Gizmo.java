package me.general_breddok.blockdisplaycreator.render;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The tool an operator moves one custom block shown through packets with: three arrows to move it along an axis,
 * three squares to move it on a plane, two arcs to turn it (around the vertical axis, and tipping it forward).
 * <p>
 * The tool is made of display entities that exist on the operator's client only. The operator aims at a part, which
 * lights up, and clicks to take hold of it: the block then follows where he looks, until he clicks again.
 */
final class Gizmo {

    /**
     * How far above the build limit the parts are summoned for the time of the reading, out of everyone's sight.
     */
    private static final int READING_HEIGHT = 1000;
    private static final double REACH = 16;
    private static final double MAX_SHIFT = 8;
    private static final double[] MOVE_STEPS = {0.0625, 0.125, 0.25, 0.5, 1};
    private static final double[] TURN_STEPS = {1, 2.5, 5, 15, 22.5, 45, 90};
    private static final Material[] COLORS = {Material.RED_CONCRETE, Material.LIME_CONCRETE, Material.LIGHT_BLUE_CONCRETE};
    private static final String[] AXES = {"X", "Y", "Z"};

    // the shapes, for a gizmo of size 1
    private static final float ARROW_FROM = 0.12f;
    private static final float ARROW_TO = 1.0f;
    private static final double ARROW_RADIUS = 0.11;
    private static final float PLANE_FROM = 0.27f;
    private static final float PLANE_TO = 0.55f;
    private static final float RING_RADIUS = 1.25f;
    private static final double RING_THICKNESS = 0.13;
    private static final int RING_SEGMENTS = 10;

    private enum Kind {MOVE_AXIS, MOVE_PLANE, TURN}

    private static final class Piece {
        final int id;
        final Vector3f center;
        final Vector3f size;
        final Quaternionf orient;
        List<Object> normal;

        Piece(int id, Vector3f center, Vector3f size, Quaternionf orient) {
            this.id = id;
            this.center = center;
            this.size = size;
            this.orient = orient;
        }
    }

    private static final class Handle {
        final Kind kind;
        /**
         * The axis moved along, the axis a plane is perpendicular to, the axis turned around.
         */
        final int axis;
        final String label;
        final List<Piece> pieces = new ArrayList<>();
        final int color;
        boolean lit;

        Handle(Kind kind, int axis, String label, int color) {
            this.kind = kind;
            this.axis = axis;
            this.label = label;
            this.color = color;
        }
    }

    private final DisplayRenderer renderer;
    private final NmsBridge nms;
    private final Player player;
    private final PlacedDisplay target;
    private Object type;
    private List<Object>[] normals;
    private final List<Handle> handles = new ArrayList<>();
    private final List<Piece> pieces = new ArrayList<>();
    private List<Object> lit;
    private final double scale;
    private final float summonedPitch;

    private final Vector3d origin = new Vector3d();
    private double yaw;
    private boolean local = true;
    private boolean snapping = true;
    private int moveStep = 1;
    private int turnStep = 3;
    private Quaterniond moveFrame = new Quaterniond();
    private Quaterniond turnFrame = new Quaterniond();

    private Handle hovered;
    private Handle active;
    private int lastClick = -100;
    private int shown;

    // what a drag remembers from the moment the part was taken
    private final double[] shiftStart = new double[5];
    private final Vector3d dragOrigin = new Vector3d();
    private final Vector3d dragAxis = new Vector3d();
    private final Vector3d dragU = new Vector3d();
    private final Vector3d dragV = new Vector3d();
    private final Vector3d hitStart = new Vector3d();
    private double paramStart;
    private double angleLast;
    private double angleTotal;
    private double basePitch;
    private String readout = "";

    /**
     * Shows the tool around a block, to its operator only.
     */
    Gizmo(DisplayRenderer renderer, NmsBridge nms, Player player, PlacedDisplay target) {
        this.renderer = renderer;
        this.nms = nms;
        this.player = player;
        this.target = target;
        this.summonedPitch = target.model.elements.get(0).pitch();

        float[] extent = target.model.extent();
        this.scale = Math.max(0.6, Math.min(3.0, Math.max(extent[0], extent[1]) * 0.6));

        read(target.world, target.chunkX(), target.chunkZ());

        build();
        double[] pose = target.pose(this.summonedPitch);
        this.origin.set(pose[0], pose[1], pose[2]);
        this.yaw = pose[3];
        updateFrames();

        List<Object> packets = new ArrayList<>();
        for (Piece piece : this.pieces) {
            packets.add(nms.spawnPacket(piece.id, UUID.randomUUID(), this.origin.x, this.origin.y, this.origin.z, 0f, 0f, this.type));
            packets.add(nms.dataPacket(piece.id, withPose(piece, false)));
        }
        nms.send(player, nms.bundle(packets));
        this.shown = 1;
    }

    PlacedDisplay target() {
        return this.target;
    }

    boolean isDragging() {
        return this.active != null;
    }

    /**
     * Reads, from entities summoned for an instant, what a client is sent for a coloured part, and for a lit one.
     */
    @SuppressWarnings("unchecked")
    private void read(World world, int chunkX, int chunkZ) {
        this.normals = new List[COLORS.length];
        Location where = new Location(world, (chunkX << 4) + 8.5, world.getMaxHeight() + READING_HEIGHT, (chunkZ << 4) + 8.5);
        for (int i = 0; i <= COLORS.length; i++) {
            Material material = i < COLORS.length ? COLORS[i] : Material.YELLOW_CONCRETE;
            boolean glowing = i == COLORS.length;
            BlockDisplay display = world.spawn(where, BlockDisplay.class, entity -> {
                entity.setPersistent(false);
                entity.setBlock(material.createBlockData());
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setShadowRadius(0f);
                if (glowing) {
                    entity.setGlowing(true);
                    entity.setGlowColorOverride(Color.YELLOW);
                }
            });
            try {
                Object handle = this.nms.handle(display);
                this.type = this.nms.type(handle);
                List<Object> values = new ArrayList<>(this.nms.syncedValues(handle));
                // the tool follows its block smoothly
                values.addAll(this.nms.teleportDurationValues(2));
                if (!glowing) {
                    // a part that stops being lit must be told it no longer glows
                    values.add(this.nms.flagsValue((byte) 0));
                    this.normals[i] = values;
                } else {
                    this.lit = values;
                }
            } finally {
                display.remove();
            }
        }
    }

    // ---------- Shapes ----------

    private void build() {
        for (int axis = 0; axis < 3; axis++) {
            Handle arrow = new Handle(Kind.MOVE_AXIS, axis, "Move " + AXES[axis], axis);
            float middle = (ARROW_FROM + 0.84f) / 2;
            piece(arrow, along(axis, middle, 0.84f - ARROW_FROM, 0.045f), new Quaternionf());
            piece(arrow, along(axis, 0.92f, 0.16f, 0.13f), new Quaternionf());
            this.handles.add(arrow);
        }
        for (int normal = 0; normal < 3; normal++) {
            int a = (normal + 1) % 3;
            int b = (normal + 2) % 3;
            Handle square = new Handle(Kind.MOVE_PLANE, normal, "Move " + AXES[a] + "/" + AXES[b], normal);
            Vector3f center = new Vector3f();
            Vector3f size = new Vector3f();
            set(center, a, (PLANE_FROM + PLANE_TO) / 2);
            set(center, b, (PLANE_FROM + PLANE_TO) / 2);
            set(size, a, PLANE_TO - PLANE_FROM);
            set(size, b, PLANE_TO - PLANE_FROM);
            set(size, normal, 0.025f);
            piece(square, new Vector3f[]{center, size}, new Quaternionf());
            this.handles.add(square);
        }
        // pitch (around X, tipping the block forward) and yaw (around Y)
        for (int axis = 0; axis < 2; axis++) {
            Handle ring = new Handle(Kind.TURN, axis, axis == 1 ? "Turn" : "Tilt", axis);
            int p = (axis + 1) % 3;
            int q = (axis + 2) % 3;
            double step = Math.PI / 2 / RING_SEGMENTS;
            float length = (float) (RING_RADIUS * step * 1.15);
            for (int i = 0; i < RING_SEGMENTS; i++) {
                double phi = (i + 0.5) * step;
                Vector3f center = new Vector3f();
                set(center, p, (float) (RING_RADIUS * Math.cos(phi)));
                set(center, q, (float) (RING_RADIUS * Math.sin(phi)));
                Vector3f tangent = new Vector3f();
                set(tangent, p, (float) -Math.sin(phi));
                set(tangent, q, (float) Math.cos(phi));
                Quaternionf orient = new Quaternionf().rotateTo(new Vector3f(1, 0, 0), tangent);
                piece(ring, new Vector3f[]{center, new Vector3f(length, 0.055f, 0.055f)}, orient);
            }
            this.handles.add(ring);
        }
    }

    private static Vector3f[] along(int axis, float middle, float length, float thickness) {
        Vector3f center = new Vector3f();
        Vector3f size = new Vector3f(thickness, thickness, thickness);
        set(center, axis, middle);
        set(size, axis, length);
        return new Vector3f[]{center, size};
    }

    private static void set(Vector3f vector, int axis, float value) {
        switch (axis) {
            case 0 -> vector.x = value;
            case 1 -> vector.y = value;
            default -> vector.z = value;
        }
    }

    private void piece(Handle handle, Vector3f[] centerAndSize, Quaternionf orient) {
        Piece piece = new Piece(this.nms.nextEntityId(), centerAndSize[0], centerAndSize[1], orient);
        piece.normal = this.normals[handle.color];
        handle.pieces.add(piece);
        this.pieces.add(piece);
    }

    private boolean turns(Piece piece) {
        for (Handle handle : this.handles) {
            if (handle.kind == Kind.TURN && handle.pieces.contains(piece)) {
                return true;
            }
        }
        return false;
    }

    // ---------- What the client is sent ----------

    private void updateFrames() {
        Quaterniond turn = new Quaterniond().rotationY(-Math.toRadians(this.yaw));
        this.turnFrame = turn;
        this.moveFrame = this.local ? new Quaterniond(turn) : new Quaterniond();
    }

    private static Quaternionf single(Quaterniond q) {
        return new Quaternionf((float) q.x, (float) q.y, (float) q.z, (float) q.w);
    }

    /**
     * The values that give a part its colour and where it stands in the tool.
     */
    private List<Object> withPose(Piece piece, boolean lit) {
        Quaternionf frame = single(turns(piece) ? this.turnFrame : this.moveFrame);
        Vector3f center = frame.transform(new Vector3f(piece.center).mul((float) this.scale));
        Quaternionf orient = new Quaternionf(frame).mul(piece.orient);
        Vector3f size = new Vector3f(piece.size).mul((float) this.scale);
        // a block display is drawn from its corner: the box is centred by moving it back by half its size
        Vector3f half = orient.transform(new Vector3f(size).mul(0.5f));
        Vector3f translation = center.sub(half);
        List<Object> values = new ArrayList<>(lit ? this.lit : piece.normal);
        values.addAll(this.nms.poseValues(translation, orient, size, new Quaternionf(), 0));
        return values;
    }

    private void sendPoses() {
        List<Object> packets = new ArrayList<>(this.pieces.size());
        for (Handle handle : this.handles) {
            for (Piece piece : handle.pieces) {
                packets.add(this.nms.dataPacket(piece.id, withPose(piece, handle.lit)));
            }
        }
        this.nms.send(this.player, this.nms.bundle(packets));
    }

    private void sendPosition() {
        List<Object> packets = new ArrayList<>(this.pieces.size());
        for (Piece piece : this.pieces) {
            packets.add(this.nms.teleportPacket(piece.id, this.origin.x, this.origin.y, this.origin.z, 0f, 0f));
        }
        this.nms.send(this.player, this.nms.bundle(packets));
    }

    private void light(Handle handle, boolean on) {
        if (handle == null || handle.lit == on) {
            return;
        }
        handle.lit = on;
        List<Object> packets = new ArrayList<>(handle.pieces.size());
        for (Piece piece : handle.pieces) {
            packets.add(this.nms.dataPacket(piece.id, on ? this.lit : piece.normal));
        }
        this.nms.send(this.player, this.nms.bundle(packets));
    }

    // ---------- Aiming ----------

    private Vector3d eye() {
        Location eye = this.player.getEyeLocation();
        return new Vector3d(eye.getX(), eye.getY(), eye.getZ());
    }

    private Vector3d look() {
        org.bukkit.util.Vector direction = this.player.getEyeLocation().getDirection();
        return new Vector3d(direction.getX(), direction.getY(), direction.getZ()).normalize();
    }

    private Quaterniond frameOf(Handle handle) {
        return handle.kind == Kind.TURN ? this.turnFrame : this.moveFrame;
    }

    /**
     * How far along the ray the part is hit, in blocks, or -1.
     */
    private double hit(Handle handle, Vector3d eye, Vector3d look) {
        Quaterniond inverse = new Quaterniond(frameOf(handle)).invert();
        Vector3d o = inverse.transform(new Vector3d(eye).sub(this.origin)).div(this.scale);
        Vector3d d = inverse.transform(new Vector3d(look));
        double t;
        switch (handle.kind) {
            case MOVE_AXIS -> t = hitArrow(handle.axis, o, d);
            case MOVE_PLANE -> t = hitSquare(handle.axis, o, d);
            default -> t = hitArc(handle.axis, o, d);
        }
        return t < 0 ? -1 : t * this.scale;
    }

    private static double component(Vector3d v, int axis) {
        return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
    }

    private static double hitArrow(int axis, Vector3d o, Vector3d d) {
        double b = component(d, axis);
        double denom = 1 - b * b;
        if (denom < 1e-6) {
            return -1;
        }
        double dd = d.dot(o);
        double u = (component(o, axis) - b * dd) / denom;
        u = Math.max(ARROW_FROM, Math.min(ARROW_TO, u));
        Vector3d point = new Vector3d();
        switch (axis) {
            case 0 -> point.set(u, 0, 0);
            case 1 -> point.set(0, u, 0);
            default -> point.set(0, 0, u);
        }
        double s = new Vector3d(point).sub(o).dot(d);
        if (s < 0) {
            return -1;
        }
        Vector3d closest = new Vector3d(o).fma(s, d);
        return closest.distance(point) <= ARROW_RADIUS ? s : -1;
    }

    private static double planeHit(int normal, Vector3d o, Vector3d d, Vector3d hit) {
        double dn = component(d, normal);
        if (Math.abs(dn) < 1e-6) {
            return -1;
        }
        double s = -component(o, normal) / dn;
        if (s < 0) {
            return -1;
        }
        hit.set(o).fma(s, d);
        return s;
    }

    private static double hitSquare(int normal, Vector3d o, Vector3d d) {
        Vector3d hit = new Vector3d();
        double s = planeHit(normal, o, d, hit);
        if (s < 0) {
            return -1;
        }
        double a = component(hit, (normal + 1) % 3);
        double b = component(hit, (normal + 2) % 3);
        double from = PLANE_FROM - 0.06;
        double to = PLANE_TO + 0.06;
        return a >= from && a <= to && b >= from && b <= to ? s : -1;
    }

    private static double hitArc(int axis, Vector3d o, Vector3d d) {
        Vector3d hit = new Vector3d();
        double s = planeHit(axis, o, d, hit);
        if (s < 0) {
            return -1;
        }
        double p = component(hit, (axis + 1) % 3);
        double q = component(hit, (axis + 2) % 3);
        if (p < -0.15 || q < -0.15) {
            return -1;
        }
        return Math.abs(Math.hypot(p, q) - RING_RADIUS) <= RING_THICKNESS ? s : -1;
    }

    private Handle pick(Vector3d eye, Vector3d look) {
        Handle best = null;
        double nearest = REACH;
        for (Handle handle : this.handles) {
            double t = hit(handle, eye, look);
            if (t >= 0 && t < nearest) {
                nearest = t;
                best = handle;
            }
        }
        return best;
    }

    // ---------- Every tick ----------

    /**
     * @return false when the tool has to close: its operator left, or the block is out of reach
     */
    boolean tick(int tick) {
        if (!this.player.isOnline() || !this.player.getWorld().equals(this.target.world)
                || this.player.getLocation().distanceSquared(new Location(this.target.world, this.origin.x, this.origin.y, this.origin.z)) > 64 * 64) {
            return false;
        }
        Vector3d eye = eye();
        Vector3d look = look();
        if (this.active == null) {
            Handle now = pick(eye, look);
            if (now != this.hovered) {
                light(this.hovered, false);
                light(now, true);
                this.hovered = now;
                this.readout = now == null ? "" : now.label;
                status();
            }
        } else {
            drag(eye, look);
        }
        if (tick % 20 == 0 || (this.active != null && tick % 3 == 0)) {
            status();
        }
        return true;
    }

    // ---------- Taking hold and dropping ----------

    /**
     * A left click: takes hold of the lit part, or drops the part held.
     */
    void click(int tick) {
        if (tick - this.lastClick < 4) {
            return;
        }
        this.lastClick = tick;
        if (this.active != null) {
            drop();
        } else if (this.hovered != null) {
            grab(this.hovered);
        }
    }

    private void grab(Handle handle) {
        Vector3d eye = eye();
        Vector3d look = look();
        System.arraycopy(this.target.shift, 0, this.shiftStart, 0, 5);
        this.dragOrigin.set(this.origin);
        Quaterniond frame = frameOf(handle);
        this.basePitch = this.target.pose(this.summonedPitch)[4] - this.target.shift[4];
        switch (handle.kind) {
            case MOVE_AXIS -> {
                frame.transform(this.dragAxis.set(unit(handle.axis)));
                Double t = axisParam(eye, look);
                if (t == null) {
                    return;
                }
                this.paramStart = t;
            }
            case MOVE_PLANE -> {
                frame.transform(this.dragAxis.set(unit(handle.axis)));
                frame.transform(this.dragU.set(unit((handle.axis + 1) % 3)));
                frame.transform(this.dragV.set(unit((handle.axis + 2) % 3)));
                if (!planePoint(eye, look, this.hitStart)) {
                    return;
                }
            }
            default -> {
                frame.transform(this.dragAxis.set(unit(handle.axis)));
                frame.transform(this.dragU.set(unit((handle.axis + 1) % 3)));
                frame.transform(this.dragV.set(unit((handle.axis + 2) % 3)));
                Double angle = angleOn(eye, look);
                if (angle == null) {
                    return;
                }
                this.angleLast = angle;
                this.angleTotal = 0;
            }
        }
        this.active = handle;
        this.renderer.beginShift(this.target);
        this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.6f);
        status();
    }

    private void drop() {
        this.active = null;
        this.renderer.commitShift(this.target);
        this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.0f);
        this.hovered = null;
        status();
    }

    private static Vector3d unit(int axis) {
        return new Vector3d(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
    }

    /**
     * Where the closest point of the line of sight to the axis taken stands along it, from the place it was taken.
     */
    private Double axisParam(Vector3d eye, Vector3d look) {
        double b = look.dot(this.dragAxis);
        double denom = 1 - b * b;
        if (denom < 1e-3) {
            return null;
        }
        Vector3d w0 = new Vector3d(eye).sub(this.dragOrigin);
        double dd = look.dot(w0);
        double ee = this.dragAxis.dot(w0);
        double t = (ee - b * dd) / denom;
        double s = t * b - dd;
        return s < 0 ? null : t;
    }

    private boolean planePoint(Vector3d eye, Vector3d look, Vector3d hit) {
        double dn = look.dot(this.dragAxis);
        if (Math.abs(dn) < 1e-3) {
            return false;
        }
        double s = new Vector3d(this.dragOrigin).sub(eye).dot(this.dragAxis) / dn;
        if (s < 0 || s > REACH) {
            return false;
        }
        hit.set(eye).fma(s, look);
        return true;
    }

    private Double angleOn(Vector3d eye, Vector3d look) {
        Vector3d hit = new Vector3d();
        if (!planePoint(eye, look, hit)) {
            return null;
        }
        hit.sub(this.dragOrigin);
        return Math.atan2(hit.dot(this.dragV), hit.dot(this.dragU));
    }

    // ---------- Dragging ----------

    private void drag(Vector3d eye, Vector3d look) {
        Handle handle = this.active;
        double x = this.shiftStart[0];
        double y = this.shiftStart[1];
        double z = this.shiftStart[2];
        double turn = this.shiftStart[3];
        double tilt = this.shiftStart[4];
        switch (handle.kind) {
            case MOVE_AXIS -> {
                Double t = axisParam(eye, look);
                if (t == null) {
                    return;
                }
                double amount = snap(t - this.paramStart, MOVE_STEPS[this.moveStep]);
                x += this.dragAxis.x * amount;
                y += this.dragAxis.y * amount;
                z += this.dragAxis.z * amount;
                this.readout = String.format(Locale.ROOT, "%s %+.3f", handle.label, amount);
            }
            case MOVE_PLANE -> {
                Vector3d hit = new Vector3d();
                if (!planePoint(eye, look, hit)) {
                    return;
                }
                hit.sub(this.hitStart);
                double a = snap(hit.dot(this.dragU), MOVE_STEPS[this.moveStep]);
                double b = snap(hit.dot(this.dragV), MOVE_STEPS[this.moveStep]);
                x += this.dragU.x * a + this.dragV.x * b;
                y += this.dragU.y * a + this.dragV.y * b;
                z += this.dragU.z * a + this.dragV.z * b;
                this.readout = String.format(Locale.ROOT, "%s %+.3f %+.3f", handle.label, a, b);
            }
            default -> {
                Double angle = angleOn(eye, look);
                if (angle == null) {
                    return;
                }
                double delta = angle - this.angleLast;
                while (delta > Math.PI) {
                    delta -= 2 * Math.PI;
                }
                while (delta < -Math.PI) {
                    delta += 2 * Math.PI;
                }
                this.angleLast = angle;
                this.angleTotal += delta;
                double degrees = snap(Math.toDegrees(this.angleTotal), TURN_STEPS[this.turnStep]);
                if (handle.axis == 1) {
                    // a turn to the right-hand side about the vertical axis lowers a yaw
                    turn -= degrees;
                } else {
                    tilt = Math.max(-90 - this.basePitch, Math.min(90 - this.basePitch, tilt + degrees));
                }
                this.readout = String.format(Locale.ROOT, "%s %+.1f°", handle.label, degrees);
            }
        }
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length > MAX_SHIFT) {
            double factor = MAX_SHIFT / length;
            x *= factor;
            y *= factor;
            z *= factor;
        }
        if (Math.abs(x - this.target.shift[0]) < 1e-9 && Math.abs(y - this.target.shift[1]) < 1e-9 && Math.abs(z - this.target.shift[2]) < 1e-9
                && Math.abs(turn - this.target.shift[3]) < 1e-9 && Math.abs(tilt - this.target.shift[4]) < 1e-9) {
            return;
        }
        this.renderer.shift(this.target, x, y, z, turn, tilt);
        double[] pose = this.target.pose(this.summonedPitch);
        this.origin.set(pose[0], pose[1], pose[2]);
        boolean turned = Math.abs(pose[3] - this.yaw) > 1e-6;
        this.yaw = pose[3];
        if (turned) {
            updateFrames();
            sendPoses();
        }
        sendPosition();
    }

    private double snap(double amount, double step) {
        return this.snapping ? Math.round(amount / step) * step : amount;
    }

    // ---------- Settings, while it is open ----------

    void scroll(boolean down) {
        if (this.active == null) {
            return;
        }
        int direction = down ? 1 : -1;
        if (this.active.kind == Kind.TURN) {
            this.turnStep = Math.max(0, Math.min(TURN_STEPS.length - 1, this.turnStep + direction));
        } else {
            this.moveStep = Math.max(0, Math.min(MOVE_STEPS.length - 1, this.moveStep + direction));
        }
        this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.4f);
        status();
    }

    void toggleSnapping() {
        this.snapping = !this.snapping;
        this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.1f);
        status();
    }

    void toggleSpace() {
        this.local = !this.local;
        updateFrames();
        sendPoses();
        this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 0.6f, 1.2f);
        status();
    }

    void status() {
        double step = this.active != null && this.active.kind == Kind.TURN ? TURN_STEPS[this.turnStep] : MOVE_STEPS[this.moveStep];
        actionBar(ChatColor.GOLD + "Gizmo " + ChatColor.WHITE + (this.readout.isEmpty() ? "aim at a part, left click to take it" : this.readout)
                + ChatColor.GRAY + "  |  step " + (this.snapping ? trimmed(step) : "off") + " (scroll)"
                + "  |  snap (Shift+F)"
                + "  |  " + (this.local ? "local" : "world") + " (F)");
    }

    private void actionBar(String text) {
        this.player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
    }

    private static String trimmed(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /**
     * Takes the tool away from its operator's client; a part still held is dropped where it is.
     */
    void close() {
        if (this.shown == 0) {
            return;
        }
        this.shown = 0;
        if (this.active != null) {
            this.active = null;
            this.renderer.commitShift(this.target);
        }
        int[] ids = new int[this.pieces.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = this.pieces.get(i).id;
        }
        if (this.player.isOnline()) {
            this.nms.send(this.player, this.nms.removePacket(ids));
            actionBar("");
        }
    }

    /**
     * Takes the tool away without touching the block: it is gone, or being read again.
     */
    void abandon() {
        if (this.shown == 0) {
            return;
        }
        this.active = null;
        close();
    }
}
