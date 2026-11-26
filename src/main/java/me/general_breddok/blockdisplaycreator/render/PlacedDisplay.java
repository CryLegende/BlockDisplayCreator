package me.general_breddok.blockdisplaycreator.render;

import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockRotation;
import me.general_breddok.blockdisplaycreator.rotation.snapper.YawSnapper;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A placed custom block whose display entities exist on the clients only.
 * <p>
 * Its position and rotation are the ones the block would give its real entities: they follow from where and how
 * it was placed, unless the block was placed elsewhere than on the centre of its block or its entities had been
 * moved before being replaced, in which case the placement is stored with the block.
 */
final class PlacedDisplay {

    final World world;
    final int x;
    final int y;
    final int z;
    final String modelName;
    final CustomBlockRotation rotation;
    final List<DisplayRenderer.Viewer> viewers = new ArrayList<>(2);
    /**
     * The model shown for another plugin this draws, null for a placed block (see {@link DisplayRenderer#show}): it
     * stands where it was shown, turned by the exact yaw it was given, and nothing of it is in the world.
     */
    DisplayRenderer.ShownModel shownFor;
    /**
     * The players a command plays an animation of the block for: until it ends, they get none of the frames the
     * others see.
     */
    final java.util.Set<UUID> privateFor = new java.util.HashSet<>(2);
    /**
     * The interactions and collisions of a block whose model has a {@code display.visible-if}: hidden from every
     * player but those it is sent to. Null for the other blocks.
     */
    UUID[] hitbox;

    /**
     * Where the block was placed: the point its interactions and collisions are laid out around.
     */
    double baseX;
    double baseY;
    double baseZ;
    /**
     * Where its display entities stand and how they are turned, when it differs from what its rotation gives.
     */
    double[] fixedPose;
    /**
     * How an operator moved the block from where its placement puts it: x, y, z in blocks, then yaw and pitch in
     * degrees added to its rotation, then the factor its size was multiplied by. Zeros and a one for a block that
     * stayed as it was placed.
     */
    final double[] shift = {0, 0, 0, 0, 0, 1};
    /**
     * The shift its interactions and collisions were last laid out for.
     */
    final double[] hitboxShift = {0, 0, 0, 0, 0, 1};
    /**
     * Whether its display entities currently glide between positions (an operator is moving it).
     */
    boolean gliding;

    DisplayModel model;
    int[] entityIds;
    /**
     * For each animation of the model, the tick it was started at by a click or the placement, -1 while it rests.
     */
    int[] startedAt;
    /**
     * For each animation of the model, the last frame played since the block was shown at rest, -1 for none.
     */
    int[] lastFrame;
    private Object spawnPacket;
    private Object removePacket;
    private Object[][] framePackets;
    /**
     * The frames without the parts of the model's {@code look-at}, for the players they look at.
     */
    private Object[][] unturnedFramePackets;
    private Object[] restPackets;
    /**
     * Whether the block is among those the renderer checks the players close to (its model has a {@code look-at} or
     * an {@code approach} animation, and someone sees it).
     */
    boolean attentive;
    /**
     * Where the pivot of the model's {@code look-at} stands in the world, and the rotation that takes a direction of
     * the world into the model: worked out again once the block is moved. Null until needed.
     */
    private double[] lookCentre;
    private org.joml.Quaterniond lookBack;
    /**
     * By player, for each animation of the model, when its {@code approach} last played for him (milliseconds).
     */
    private java.util.Map<UUID, long[]> approached;
    /**
     * The id of the invisible display the parts of the model ride when its animations move the whole model, -1 for a
     * model without one.
     */
    int carrierId = -1;
    /**
     * The ids of every entity a client is sent for the block: those of the model, then the carrier's.
     */
    int[] allIds;
    /**
     * The glide, in ticks, every player who sees the block has for the teleports of its carrier, -1 when they do not
     * all have the same: a frame moving the whole model sends it only when it changes it.
     */
    int carrierGlide;
    private Object passengersPacket;
    /**
     * For each animation, for each of its frames moving the whole model, the teleport of the carrier, alone then with
     * the glide of the frame.
     */
    private Object[][] rootPackets;
    private Object[][] rootGlidePackets;

    PlacedDisplay(World world, int x, int y, int z, String modelName, CustomBlockRotation rotation) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.modelName = modelName;
        this.rotation = rotation;
        this.baseX = x + 0.5;
        this.baseY = y;
        this.baseZ = z + 0.5;
    }

    int chunkX() {
        return this.x >> 4;
    }

    int chunkZ() {
        return this.z >> 4;
    }

    Location base() {
        return new Location(this.world, this.baseX, this.baseY, this.baseZ);
    }

    /**
     * Whether a player stands close enough to hear the sounds of an animation of the block: within its
     * {@code sound-range} of where the block is seen, an operator's moves included.
     */
    boolean heardBy(Player player, double range) {
        Location at = player.getLocation();
        if (at.getWorld() != this.world) {
            return false;
        }
        double dx = at.getX() - this.baseX - this.shift[0];
        double dy = at.getY() - this.baseY - this.shift[1];
        double dz = at.getZ() - this.baseZ - this.shift[2];
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    boolean isOffCentre() {
        return Math.abs(this.baseX - (this.x + 0.5)) > 1.0E-4 || Math.abs(this.baseY - this.y) > 1.0E-4 || Math.abs(this.baseZ - (this.z + 0.5)) > 1.0E-4;
    }

    /**
     * Gives the block a model, and entity ids to show it with.
     */
    void bind(DisplayModel model, NmsBridge nms) {
        this.model = model;
        this.entityIds = new int[model.elements.size()];
        for (int i = 0; i < this.entityIds.length; i++) {
            this.entityIds[i] = nms.nextEntityId();
        }
        if (model.hasCarrier()) {
            this.carrierId = nms.nextEntityId();
            this.allIds = java.util.Arrays.copyOf(this.entityIds, this.entityIds.length + 1);
            this.allIds[this.entityIds.length] = this.carrierId;
        } else {
            this.carrierId = -1;
            this.allIds = this.entityIds;
        }
        this.carrierGlide = model.carrierGlide;
        this.passengersPacket = null;
        this.rootPackets = new Object[model.animations.size()][];
        this.rootGlidePackets = new Object[model.animations.size()][];
        this.spawnPacket = null;
        this.removePacket = null;

        int animations = model.animations.size();
        this.startedAt = new int[animations];
        this.lastFrame = new int[animations];
        this.framePackets = new Object[animations][];
        this.unturnedFramePackets = new Object[animations][];
        this.restPackets = new Object[animations];
        java.util.Arrays.fill(this.startedAt, -1);
        java.util.Arrays.fill(this.lastFrame, -1);
        this.lookCentre = null;
        this.lookBack = null;
        // the animations may be others, in another order
        this.approached = null;
    }

    /**
     * Gives the block the same model with other frames for its animations (its entities stay): the packets of the
     * old frames are dropped, and an animation that ended keeps its end pose for players arriving.
     */
    void rebindAnimations(DisplayModel model) {
        for (int i = 0; i < this.lastFrame.length && i < model.animations.size(); i++) {
            if (this.lastFrame[i] >= 0) {
                DisplayAnimation.Frame[] before = this.model.animations.get(i).frames;
                DisplayAnimation.Frame[] after = model.animations.get(i).frames;
                int at = before[Math.min(this.lastFrame[i], before.length - 1)].at();
                int last = -1;
                while (last + 1 < after.length && after[last + 1].at() <= at) {
                    last++;
                }
                this.lastFrame[i] = this.lastFrame[i] == before.length - 1 ? after.length - 1 : last;
            }
        }
        this.model = model;
        this.framePackets = new Object[model.animations.size()][];
        this.unturnedFramePackets = new Object[model.animations.size()][];
        this.restPackets = new Object[model.animations.size()];
        this.rootPackets = new Object[model.animations.size()][];
        this.rootGlidePackets = new Object[model.animations.size()][];
    }

    /**
     * The packet that puts the parts an animation moves back at rest at once.
     */
    Object restPacket(NmsBridge nms, int animation) {
        if (this.restPackets[animation] == null) {
            int[] touched = this.model.animations.get(animation).touched;
            List<Object> parts = new ArrayList<>(touched.length);
            for (int part : touched) {
                org.bukkit.util.Transformation rest = this.model.elements.get(part).transformation();
                List<?> values = nms.poseValues(rest.getTranslation(), rest.getLeftRotation(), rest.getScale(), rest.getRightRotation(), 0);
                parts.add(nms.dataPacket(this.entityIds[part], sent(nms, part, values)));
            }
            this.restPackets[animation] = nms.bundle(parts);
        }
        return this.restPackets[animation];
    }

    /**
     * Stands for a frame that has nothing to send, among the packets kept.
     */
    private static final Object NOTHING = new Object();

    /**
     * The packet that starts the parts of a frame towards their pose: for each part, the start and the duration of the
     * interpolation and the fields of its transformation some pose of the model changes ({@link DisplayModel#varying}).
     * The client keeps the others, which every pose packet gives the same value. A part that never leaves its rest is
     * left out.
     *
     * @return null when the frame moves nothing
     */
    Object framePacket(NmsBridge nms, int animation, int frame) {
        return framePacket(nms, animation, frame, false);
    }

    /**
     * @param unturned without the parts of the model's {@code look-at}: for a player they look at
     */
    Object framePacket(NmsBridge nms, int animation, int frame, boolean unturned) {
        LookAt lookAt = unturned ? this.model.lookAt : null;
        Object[][] all = lookAt != null ? this.unturnedFramePackets : this.framePackets;
        Object[] packets = all[animation];
        if (packets == null) {
            packets = all[animation] = new Object[this.model.animations.get(animation).frames.length];
        }
        if (packets[frame] == null) {
            DisplayAnimation.Frame played = this.model.animations.get(animation).frames[frame];
            List<Object> parts = new ArrayList<>(played.parts().length);
            for (int i = 0; i < played.parts().length; i++) {
                int part = played.parts()[i];
                int varying = this.model.varying[part];
                if (varying != 0 && (lookAt == null || !lookAt.turns(part))) {
                    // the fields are left out once the values are sized and raised: those add a missing scale or translation
                    parts.add(nms.dataPacket(this.entityIds[part], nms.varyingValues(sent(nms, part, played.moving()[i]), varying)));
                }
            }
            packets[frame] = parts.isEmpty() ? NOTHING : nms.bundle(parts);
        }
        return packets[frame] == NOTHING ? null : packets[frame];
    }

    /**
     * The packet that puts a client that just received the block at rest where an animation has taken it: the
     * poses of its frames up to the given one, applied at once.
     * <p>
     * A pose is a whole transformation, so each part only needs the last one a frame gave it: one packet per part
     * at most, where replaying every frame would send frames × parts packets (8340 for a model of 139 parts turned
     * in 60 frames, past what a client takes in a bundle).
     */
    Object catchUpPacket(NmsBridge nms, int animation, int lastFrame) {
        return catchUpPacket(nms, animation, lastFrame, false);
    }

    /**
     * @param unturned without the parts of the model's {@code look-at}, for a player they look at
     * @return null when nothing is left to send
     */
    Object catchUpPacket(NmsBridge nms, int animation, int lastFrame, boolean unturned) {
        DisplayAnimation.Frame[] frames = this.model.animations.get(animation).frames;
        boolean[] posed = new boolean[this.entityIds.length];
        if (unturned && this.model.lookAt != null) {
            for (int part : this.model.lookAt.parts) {
                posed[part] = true;
            }
        }
        List<Object> parts = new ArrayList<>();
        for (int frame = lastFrame; frame >= 0; frame--) {
            DisplayAnimation.Frame played = frames[frame];
            for (int i = played.parts().length - 1; i >= 0; i--) {
                int part = played.parts()[i];
                if (!posed[part]) {
                    posed[part] = true;
                    parts.add(nms.dataPacket(this.entityIds[part], sent(nms, part, played.jumping()[i])));
                }
            }
        }
        return parts.isEmpty() && unturned ? null : nms.bundle(parts);
    }

    /**
     * The packet that hands the parts of a loop back to it for a player an animation was played for alone: each part
     * glides from where that animation left it to the pose the loop gives it now, and gets there with the parts the
     * other players see when theirs are still on the way, or in {@code minimum} ticks. Each part takes the last frame
     * of the loop that moved it, in this round or at the end of the one before, so no part waits for its next frame.
     *
     * @param phase  the tick of the loop it is at
     * @param turned whether the loop has played a whole round already
     * @return null when the loop moves nothing
     */
    Object rejoinPacket(NmsBridge nms, int animation, int phase, boolean turned, int minimum) {
        return rejoinPacket(nms, animation, phase, turned, minimum, false);
    }

    /**
     * @param unturned without the parts of the model's {@code look-at}, for a player they look at
     */
    Object rejoinPacket(NmsBridge nms, int animation, int phase, boolean turned, int minimum, boolean unturned) {
        DisplayAnimation loop = this.model.animations.get(animation);
        DisplayAnimation.Frame[] frames = loop.frames;
        boolean[] posed = new boolean[this.entityIds.length];
        if (unturned && this.model.lookAt != null) {
            for (int part : this.model.lookAt.parts) {
                posed[part] = true;
            }
        }
        List<Object> parts = new ArrayList<>();
        int last = -1;
        while (last + 1 < frames.length && frames[last + 1].at() <= phase) {
            last++;
        }
        // the frames of this round, latest first, then those of the end of the round before
        for (int k = 0; k < frames.length; k++) {
            int frame = last - k;
            int start;
            if (frame >= 0) {
                start = frames[frame].at();
            } else if (turned) {
                frame += frames.length;
                start = frames[frame].at() - loop.duration;
            } else {
                break;
            }
            DisplayAnimation.Frame played = frames[frame];
            int ticks = Math.max(minimum, start + played.over() - phase);
            for (int i = played.parts().length - 1; i >= 0; i--) {
                int part = played.parts()[i];
                if (posed[part]) {
                    continue;
                }
                posed[part] = true;
                org.bukkit.util.Transformation target = played.targets()[i];
                List<?> values = target == null ? played.jumping()[i]
                        : nms.poseValues(target.getTranslation(), target.getLeftRotation(), target.getScale(), target.getRightRotation(), ticks);
                parts.add(nms.dataPacket(this.entityIds[part], sent(nms, part, values)));
            }
        }
        return parts.isEmpty() ? null : nms.bundle(parts);
    }

    /**
     * Whether an entity of the block is shown with its culling box: the block stands on its block, as placed.
     */
    boolean upright(DisplayModel.Element element) {
        return element.upright() != null && this.fixedPose == null && pose(element.pitch())[4] == 0;
    }

    /**
     * How much lower than the block an entity of it stands, in blocks of the world (0 for none): what its culling
     * box needs, when it has one.
     */
    double sink(int part) {
        DisplayModel.Element element = this.model.elements.get(part);
        return element.sink() > 0 && upright(element) ? element.sink() * scale() : 0;
    }

    /**
     * Synced values of an entity of the block, as its clients are sent them: multiplied by the size of the block,
     * and raised by as much as the entity stands lower than the block.
     */
    List<?> sent(NmsBridge nms, int part, List<?> values) {
        DisplayModel.Element element = this.model.elements.get(part);
        if (element.sink() > 0 && upright(element)) {
            values = nms.raisedValues(values, element.sink());
        }
        return scale() == 1 ? values : nms.scaledValues(values, (float) scale());
    }

    /**
     * Position (x, y, z) then rotation (yaw, pitch) of the display entities, as the block's rotation gives them to
     * real entities. The pitch only holds for an entity summoned without one.
     */
    double[] pose(float summonedPitch) {
        double[] placed = placedPose(summonedPitch);
        if (!isShifted()) {
            return placed;
        }
        return new double[]{placed[0] + this.shift[0], placed[1] + this.shift[1], placed[2] + this.shift[2],
                Location.normalizeYaw((float) (placed[3] + this.shift[3])), Location.normalizePitch((float) (placed[4] + this.shift[4]))};
    }

    /**
     * How an entity turned by a yaw and a pitch (degrees) is oriented: the yaw about the vertical axis, then the
     * pitch about the entity's own X axis.
     */
    static org.joml.Quaterniond orientation(double yaw, double pitch) {
        return new org.joml.Quaterniond().rotationYXZ(-Math.toRadians(yaw), Math.toRadians(pitch), 0);
    }

    /**
     * The point the block turns around when an operator turns it: the middle of what it draws.
     */
    org.joml.Vector3d pivot(float summonedPitch) {
        double[] pose = pose(summonedPitch);
        org.joml.Vector3d middle = this.model.centre().mul(scale());
        orientation(pose[3], pose[4]).transform(middle);
        return middle.add(pose[0], pose[1], pose[2]);
    }

    /**
     * How far along a line of sight the box around what the block draws is entered, in blocks, or -1 when the line
     * misses it (what lies behind the starting point does not count).
     *
     * @param from      where the line starts, in the world
     * @param direction its direction, of length 1
     */
    double reach(org.joml.Vector3d from, org.joml.Vector3d direction) {
        double[] pose = pose(this.model.elements.get(0).pitch());
        org.joml.Quaterniond back = orientation(pose[3], pose[4]).invert();
        double size = scale();
        // in the model's own axes, at size 1: a point of the line is start + t × step, t in blocks of the world
        org.joml.Vector3d start = back.transform(new org.joml.Vector3d(from).sub(pose[0], pose[1], pose[2])).div(size);
        org.joml.Vector3d step = back.transform(new org.joml.Vector3d(direction)).div(size);
        float[] box = this.model.bounds();
        double near = 0;
        double far = Double.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            double s = start.get(axis);
            double d = step.get(axis);
            if (Math.abs(d) < 1.0E-12) {
                if (s < box[axis] || s > box[axis + 3]) {
                    return -1;
                }
                continue;
            }
            double first = (box[axis] - s) / d;
            double second = (box[axis + 3] - s) / d;
            near = Math.max(near, Math.min(first, second));
            far = Math.min(far, Math.max(first, second));
            if (near > far) {
                return -1;
            }
        }
        return near;
    }

    /**
     * Whether an operator moved the block.
     */
    boolean isShifted() {
        for (int i = 0; i < 5; i++) {
            if (Math.abs(this.shift[i]) > 1.0E-6) {
                return true;
            }
        }
        return Math.abs(this.shift[5] - 1) > 1.0E-6;
    }

    /**
     * The factor the size of the block was multiplied by: 1 for a block that kept its size.
     */
    double scale() {
        return this.shift[5];
    }

    // ---------- What an administrator set on this block alone ----------

    /**
     * What an administrator set on this block, on top of its file: null for a block that follows its file.
     */
    @org.jetbrains.annotations.Nullable
    BlockSettings settings;

    /**
     * How far, in blocks, players are sent the block: what an administrator set on it, else what its file says (0 for
     * the distance the world tracks display entities at).
     */
    int viewDistance() {
        return this.settings != null && this.settings.viewDistance >= 0 ? this.settings.viewDistance : this.model.viewDistance;
    }

    /**
     * How many blocks above it players are still sent it, -1 for no limit. A distance set on the block counts upwards
     * and downwards too.
     */
    int viewAbove() {
        return this.settings != null && this.settings.viewDistance > 0 ? this.settings.viewDistance : this.model.viewAbove;
    }

    int viewBelow() {
        return this.settings != null && this.settings.viewDistance > 0 ? this.settings.viewDistance : this.model.viewBelow;
    }

    /**
     * Whether the loops of its file play on this block.
     */
    boolean loops() {
        return this.model.loops && (this.settings == null || this.settings.loops);
    }

    /**
     * Whether the model stands upright as it was summoned, neither tipped by an operator nor hung on a wall.
     */
    boolean standsUpright() {
        return Math.abs(pose(this.model.elements.get(0).pitch())[4] - this.model.elements.get(0).pitch()) < 1.0E-6;
    }

    /**
     * The point of the model a change of size keeps in place, in the model's own axes at its file's size: the middle
     * of its floor for a model standing upright (it grows from where it stands, neither sinking into the ground nor
     * floating), else the middle of what it draws.
     */
    org.joml.Vector3d sizeAnchor() {
        org.joml.Vector3d centre = this.model.centre();
        return standsUpright() ? new org.joml.Vector3d(centre.x, 0, centre.z) : centre;
    }

    /**
     * Where, in the world, a point of the model (in its own axes at its file's size) stands now.
     */
    org.joml.Vector3d worldPoint(org.joml.Vector3d local) {
        double[] pose = pose(this.model.elements.get(0).pitch());
        org.joml.Vector3d point = new org.joml.Vector3d(local).mul(scale());
        orientation(pose[3], pose[4]).transform(point);
        return point.add(pose[0], pose[1], pose[2]);
    }

    /**
     * The shift that gives the block another size, keeping its {@link #sizeAnchor()} where it stands.
     */
    double[] shiftForSize(double size) {
        float pitch = this.model.elements.get(0).pitch();
        org.joml.Vector3d anchor = sizeAnchor();
        org.joml.Vector3d fixed = worldPoint(anchor);
        double[] base = placedPose(pitch);
        double[] pose = pose(pitch);
        org.joml.Vector3d moved = orientation(pose[3], pose[4]).transform(new org.joml.Vector3d(anchor).mul(size));
        return new double[]{fixed.x - moved.x - base[0], fixed.y - moved.y - base[1], fixed.z - moved.z - base[2], this.shift[3], this.shift[4], size};
    }

    /**
     * Puts the shift of the hitbox entities back to none.
     */
    void resetHitboxShift() {
        java.util.Arrays.fill(this.hitboxShift, 0);
        this.hitboxShift[5] = 1;
    }

    /**
     * Moves the block, and forgets the packets built for where it was.
     */
    void shift(double x, double y, double z, double yaw, double pitch) {
        shift(x, y, z, yaw, pitch, this.shift[5]);
    }

    void shift(double x, double y, double z, double yaw, double pitch, double scale) {
        boolean resized = Math.abs(scale - this.shift[5]) > 1.0E-9;
        this.shift[0] = x;
        this.shift[1] = y;
        this.shift[2] = z;
        this.shift[3] = yaw;
        this.shift[4] = pitch;
        this.shift[5] = scale;
        this.spawnPacket = null;
        this.lookCentre = null;
        this.lookBack = null;
        if (this.rootPackets != null) {
            // the carrier goes to other places
            java.util.Arrays.fill(this.rootPackets, null);
            java.util.Arrays.fill(this.rootGlidePackets, null);
        }
        if ((resized || this.model != null && this.model.sinks) && this.framePackets != null) {
            // the poses of the frames were multiplied by the old size, or raised for a block standing upright
            java.util.Arrays.fill(this.framePackets, null);
            java.util.Arrays.fill(this.unturnedFramePackets, null);
            java.util.Arrays.fill(this.restPackets, null);
        }
    }

    // ---------- Parts turned towards a player (look-at) ----------

    /**
     * Where the pivot of the model's {@code look-at} stands in the world: x, y, z.
     */
    double[] lookCentre() {
        if (this.lookCentre == null) {
            LookAt lookAt = this.model.lookAt;
            double[] pose = pose(this.model.elements.get(lookAt.parts[0]).pitch());
            org.joml.Quaterniond orientation = orientation(pose[3], pose[4]);
            // the entity of a lowered part stands lower, its translation raised as much: they cancel out
            org.joml.Vector3d pivot = orientation.transform(new org.joml.Vector3d(lookAt.pivot.x, lookAt.pivot.y, lookAt.pivot.z).mul(scale()));
            this.lookCentre = new double[]{pose[0] + pivot.x, pose[1] + pivot.y, pose[2] + pivot.z};
            this.lookBack = orientation.invert();
        }
        return this.lookCentre;
    }

    /**
     * Where the pivot of the model's {@code look-at} stands in the world at a tick, x, y, z: moved with the whole model
     * when its parts ride the carrier, by where the frames everyone sees have taken it.
     *
     * @param out receives the position
     */
    double[] lookCentre(int tick, long skipLoops, double[] out) {
        double[] centre = lookCentre();
        out[0] = centre[0];
        out[1] = centre[1];
        out[2] = centre[2];
        if (this.model.carried(this.model.lookAt.parts[0])) {
            org.joml.Vector3f offset = rootTarget(tick, skipLoops);
            if (offset != null) {
                double[] moved = carrierAt(offset);
                double[] still = pose(this.model.elements.get(0).pitch());
                out[0] += moved[0] - still[0];
                out[1] += moved[1] - still[1];
                out[2] += moved[2] - still[2];
            }
        }
        return out;
    }

    /**
     * The rotation that takes a direction of the world into the model, as its entities are turned.
     */
    org.joml.Quaterniond lookBack() {
        lookCentre();
        return this.lookBack;
    }

    // ---------- The whole model moved at once (root frames) ----------

    /**
     * Where the frames everyone sees have taken the whole model at a tick, in the model's axes (blocks at its scale),
     * or null when none moved it: for each animation that moves it, its last frame doing so (a loop by its tick, the
     * others by their last frame played), later animations of the file first, as the parts are caught up.
     */
    org.joml.Vector3f rootTarget(int tick) {
        return rootTarget(tick, 0L);
    }

    /**
     * @param skipLoops the loops (bit i for the animation i) left out: those not played for a player
     */
    org.joml.Vector3f rootTarget(int tick, long skipLoops) {
        org.joml.Vector3f target = null;
        List<DisplayAnimation> animations = this.model.animations;
        for (int a = 0; a < animations.size(); a++) {
            DisplayAnimation animation = animations.get(a);
            if (!animation.rooted || animation.trigger == DisplayAnimation.Trigger.LOOP && (!loops() || a < 64 && (skipLoops & 1L << a) != 0)) {
                // an administrator may have stopped the loops of this block, or this one is not played for him
                continue;
            }
            org.joml.Vector3f after = animation.trigger == DisplayAnimation.Trigger.LOOP ? animation.rootAfter(tick, -1)
                    : animation.rootAfter(-1, this.lastFrame[a]);
            if (after != null) {
                target = after;
            }
        }
        return target;
    }

    /**
     * Where the carrier stands in the world for the whole model moved by an offset (null for none): the offset turned
     * and sized as the model is.
     */
    double[] carrierAt(org.joml.Vector3f offset) {
        double[] pose = pose(this.model.elements.get(0).pitch());
        if (offset == null) {
            return pose;
        }
        org.joml.Vector3d moved = orientation(pose[3], pose[4]).transform(new org.joml.Vector3d(offset.x, offset.y, offset.z).mul(scale()));
        return new double[]{pose[0] + moved.x, pose[1] + moved.y, pose[2] + moved.z, pose[3], pose[4]};
    }

    /**
     * Where a point of the model (in its axes at its scale, blocks from where the block stands) is in the world: turned,
     * moved and sized as the block is, with the whole model when its root frames have moved it ({@code root}, null for
     * not).
     */
    double[] modelPoint(org.joml.Vector3f local, org.joml.Vector3f root) {
        double[] pose = pose(this.model.elements.get(0).pitch());
        org.joml.Vector3d point = new org.joml.Vector3d(local.x, local.y, local.z);
        if (root != null && this.model.hasCarrier()) {
            point.add(root.x, root.y, root.z);
        }
        orientation(pose[3], pose[4]).transform(point.mul(scale()));
        return new double[]{pose[0] + point.x, pose[1] + point.y, pose[2] + point.z};
    }

    /**
     * How far particles spread each way in the world for a spread in the model's axes: turned as the block is (a
     * spread along the world's axes, so the box around it) and sized.
     */
    double[] modelSpread(org.joml.Vector3f local) {
        double[] pose = pose(this.model.elements.get(0).pitch());
        org.joml.Quaterniond turn = orientation(pose[3], pose[4]);
        double[] out = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            org.joml.Vector3d along = new org.joml.Vector3d(axis == 0 ? local.x : 0, axis == 1 ? local.y : 0, axis == 2 ? local.z : 0);
            turn.transform(along);
            out[0] += Math.abs(along.x);
            out[1] += Math.abs(along.y);
            out[2] += Math.abs(along.z);
        }
        double size = scale();
        out[0] *= size;
        out[1] *= size;
        out[2] *= size;
        return out;
    }

    /**
     * The packet that teleports the carrier where a frame moving the whole model takes it, over the frame's time:
     * with the glide of the frame first when the client does not have it.
     */
    Object rootFramePacket(NmsBridge nms, int animation, int frame, boolean withGlide) {
        Object[][] all = withGlide ? this.rootGlidePackets : this.rootPackets;
        Object[] packets = all[animation];
        if (packets == null) {
            packets = all[animation] = new Object[this.model.animations.get(animation).frames.length];
        }
        if (packets[frame] == null) {
            DisplayAnimation.Frame played = this.model.animations.get(animation).frames[frame];
            double[] at = carrierAt(played.root());
            Object teleport = nms.teleportPacket(this.carrierId, at[0], at[1], at[2], (float) at[3], (float) at[4]);
            packets[frame] = withGlide ? nms.bundle(List.of(nms.dataPacket(this.carrierId, nms.teleportDurationValues(played.over())), teleport))
                    : teleport;
        }
        return packets[frame];
    }

    /**
     * The packet that takes the carrier somewhere over some ticks, for a client that has a given glide, and gives it
     * another one after (the one the others have), when needed.
     *
     * @param holds   the glide the client has, -1 when it is not known
     * @param restore the glide it is given after, -1 for none
     */
    Object carrierMove(NmsBridge nms, org.joml.Vector3f offset, int ticks, int holds, int restore) {
        List<Object> packets = new ArrayList<>(3);
        if (holds != ticks) {
            packets.add(nms.dataPacket(this.carrierId, nms.teleportDurationValues(ticks)));
        }
        double[] at = carrierAt(offset);
        packets.add(nms.teleportPacket(this.carrierId, at[0], at[1], at[2], (float) at[3], (float) at[4]));
        if (restore >= 0 && restore != ticks) {
            packets.add(nms.dataPacket(this.carrierId, nms.teleportDurationValues(restore)));
        }
        return nms.bundle(packets);
    }

    private Object passengersPacket(NmsBridge nms) {
        if (this.passengersPacket == null) {
            int count = 0;
            for (int i = 0; i < this.entityIds.length; i++) {
                if (this.model.carried(i)) {
                    count++;
                }
            }
            int[] riders = new int[count];
            for (int i = 0, n = 0; i < this.entityIds.length; i++) {
                if (this.model.carried(i)) {
                    riders[n++] = this.entityIds[i];
                }
            }
            this.passengersPacket = nms.passengersPacket(this.carrierId, riders);
        }
        return this.passengersPacket;
    }

    /**
     * The packet that turns the parts of the model's {@code look-at} by a yaw and a pitch from their rest, over the
     * given ticks.
     */
    Object turnPacket(NmsBridge nms, float yaw, float pitch, int ticks) {
        return turnPacket(nms, yaw, pitch, ticks, -1, 0L);
    }

    /**
     * The packet that turns the parts of the model's {@code look-at} by a yaw and a pitch: from their rest, or, when
     * the loops keep moving them ({@code loop: true}), from where the animations have them at a tick (see
     * {@link #publicPoses}), reaching it with them when a frame of a loop is on its way.
     *
     * @param tick      the tick, -1 for the rest pose
     * @param skipLoops the loops (bit i for the animation i) this player is not sent
     */
    Object turnPacket(NmsBridge nms, float yaw, float pitch, int ticks, int tick, long skipLoops) {
        LookAt lookAt = this.model.lookAt;
        org.joml.Quaternionf turn = lookAt.turn(yaw, pitch);
        PublicPoses from = lookAt.keepsLoop && tick >= 0 ? publicPoses(lookAt.parts, tick, ticks, skipLoops) : null;
        List<Object> parts = new ArrayList<>(lookAt.parts.length);
        for (int p = 0; p < lookAt.parts.length; p++) {
            int part = lookAt.parts[p];
            org.bukkit.util.Transformation base = from == null ? null : from.targets()[p];
            if (base == null) {
                // its rest, or a pose the server could not split: turned from its rest
                base = this.model.elements.get(part).transformation();
            }
            org.bukkit.util.Transformation pose = lookAt.turned(base, turn);
            List<?> values = nms.poseValues(pose.getTranslation(), pose.getLeftRotation(), pose.getScale(), pose.getRightRotation(),
                    from == null ? ticks : from.ticks()[p]);
            parts.add(nms.dataPacket(this.entityIds[part], nms.varyingValues(sent(nms, part, values), this.model.varying[part])));
        }
        return nms.bundle(parts);
    }

    /**
     * The packet that sends the parts of the model's {@code look-at} a frame moves, turned by a yaw and a pitch around
     * the pivot, over the frame's time: what a player they look at gets of a frame of a loop when the loops keep
     * moving them. Null when the frame moves none of them.
     */
    Object turnedFramePacket(NmsBridge nms, int animation, int frame, float yaw, float pitch) {
        LookAt lookAt = this.model.lookAt;
        DisplayAnimation.Frame played = this.model.animations.get(animation).frames[frame];
        org.joml.Quaternionf turn = null;
        List<Object> parts = null;
        for (int i = 0; i < played.parts().length; i++) {
            int part = played.parts()[i];
            org.bukkit.util.Transformation target = played.targets()[i];
            if (!lookAt.turns(part) || target == null || this.model.varying[part] == 0) {
                continue;
            }
            if (turn == null) {
                turn = lookAt.turn(yaw, pitch);
                parts = new ArrayList<>(lookAt.parts.length);
            }
            org.bukkit.util.Transformation pose = lookAt.turned(target, turn);
            List<?> values = nms.poseValues(pose.getTranslation(), pose.getLeftRotation(), pose.getScale(), pose.getRightRotation(), played.over());
            parts.add(nms.dataPacket(this.entityIds[part], nms.varyingValues(sent(nms, part, values), this.model.varying[part])));
        }
        return parts == null ? null : nms.bundle(parts);
    }

    /**
     * Where some parts are for the players who get every frame: for each, the pose of the last frame that moved it, of
     * a loop (in this round, or at the end of the one before) or of an animation played since the block was last at
     * rest, later animations of the file first. A part a loop moves gets there with the others when their frame is
     * still on the way, in {@code minimum} ticks at least.
     *
     * @param skipLoops the loops (bit i for the animation i) left out
     */
    PublicPoses publicPoses(int[] parts, int tick, int minimum, long skipLoops) {
        List<DisplayAnimation> animations = this.model.animations;
        PublicPoses out = new PublicPoses(new org.bukkit.util.Transformation[parts.length], new List<?>[parts.length], new int[parts.length]);
        java.util.Arrays.fill(out.ticks(), minimum);
        for (int a = 0; a < animations.size(); a++) {
            DisplayAnimation animation = animations.get(a);
            DisplayAnimation.Frame[] frames = animation.frames;
            boolean loop = animation.trigger == DisplayAnimation.Trigger.LOOP;
            if (loop && (!loops() || a < 64 && (skipLoops & 1L << a) != 0)) {
                // an administrator stopped the loops of this block, or this one is not played for him: at rest
                continue;
            }
            int phase = loop ? tick % animation.duration : 0;
            boolean turned = loop && tick >= animation.duration;
            int last;
            if (loop) {
                last = -1;
                while (last + 1 < frames.length && frames[last + 1].at() <= phase) {
                    last++;
                }
            } else {
                last = Math.min(this.lastFrame[a], frames.length - 1);
                if (last < 0) {
                    continue;
                }
            }
            for (int p = 0; p < parts.length; p++) {
                for (int k = 0; k < frames.length; k++) {
                    int frame = last - k;
                    int start;
                    if (frame >= 0) {
                        start = frames[frame].at();
                    } else if (turned) {
                        frame += frames.length;
                        start = frames[frame].at() - animation.duration;
                    } else {
                        break;
                    }
                    int j = java.util.Arrays.binarySearch(frames[frame].parts(), parts[p]);
                    if (j >= 0) {
                        out.targets()[p] = frames[frame].targets()[j];
                        out.jumps()[p] = frames[frame].jumping()[j];
                        out.ticks()[p] = loop ? Math.max(minimum, start + frames[frame].over() - phase) : minimum;
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Where {@link #publicPoses} puts some parts: for each, its pose (null with {@code jumps} set when the server could
     * not split it, both null for its rest) and the ticks it takes to get there.
     */
    record PublicPoses(org.bukkit.util.Transformation[] targets, List<?>[] jumps, int[] ticks) {
    }

    /**
     * The packet that takes some parts gliding to where the others see them (see {@link #publicPoses}).
     */
    Object publicPacket(NmsBridge nms, int[] parts, int tick, int minimum) {
        return publicPacket(nms, parts, tick, minimum, 0L);
    }

    /**
     * @param skipLoops the loops (bit i for the animation i) this player is not sent: their parts go to rest
     */
    Object publicPacket(NmsBridge nms, int[] parts, int tick, int minimum, long skipLoops) {
        PublicPoses poses = publicPoses(parts, tick, minimum, skipLoops);
        List<Object> packets = new ArrayList<>(parts.length);
        for (int p = 0; p < parts.length; p++) {
            org.bukkit.util.Transformation target = poses.targets()[p] != null || poses.jumps()[p] != null ? poses.targets()[p]
                    : this.model.elements.get(parts[p]).transformation();
            List<?> values = target == null ? poses.jumps()[p]
                    : nms.poseValues(target.getTranslation(), target.getLeftRotation(), target.getScale(), target.getRightRotation(), poses.ticks()[p]);
            packets.add(nms.dataPacket(this.entityIds[parts[p]], sent(nms, parts[p], values)));
        }
        return nms.bundle(packets);
    }

    // ---------- Animations played on approach ----------

    /**
     * Whether an {@code approach} animation may play for a player now, its cooldown over since it last played for
     * him at this block; if so, it is noted as played now.
     */
    boolean approach(UUID player, int animation, long now) {
        long cooldown = this.model.animations.get(animation).cooldown;
        if (this.approached == null) {
            this.approached = new java.util.HashMap<>();
        }
        long[] times = this.approached.get(player);
        if (times != null && times.length > animation && times[animation] != 0 && now - times[animation] < cooldown) {
            return false;
        }
        if (times == null || times.length <= animation) {
            if (this.approached.size() >= 64) {
                forgetApproaches(now);
            }
            times = times == null ? new long[this.model.animations.size()] : java.util.Arrays.copyOf(times, this.model.animations.size());
            this.approached.put(player, times);
        }
        times[animation] = now;
        return true;
    }

    /**
     * Forgets the players whose cooldowns are all over: those who went away do not stay in memory.
     */
    private void forgetApproaches(long now) {
        this.approached.values().removeIf(times -> {
            for (int i = 0; i < times.length && i < this.model.animations.size(); i++) {
                if (times[i] != 0 && now - times[i] < this.model.animations.get(i).cooldown) {
                    return false;
                }
            }
            return true;
        });
    }

    /**
     * The shift to store with the block, or null for a block that stayed where it was placed.
     */
    String shiftText() {
        if (!isShifted()) {
            return null;
        }
        return String.format(Locale.ROOT, "%s;%s;%s;%s;%s;%s", this.shift[0], this.shift[1], this.shift[2], this.shift[3], this.shift[4], this.shift[5]);
    }

    /**
     * Reads back what {@link #shiftText()} stored; the hitbox entities are taken to be laid out for it.
     */
    void shiftText(String stored) {
        if (stored == null || stored.isEmpty()) {
            return;
        }
        String[] parts = stored.split(";");
        if (parts.length < 5) {
            return;
        }
        try {
            double[] read = {0, 0, 0, 0, 0, 1};
            for (int i = 0; i < Math.min(6, parts.length); i++) {
                read[i] = Double.parseDouble(parts[i]);
            }
            shift(read[0], read[1], read[2], read[3], read[4], read[5]);
            System.arraycopy(read, 0, this.hitboxShift, 0, 6);
        } catch (NumberFormatException ignored) {
            // an unreadable shift leaves the block where it was placed
        }
    }

    /**
     * Where the block stands and how it is turned before any shift of an operator.
     */
    double[] placedPose(float summonedPitch) {
        if (this.fixedPose != null) {
            return this.fixedPose;
        }
        double px = this.baseX;
        double py = this.baseY;
        double pz = this.baseZ;
        float yaw;
        float pitch;
        BlockFace face = this.rotation.getAttachedFace();
        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            // a model shown for another plugin takes any angle, whatever the sides of its file
            yaw = this.shownFor != null ? this.rotation.getDirection() : new YawSnapper(this.model.sidesCount).getClosestAngle(this.rotation.getDirection());
            pitch = Location.normalizePitch(summonedPitch);
        } else {
            pitch = 90;
            switch (face) {
                case WEST -> {
                    yaw = 90;
                    px += 0.499;
                }
                case NORTH -> {
                    yaw = 180;
                    pz += 0.499;
                }
                case EAST -> {
                    yaw = 270;
                    px -= 0.499;
                }
                case SOUTH -> {
                    yaw = 0;
                    pz -= 0.499;
                }
                default -> yaw = 0;
            }
            py += 0.5;
        }
        yaw = Location.normalizeYaw(yaw);
        if (this.model.translationYaw != 0 || this.model.translationPitch != 0) {
            yaw = Location.normalizeYaw(yaw + this.model.translationYaw);
            pitch = Location.normalizePitch(pitch + this.model.translationPitch);
        }
        return new double[]{px, py, pz, yaw, pitch};
    }

    /**
     * The packet that makes a client create every display entity of the block, in one frame, with the whole model
     * moved by an offset when it rides a carrier: the carrier, its parts where it stands (a part riding it is drawn
     * from where it stands), then the parts seated on it.
     *
     * @param offset where the frames moving the whole model have taken it for this client, null for nowhere
     * @param glide  the glide the carrier is given for its teleports
     */
    Object spawnPacket(NmsBridge nms, org.joml.Vector3f offset, int glide) {
        return spawnPacket(nms, offset, glide, Float.NaN);
    }

    /**
     * @param spin how far its {@code spin} has turned its spinning parts, in degrees, NaN for not at all: they are
     *             created turned so, and given the glide of the spin
     */
    Object spawnPacket(NmsBridge nms, org.joml.Vector3f offset, int glide, float spin) {
        boolean spinning = !Float.isNaN(spin) && this.model.spin != null;
        if (!this.model.hasCarrier() && !spinning) {
            return spawnPacket(nms);
        }
        List<DisplayModel.Element> elements = this.model.elements;
        List<Object> packets = new ArrayList<>(elements.size() * 2 + 3);
        double[] at = this.model.hasCarrier() ? carrierAt(offset) : null;
        if (at != null) {
            packets.add(nms.spawnPacket(this.carrierId, UUID.randomUUID(), at[0], at[1], at[2], (float) at[4], (float) at[3], this.model.carrierType));
            packets.add(nms.dataPacket(this.carrierId, nms.teleportDurationValues(Math.max(0, glide))));
        }
        for (int i = 0; i < elements.size(); i++) {
            DisplayModel.Element element = elements.get(i);
            double[] pose = pose(element.pitch());
            boolean riding = this.model.carried(i);
            double sink = riding ? 0 : sink(i);
            boolean turning = spinning && this.model.spin.turns(i);
            float yaw = (float) pose[3] + (turning ? spin : 0);
            packets.add(nms.spawnPacket(this.entityIds[i], UUID.randomUUID(), riding ? at[0] : pose[0], riding ? at[1] : pose[1] - sink,
                    riding ? at[2] : pose[2], (float) pose[4], yaw, element.type()));
            if (turning) {
                // a client glides the rotations of the spin over the time between two
                packets.add(nms.dataPacket(this.entityIds[i], nms.teleportDurationValues(this.model.spin.step)));
            }
            List<?> data = upright(element) ? element.upright() : element.data();
            if (sink > 0) {
                org.bukkit.block.Block lit = this.world.getBlockAt((int) Math.floor(pose[0]), (int) Math.floor(pose[1]), (int) Math.floor(pose[2]));
                data = nms.litValues(data, lit.getLightFromBlocks(), lit.getLightFromSky());
            }
            data = sent(nms, i, data);
            if (!data.isEmpty()) {
                packets.add(nms.dataPacket(this.entityIds[i], data));
            }
        }
        if (at != null) {
            packets.add(passengersPacket(nms));
        }
        return nms.bundle(packets);
    }

    // ---------- Parts turning on and on (spin) ----------

    /**
     * Whether the {@code spin} of the model turns parts of this block: it has one, its loops play (an administrator may
     * stop them), and the block stands upright, as summoned (on a wall or tipped, the vertical axis of its parts is no
     * longer the world's, around which a yaw turns them).
     */
    boolean spins() {
        return this.model.spin != null && loops() && Math.abs(pose(this.model.elements.get(0).pitch())[4]) < 1.0E-6;
    }

    /**
     * The packet that turns the spinning parts by an angle from their rest, in degrees: they glide there over the step
     * of the spin.
     */
    Object spinPacket(NmsBridge nms, float angle) {
        Spin spin = this.model.spin;
        List<Object> packets = new ArrayList<>(spin.parts.length);
        for (int part : spin.parts) {
            double[] pose = pose(this.model.elements.get(part).pitch());
            packets.add(nms.rotationPacket(this.entityIds[part], (float) pose[3] + angle, (float) pose[4]));
        }
        return nms.bundle(packets);
    }

    /**
     * The packet that gives the spinning parts the glide of the spin again (an operator's moves give them another).
     */
    Object spinGlidePacket(NmsBridge nms) {
        List<?> values = nms.teleportDurationValues(this.model.spin.step);
        List<Object> packets = new ArrayList<>(this.model.spin.parts.length);
        for (int part : this.model.spin.parts) {
            packets.add(nms.dataPacket(this.entityIds[part], values));
        }
        return nms.bundle(packets);
    }

    /**
     * The packet that makes a client create every display entity of the block, in one frame.
     */
    Object spawnPacket(NmsBridge nms) {
        if (this.spawnPacket == null) {
            List<DisplayModel.Element> elements = this.model.elements;
            List<Object> packets = new ArrayList<>(elements.size() * 2);
            for (int i = 0; i < elements.size(); i++) {
                DisplayModel.Element element = elements.get(i);
                double[] pose = pose(element.pitch());
                double sink = sink(i);
                packets.add(nms.spawnPacket(this.entityIds[i], UUID.randomUUID(), pose[0], pose[1] - sink, pose[2], (float) pose[4], (float) pose[3], element.type()));
                // the culling box of a model is that of a model standing upright on its block
                List<?> data = upright(element) ? element.upright() : element.data();
                if (sink > 0) {
                    // a client lights an entity where it stands: the lowered one keeps the light of the block
                    org.bukkit.block.Block lit = this.world.getBlockAt((int) Math.floor(pose[0]), (int) Math.floor(pose[1]), (int) Math.floor(pose[2]));
                    data = nms.litValues(data, lit.getLightFromBlocks(), lit.getLightFromSky());
                }
                data = sent(nms, i, data);
                if (!data.isEmpty()) {
                    packets.add(nms.dataPacket(this.entityIds[i], data));
                }
            }
            this.spawnPacket = nms.bundle(packets);
        }
        return this.spawnPacket;
    }

    Object removePacket(NmsBridge nms) {
        if (this.removePacket == null) {
            this.removePacket = nms.removePacket(this.allIds);
        }
        return this.removePacket;
    }

    /**
     * The placement to store with the block, or null when it follows from its position and rotation.
     */
    String placement() {
        if (this.fixedPose != null) {
            return String.format(Locale.ROOT, "%s;%s;%s;%s;%s;%s;%s;%s", this.baseX, this.baseY, this.baseZ,
                    this.fixedPose[0], this.fixedPose[1], this.fixedPose[2], (float) this.fixedPose[3], (float) this.fixedPose[4]);
        }
        return isOffCentre() ? String.format(Locale.ROOT, "%s;%s;%s", this.baseX, this.baseY, this.baseZ) : null;
    }

    /**
     * Reads back what {@link #placement()} stored.
     */
    void placement(String stored) {
        if (stored == null || stored.isEmpty()) {
            return;
        }
        String[] parts = stored.split(";");
        try {
            if (parts.length >= 3) {
                this.baseX = Double.parseDouble(parts[0]);
                this.baseY = Double.parseDouble(parts[1]);
                this.baseZ = Double.parseDouble(parts[2]);
            }
            if (parts.length >= 8) {
                this.fixedPose = new double[]{Double.parseDouble(parts[3]), Double.parseDouble(parts[4]), Double.parseDouble(parts[5]),
                        Double.parseDouble(parts[6]), Double.parseDouble(parts[7])};
            }
        } catch (NumberFormatException ignored) {
            // an unreadable placement leaves the block where its position and rotation put it
        }
    }
}
