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
    /**
     * Whether its file gives it a click box. One without is picked by what it draws when an operator moves it.
     */
    final boolean clickable;
    final List<Element> elements;
    final List<DisplayAnimation> animations;
    /**
     * Whether one of its animations plays in a loop.
     */
    final boolean loops;
    /**
     * Whether one of its entities stands lower than the block when it stands on its block (see {@link Element#sink()}).
     */
    final boolean sinks;
    /**
     * How far, in blocks, players are sent the model: its file's {@code display.view-distance}, 0 for the distance
     * the world tracks display entities at.
     */
    final int viewDistance;
    /**
     * Its file's {@code display.visible-if}: the conditions a player must meet to be sent the model, empty for a model
     * everyone is sent.
     */
    final List<Condition> visibleIf;

    private DisplayModel(AbstractCustomBlock definition, List<Element> elements, List<DisplayAnimation> animations, int viewDistance,
                         List<Condition> visibleIf) {
        this.name = definition.getName();
        this.viewDistance = viewDistance;
        this.visibleIf = visibleIf;
        this.sidesCount = definition.getSidesCount();
        this.hitboxSignature = BDCCustomBlockService.hitboxSignature(definition);
        this.clickable = definition.getConfiguredInteractions().stream().anyMatch(interaction -> !BDCCustomBlockService.isUseless(interaction));
        this.elements = elements;
        this.animations = animations;
        this.loops = animations.stream().anyMatch(animation -> animation.trigger == DisplayAnimation.Trigger.LOOP);
        this.sinks = elements.stream().anyMatch(element -> element.sink() > 0);

        GroupSummoner<Display> summoner = definition.getDisplaySummoner();
        DirectedVector translation = summoner instanceof TranslationVectorAdjustable adjustable ? adjustable.getTranslation() : null;
        this.translationYaw = translation == null ? 0 : translation.getYaw();
        this.translationPitch = translation == null ? 0 : translation.getPitch();
    }

    /**
     * The same model with other animations (as many, in the same order): its entities do not change.
     */
    DisplayModel withAnimations(List<DisplayAnimation> animations) {
        return new DisplayModel(this, animations);
    }

    private DisplayModel(DisplayModel model, List<DisplayAnimation> animations) {
        this.name = model.name;
        this.sidesCount = model.sidesCount;
        this.translationYaw = model.translationYaw;
        this.translationPitch = model.translationPitch;
        this.hitboxSignature = model.hitboxSignature;
        this.clickable = model.clickable;
        this.elements = model.elements;
        this.animations = List.copyOf(animations);
        this.loops = model.loops;
        this.sinks = model.sinks;
        this.viewDistance = model.viewDistance;
        this.visibleIf = model.visibleIf;
        this.bounds = model.bounds;
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
     * @param shape          what it draws before its transformation, as a box (smallest x, y, z then largest), null
     *                       when that is not known
     * @param billboard      how it turns to face the player: {@link #BILLBOARD_FIXED}, {@link #BILLBOARD_VERTICAL} or
     *                       {@link #BILLBOARD_ALL}
     * @param upright        its synced data when the model stands on its block: with a culling box if it can have one
     * @param sink           how much lower than the model its entity stands when the model stands on its block, in
     *                       blocks of the model, its translation raised as much (0 for none): a client draws a culling box
     *                       from the entity up
     */
    record Element(Object type, List<?> data, float yaw, float pitch, Transformation transformation, boolean block, boolean drawn,
                   float[] shape, byte billboard, List<?> upright, float sink) {
    }

    /**
     * Reads the model of a custom block by summoning it for an instant, far above the given chunk, which must be
     * loaded with its entities.
     *
     * @param animations   the {@code animations} section of its file, or null
     * @param viewDistance how far players are sent it, in blocks, 0 for the world's distance
     * @param visibleIf    the conditions a player must meet to be sent it, empty for none
     * @param problems     receives what could not be read of its animations
     * @return the model, without any element when the summon commands of the block create nothing
     */
    static DisplayModel read(NmsBridge nms, AbstractCustomBlock definition, List<String[]> summonTexts, World world, int chunkX, int chunkZ,
                             ConfigurationSection animations, int viewDistance, List<Condition> visibleIf, List<String> problems) {
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
        return new DisplayModel(definition, List.copyOf(withCullingBox(nms, elements, read)), read, viewDistance, visibleIf);
    }

    /**
     * Whether only the players who meet its {@code display.visible-if} are sent the model.
     */
    boolean conditional() {
        return !this.visibleIf.isEmpty();
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
     * How far under its base a part may be lowered so that its box holds it: past that, it stays drawn at all times.
     */
    private static final float MAX_SINK = 32f;

    /**
     * A display entity without a culling box is drawn whenever it is in range, even behind the player. Each entity
     * of a model standing on its block gets its own box: a cylinder around the vertical axis of the model, from its
     * base to the top of what the entity draws, with some room, holding it at rest, in every pose of its animations
     * and on the way between them, as the clients draw it.
     * <p>
     * A client draws a box from the entity up only. A part that goes further under the base than the half block of
     * room clients add is lowered by its {@link Element#sink() sink}: its entity stands that much lower and its
     * translation is raised as much, so that it is drawn at the same place (see {@link PlacedDisplay}).
     * <p>
     * Texts are boxed by the size their text can take at most, parts turning on themselves to face the player
     * (billboard {@code vertical}) by their cylinder, which they do not leave; parts facing the player on every axis
     * by the sphere they turn in. An entity whose pose cannot be followed keeps being drawn at all times.
     */
    private static List<Element> withCullingBox(NmsBridge nms, List<Element> elements, List<DisplayAnimation> animations) {
        List<Element> boxed = new ArrayList<>(elements.size());
        for (int part = 0; part < elements.size(); part++) {
            Element element = elements.get(part);
            if (!element.drawn() || element.shape() == null) {
                boxed.add(element);
                continue;
            }
            // radius around the vertical axis, height of the top, height of the bottom
            float[] reach = {0, -Float.MAX_VALUE, Float.MAX_VALUE};
            boolean followed = true;
            reach(element, element.transformation(), reach);
            for (DisplayAnimation animation : animations) {
                // the poses the part goes through, in their order: rest, those of its frames, rest again
                List<Transformation> path = new ArrayList<>();
                path.add(element.transformation());
                for (DisplayAnimation.Frame frame : animation.frames) {
                    for (int i = 0; i < frame.parts().length; i++) {
                        if (frame.parts()[i] == part) {
                            if (frame.targets()[i] == null) {
                                followed = false;
                            }
                            path.add(frame.targets()[i]);
                        }
                    }
                }
                if (!followed) {
                    break;
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
                        reach(element, between(path.get(i - 1), path.get(i), step / (float) INTERPOLATION_STEPS), reach);
                    }
                }
            }
            float sink = reach[2] < -0.5f ? (float) Math.ceil((-reach[2] - 0.5f) * 16f + 1f) / 16f : 0f;
            float top = reach[1] + sink;
            if (!followed || sink > MAX_SINK || top <= 0 || !nms.canAnimate() && sink > 0) {
                boxed.add(element);
                continue;
            }
            // the box starts at the entity: what little goes under it is covered by the room given all around
            float width = reach[0] * 2.2f + 1.5f;
            float height = top * 1.1f + 0.75f;
            boxed.add(new Element(element.type(), element.data(), element.yaw(), element.pitch(), element.transformation(), element.block(),
                    element.drawn(), element.shape(), element.billboard(), nms.withCullingBox(element.data(), width, height), sink));
        }
        return boxed;
    }

    /**
     * Widens a reach (radius around the vertical axis, height of the top, height of the bottom) to hold what an
     * element draws in a pose.
     */
    private static void reach(Element element, Transformation pose, float[] reach) {
        org.joml.Matrix4f matrix = new org.joml.Matrix4f().translate(pose.getTranslation()).rotate(pose.getLeftRotation())
                .scale(pose.getScale()).rotate(pose.getRightRotation());
        float[] shape = element.shape();
        org.joml.Vector3f corner = new org.joml.Vector3f();
        for (int i = 0; i < 8; i++) {
            matrix.transformPosition(corner.set((i & 1) == 0 ? shape[0] : shape[3], (i & 2) == 0 ? shape[1] : shape[4], (i & 4) == 0 ? shape[2] : shape[5]));
            if (element.billboard() == BILLBOARD_ALL) {
                // turned on every axis around the entity: anywhere on the sphere it draws at
                float distance = corner.length();
                reach[0] = Math.max(reach[0], distance);
                reach[1] = Math.max(reach[1], distance);
                reach[2] = Math.min(reach[2], -distance);
            } else {
                // fixed, or turned around the vertical axis only, which keeps it in the same cylinder
                reach[0] = Math.max(reach[0], (float) Math.sqrt(corner.x * corner.x + corner.z * corner.z));
                reach[1] = Math.max(reach[1], corner.y);
                reach[2] = Math.min(reach[2], corner.y);
            }
        }
    }

    static final byte BILLBOARD_FIXED = 0;
    static final byte BILLBOARD_VERTICAL = 1;
    static final byte BILLBOARD_ALL = 2;

    /**
     * Width of a character of text at most, in pixels of a text display (bold, and the wide signs of the fallback
     * font, included).
     */
    private static final int CHARACTER_WIDTH = 10;

    /**
     * What a display entity draws around its origin before its transformation, as a box (smallest x, y, z then
     * largest x, y, z), in blocks.
     * <p>
     * A text display draws its text flat, centred on its origin, from the origin up, at 40 pixels a block: lines of
     * 10 pixels, as wide as their text up to the line width, past which a line wraps. The size is taken at its
     * largest: every character as wide as the widest, a wrapped line counted twice.
     */
    private static float[] shape(Display display) {
        if (display instanceof BlockDisplay) {
            return new float[]{0, 0, 0, 1, 1, 1};
        }
        if (!(display instanceof org.bukkit.entity.TextDisplay text)) {
            return new float[]{-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f};
        }
        String legacy = text.getText();
        String plain = legacy == null ? "" : org.bukkit.ChatColor.stripColor(legacy);
        int lineWidth = Math.max(1, text.getLineWidth());
        int widest = 0;
        int lines = 0;
        for (String line : plain.split("\n", -1)) {
            int width = line.codePointCount(0, line.length()) * CHARACTER_WIDTH;
            if (width <= lineWidth) {
                lines++;
                widest = Math.max(widest, width);
            } else {
                lines += 2 * (int) Math.ceil(width / (double) lineWidth);
                widest = lineWidth;
            }
        }
        float halfWidth = (widest / 2f + 4) / 40f;
        float height = (lines * 10 + 4) / 40f;
        return new float[]{-halfWidth, -0.1f, -0.1f, halfWidth, height, 0.1f};
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
        byte billboard = switch (display.getBillboard()) {
            case FIXED -> BILLBOARD_FIXED;
            case VERTICAL -> BILLBOARD_VERTICAL;
            default -> BILLBOARD_ALL;
        };
        elements.add(new Element(nms.type(handle), nms.syncedValues(handle), location.getYaw(), location.getPitch(), display.getTransformation(),
                display instanceof BlockDisplay, isDrawn(display), shape(display), billboard, null, 0f));
        for (Entity passenger : display.getPassengers()) {
            if (passenger instanceof Display child) {
                collect(nms, child, elements);
            }
        }
    }
}
