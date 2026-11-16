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
    /**
     * How far, in blocks, players are sent the model: its file's {@code display.view-distance}, 0 for the distance
     * the world tracks display entities at.
     */
    final int viewDistance;

    private DisplayModel(AbstractCustomBlock definition, List<Element> elements, List<DisplayAnimation> animations, int viewDistance) {
        this.name = definition.getName();
        this.viewDistance = viewDistance;
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
     * @param animations   the {@code animations} section of its file, or null
     * @param viewDistance how far players are sent it, in blocks, 0 for the world's distance
     * @param problems     receives what could not be read of its animations
     * @return the model, without any element when the summon commands of the block create nothing
     */
    static DisplayModel read(NmsBridge nms, AbstractCustomBlock definition, List<String[]> summonTexts, World world, int chunkX, int chunkZ,
                             ConfigurationSection animations, int viewDistance, List<String> problems) {
        Location location = new Location(world, (chunkX << 4) + 8.5, world.getMaxHeight() + READING_HEIGHT, (chunkZ << 4) + 8.5);
        List<Element> elements = new ArrayList<>();

        Interaction sender = world.spawn(location, Interaction.class);
        List<Display> displays;
        try {
            displays = summonTexts == null ? definition.spawnDisplay(location, sender, display -> true) : summon(definition, summonTexts, location, sender);
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
        return new DisplayModel(definition, List.copyOf(withCullingBox(nms, elements, read)), read, viewDistance);
    }

    /**
     * How far what the model draws reaches at rest: its radius around the vertical axis, then its height above the
     * floor, in blocks.
     */
    float[] extent() {
        float radius = 0;
        float top = 0;
        org.joml.Vector3f corner = new org.joml.Vector3f();
        for (Element element : this.elements) {
            if (!element.drawn()) {
                continue;
            }
            Transformation rest = element.transformation();
            org.joml.Matrix4f matrix = new org.joml.Matrix4f().translate(rest.getTranslation()).rotate(rest.getLeftRotation())
                    .scale(rest.getScale()).rotate(rest.getRightRotation());
            float low = element.block() ? 0f : -0.5f;
            float high = element.block() ? 1f : 0.5f;
            for (int i = 0; i < 8; i++) {
                matrix.transformPosition(corner.set((i & 1) == 0 ? low : high, (i & 2) == 0 ? low : high, (i & 4) == 0 ? low : high));
                radius = Math.max(radius, (float) Math.sqrt(corner.x * corner.x + corner.z * corner.z));
                top = Math.max(top, corner.y);
            }
        }
        return new float[]{radius, top};
    }

    private float[] bounds;

    /**
     * The box that holds what the model draws at rest: its smallest corner, then its largest (x, y, z each), in
     * blocks around the entity. All zero for a model that draws nothing.
     */
    float[] bounds() {
        if (this.bounds == null) {
            float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            org.joml.Vector3f corner = new org.joml.Vector3f();
            boolean any = false;
            for (Element element : this.elements) {
                if (!element.drawn()) {
                    continue;
                }
                Transformation rest = element.transformation();
                org.joml.Matrix4f matrix = new org.joml.Matrix4f().translate(rest.getTranslation()).rotate(rest.getLeftRotation())
                        .scale(rest.getScale()).rotate(rest.getRightRotation());
                float low = element.block() ? 0f : -0.5f;
                float high = element.block() ? 1f : 0.5f;
                for (int i = 0; i < 8; i++) {
                    matrix.transformPosition(corner.set((i & 1) == 0 ? low : high, (i & 2) == 0 ? low : high, (i & 4) == 0 ? low : high));
                    box[0] = Math.min(box[0], corner.x);
                    box[1] = Math.min(box[1], corner.y);
                    box[2] = Math.min(box[2], corner.z);
                    box[3] = Math.max(box[3], corner.x);
                    box[4] = Math.max(box[4], corner.y);
                    box[5] = Math.max(box[5], corner.z);
                    any = true;
                }
            }
            this.bounds = any ? box : new float[6];
        }
        return this.bounds;
    }

    /**
     * The middle of {@link #bounds()}: where an operator's tool is shown and what the block turns around.
     */
    org.joml.Vector3d centre() {
        float[] b = bounds();
        return new org.joml.Vector3d((b[0] + b[3]) / 2.0, (b[1] + b[4]) / 2.0, (b[2] + b[5]) / 2.0);
    }

    /**
     * Steps a pose change is looked at in, on the way the clients draw it, when an animated model gets its box.
     */
    private static final int INTERPOLATION_STEPS = 8;

    /**
     * A display entity without a culling box is drawn whenever it is in range, even behind the player. A model
     * standing on its block gets one box for all its entities: a cylinder around its vertical axis, from its base
     * to its top, with some room. An animated model gets the box that holds it in every pose of its animations and
     * on the way between them, as the clients draw it. Models that reach below their base (at rest or in a pose),
     * or whose entities face the player, keep being drawn at all times.
     */
    private static List<Element> withCullingBox(NmsBridge nms, List<Element> elements, List<DisplayAnimation> animations) {
        // radius around the vertical axis, then height above the base
        float[] reach = {0, 0};
        for (Element element : elements) {
            if (!element.drawn()) {
                continue;
            }
            if (!element.boxed() || !reach(element, element.transformation(), reach)) {
                return elements;
            }
        }
        for (DisplayAnimation animation : animations) {
            for (int part = 0; part < elements.size(); part++) {
                Element element = elements.get(part);
                if (!element.drawn()) {
                    continue;
                }
                // the poses the part goes through, in their order: rest, those of its frames, rest again
                List<Transformation> path = new ArrayList<>();
                path.add(element.transformation());
                for (DisplayAnimation.Frame frame : animation.frames) {
                    for (int i = 0; i < frame.parts().length; i++) {
                        if (frame.parts()[i] == part) {
                            if (frame.targets()[i] == null) {
                                return elements;
                            }
                            path.add(frame.targets()[i]);
                        }
                    }
                }
                if (path.size() == 1) {
                    continue;
                }
                path.add(element.transformation());
                if (animation.trigger == DisplayAnimation.Trigger.LOOP) {
                    // a new round starts from where the last one left the part
                    path.add(path.get(path.size() - 2));
                    path.add(path.get(1));
                }
                for (int i = 1; i < path.size(); i++) {
                    for (int step = 1; step <= INTERPOLATION_STEPS; step++) {
                        if (!reach(element, between(path.get(i - 1), path.get(i), step / (float) INTERPOLATION_STEPS), reach)) {
                            return elements;
                        }
                    }
                }
            }
        }
        float radius = reach[0];
        float top = reach[1];
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

    /**
     * Widens a reach (radius around the vertical axis, height above the base) to hold an element in a pose.
     *
     * @return false when the element goes under its base, where the box does not reach
     */
    private static boolean reach(Element element, Transformation pose, float[] reach) {
        org.joml.Matrix4f matrix = new org.joml.Matrix4f().translate(pose.getTranslation()).rotate(pose.getLeftRotation())
                .scale(pose.getScale()).rotate(pose.getRightRotation());
        // a block display draws the unit cube, the others are drawn around their origin
        float low = element.block() ? 0f : -0.5f;
        float high = element.block() ? 1f : 0.5f;
        org.joml.Vector3f corner = new org.joml.Vector3f();
        for (int i = 0; i < 8; i++) {
            matrix.transformPosition(corner.set((i & 1) == 0 ? low : high, (i & 2) == 0 ? low : high, (i & 4) == 0 ? low : high));
            if (corner.y < -0.6f) {
                return false;
            }
            reach[0] = Math.max(reach[0], (float) Math.sqrt(corner.x * corner.x + corner.z * corner.z));
            reach[1] = Math.max(reach[1], corner.y);
        }
        return true;
    }

    /**
     * A pose on the way from one to another, as a client draws it: translation and scale in a straight line, the
     * rotations along the shortest arc.
     */
    private static Transformation between(Transformation from, Transformation to, float progress) {
        return new Transformation(
                new org.joml.Vector3f(from.getTranslation()).lerp(to.getTranslation(), progress),
                new org.joml.Quaternionf(from.getLeftRotation()).slerp(to.getLeftRotation(), progress),
                new org.joml.Vector3f(from.getScale()).lerp(to.getScale(), progress),
                new org.joml.Quaternionf(from.getRightRotation()).slerp(to.getRightRotation(), progress));
    }

    /**
     * Summons a model from the text of its commands, as its summoner does, without building that text again.
     */
    private static List<Display> summon(AbstractCustomBlock definition, List<String[]> summonTexts, Location location, Interaction sender) {
        World world = location.getWorld();
        Boolean feedback = world.getGameRuleValue(org.bukkit.GameRule.SEND_COMMAND_FEEDBACK);
        Boolean logged = world.getGameRuleValue(org.bukkit.GameRule.LOG_ADMIN_COMMANDS);
        sender.addAttachment(me.general_breddok.blockdisplaycreator.BlockDisplayCreator.getInstance(),
                me.general_breddok.blockdisplaycreator.permission.DefaultPermissions.Vanilla.Command.SUMMON, true);
        world.setGameRule(org.bukkit.GameRule.SEND_COMMAND_FEEDBACK, false);
        world.setGameRule(org.bukkit.GameRule.LOG_ADMIN_COMMANDS, false);
        try {
            String at = " " + location.getX() + " " + location.getY() + " " + location.getZ() + " ";
            for (String[] text : summonTexts) {
                org.bukkit.Bukkit.dispatchCommand(sender, text[0] + at + text[1]);
            }
        } finally {
            world.setGameRule(org.bukkit.GameRule.SEND_COMMAND_FEEDBACK, feedback == null || feedback);
            world.setGameRule(org.bukkit.GameRule.LOG_ADMIN_COMMANDS, logged == null || logged);
        }
        DirectedVector translation = definition.getDisplaySummoner() instanceof TranslationVectorAdjustable adjustable ? adjustable.getTranslation() : null;
        List<Display> displays = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(location, 0.01, 0.01, 0.01, entity -> entity instanceof Display)) {
            Display display = (Display) entity;
            if (translation != null) {
                display.setTransformation(new me.general_breddok.blockdisplaycreator.world.TransformationBuilder(display).addTranslation(translation.clone()).build());
            }
            displays.add(display);
        }
        return displays;
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
