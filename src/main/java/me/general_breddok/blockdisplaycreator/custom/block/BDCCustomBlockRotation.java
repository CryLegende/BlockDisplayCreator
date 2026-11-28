package me.general_breddok.blockdisplaycreator.custom.block;

import lombok.*;
import lombok.experimental.FieldDefaults;
import me.general_breddok.blockdisplaycreator.rotation.EntityRotation;
import me.general_breddok.blockdisplaycreator.rotation.snapper.YawSnapper;
import me.general_breddok.blockdisplaycreator.util.EntityUtil;
import me.general_breddok.blockdisplaycreator.util.MathUtil;
import me.general_breddok.blockdisplaycreator.version.VersionCompat;
import org.apache.logging.log4j.util.TriConsumer;
import org.bukkit.Location;
import org.bukkit.attribute.Attributable;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Shulker;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

@Getter
@Setter
@EqualsAndHashCode
@FieldDefaults(level = AccessLevel.PACKAGE)
@AllArgsConstructor
public class BDCCustomBlockRotation implements CustomBlockRotation {
    BlockFace attachedFace;
    float direction;

    @Override
    public void adjustDisplayRotation(@NotNull Display display, int sidesCount) {

        EntityRotation displayRotation = new EntityRotation(display);

        if (this.attachedFace == BlockFace.UP || this.attachedFace == BlockFace.DOWN) {
            YawSnapper yawSnapper = new YawSnapper(sidesCount);
            float closestAngle = yawSnapper.getClosestAngle(this.direction);

            displayRotation.setYaw(closestAngle);
        } else {
            float yaw = switch (this.attachedFace) {
                case SOUTH -> 0;
                case WEST -> 90;
                case NORTH -> 180;
                case EAST -> 270;
                default -> 0;
            };

            displayRotation.setPitch(90);
            displayRotation.setYaw(yaw);

            if (!display.isInsideVehicle()) {
                withDirectionalOffset(this.attachedFace, 0.5, 0.499, (x, y, z) -> {
                    Location newLoc = display.getLocation().add(x, y, z);
                    EntityUtil.teleportEntityWithPassengers(display, newLoc);
                });
            }
        }

        displayRotation.applyToEntity(display);
    }

    @Override
    public void adjustInteractionRotation(@NotNull Interaction interaction, Vector offset, int sidesCount) {
        if (offset == null) {
            offset = new Vector(0, 0, 0);
        }

        Location location = interaction.getLocation();

        Vector calculatedOffset = calculateOffset(interaction, offset, sidesCount);

        // the offset given may be the one returned: it belongs to the definition of the block and is left as it is
        Vector subtracted = calculatedOffset.clone().subtract(offset);
        interaction.teleport(location.clone().add(subtracted));
    }

    @Override
    public void adjustCollisionRotation(@NotNull Shulker collision, Vector offset, int sidesCount) {
        if (offset == null) {
            offset = new Vector(0, 0, 0);
        }

        Entity vehicle = collision.getVehicle();

        Location location = Objects.requireNonNullElse(vehicle, collision).getLocation();

        Vector calculatedOffset = calculateOffset(collision, offset, sidesCount);

        // the offset given may be the one returned: it belongs to the definition of the block and is left as it is
        Vector subtracted = calculatedOffset.clone().subtract(offset);
        EntityUtil.teleportEntityWithVehicle(collision, location.clone().add(subtracted));
    }

    public Vector calculateOffset(Entity entity, Vector originalOffset, int sidesCount) {
        double[] half = halfSize(entity);
        return calculateOffset(half[0], half[1], originalOffset, sidesCount);
    }

    /**
     * Where an entity of these half sizes stands from the block it belongs to, for an offset of its file, as
     * {@link #adjustInteractionRotation} puts a summoned one: what a click box will be laid out at, worked out without it.
     */
    public Vector calculateOffset(double halfWidth, double halfHeight, Vector originalOffset, int sidesCount) {
        return switch (attachedFace) {
            case UP, DOWN -> rotateForVertical(originalOffset, sidesCount);
            case NORTH, SOUTH, EAST, WEST -> rotateForCardinal(halfWidth, halfHeight, originalOffset);
            default -> rotateForDiagonal(halfWidth, halfHeight, originalOffset, sidesCount);
        };
    }

    private Vector rotateForVertical(Vector originalOffset, int sidesCount) {
        if (MathUtil.isYOnly(originalOffset)) {
            return originalOffset;
        }

        float snappedAngle = new YawSnapper(sidesCount).getClosestAngle(direction);
        return originalOffset.clone().rotateAroundY(-Math.toRadians(snappedAngle));
    }

    private Vector rotateForCardinal(double halfWidth, double halfHeight, Vector originalOffset) {
        Vector rotated = originalOffset.clone()
                .rotateAroundY(getYawRadians(this.attachedFace));

        switch (this.attachedFace) {
            case NORTH, SOUTH -> rotated.rotateAroundX(Math.toRadians(this.attachedFace == BlockFace.SOUTH ? 90 : 270));
            case WEST, EAST -> rotated.rotateAroundZ(Math.toRadians(this.attachedFace == BlockFace.WEST ? 90 : 270));
        }


        applyStandardSideOffset(halfWidth, halfHeight, rotated);
        return rotated;
    }

    private Vector rotateForDiagonal(double halfWidth, double halfHeight, Vector originalOffset, int sidesCount) {
        Vector rotated = originalOffset.clone();
        float snappedYaw = new YawSnapper(sidesCount).getClosestAngle(this.direction);
        rotated.rotateAroundY(-Math.toRadians(snappedYaw));

        applyStandardSideOffset(halfWidth, halfHeight, rotated);
        return rotated;
    }

    /**
     * Half the width and half the height of an entity as its side offset counts them: an interaction's box, the scale
     * of a collision.
     */
    private static double[] halfSize(Entity entity) {
        double halfWidth = entity.getWidth() / 2;
        double halfHeight = entity.getHeight() / 2;

        if (entity instanceof Interaction interaction) {
            halfWidth = interaction.getInteractionWidth() / 2;
            halfHeight = interaction.getInteractionHeight() / 2;
        } else {
            Attribute scaleAttribute = VersionCompat.getScaleAttribute();
            if (scaleAttribute != null && entity instanceof Attributable attributable) {
                AttributeInstance attributeInstance = attributable.getAttribute(scaleAttribute);

                if (attributeInstance != null) {
                    double baseValue = attributeInstance.getBaseValue();
                    halfWidth = baseValue / 2;
                    halfHeight = baseValue / 2;
                }
            }
        }
        return new double[]{halfWidth, halfHeight};
    }

    private void applyStandardSideOffset(double halfWidth, double halfHeight, Vector originalOffset) {
        withDirectionalOffset(attachedFace, 0.5, 0.5, (x, y, z) -> originalOffset.add(new Vector(x, y, z)));
        if (halfWidth != 0 || halfHeight != 0) {
            withDirectionalOffset(attachedFace, -halfHeight, -halfWidth, (x, y, z) -> originalOffset.add(new Vector(x, y, z)));
        }
    }

    private double getYawRadians(BlockFace face) {
        return switch (face) {
            case SOUTH -> Math.toRadians(0);
            case EAST -> Math.toRadians(90);
            case NORTH -> Math.toRadians(180);
            case WEST -> Math.toRadians(270);
            default -> 0.0;
        };
    }

    private void withDirectionalOffset(BlockFace face, double y, double axisOffset, TriConsumer<Double, Double, Double> action) {
        double x = switch (face) {
            case WEST -> axisOffset;
            case EAST -> -axisOffset;
            default -> 0.0;
        };

        double z = switch (face) {
            case NORTH -> axisOffset;
            case SOUTH -> -axisOffset;
            default -> 0.0;
        };

        action.accept(x, y, z);
    }
}
