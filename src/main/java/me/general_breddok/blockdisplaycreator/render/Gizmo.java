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
import org.bukkit.entity.Interaction;
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
 * The tool a player moves one custom block shown through packets with: three arrows to move it along an axis,
 * three squares to move it on a plane, two arcs to turn it (around the vertical axis, and tipping it forward), a bar
 * whose knob sets its size, a button that puts it back where it was placed, and for administrators a button that
 * opens the panel of what is set on this block alone (see {@link AdminMenu}), one that puts its item in their hand,
 * and a switch that puts the tool on the block's click box.
 * <p>
 * The tool is made of display entities that exist on the player's client only. He aims at a part, which lights up,
 * and clicks to take hold of it: the block then follows where he looks, until he clicks again. A right click on a
 * part puts back what that part changes.
 * <p>
 * On the click box, the tool stands in its middle and draws its outline: the arrows and squares move it, a white cube
 * on its top sets its height, those on its sides its width (its base stays square), the red button gives it the
 * click boxes of its file back. What is drawn is the one click box of {@link BlockSettings#clickBox}, summoned again
 * when a part is dropped.
 * <p>
 * An invisible click box, on his client only, stays around his eyes while the tool is open: his client then sends
 * every right click, even with an empty hand and aiming at nothing, and no click of his reaches a block.
 */
final class Gizmo {

    /**
     * How far above the build limit the parts are summoned for the time of the reading, out of everyone's sight.
     */
    private static final int READING_HEIGHT = 1000;
    private static final double REACH = 16;
    /**
     * The largest the tool gets, around the largest models.
     */
    private static final double MAX_TOOL_SCALE = 6;
    private static final double MAX_SHIFT = 8;
    /**
     * How far, in blocks, the player may walk from the block before the tool closes by itself.
     */
    private static final double MAX_DISTANCE = 16;
    private static final double[] MOVE_STEPS = {0.0625, 0.125, 0.25, 0.5, 1};
    private static final double[] TURN_STEPS = {1, 2.5, 5, 15, 22.5, 45, 90};
    private static final Material[] COLORS = {Material.RED_CONCRETE, Material.LIME_CONCRETE, Material.LIGHT_BLUE_CONCRETE,
            Material.WHITE_CONCRETE, Material.LIGHT_GRAY_CONCRETE, Material.BLACK_CONCRETE, Material.COMMAND_BLOCK,
            Material.CARTOGRAPHY_TABLE, Material.TARGET};
    private static final int RED = 0;
    private static final int LIGHT_BLUE = 2;
    private static final int WHITE = 3;
    private static final int GREY = 4;
    private static final int BLACK = 5;
    private static final int COMMAND = 6;
    private static final int CARTOGRAPHY = 7;
    private static final int TARGET = 8;
    private static final double[] SCALE_STEPS = {0.01, 0.025, 0.05, 0.1};
    /**
     * The strip above the tool, turned to face the player wherever he stands: a bar whose knob sets the size of the
     * block (smallest at the left end, as placed in the middle, largest at the right end), and at its right the
     * button that puts the block back where it was placed.
     */
    private static final float PANEL_Y = 1.45f;
    private static final float SLIDER_FROM = -0.5f;
    private static final float SLIDER_TO = 0.5f;
    private static final double SLIDER_RADIUS = 0.1;
    private static final float KNOB = 0.15f;
    private static final float BUTTON_X = 0.85f;
    /**
     * Where the button of the admin panel stands, at the left end of the strip.
     */
    private static final float ADMIN_X = -0.85f;
    /**
     * Where the button that puts the block's item in the player's hand stands, left of the admin button, and the
     * switch to the click box, right of the red button.
     */
    private static final float COPY_X = -1.2f;
    private static final float HITBOX_X = 1.2f;
    /**
     * The side of the white cubes that size the click box, and how close to the middle of the tool they come at least
     * (a small box would bring them all together).
     */
    private static final float BOX_CUBE = 0.16f;
    private static final double BOX_CUBE_MIN = 0.3;
    /**
     * How thick the lines of the outline of the click box are, in blocks.
     */
    private static final float OUTLINE = 0.035f;
    /**
     * The narrowest a click box gets, in blocks.
     */
    private static final double BOX_MIN = 0.1;
    /**
     * How far, in degrees, the player may walk around the tool before the strip turns to face him again.
     */
    private static final double PANEL_TURN = 15;
    /**
     * The side of the click box kept around the player's eyes.
     */
    private static final float CATCHER_SIZE = 4;
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

    /**
     * What a part of the tool does. {@code COPY} puts the block's item in the player's hand, {@code HITBOX} switches the
     * tool between the model and its click box, {@code BOX_SIZE} is a white cube that sizes the click box along its axis
     * (width on x and z, height on y), {@code OUTLINE} the lines around the click box, which nothing takes hold of.
     */
    private enum Kind {MOVE_AXIS, MOVE_PLANE, TURN, SCALE, RESET, ADMIN, COPY, HITBOX, BOX_SIZE, OUTLINE}

    private static final class Piece {
        final int id;
        final Vector3f center;
        final Vector3f size;
        final Quaternionf orient;
        /**
         * Whether the piece is the knob of the size bar: it stands where the size of the block puts it.
         */
        final boolean knob;
        /**
         * Which line of the outline of the click box the piece is (0 to 11), -1 for another piece.
         */
        int edge = -1;
        List<Object> normal;

        Piece(int id, Vector3f center, Vector3f size, Quaternionf orient, boolean knob) {
            this.id = id;
            this.center = center;
            this.size = size;
            this.orient = orient;
            this.knob = knob;
        }
    }

    private static final class Handle {
        final Kind kind;
        /**
         * The axis moved along, the axis a plane is perpendicular to, the axis turned around.
         */
        final int axis;
        final String label;
        /**
         * What it is called while the tool is on the click box, null for the same.
         */
        String boxLabel;
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

    private final GizmoManager manager;
    private final DisplayRenderer renderer;
    private final NmsBridge nms;
    private final Player player;
    private final PlacedDisplay target;
    private Object type;
    private List<Object>[] normals;
    private final List<Handle> handles = new ArrayList<>();
    private final List<Piece> pieces = new ArrayList<>();
    private List<Object> lit;
    private double scale;
    /**
     * Whether its player is an administrator: the admin button, and a size bar from {@code gizmo.admin-size-min} to
     * {@code gizmo.admin-size-max}, logarithmic (each step of the knob multiplies the size by the same factor).
     */
    private final boolean admin;
    /**
     * The sizes at the ends of the bar: what its player may give the block, and the size it has if it is beyond.
     */
    private final double sizeMin;
    private final double sizeMax;
    private final boolean logBar;
    private final float summonedPitch;
    private Object catcherType;
    private List<Object> catcherValues;
    private final int catcherId;
    private final Vector3d catcherAt = new Vector3d();

    private final Vector3d origin = new Vector3d();
    private double yaw;
    private boolean local = true;
    private boolean snapping = true;
    private int moveStep = 1;
    private int turnStep = 3;
    private Quaterniond moveFrame = new Quaterniond();
    private Quaterniond turnFrame = new Quaterniond();
    private Quaterniond viewFrame = new Quaterniond();
    private double viewAngle = Double.NaN;

    private Handle hovered;
    private Handle active;
    private int lastClick = -100;
    private int lastRightClick = -100;
    private int shown;
    private double knobSize;

    // what a drag remembers from the moment the part was taken
    private final double[] shiftStart = new double[6];
    private int scaleStep = 2;
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
    private final Vector3d pivotStart = new Vector3d();
    /**
     * The point of the model a change of size keeps where it stands, in its own axes, and where it stood in the world
     * when the knob was taken.
     */
    private final Vector3d anchorLocal = new Vector3d();
    private final Vector3d anchorStart = new Vector3d();
    private final Vector3d centre;
    private final Vector3d lastEye = new Vector3d(Double.NaN, 0, 0);
    private final Vector3d lastLook = new Vector3d();

    // the click box, while the tool is on it
    /**
     * Whether the handles act on the block's click box rather than on its model.
     */
    private boolean boxMode;
    /**
     * The click box drawn and edited: width, height, floor, x, z, at the size the file gives (see
     * {@link BlockSettings#clickBox}). It is the block's once a part is dropped.
     */
    private double[] box;
    private final double[] boxStart = new double[5];
    /**
     * Where the click box stands in the world: smallest x, y, z then largest.
     */
    private final double[] boxBounds = new double[6];
    /**
     * What takes a move in the world into a change of where the click box stands from its block (x, floor, z),
     * worked out when a part is taken.
     */
    private org.joml.Matrix3d boxInverse = new org.joml.Matrix3d();
    private Handle hitboxSwitch;

    /**
     * Shows the tool around a block, to its player only.
     */
    Gizmo(GizmoManager manager, DisplayRenderer renderer, NmsBridge nms, Player player, PlacedDisplay target) {
        this.manager = manager;
        this.renderer = renderer;
        this.nms = nms;
        this.player = player;
        this.target = target;
        this.summonedPitch = target.model.elements.get(0).pitch();

        this.centre = target.model.centre();
        this.scale = toolScale();
        this.admin = player.hasPermission(GizmoManager.ADMIN_PERMISSION);
        double current = target.scale();
        double min = manager.setting(this.admin ? "gizmo.admin-size-min" : "gizmo.size-min", this.admin ? 0.1 : 0.75);
        double max = manager.setting(this.admin ? "gizmo.admin-size-max" : "gizmo.size-max", this.admin ? 10 : 1.25);
        this.sizeMin = Math.max(0.01, Math.min(min, current));
        this.sizeMax = Math.max(Math.max(max, current), this.sizeMin * 1.01);
        this.logBar = this.admin;

        read(target.world, target.chunkX(), target.chunkZ());

        build();
        double[] pose = target.pose(this.summonedPitch);
        // the tool stands in the middle of the model, which is also what the model turns around
        this.origin.set(target.pivot(this.summonedPitch));
        this.yaw = pose[3];
        this.knobSize = target.scale();
        updateFrames();
        faceThePlayer(eye());

        List<Object> packets = new ArrayList<>();
        for (Piece piece : this.pieces) {
            packets.add(nms.spawnPacket(piece.id, UUID.randomUUID(), this.origin.x, this.origin.y, this.origin.z, 0f, 0f, this.type));
            packets.add(nms.dataPacket(piece.id, withPose(piece, false)));
        }
        this.catcherId = nms.nextEntityId();
        Vector3d eye = eye();
        this.catcherAt.set(eye);
        packets.add(nms.spawnPacket(this.catcherId, UUID.randomUUID(), eye.x, eye.y - CATCHER_SIZE / 2, eye.z, 0f, 0f, this.catcherType));
        packets.add(nms.dataPacket(this.catcherId, this.catcherValues));
        nms.send(player, nms.bundle(packets));
        this.shown = 1;
    }

    PlacedDisplay target() {
        return this.target;
    }

    /**
     * How big the tool is drawn: after the largest side of what the block draws, at the size it has now, so that the
     * tool stands around it whatever the model (a flat item stretched along its thickness counts as thin).
     */
    private double toolScale() {
        float[] box = this.target.model.bounds();
        double size = Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2])) * this.target.scale();
        return Math.max(1.0, Math.min(MAX_TOOL_SCALE, size * 0.7));
    }

    /**
     * Where along the size bar (0 at its left end, 1 at its right end) a size stands.
     */
    private double toBar(double size) {
        double s = Math.max(this.sizeMin, Math.min(this.sizeMax, size));
        return this.logBar ? Math.log(s / this.sizeMin) / Math.log(this.sizeMax / this.sizeMin) : (s - this.sizeMin) / (this.sizeMax - this.sizeMin);
    }

    private double fromBar(double at) {
        double u = Math.max(0, Math.min(1, at));
        return this.logBar ? this.sizeMin * Math.pow(this.sizeMax / this.sizeMin, u) : this.sizeMin + u * (this.sizeMax - this.sizeMin);
    }

    /**
     * How far the player may stand from the tool and still aim at it.
     */
    private double reach() {
        return Math.max(REACH, this.scale * 8);
    }

    /**
     * Brings the tool to its block after something else changed it (the admin panel): where it stands, its turn and
     * its size, and the tool's own size.
     */
    void follow() {
        if (this.active != null) {
            return;
        }
        if (this.boxMode) {
            // its click box may have been changed too (the hitbox page, a typed value)
            double[] stored = storedBox();
            if (stored != null) {
                this.box = stored;
            }
        }
        refreshTool();
        rescale();
        unhover();
        status();
    }

    /**
     * Draws the tool at the size the block now has, once it has grown or shrunk enough for it to matter.
     */
    private void rescale() {
        double next = toolScale();
        if (Math.abs(next - this.scale) / this.scale < 0.2) {
            return;
        }
        this.scale = next;
        sendPoses();
        this.lastEye.set(Double.NaN, 0, 0);
    }

    boolean isDragging() {
        return this.active != null;
    }

    /**
     * Whether an entity id is one of the entities of the tool, which exist on its player's client only.
     */
    boolean owns(int entityId) {
        if (entityId == this.catcherId) {
            return true;
        }
        for (Piece piece : this.pieces) {
            if (piece.id == entityId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads, from entities summoned for an instant, what a client is sent for a coloured part, for a lit one, and
     * for the click box around the player's eyes.
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
        Interaction catcher = world.spawn(where, Interaction.class, entity -> {
            entity.setPersistent(false);
            entity.setInteractionWidth(CATCHER_SIZE);
            entity.setInteractionHeight(CATCHER_SIZE);
            entity.setResponsive(false);
        });
        try {
            Object handle = this.nms.handle(catcher);
            this.catcherType = this.nms.type(handle);
            this.catcherValues = new ArrayList<>(this.nms.syncedValues(handle));
        } finally {
            catcher.remove();
        }
    }

    // ---------- Shapes ----------

    private void build() {
        for (int axis = 0; axis < 3; axis++) {
            Handle arrow = new Handle(Kind.MOVE_AXIS, axis, Texts.get("gizmo.label.move", "%axis%", AXES[axis]), axis);
            arrow.boxLabel = Texts.get("gizmo.label.box-move", "%axis%", AXES[axis]);
            float middle = (ARROW_FROM + 0.84f) / 2;
            piece(arrow, along(axis, middle, 0.84f - ARROW_FROM, 0.045f), new Quaternionf());
            piece(arrow, along(axis, 0.92f, 0.16f, 0.13f), new Quaternionf());
            this.handles.add(arrow);
        }
        for (int normal = 0; normal < 3; normal++) {
            int a = (normal + 1) % 3;
            int b = (normal + 2) % 3;
            Handle square = new Handle(Kind.MOVE_PLANE, normal, Texts.get("gizmo.label.move", "%axis%", AXES[a] + "/" + AXES[b]), normal);
            square.boxLabel = Texts.get("gizmo.label.box-move", "%axis%", AXES[a] + "/" + AXES[b]);
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
        if (this.nms.canAnimate()) {
            // a grey bar, a stop at each end, a mark at the size the block was placed with, and the white knob
            Handle slider = new Handle(Kind.SCALE, 0, Texts.get("gizmo.label.size"), WHITE);
            float middle = (SLIDER_FROM + SLIDER_TO) / 2;
            piece(slider, new Vector3f[]{new Vector3f(middle, PANEL_Y, 0), new Vector3f(SLIDER_TO - SLIDER_FROM, 0.035f, 0.035f)}, new Quaternionf(), GREY, false);
            for (float at : new float[]{SLIDER_FROM, SLIDER_TO}) {
                piece(slider, new Vector3f[]{new Vector3f(at, PANEL_Y, 0), new Vector3f(0.025f, 0.11f, 0.11f)}, new Quaternionf(), GREY, false);
            }
            if (1 >= this.sizeMin && 1 <= this.sizeMax) {
                float placed = (float) (SLIDER_FROM + toBar(1) * (SLIDER_TO - SLIDER_FROM));
                piece(slider, new Vector3f[]{new Vector3f(placed, PANEL_Y, 0), new Vector3f(0.02f, 0.07f, 0.07f)}, new Quaternionf(), GREY, false);
            }
            piece(slider, new Vector3f[]{new Vector3f(), new Vector3f(KNOB, KNOB, KNOB)}, new Quaternionf(), WHITE, true);
            this.handles.add(slider);
        }
        // a red button on a black plate, turned to the player
        Handle button = new Handle(Kind.RESET, 0, Texts.get("gizmo.label.reset"), RED);
        button.boxLabel = Texts.get("gizmo.label.box-reset");
        piece(button, new Vector3f[]{new Vector3f(BUTTON_X, PANEL_Y, 0), new Vector3f(0.30f, 0.30f, 0.12f)}, new Quaternionf(), BLACK, false);
        piece(button, new Vector3f[]{new Vector3f(BUTTON_X, PANEL_Y, 0.09f), new Vector3f(0.19f, 0.19f, 0.08f)}, new Quaternionf(), RED, false);
        this.handles.add(button);
        if (this.admin) {
            // a command block on a black plate, at the other end of the strip
            Handle panel = new Handle(Kind.ADMIN, 0, Texts.get("gizmo.label.admin"), COMMAND);
            piece(panel, new Vector3f[]{new Vector3f(ADMIN_X, PANEL_Y, 0), new Vector3f(0.30f, 0.30f, 0.12f)}, new Quaternionf(), BLACK, false);
            piece(panel, new Vector3f[]{new Vector3f(ADMIN_X, PANEL_Y, 0.09f), new Vector3f(0.19f, 0.19f, 0.08f)}, new Quaternionf(), COMMAND, false);
            this.handles.add(panel);
            // a cartography table (the table that copies maps) left of it: the block's item in the hand
            Handle copy = new Handle(Kind.COPY, 0, Texts.get("gizmo.label.copy"), CARTOGRAPHY);
            piece(copy, new Vector3f[]{new Vector3f(COPY_X, PANEL_Y, 0), new Vector3f(0.30f, 0.30f, 0.12f)}, new Quaternionf(), BLACK, false);
            piece(copy, new Vector3f[]{new Vector3f(COPY_X, PANEL_Y, 0.09f), new Vector3f(0.19f, 0.19f, 0.08f)}, new Quaternionf(), CARTOGRAPHY, false);
            this.handles.add(copy);
            // a target right of the red button: the tool on the click box (its plate turns light blue), or back on the model
            Handle hitbox = new Handle(Kind.HITBOX, 0, Texts.get("gizmo.label.hitbox"), TARGET);
            hitbox.boxLabel = Texts.get("gizmo.label.model");
            piece(hitbox, new Vector3f[]{new Vector3f(HITBOX_X, PANEL_Y, 0), new Vector3f(0.30f, 0.30f, 0.12f)}, new Quaternionf(), BLACK, false);
            piece(hitbox, new Vector3f[]{new Vector3f(HITBOX_X, PANEL_Y, 0.09f), new Vector3f(0.19f, 0.19f, 0.08f)}, new Quaternionf(), TARGET, false);
            this.handles.add(hitbox);
            this.hitboxSwitch = hitbox;
            // the white cubes on the sides and the top of the click box, and its outline
            for (int axis = 0; axis < 3; axis++) {
                Handle cube = new Handle(Kind.BOX_SIZE, axis, Texts.get(axis == 1 ? "gizmo.label.box-height" : "gizmo.label.box-width"), WHITE);
                piece(cube, new Vector3f[]{new Vector3f(), new Vector3f(BOX_CUBE, BOX_CUBE, BOX_CUBE)}, new Quaternionf());
                this.handles.add(cube);
            }
            Handle outline = new Handle(Kind.OUTLINE, 0, "", WHITE);
            for (int edge = 0; edge < 12; edge++) {
                piece(outline, new Vector3f[]{new Vector3f(), new Vector3f()}, new Quaternionf());
                outline.pieces.get(edge).edge = edge;
            }
            this.handles.add(outline);
        }
        // pitch (around X, tipping the block forward) and yaw (around Y)
        for (int axis = 0; axis < 2; axis++) {
            Handle ring = new Handle(Kind.TURN, axis, Texts.get(axis == 1 ? "gizmo.label.turn" : "gizmo.label.tilt"), axis);
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
        piece(handle, centerAndSize, orient, handle.color, false);
    }

    private void piece(Handle handle, Vector3f[] centerAndSize, Quaternionf orient, int color, boolean knob) {
        Piece piece = new Piece(this.nms.nextEntityId(), centerAndSize[0], centerAndSize[1], orient, knob);
        piece.normal = this.normals[color];
        handle.pieces.add(piece);
        this.pieces.add(piece);
    }

    private Handle owner(Piece piece) {
        for (Handle handle : this.handles) {
            if (handle.pieces.contains(piece)) {
                return handle;
            }
        }
        throw new IllegalStateException("a piece without its part");
    }

    /**
     * Where the knob of the size bar stands, in the tool, for the size the block has.
     */
    private Vector3f knobAt() {
        float u = (float) (SLIDER_FROM + toBar(this.target.scale()) * (SLIDER_TO - SLIDER_FROM));
        return new Vector3f(u, PANEL_Y, 0);
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
        Handle handle = owner(piece);
        List<Object> values = new ArrayList<>(lit ? this.lit : piece.normal);
        if (!shows(handle)) {
            // the parts of the other mode stay on the client, drawn at no size
            values.addAll(this.nms.poseValues(new Vector3f(), new Quaternionf(), new Vector3f(), new Quaternionf(), 0));
            return values;
        }
        if (handle.kind == Kind.OUTLINE) {
            Vector3f[] line = outlineLine(piece.edge);
            values.addAll(this.nms.poseValues(line[0].sub(new Vector3f(line[1]).mul(0.5f)), new Quaternionf(), line[1], new Quaternionf(), 0));
            return values;
        }
        Quaternionf frame = single(frameOf(handle));
        Vector3f at = handle.kind == Kind.BOX_SIZE ? cubeAt(handle.axis) : piece.knob ? knobAt() : piece.center;
        Vector3f center = frame.transform(new Vector3f(at).mul((float) this.scale));
        Quaternionf orient = new Quaternionf(frame).mul(piece.orient);
        Vector3f size = new Vector3f(piece.size).mul((float) this.scale);
        // a block display is drawn from its corner: the box is centred by moving it back by half its size
        Vector3f half = orient.transform(new Vector3f(size).mul(0.5f));
        Vector3f translation = center.sub(half);
        values.addAll(this.nms.poseValues(translation, orient, size, new Quaternionf(), 0));
        return values;
    }

    /**
     * Whether a part is drawn in the mode the tool is in: the arcs and the size bar on the model only, the cubes and the
     * outline on the click box only.
     */
    private boolean shows(Handle handle) {
        return switch (handle.kind) {
            case TURN, SCALE -> !this.boxMode;
            case BOX_SIZE, OUTLINE -> this.boxMode;
            default -> true;
        };
    }

    /**
     * Where one line of the outline of the click box stands from the tool, in the world's axes: its middle, then its size.
     */
    private Vector3f[] outlineLine(int edge) {
        double[] b = this.boxBounds;
        int along = edge / 4;
        int corner = edge % 4;
        double[] middle = new double[3];
        float[] size = {OUTLINE, OUTLINE, OUTLINE};
        int first = (along + 1) % 3;
        int second = (along + 2) % 3;
        middle[along] = (b[along] + b[along + 3]) / 2;
        middle[first] = (corner & 1) == 0 ? b[first] : b[first + 3];
        middle[second] = (corner & 2) == 0 ? b[second] : b[second + 3];
        size[along] = (float) (b[along + 3] - b[along]) + OUTLINE;
        return new Vector3f[]{new Vector3f((float) (middle[0] - this.origin.x), (float) (middle[1] - this.origin.y), (float) (middle[2] - this.origin.z)),
                new Vector3f(size[0], size[1], size[2])};
    }

    /**
     * Where the white cube that sizes the click box along an axis of the tool stands, in the tool: on the side of the box
     * that axis points to, never closer to the middle of the box than {@link #BOX_CUBE_MIN}. The tool stays where it is
     * while a cube is held: the middle of the box is taken where it now is.
     */
    private Vector3f cubeAt(int axis) {
        Vector3d direction = this.moveFrame.transform(unit(axis));
        double reach = (Math.abs(direction.x) * (this.boxBounds[3] - this.boxBounds[0]) + Math.abs(direction.y) * (this.boxBounds[4] - this.boxBounds[1])
                + Math.abs(direction.z) * (this.boxBounds[5] - this.boxBounds[2])) / 2;
        double at = Math.max(BOX_CUBE_MIN, reach / this.scale);
        Vector3d middle = this.moveFrame.transformInverse(boxMiddle().sub(this.origin)).div(this.scale);
        return new Vector3f((float) (middle.x + (axis == 0 ? at : 0)), (float) (middle.y + (axis == 1 ? at : 0)), (float) (middle.z + (axis == 2 ? at : 0)));
    }

    /**
     * The middle of the click box in the world.
     */
    private Vector3d boxMiddle() {
        double[] b = this.boxBounds;
        return new Vector3d((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
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

    /**
     * Slides the knob of the size bar to the size the block has.
     */
    private void sendKnob() {
        sendPieces(false);
    }

    /**
     * Sends again where the knob stands, or where every piece of the strip stands.
     */
    private void sendPieces(boolean strip) {
        List<Object> packets = new ArrayList<>();
        for (Handle handle : this.handles) {
            for (Piece piece : handle.pieces) {
                if (strip ? onStrip(handle) : piece.knob) {
                    packets.add(this.nms.dataPacket(piece.id, withPose(piece, handle.lit)));
                }
            }
        }
        if (!packets.isEmpty()) {
            this.nms.send(this.player, this.nms.bundle(packets));
        }
    }

    private static boolean onStrip(Handle handle) {
        return switch (handle.kind) {
            case SCALE, RESET, ADMIN, COPY, HITBOX -> true;
            default -> false;
        };
    }

    /**
     * Sends again where the outline of the click box and its cubes stand.
     */
    private void sendBox() {
        List<Object> packets = new ArrayList<>();
        for (Handle handle : this.handles) {
            if (handle.kind == Kind.BOX_SIZE || handle.kind == Kind.OUTLINE) {
                for (Piece piece : handle.pieces) {
                    packets.add(this.nms.dataPacket(piece.id, withPose(piece, handle.lit)));
                }
            }
        }
        if (!packets.isEmpty()) {
            this.nms.send(this.player, this.nms.bundle(packets));
        }
    }

    /**
     * Turns the strip to the player when he has walked around the tool.
     *
     * @return whether it turned
     */
    private boolean faceThePlayer(Vector3d eye) {
        double angle = Math.toDegrees(Math.atan2(eye.x - this.origin.x, eye.z - this.origin.z));
        if (!Double.isNaN(this.viewAngle)) {
            double apart = Math.abs(angle - this.viewAngle) % 360;
            if (Math.min(apart, 360 - apart) < PANEL_TURN) {
                return false;
            }
        }
        this.viewAngle = angle;
        this.viewFrame = new Quaterniond().rotationY(Math.toRadians(angle));
        return true;
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
        return switch (handle.kind) {
            case TURN -> this.turnFrame;
            case SCALE, RESET, ADMIN, COPY, HITBOX -> this.viewFrame;
            case OUTLINE -> new Quaterniond();
            default -> this.moveFrame;
        };
    }

    /**
     * How far along the ray the part is hit, in blocks, or -1.
     */
    private double hit(Handle handle, Vector3d eye, Vector3d look) {
        if (!shows(handle) || handle.kind == Kind.OUTLINE) {
            return -1;
        }
        Quaterniond inverse = new Quaterniond(frameOf(handle)).invert();
        Vector3d o = inverse.transform(new Vector3d(eye).sub(this.origin)).div(this.scale);
        Vector3d d = inverse.transform(new Vector3d(look));
        double t;
        switch (handle.kind) {
            case MOVE_AXIS -> t = hitArrow(handle.axis, o, d);
            case MOVE_PLANE -> t = hitSquare(handle.axis, o, d);
            case SCALE -> t = hitSegment(o, d, new Vector3d(SLIDER_FROM - 0.05, PANEL_Y, 0), new Vector3d(SLIDER_TO + 0.05, PANEL_Y, 0), SLIDER_RADIUS);
            case RESET -> t = hitButton(o, d, BUTTON_X);
            case ADMIN -> t = hitButton(o, d, ADMIN_X);
            case COPY -> t = hitButton(o, d, COPY_X);
            case HITBOX -> t = hitButton(o, d, HITBOX_X);
            case BOX_SIZE -> {
                Vector3f at = cubeAt(handle.axis);
                double half = BOX_CUBE / 2 + 0.03;
                t = hitBox(o, d, new double[]{at.x - half, at.y - half, at.z - half}, new double[]{at.x + half, at.y + half, at.z + half});
            }
            default -> t = hitArc(handle.axis, o, d);
        }
        return t < 0 ? -1 : t * this.scale;
    }

    /**
     * Where the ray enters a button of the strip standing at that place along it, or -1.
     */
    private static double hitButton(Vector3d o, Vector3d d, float x) {
        return hitBox(o, d, new double[]{x - 0.17, PANEL_Y - 0.17, -0.08}, new double[]{x + 0.17, PANEL_Y + 0.17, 0.15});
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

    /**
     * Where the ray passes within a radius of a segment, or -1.
     */
    private static double hitSegment(Vector3d o, Vector3d d, Vector3d from, Vector3d to, double radius) {
        Vector3d u = new Vector3d(to).sub(from);
        double length = u.length();
        u.div(length);
        Vector3d w = new Vector3d(o).sub(from);
        double b = d.dot(u);
        double denom = 1 - b * b;
        double t = denom < 1e-6 ? 0 : (w.dot(u) - b * w.dot(d)) / denom;
        t = Math.max(0, Math.min(length, t));
        Vector3d point = new Vector3d(from).fma(t, u);
        double s = new Vector3d(point).sub(o).dot(d);
        if (s < 0) {
            return -1;
        }
        return new Vector3d(o).fma(s, d).distance(point) <= radius ? s : -1;
    }

    /**
     * Where the ray enters a straight box, or -1.
     */
    private static double hitBox(Vector3d o, Vector3d d, double[] min, double[] max) {
        double near = 0;
        double far = Double.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            double start = component(o, axis);
            double direction = component(d, axis);
            if (Math.abs(direction) < 1e-9) {
                if (start < min[axis] || start > max[axis]) {
                    return -1;
                }
                continue;
            }
            double a = (min[axis] - start) / direction;
            double b = (max[axis] - start) / direction;
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
            if (near > far) {
                return -1;
            }
        }
        return near;
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
        double nearest = reach();
        Handle cube = null;
        double cubeAt = reach();
        for (Handle handle : this.handles) {
            double t = hit(handle, eye, look);
            if (onStrip(handle) && handle.kind != Kind.SCALE && t >= 0 && t < reach()) {
                // a button stays within reach when a part of the tool stands in front of it
                return handle;
            }
            if (handle.kind == Kind.BOX_SIZE && t >= 0 && t < cubeAt) {
                // a cube of the click box often stands on an arrow: aimed at, it is the cube that is taken
                cubeAt = t;
                cube = handle;
            }
            if (t >= 0 && t < nearest) {
                nearest = t;
                best = handle;
            }
        }
        return cube != null ? cube : best;
    }

    /**
     * What a part is called in the mode the tool is in.
     */
    private String labelOf(Handle handle) {
        return this.boxMode && handle.boxLabel != null ? handle.boxLabel : handle.label;
    }

    // ---------- Every tick ----------

    /**
     * @return null while the tool stays open, else why it has to close: "left" (its player went away) or "far" (he
     * walked too far from the block)
     */
    String tick(int tick) {
        if (!this.player.isOnline() || !this.player.getWorld().equals(this.target.world)) {
            return "left";
        }
        Vector3d eye = eye();
        double reach = Math.max(MAX_DISTANCE, this.scale * 8);
        if (eye.distanceSquared(this.origin) > reach * reach) {
            return "far";
        }
        if (eye.distanceSquared(this.catcherAt) > 0.25) {
            // the click box follows the player's eyes, which never come near its sides
            this.catcherAt.set(eye);
            this.nms.send(this.player, this.nms.teleportPacket(this.catcherId, eye.x, eye.y - CATCHER_SIZE / 2, eye.z, 0f, 0f));
        }
        Vector3d look = look();
        if (this.active == null && faceThePlayer(eye)) {
            sendPieces(true);
            this.lastEye.set(Double.NaN, 0, 0);
        }
        if (this.active == null) {
            // the part aimed at only changes when the player looks elsewhere, or the tool moves
            if (!eye.equals(this.lastEye, 1e-6) || !look.equals(this.lastLook, 1e-6)) {
                this.lastEye.set(eye);
                this.lastLook.set(look);
                Handle now = pick(eye, look);
                if (now != this.hovered) {
                    light(this.hovered, false);
                    light(now, true);
                    this.hovered = now;
                    this.readout = now == null ? "" : labelOf(now);
                    status();
                }
            }
        } else {
            drag(eye, look);
        }
        if (tick % 20 == 0 || (this.active != null && tick % 3 == 0)) {
            status();
        }
        return null;
    }

    // ---------- Taking hold and dropping ----------

    /**
     * A left click: takes hold of the lit part, or drops the part held; on the button, puts the block back.
     */
    void click(int tick) {
        if (tick - this.lastClick < 4) {
            return;
        }
        this.lastClick = tick;
        this.lastEye.set(Double.NaN, 0, 0);
        if (this.active != null) {
            drop();
        } else if (this.hovered != null) {
            if (this.hovered.kind == Kind.ADMIN) {
                unhover();
                this.manager.openAdmin(this.player, this.target);
            } else if (this.hovered.kind == Kind.COPY) {
                copyItem();
            } else if (this.hovered.kind == Kind.HITBOX) {
                boxMode(!this.boxMode);
            } else if (this.hovered.kind == Kind.RESET) {
                if (this.boxMode) {
                    resetBox();
                } else {
                    reset(this.hovered);
                }
            } else {
                grab(this.hovered);
            }
        }
    }

    /**
     * A right click: puts back what the part aimed at (or held) changes, as it was when the block was placed.
     */
    void rightClick(int tick) {
        if (tick - this.lastRightClick < 4) {
            return;
        }
        this.lastRightClick = tick;
        Handle handle = this.active != null ? this.active : this.hovered;
        if (handle == null || handle.kind == Kind.ADMIN || handle.kind == Kind.COPY || handle.kind == Kind.HITBOX) {
            return;
        }
        if (this.boxMode) {
            if (this.active != null) {
                // a part held goes back as it was taken, then what it changes is put back
                this.active = null;
                this.box = this.boxStart.clone();
                boxChanged(true, true);
            }
            if (handle.kind == Kind.RESET) {
                resetBox();
            } else {
                resetBoxPart(handle);
            }
            return;
        }
        if (this.active != null) {
            // a part held goes back as it was taken, then what it changes is put back
            this.active = null;
            restore(this.shiftStart);
        }
        reset(handle);
    }

    /**
     * Puts the item of the block in the player's hand: the one he has, else a new one.
     */
    private void copyItem() {
        boolean held = this.manager.copyItem(this.player, this.target);
        this.readout = Texts.get(held ? "gizmo.readout.copied" : "gizmo.readout.copy-failed");
        this.player.playSound(this.player, held ? Sound.ENTITY_ITEM_PICKUP : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, held ? 1.2f : 0.7f);
        status();
    }

    private void grab(Handle handle) {
        if (this.boxMode) {
            grabBox(handle);
            return;
        }
        Vector3d eye = eye();
        Vector3d look = look();
        System.arraycopy(this.target.shift, 0, this.shiftStart, 0, 6);
        this.dragOrigin.set(this.origin);
        this.pivotStart.set(this.origin);
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
            case SCALE -> {
                // the block grows from where it stands (see PlacedDisplay#sizeAnchor), the knob slides along the bar
                this.anchorLocal.set(this.target.sizeAnchor());
                this.anchorStart.set(this.target.worldPoint(this.anchorLocal));
                this.dragOrigin.y += PANEL_Y * this.scale;
                frame.transform(this.dragAxis.set(1, 0, 0));
                Double t = axisParam(eye, look);
                if (t == null) {
                    return;
                }
                this.paramStart = t;
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
        Handle held = this.active;
        this.active = null;
        light(held, false);
        unhover();
        if (this.boxMode) {
            dropBox(held);
            return;
        }
        if (!mayStay()) {
            // out of what the player may touch (another island, a protected region): it goes back
            restore(this.shiftStart);
            this.renderer.commitShift(this.target);
            refused();
            return;
        }
        this.renderer.commitShift(this.target);
        if (held.kind == Kind.SCALE) {
            // the tool goes to the middle of the block at its new size
            refreshTool();
            rescale();
        }
        this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.0f);
        status();
    }

    /**
     * Whether the player may leave the block where it now stands.
     */
    private boolean mayStay() {
        double[] pose = this.target.pose(this.summonedPitch);
        return this.manager.mayEditAt(this.player, this.target, new Location(this.target.world, this.origin.x, pose[1], this.origin.z));
    }

    private void refused() {
        this.manager.say(this.player, "gizmo.refused");
        this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
        status();
    }

    /**
     * Puts back what a part changes as it was when the block was placed, the rest staying as it is: the place along
     * an arrow's axis or on a square's plane (the middle of the model goes back there), the turn of an arc, the size
     * of the bar. The button puts back everything.
     */
    private void reset(Handle handle) {
        double[] before = this.target.shift.clone();
        double[] next;
        if (handle.kind == Kind.RESET) {
            next = new double[]{0, 0, 0, 0, 0, 1};
        } else {
            double[] base = this.target.placedPose(this.summonedPitch);
            double turn = before[3];
            double tilt = before[4];
            double size = before[5];
            // where the middle of the model stood when it was placed, and how far it is from there
            Vector3d home = PlacedDisplay.orientation(base[3], base[4]).transform(new Vector3d(this.centre)).add(base[0], base[1], base[2]);
            Vector3d offset = this.target.pivot(this.summonedPitch).sub(home);
            Quaterniond frame = frameOf(handle);
            switch (handle.kind) {
                case MOVE_AXIS -> {
                    Vector3d axis = frame.transform(unit(handle.axis));
                    offset.fma(-offset.dot(axis), axis);
                }
                case MOVE_PLANE -> {
                    Vector3d normal = frame.transform(unit(handle.axis));
                    offset.set(normal.mul(offset.dot(normal)));
                }
                case SCALE -> size = 1;
                default -> {
                    if (handle.axis == 1) {
                        turn = 0;
                    } else {
                        tilt = 0;
                    }
                }
            }
            // the model is put where its middle stays at that place
            Vector3d pivot = home.add(offset);
            Vector3d moved = PlacedDisplay.orientation(base[3] + turn, base[4] + tilt).transform(new Vector3d(this.centre).mul(size));
            next = new double[]{pivot.x - moved.x - base[0], pivot.y - moved.y - base[1], pivot.z - moved.z - base[2], turn, tilt, size};
            for (int i = 0; i < 5; i++) {
                if (Math.abs(next[i]) < 1e-6) {
                    next[i] = 0;
                }
            }
        }
        light(handle, false);
        unhover();
        boolean same = true;
        for (int i = 0; i < 6; i++) {
            same &= Math.abs(next[i] - before[i]) < 1e-6;
        }
        if (same) {
            this.renderer.commitShift(this.target);
            this.manager.say(this.player, handle.kind == Kind.RESET ? "gizmo.already-at-origin" : "gizmo.nothing-to-reset");
            this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
            status();
            return;
        }
        this.renderer.beginShift(this.target);
        restore(next);
        if (!mayStay()) {
            restore(before);
            this.renderer.commitShift(this.target);
            refused();
            return;
        }
        this.renderer.commitShift(this.target);
        this.readout = handle.kind == Kind.RESET ? Texts.get("gizmo.readout.reset-all") : Texts.get("gizmo.readout.reset", "%part%", handle.label);
        this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 0.8f);
        status();
    }

    /**
     * Forgets the part aimed at and puts its light out: the next tick lights what the player then aims at.
     */
    private void unhover() {
        light(this.hovered, false);
        this.hovered = null;
        this.lastEye.set(Double.NaN, 0, 0);
    }

    /**
     * Puts the block back to a shift, and the tool around it.
     */
    private void restore(double[] shift) {
        this.renderer.shift(this.target, shift[0], shift[1], shift[2], shift[3], shift[4], shift[5]);
        refreshTool();
    }

    /**
     * Brings the tool to the middle of the block where it now stands, turned as the block is.
     */
    private void refreshTool() {
        double[] pose = this.target.pose(this.summonedPitch);
        if (this.boxMode) {
            // on the click box: in its middle, where the model and the box now put it
            this.yaw = pose[3];
            updateFrames();
            this.knobSize = this.target.scale();
            measureBox();
            this.origin.set(boxMiddle());
            sendPoses();
            sendPosition();
            return;
        }
        if (this.active == null || this.active.kind != Kind.SCALE) {
            // while the knob is held the tool stays where it is, under the line of sight that slides it
            this.origin.set(this.target.pivot(this.summonedPitch));
        }
        boolean turned = Math.abs(pose[3] - this.yaw) > 1e-6;
        this.yaw = pose[3];
        if (turned) {
            updateFrames();
            sendPoses();
        } else if (Math.abs(this.target.scale() - this.knobSize) > 1e-9) {
            sendKnob();
        }
        this.knobSize = this.target.scale();
        sendPosition();
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
        if (this.boxMode) {
            dragBox(handle, eye, look);
            return;
        }
        double x = this.shiftStart[0];
        double y = this.shiftStart[1];
        double z = this.shiftStart[2];
        double turn = this.shiftStart[3];
        double tilt = this.shiftStart[4];
        double size = this.shiftStart[5];
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
            case SCALE -> {
                Double t = axisParam(eye, look);
                if (t == null) {
                    return;
                }
                // the knob follows the line of sight along the bar, whose length goes from the smallest size to the largest
                double span = this.scale * (SLIDER_TO - SLIDER_FROM);
                size = fromBar(toBar(this.shiftStart[5]) + (t - this.paramStart) / span);
                if (this.snapping) {
                    size = Math.max(this.sizeMin, Math.min(this.sizeMax, Math.round(size / SCALE_STEPS[this.scaleStep]) * SCALE_STEPS[this.scaleStep]));
                }
                this.readout = String.format(Locale.ROOT, "%s %d %%", handle.label, Math.round(size * 100));
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
        if (handle.kind == Kind.TURN) {
            // the model turns around the middle of what it draws: its position follows so that this point stays put
            double[] base = this.target.placedPose(this.summonedPitch);
            Vector3d moved = PlacedDisplay.orientation(base[3] + turn, base[4] + tilt).transform(new Vector3d(this.centre).mul(size));
            x = this.pivotStart.x - moved.x - base[0];
            y = this.pivotStart.y - moved.y - base[1];
            z = this.pivotStart.z - moved.z - base[2];
        } else if (handle.kind == Kind.SCALE) {
            // the model grows from where it stands: its position follows so that its anchor stays put
            double[] base = this.target.placedPose(this.summonedPitch);
            Vector3d moved = PlacedDisplay.orientation(base[3] + turn, base[4] + tilt).transform(new Vector3d(this.anchorLocal).mul(size));
            x = this.anchorStart.x - moved.x - base[0];
            y = this.anchorStart.y - moved.y - base[1];
            z = this.anchorStart.z - moved.z - base[2];
        } else {
            double length = Math.sqrt(x * x + y * y + z * z);
            if (length > MAX_SHIFT) {
                double factor = MAX_SHIFT / length;
                x *= factor;
                y *= factor;
                z *= factor;
            }
        }
        if (Math.abs(x - this.target.shift[0]) < 1e-9 && Math.abs(y - this.target.shift[1]) < 1e-9 && Math.abs(z - this.target.shift[2]) < 1e-9
                && Math.abs(turn - this.target.shift[3]) < 1e-9 && Math.abs(tilt - this.target.shift[4]) < 1e-9
                && Math.abs(size - this.target.shift[5]) < 1e-9) {
            return;
        }
        this.renderer.shift(this.target, x, y, z, turn, tilt, size);
        refreshTool();
    }

    private double snap(double amount, double step) {
        return this.snapping ? Math.round(amount / step) * step : amount;
    }

    // ---------- The click box ----------

    /**
     * Puts the tool on the block's click box, or back on its model (administrators only).
     */
    void boxMode(boolean on) {
        if (!this.admin || on == this.boxMode) {
            status();
            return;
        }
        if (this.active != null) {
            drop();
        }
        if (on) {
            double[] start = storedBox();
            if (start == null) {
                this.manager.say(this.player, "gizmo.hitbox-nothing");
                this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
                return;
            }
            this.box = start;
        }
        unhover();
        this.boxMode = on;
        if (this.hitboxSwitch != null) {
            // its plate is light blue while the tool is on the click box
            this.hitboxSwitch.pieces.get(0).normal = this.normals[on ? LIGHT_BLUE : BLACK];
        }
        this.readout = "";
        refreshTool();
        if (!on) {
            sendPoses();
        }
        this.manager.say(this.player, on ? "gizmo.hitbox-on" : "gizmo.hitbox-off");
        this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 0.6f, on ? 1.5f : 1.0f);
        status();
    }

    /**
     * The click box the tool starts from on this block: the one an administrator drew, else one around the click boxes
     * of its file, else one around what it draws; null for a model that draws nothing.
     */
    @org.jetbrains.annotations.Nullable
    private double[] storedBox() {
        BlockSettings settings = this.target.settings;
        if (settings != null && !settings.noClickBox && settings.clickBox != null) {
            return settings.clickBox.clone();
        }
        return referenceBox();
    }

    /**
     * What a right click on a part of the click box puts back: around the click boxes of its file, else around what
     * the model draws.
     */
    @org.jetbrains.annotations.Nullable
    private double[] referenceBox() {
        return AdminMenu.startBox(this.target, new BlockSettings(), this.manager.definition(this.target));
    }

    private double hitboxMax() {
        return Math.max(0.5, this.manager.setting("gizmo.hitbox-max", 32));
    }

    /**
     * Works out where the click box stands in the world, as the block's hitbox will be laid out.
     */
    private void measureBox() {
        double[] bounds = boundsOf(this.box);
        System.arraycopy(bounds, 0, this.boxBounds, 0, 6);
    }

    /**
     * Where a click box stands in the world, around every box it becomes: smallest x, y, z then largest.
     */
    private double[] boundsOf(double[] box) {
        double[] bounds = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double[] laid : this.renderer.clickBoxesFor(this.target, box)) {
            bounds[0] = Math.min(bounds[0], laid[0] - laid[3] / 2);
            bounds[1] = Math.min(bounds[1], laid[1]);
            bounds[2] = Math.min(bounds[2], laid[2] - laid[3] / 2);
            bounds[3] = Math.max(bounds[3], laid[0] + laid[3] / 2);
            bounds[4] = Math.max(bounds[4], laid[1] + laid[4]);
            bounds[5] = Math.max(bounds[5], laid[2] + laid[3] / 2);
        }
        if (bounds[0] > bounds[3]) {
            Vector3d pivot = this.target.pivot(this.summonedPitch);
            return new double[]{pivot.x, pivot.y, pivot.z, pivot.x, pivot.y, pivot.z};
        }
        return bounds;
    }

    private Vector3d middleOf(double[] box) {
        double[] b = boundsOf(box);
        return new Vector3d((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
    }

    /**
     * Works out what takes a move in the world into a change of where the click box stands from its block, through the
     * way it is laid out (the model's turn and size, a block on a wall): where its middle goes for a step on each.
     */
    private void measureMoves() {
        Vector3d middle = middleOf(this.box);
        int[] index = {3, 2, 4};
        Vector3d[] columns = new Vector3d[3];
        for (int i = 0; i < 3; i++) {
            double[] moved = this.box.clone();
            moved[index[i]] += 1;
            columns[i] = middleOf(moved).sub(middle);
        }
        org.joml.Matrix3d matrix = new org.joml.Matrix3d(columns[0], columns[1], columns[2]);
        this.boxInverse = Math.abs(matrix.determinant()) < 1.0E-9
                ? new org.joml.Matrix3d().scaling(1 / Math.max(1.0E-3, this.target.scale())) : matrix.invert();
    }

    /**
     * Shows the click box as it now is.
     *
     * @param recentre whether the tool goes to its middle (it stays where it is while a cube is held)
     * @param reshape  whether its size changed: its outline and cubes are drawn again
     */
    private void boxChanged(boolean recentre, boolean reshape) {
        measureBox();
        if (recentre) {
            this.origin.set(boxMiddle());
            sendPosition();
        }
        if (reshape) {
            sendBox();
        }
    }

    private void grabBox(Handle handle) {
        Vector3d eye = eye();
        Vector3d look = look();
        System.arraycopy(this.box, 0, this.boxStart, 0, 5);
        this.dragOrigin.set(this.origin);
        Quaterniond frame = frameOf(handle);
        frame.transform(this.dragAxis.set(unit(handle.axis)));
        switch (handle.kind) {
            case MOVE_PLANE -> {
                frame.transform(this.dragU.set(unit((handle.axis + 1) % 3)));
                frame.transform(this.dragV.set(unit((handle.axis + 2) % 3)));
                if (!planePoint(eye, look, this.hitStart)) {
                    return;
                }
            }
            case MOVE_AXIS, BOX_SIZE -> {
                Double t = axisParam(eye, look);
                if (t == null) {
                    return;
                }
                this.paramStart = t;
            }
            default -> {
                return;
            }
        }
        measureMoves();
        this.active = handle;
        this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.6f);
        status();
    }

    private void dragBox(Handle handle, Vector3d eye, Vector3d look) {
        double size = Math.max(1.0E-3, this.target.scale());
        double max = hitboxMax();
        double step = MOVE_STEPS[this.moveStep];
        double[] next = this.boxStart.clone();
        Vector3d moved = null;
        switch (handle.kind) {
            case MOVE_AXIS -> {
                Double t = axisParam(eye, look);
                if (t == null) {
                    return;
                }
                double amount = snap(t - this.paramStart, step);
                moved = new Vector3d(this.dragAxis).mul(amount);
                this.readout = String.format(Locale.ROOT, "%s %+.3f", labelOf(handle), amount);
            }
            case MOVE_PLANE -> {
                Vector3d hit = new Vector3d();
                if (!planePoint(eye, look, hit)) {
                    return;
                }
                hit.sub(this.hitStart);
                double a = snap(hit.dot(this.dragU), step);
                double b = snap(hit.dot(this.dragV), step);
                moved = new Vector3d(this.dragU).mul(a).fma(b, this.dragV);
                this.readout = String.format(Locale.ROOT, "%s %+.3f %+.3f", labelOf(handle), a, b);
            }
            case BOX_SIZE -> {
                Double t = axisParam(eye, look);
                if (t == null) {
                    return;
                }
                int index = handle.axis == 1 ? 1 : 0;
                // the top goes up and down from the floor; a side goes out as much as the cube, the other side too (the
                // base stays square around its middle)
                double spread = handle.axis == 1 ? 1 : 2 / Math.max(1.0, Math.abs(this.dragAxis.x) + Math.abs(this.dragAxis.z));
                double real = this.boxStart[index] * size + (t - this.paramStart) * spread;
                if (this.snapping) {
                    real = Math.round(real / step) * step;
                }
                real = Math.max(BOX_MIN, Math.min(max, real));
                next[index] = real / size;
                this.readout = Texts.get("gizmo.readout.box-size", "%part%", labelOf(handle), "%value%", AdminMenu.blocks(real));
            }
            default -> {
                return;
            }
        }
        if (moved != null) {
            Vector3d local = this.boxInverse.transform(moved, new Vector3d());
            next[3] = clampOffset(this.boxStart[3] + local.x, size, max);
            next[2] = clampOffset(this.boxStart[2] + local.y, size, max);
            next[4] = clampOffset(this.boxStart[4] + local.z, size, max);
        }
        if (java.util.Arrays.equals(next, this.box)) {
            return;
        }
        this.box = next;
        // a box moved takes the tool along; while a cube is held the tool stays where it is
        boxChanged(handle.kind != Kind.BOX_SIZE, handle.kind == Kind.BOX_SIZE);
    }

    private static double clampOffset(double value, double size, double max) {
        return Math.max(-max / size, Math.min(max / size, value));
    }

    private void dropBox(Handle held) {
        // the tool goes to the middle of the box as it now is
        boxChanged(true, held.kind == Kind.BOX_SIZE);
        if (!java.util.Arrays.equals(this.box, this.boxStart)) {
            saveBox();
            this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.0f);
        }
        status();
    }

    /**
     * Gives the block the click box drawn: its hitbox is summoned again.
     */
    private void saveBox() {
        if (!this.manager.adminMenu().setClickBox(this.player, this.target, this.box)) {
            this.manager.say(this.player, "gizmo-admin.hitbox-later");
        }
    }

    /**
     * The red button on the click box: the block takes the click boxes of its file back.
     */
    private void resetBox() {
        unhover();
        BlockSettings settings = this.target.settings;
        if (settings == null || !settings.noClickBox && settings.clickBox == null) {
            this.manager.say(this.player, "gizmo.hitbox-already-file");
            this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
            status();
            return;
        }
        if (!this.manager.adminMenu().setClickBox(this.player, this.target, null)) {
            this.manager.say(this.player, "gizmo-admin.hitbox-later");
        }
        double[] start = storedBox();
        if (start != null) {
            this.box = start;
        }
        boxChanged(true, true);
        this.readout = Texts.get("gizmo.readout.box-reset");
        this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 0.8f);
        status();
    }

    /**
     * A right click on a part of the click box: what it changes goes back to the click box of the file (or the one
     * around the model), the rest staying as it is. An arrow puts back where the box stands along its axis, a square
     * on its plane, a cube its width or its height.
     */
    private void resetBoxPart(Handle handle) {
        double[] reference = referenceBox();
        if (reference == null) {
            return;
        }
        double[] next = this.box.clone();
        switch (handle.kind) {
            case BOX_SIZE -> {
                int index = handle.axis == 1 ? 1 : 0;
                next[index] = reference[index];
            }
            case MOVE_AXIS, MOVE_PLANE -> {
                double[] home = this.box.clone();
                home[2] = reference[2];
                home[3] = reference[3];
                home[4] = reference[4];
                Vector3d away = middleOf(this.box).sub(middleOf(home));
                Vector3d axis = frameOf(handle).transform(unit(handle.axis));
                Vector3d back = handle.kind == Kind.MOVE_AXIS ? new Vector3d(axis).mul(-away.dot(axis))
                        : new Vector3d(axis).mul(away.dot(axis)).sub(away);
                measureMoves();
                Vector3d local = this.boxInverse.transform(back, new Vector3d());
                next[3] += local.x;
                next[2] += local.y;
                next[4] += local.z;
                for (int i = 2; i < 5; i++) {
                    if (Math.abs(next[i] - reference[i]) < 1.0E-6) {
                        next[i] = reference[i];
                    }
                }
            }
            default -> {
                return;
            }
        }
        unhover();
        boolean same = true;
        for (int i = 0; i < 5; i++) {
            same &= Math.abs(next[i] - this.box[i]) < 1.0E-6;
        }
        if (same) {
            this.manager.say(this.player, "gizmo.nothing-to-reset");
            this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
            status();
            return;
        }
        this.box = next;
        saveBox();
        boxChanged(true, true);
        this.readout = Texts.get("gizmo.readout.reset", "%part%", labelOf(handle));
        this.player.playSound(this.player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 0.8f);
        status();
    }

    // ---------- Settings, while it is open ----------

    void scroll(boolean down) {
        if (this.active == null) {
            return;
        }
        int direction = down ? 1 : -1;
        if (this.boxMode) {
            // the click box moves and is sized by the same steps, in blocks
            this.moveStep = Math.max(0, Math.min(MOVE_STEPS.length - 1, this.moveStep + direction));
        } else if (this.active.kind == Kind.TURN) {
            this.turnStep = Math.max(0, Math.min(TURN_STEPS.length - 1, this.turnStep + direction));
        } else if (this.active.kind == Kind.SCALE) {
            this.scaleStep = Math.max(0, Math.min(SCALE_STEPS.length - 1, this.scaleStep + direction));
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
        if (this.boxMode) {
            actionBar(Texts.get("gizmo.status-hitbox", "%readout%", this.readout.isEmpty() ? Texts.get("gizmo.readout.aim") : this.readout,
                    "%state%", this.manager.adminMenu().clickState(this.target),
                    "%step%", this.snapping ? trimmed(MOVE_STEPS[this.moveStep]) : Texts.get("gizmo.readout.free-step"),
                    "%axes%", Texts.get(this.local ? "gizmo.readout.model-axes" : "gizmo.readout.world-axes")));
            return;
        }
        boolean sizing = this.active != null && this.active.kind == Kind.SCALE;
        double step = this.active != null && this.active.kind == Kind.TURN ? TURN_STEPS[this.turnStep] : sizing ? SCALE_STEPS[this.scaleStep] * 100 : MOVE_STEPS[this.moveStep];
        actionBar(Texts.get("gizmo.status", "%readout%", this.readout.isEmpty() ? Texts.get("gizmo.readout.aim") : this.readout,
                "%step%", this.snapping ? trimmed(step) : Texts.get("gizmo.readout.free-step"),
                "%axes%", Texts.get(this.local ? "gizmo.readout.model-axes" : "gizmo.readout.world-axes")));
    }

    private void actionBar(String text) {
        this.player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
    }

    private static String trimmed(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /**
     * Takes the tool away from its player's client; a part still held is dropped where it is.
     */
    void close() {
        if (this.shown == 0) {
            return;
        }
        this.shown = 0;
        if (this.active != null) {
            this.active = null;
            if (this.boxMode) {
                saveBox();
            } else {
                this.renderer.commitShift(this.target);
            }
        }
        int[] ids = new int[this.pieces.size() + 1];
        for (int i = 0; i < this.pieces.size(); i++) {
            ids[i] = this.pieces.get(i).id;
        }
        ids[this.pieces.size()] = this.catcherId;
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
