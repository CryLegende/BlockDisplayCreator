package me.general_breddok.blockdisplaycreator.render;

import me.general_breddok.blockdisplaycreator.custom.block.CustomBlockRotation;
import me.general_breddok.blockdisplaycreator.rotation.snapper.YawSnapper;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

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
     * Where the block was placed: the point its interactions and collisions are laid out around.
     */
    double baseX;
    double baseY;
    double baseZ;
    /**
     * Where its display entities stand and how they are turned, when it differs from what its rotation gives.
     */
    double[] fixedPose;

    DisplayModel model;
    int[] entityIds;
    private Object spawnPacket;
    private Object removePacket;

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
    }

    /**
     * Position (x, y, z) then rotation (yaw, pitch) of the display entities, as the block's rotation gives them to
     * real entities. The pitch only holds for an entity summoned without one.
     */
    double[] pose(float summonedPitch) {
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
                packets.add(nms.spawnPacket(this.entityIds[i], UUID.randomUUID(), pose[0], pose[1], pose[2], (float) pose[4], (float) pose[3], element.type()));
                if (!element.data().isEmpty()) {
                    packets.add(nms.dataPacket(this.entityIds[i], element.data()));
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
