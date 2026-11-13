package me.general_breddok.blockdisplaycreator.render;

import me.general_breddok.blockdisplaycreator.custom.block.AbstractCustomBlock;
import me.general_breddok.blockdisplaycreator.custom.block.BDCCustomBlockService;
import me.general_breddok.blockdisplaycreator.entity.GroupSummoner;
import me.general_breddok.blockdisplaycreator.entity.display.TranslationVectorAdjustable;
import me.general_breddok.blockdisplaycreator.rotation.DirectedVector;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Transformation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the clients need to draw a custom block, read once from its file: the display entities its summon commands
 * create, each with its type and synced data exactly as the server would send them.
 */
final class DisplayModel {

    /**
     * How far above the build limit the model is summoned for the time of the reading, out of everyone's sight.
     */
    private static final int READING_HEIGHT = 1000;

    final String name;
    final short sidesCount;
    final float translationYaw;
    final float translationPitch;
    final String hitboxSignature;
    final List<Element> elements;
    final List<DisplayAnimation> animations;
    /**
     * Whether one of its animations plays in a loop.
     */
    final boolean loops;

    private DisplayModel(AbstractCustomBlock definition, List<Element> elements, List<DisplayAnimation> animations) {
        this.name = definition.getName();
        this.sidesCount = definition.getSidesCount();
        this.hitboxSignature = BDCCustomBlockService.hitboxSignature(definition);
        this.elements = elements;
        this.animations = animations;
        this.loops = animations.stream().anyMatch(animation -> animation.trigger == DisplayAnimation.Trigger.LOOP);

        GroupSummoner<Display> summoner = definition.getDisplaySummoner();
        DirectedVector translation = summoner instanceof TranslationVectorAdjustable adjustable ? adjustable.getTranslation() : null;
        this.translationYaw = translation == null ? 0 : translation.getYaw();
        this.translationPitch = translation == null ? 0 : translation.getPitch();
    }

    /**
     * One display entity of the model.
     *
     * @param type           its server-side entity type
     * @param data           its synced data, as a client receives it
     * @param yaw            the yaw it is summoned with
     * @param pitch          the pitch it is summoned with
     * @param transformation its resting transformation
     * @param block          whether it is a block display, drawn from its corner rather than around its origin
     * @param drawn          whether it shows something: the root of a bdengine model is an empty block display
     * @param boxed          whether a box drawn around the model is enough to tell if it is on screen
     * @param upright        its synced data when the model stands on its block: with a culling box if it can have one
     */
    record Element(Object type, List<?> data, float yaw, float pitch, Transformation transformation, boolean block, boolean drawn, boolean boxed,
                   List<?> upright) {
    }

    /**
     * Reads the model of a custom block by summoning it for an instant, far above the given chunk, which must be
     * loaded with its entities.
     *
     * @param animations the {@code animations} section of its file, or null
     * @param problems   receives what could not be read of its animations
     * @return the model, without any element when the summon commands of the block create nothing
     */
    static DisplayModel read(NmsBridge nms, AbstractCustomBlock definition, World world, int chunkX, int chunkZ,
                             ConfigurationSection animations, List<String> problems) {
        Location location = new Location(world, (chunkX << 4) + 8.5, world.getMaxHeight() + READING_HEIGHT, (chunkZ << 4) + 8.5);
        List<Element> elements = new ArrayList<>();

        Interaction sender = world.spawn(location, Interaction.class);
        List<Display> displays;
        try {
            displays = definition.spawnDisplay(location, sender, display -> true);
        } finally {
            sender.remove();
        }

        try {
            // a model made of several summon commands has several roots, created in the order of the commands
            displays.stream()
                    .filter(display -> display.getVehicle() == null)
                    .sorted(Comparator.comparingInt(Entity::getEntityId))
                    .forEach(root -> collect(nms, root, elements));
        } finally {
            for (Display display : displays) {
                display.getPassengers().forEach(Entity::remove);
                display.remove();
            }
        }
        List<DisplayAnimation> read = elements.isEmpty() ? List.of() : List.copyOf(DisplayAnimation.read(animations, elements, nms, problems));
        return new DisplayModel(definition, List.copyOf(read.isEmpty() ? withCullingBox(nms, elements) : elements), read);
    }

    /**
     * A display entity without a culling box is drawn whenever it is in range, even behind the player. A model
     * standing on its block gets one box for all its entities: a cylinder around its vertical axis, from its base
     * to its top, with some room. Models that reach below their base, that move, or whose entities face the
     * player keep being drawn at all times.
     */
    private static List<Element> withCullingBox(NmsBridge nms, List<Element> elements) {
        float radius = 0;
        float top = 0;
        org.joml.Vector3f corner = new org.joml.Vector3f();
        for (Element element : elements) {
            if (!element.drawn()) {
                continue;
            }
            if (!element.boxed()) {
                return elements;
            }
            Transformation rest = element.transformation();
            org.joml.Matrix4f matrix = new org.joml.Matrix4f().translate(rest.getTranslation()).rotate(rest.getLeftRotation())
                    .scale(rest.getScale()).rotate(rest.getRightRotation());
            // a block display draws the unit cube, the others are drawn around their origin
            float low = element.block() ? 0f : -0.5f;
            float high = element.block() ? 1f : 0.5f;
            for (int i = 0; i < 8; i++) {
                matrix.transformPosition(corner.set((i & 1) == 0 ? low : high, (i & 2) == 0 ? low : high, (i & 4) == 0 ? low : high));
                if (corner.y < -0.6f) {
                    return elements;
                }
                radius = Math.max(radius, (float) Math.sqrt(corner.x * corner.x + corner.z * corner.z));
                top = Math.max(top, corner.y);
            }
        }
        if (radius == 0 || top <= 0) {
            return elements;
        }
        // the box starts at the entity: what little goes under it is covered by the room given all around
        float width = radius * 2.2f + 1.5f;
        float height = top * 1.1f + 0.75f;
        List<Element> boxed = new ArrayList<>(elements.size());
        for (Element element : elements) {
            boxed.add(new Element(element.type(), element.data(), element.yaw(), element.pitch(), element.transformation(),
                    element.block(), element.drawn(), element.boxed(), nms.withCullingBox(element.data(), width, height)));
        }
        return boxed;
    }

    private static boolean isDrawn(Display display) {
        if (display instanceof BlockDisplay block) {
            return !block.getBlock().getMaterial().isAir();
        }
        if (display instanceof ItemDisplay item) {
            return item.getItemStack() != null && !item.getItemStack().getType().isAir();
        }
        return true;
    }

    private static void collect(NmsBridge nms, Display display, List<Element> elements) {
        Object handle = nms.handle(display);
        Location location = display.getLocation();
        elements.add(new Element(nms.type(handle), nms.syncedValues(handle), location.getYaw(), location.getPitch(), display.getTransformation(), display instanceof BlockDisplay, isDrawn(display),
                !(display instanceof org.bukkit.entity.TextDisplay) && display.getBillboard() == Display.Billboard.FIXED, null));
        for (Entity passenger : display.getPassengers()) {
            if (passenger instanceof Display child) {
                collect(nms, child, elements);
            }
        }
    }
}
