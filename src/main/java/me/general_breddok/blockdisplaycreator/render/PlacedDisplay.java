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
    private Object[] restPackets;

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
        this.spawnPacket = null;
        this.removePacket = null;

        int animations = model.animations.size();
        this.startedAt = new int[animations];
        this.lastFrame = new int[animations];
        this.framePackets = new Object[animations][];
        this.restPackets = new Object[animations];
        java.util.Arrays.fill(this.startedAt, -1);
        java.util.Arrays.fill(this.lastFrame, -1);
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
        this.restPackets = new Object[model.animations.size()];
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
     * The packet that starts the parts of a frame towards their pose.
     */
    Object framePacket(NmsBridge nms, int animation, int frame) {
        Object[] packets = this.framePackets[animation];
        if (packets == null) {
            packets = this.framePackets[animation] = new Object[this.model.animations.get(animation).frames.length];
        }
        if (packets[frame] == null) {
            DisplayAnimation.Frame played = this.model.animations.get(animation).frames[frame];
            List<Object> parts = new ArrayList<>(played.parts().length);
            for (int i = 0; i < played.parts().length; i++) {
                parts.add(nms.dataPacket(this.entityIds[played.parts()[i]], sent(nms, played.parts()[i], played.moving()[i])));
            }
            packets[frame] = nms.bundle(parts);
        }
        return packets[frame];
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
        DisplayAnimation.Frame[] frames = this.model.animations.get(animation).frames;
        boolean[] posed = new boolean[this.entityIds.length];
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
        return nms.bundle(parts);
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
        DisplayAnimation loop = this.model.animations.get(animation);
        DisplayAnimation.Frame[] frames = loop.frames;
        boolean[] posed = new boolean[this.entityIds.length];
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
        if ((resized || this.model != null && this.model.sinks) && this.framePackets != null) {
            // the poses of the frames were multiplied by the old size, or raised for a block standing upright
            java.util.Arrays.fill(this.framePackets, null);
            java.util.Arrays.fill(this.restPackets, null);
        }
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
            yaw = new YawSnapper(this.model.sidesCount).getClosestAngle(this.rotation.getDirection());
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
            this.removePacket = nms.removePacket(this.entityIds);
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
