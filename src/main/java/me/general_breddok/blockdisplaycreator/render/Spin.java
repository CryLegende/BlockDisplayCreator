package me.general_breddok.blockdisplaycreator.render;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * Parts of a model that turn on and on around its vertical axis, written in the {@code spin} section of its file: a
 * carousel, a fan, a planet around a star. They turn through the yaw of their display entities, which a client glides
 * on its own between two packets ({@code teleport_duration}): one small rotation packet every {@code step} ticks,
 * where frames turning them would need about fourteen frames a turn to stay on the circle.
 * <pre>
 * spin:
 *   parts: [3, 4, "8-12"]  # display entities that turn, numbered as in the frames
 *   period: 240            # ticks a whole turn takes; clockwise seen from above, a negative period the other way
 *   step: 40               # ticks between two rotation packets, 59 at most and less than half the period. By def a
 *                          # third of the period, 40 at most
 * </pre>
 * They turn around the vertical line through where the block stands (the origin of their entities), as the block is
 * turned, and still play the frames of the animations that move them: their translation and rotations are in the
 * turned frame of their entity. A block hung on a wall or tipped by an operator does not spin (its vertical axis is no
 * longer the world's): its parts stay as placed.
 */
final class Spin {

    /**
     * The most ticks a client glides a rotation over.
     */
    static final int MAX_STEP = 59;

    /**
     * The parts that turn, in increasing order.
     */
    final int[] parts;
    /**
     * Ticks a whole turn takes, negative for the other way.
     */
    final int period;
    /**
     * Ticks between two rotation packets: also the glide of the parts.
     */
    final int step;

    private Spin(int[] parts, int period, int step) {
        this.parts = parts;
        this.period = period;
        this.step = step;
    }

    /**
     * Reads the {@code spin} section of the file of a block.
     *
     * @throws IllegalArgumentException with what is wrong in it, in words for the server owner
     */
    static Spin read(ConfigurationSection section, List<DisplayModel.Element> elements) {
        if (!section.contains("parts")) {
            throw new IllegalArgumentException("parts is missing: the numbers of the parts that turn, as in a frame");
        }
        int[] parts = DisplayAnimation.parts(section.get("parts"), elements.size());
        int period = DisplayAnimation.number(section.get("period"), 0).intValue();
        if (Math.abs(period) < 4) {
            throw new IllegalArgumentException("period must be a number of ticks a turn takes, 4 or more (negative to turn the other way), not "
                    + section.get("period"));
        }
        int step = DisplayAnimation.number(section.get("step"), Math.max(1, Math.min(40, Math.abs(period) / 3))).intValue();
        if (step < 1 || step > MAX_STEP || 2 * step >= Math.abs(period)) {
            throw new IllegalArgumentException("step must be from 1 to " + MAX_STEP + " ticks and less than half the period, not " + section.get("step"));
        }
        return new Spin(parts, period, step);
    }

    /**
     * Whether a part turns.
     */
    boolean turns(int part) {
        return java.util.Arrays.binarySearch(this.parts, part) >= 0;
    }

    /**
     * How far the parts have turned at a tick, in degrees added to the yaw of their entities.
     */
    float angle(long tick) {
        int length = Math.abs(this.period);
        float turned = 360f * Math.floorMod(tick, (long) length) / length;
        return this.period > 0 ? turned : -turned;
    }
}
