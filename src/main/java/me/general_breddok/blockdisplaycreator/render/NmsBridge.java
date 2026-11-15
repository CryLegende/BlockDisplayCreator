package me.general_breddok.blockdisplaycreator.render;

import org.bukkit.Bukkit;
import org.bukkit.UnsafeValues;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The few server internals needed to show display entities to players without the entities existing on the
 * server: reading the synced data of an entity, building the spawn, data and removal packets, sending them.
 * <p>
 * Everything is looked up once, by its Mojang name. On a server where one of them is missing, {@link #create()}
 * fails and custom blocks keep their real entities.
 */
final class NmsBridge {

    private final MethodHandle getHandle;
    private final MethodHandle getEntityData;
    private final MethodHandle getType;
    private final MethodHandle getNonDefaultValues;
    private final MethodHandle newAddEntity;
    private final MethodHandle newSetData;
    private final MethodHandle newRemove;
    private final MethodHandle newBundle;
    private final MethodHandle getConnection;
    private final MethodHandle send;
    private final MethodHandle nextEntityId;
    private final Object noMovement;
    /**
     * What moving a display entity takes, or null on a server that names it otherwise: models are then shown
     * without their animations.
     */
    private final Poses poses;
    /**
     * What teleporting an entity and setting its flags takes, or null on a server that names it otherwise: display
     * entities can then not be moved once shown.
     */
    private final Moves moves;
    /**
     * Why {@link #moves} is missing, when it is.
     */
    private final Throwable movesFailure;

    private NmsBridge() throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        String craftBukkit = Bukkit.getServer().getClass().getPackage().getName();

        Method handle = Class.forName(craftBukkit + ".entity.CraftEntity").getMethod("getHandle");
        Class<?> entity = handle.getReturnType();
        this.getHandle = lookup.unreflect(handle);

        Method entityData = entity.getMethod("getEntityData");
        this.getEntityData = lookup.unreflect(entityData);
        this.getNonDefaultValues = lookup.unreflect(entityData.getReturnType().getMethod("getNonDefaultValues"));

        Method type = entity.getMethod("getType");
        this.getType = lookup.unreflect(type);

        Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
        this.noMovement = vec3.getField("ZERO").get(null);

        String packets = "net.minecraft.network.protocol.game.";
        this.newAddEntity = lookup.findConstructor(Class.forName(packets + "ClientboundAddEntityPacket"),
                MethodType.methodType(void.class, int.class, UUID.class, double.class, double.class, double.class,
                        float.class, float.class, type.getReturnType(), int.class, vec3, double.class));
        this.newSetData = lookup.findConstructor(Class.forName(packets + "ClientboundSetEntityDataPacket"),
                MethodType.methodType(void.class, int.class, List.class));
        this.newRemove = lookup.findConstructor(Class.forName(packets + "ClientboundRemoveEntitiesPacket"),
                MethodType.methodType(void.class, int[].class));
        this.newBundle = lookup.findConstructor(Class.forName(packets + "ClientboundBundlePacket"),
                MethodType.methodType(void.class, Iterable.class));

        Class<?> serverPlayer = Class.forName(craftBukkit + ".entity.CraftPlayer").getMethod("getHandle").getReturnType();
        Field connection = serverPlayer.getField("connection");
        this.getConnection = lookup.unreflectGetter(connection);
        this.send = lookup.unreflect(connection.getType().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet")));

        this.nextEntityId = entityIdSource(lookup, craftBukkit);

        Poses poses;
        try {
            poses = new Poses(type.getDeclaringClass().getClassLoader());
        } catch (ReflectiveOperationException | RuntimeException e) {
            poses = null;
        }
        this.poses = poses;

        Moves moves;
        Throwable failure = null;
        try {
            moves = new Moves(lookup, packets, vec3, this.noMovement, type.getDeclaringClass().getClassLoader());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            moves = null;
            failure = e;
        }
        this.moves = moves;
        this.movesFailure = failure;
    }

    /**
     * Builds the packet that teleports an entity, and the synced values that smooth the teleports of a display.
     */
    private static final class Moves {
        private final MethodHandle newVec3;
        private final MethodHandle newPosition;
        private final MethodHandle newTeleport;
        private final Object noMovement;
        private final Method createValue;
        private final Object teleportDurationAccessor;
        private final Object flagsAccessor;

        Moves(MethodHandles.Lookup lookup, String packets, Class<?> vec3, Object noMovement, ClassLoader server) throws ReflectiveOperationException {
            Class<?> position = Class.forName("net.minecraft.world.entity.PositionMoveRotation", true, server);
            this.newVec3 = lookup.findConstructor(vec3, MethodType.methodType(void.class, double.class, double.class, double.class));
            this.newPosition = lookup.findConstructor(position, MethodType.methodType(void.class, vec3, vec3, float.class, float.class));
            this.newTeleport = lookup.findConstructor(Class.forName(packets + "ClientboundTeleportEntityPacket", true, server),
                    MethodType.methodType(void.class, int.class, position, java.util.Set.class, boolean.class));
            this.noMovement = noMovement;

            Class<?> accessor = Class.forName("net.minecraft.network.syncher.EntityDataAccessor", true, server);
            Class<?> value = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue", true, server);
            this.createValue = value.getMethod("create", accessor, Object.class);
            this.teleportDurationAccessor = field(Class.forName("net.minecraft.world.entity.Display", true, server), "DATA_POS_ROT_INTERPOLATION_DURATION_ID");
            this.flagsAccessor = field(Class.forName("net.minecraft.world.entity.Entity", true, server), "DATA_SHARED_FLAGS_ID");
        }

        private static Object field(Class<?> type, String name) throws ReflectiveOperationException {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        }

        Object teleport(int id, double x, double y, double z, float yaw, float pitch) throws Throwable {
            Object where = this.newVec3.invoke(x, y, z);
            Object change = this.newPosition.invoke(where, this.noMovement, yaw, pitch);
            return this.newTeleport.invoke(id, change, java.util.Set.of(), false);
        }

        Object teleportDuration(int ticks) throws ReflectiveOperationException {
            return this.createValue.invoke(null, this.teleportDurationAccessor, ticks);
        }

        Object flags(byte flags) throws ReflectiveOperationException {
            return this.createValue.invoke(null, this.flagsAccessor, flags);
        }
    }

    /**
     * Whether display entities can be teleported once shown on this server.
     */
    boolean canMove() {
        return this.moves != null;
    }

    /**
     * Why custom blocks cannot be moved on this server, or null when they can.
     */
    Throwable movesFailure() {
        return this.movesFailure;
    }

    /**
     * The packet that teleports a shown entity, with the yaw and pitch it ends up with.
     */
    Object teleportPacket(int id, double x, double y, double z, float yaw, float pitch) {
        try {
            return this.moves.teleport(id, x, y, z, yaw, pitch);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    /**
     * The synced values that make a display entity glide to a teleport over the given ticks instead of jumping.
     */
    List<?> teleportDurationValues(int ticks) {
        try {
            return List.of(this.moves.teleportDuration(ticks));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The synced value that sets the flags every entity has (bit 6 glowing, 0 for none).
     */
    Object flagsValue(byte flags) {
        try {
            return this.moves.flags(flags);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Builds the synced values that give a display entity a new transformation.
     */
    private static final class Poses {
        private final Constructor<?> decompose;
        private final Method translation;
        private final Method leftRotation;
        private final Method scale;
        private final Method rightRotation;
        private final Method createValue;
        private final Object startAccessor;
        private final Object durationAccessor;
        private final Object translationAccessor;
        private final Object leftRotationAccessor;
        private final Object scaleAccessor;
        private final Object rightRotationAccessor;
        private final Object widthAccessor;
        private final Object heightAccessor;
        private final Method accessorId;
        private final Method valueId;

        Poses(ClassLoader server) throws ReflectiveOperationException {
            // the server splits a matrix into the translation, rotations and scale a display entity is made of
            Class<?> transformation = Class.forName("com.mojang.math.Transformation", true, server);
            Constructor<?> decompose = null;
            for (Constructor<?> constructor : transformation.getConstructors()) {
                if (constructor.getParameterCount() == 1 && constructor.getParameterTypes()[0].isAssignableFrom(Matrix4f.class)) {
                    decompose = constructor;
                }
            }
            if (decompose == null) {
                throw new NoSuchMethodException("Transformation(Matrix4f)");
            }
            this.decompose = decompose;
            this.translation = either(transformation, "translation", "getTranslation");
            this.leftRotation = either(transformation, "leftRotation", "getLeftRotation");
            this.scale = either(transformation, "scale", "getScale");
            this.rightRotation = either(transformation, "rightRotation", "getRightRotation");

            Class<?> display = Class.forName("net.minecraft.world.entity.Display", true, server);
            this.startAccessor = accessor(display, "DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID");
            this.durationAccessor = accessor(display, "DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID");
            this.translationAccessor = accessor(display, "DATA_TRANSLATION_ID");
            this.leftRotationAccessor = accessor(display, "DATA_LEFT_ROTATION_ID");
            this.scaleAccessor = accessor(display, "DATA_SCALE_ID");
            this.rightRotationAccessor = accessor(display, "DATA_RIGHT_ROTATION_ID");

            this.widthAccessor = accessor(display, "DATA_WIDTH_ID");
            this.heightAccessor = accessor(display, "DATA_HEIGHT_ID");

            Class<?> accessor = Class.forName("net.minecraft.network.syncher.EntityDataAccessor", true, server);
            Class<?> value = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue", true, server);
            this.createValue = value.getMethod("create", accessor, Object.class);
            this.accessorId = accessor.getMethod("id");
            this.valueId = value.getMethod("id");
        }

        /**
         * The synced values of a display entity, with the box out of which a client stops drawing it. Values that
         * already give it one are left as they are.
         */
        List<?> withCullingBox(List<?> values, float width, float height) throws ReflectiveOperationException {
            Object widthId = this.accessorId.invoke(this.widthAccessor);
            Object heightId = this.accessorId.invoke(this.heightAccessor);
            for (Object value : values) {
                Object id = this.valueId.invoke(value);
                if (id.equals(widthId) || id.equals(heightId)) {
                    return values;
                }
            }
            List<Object> boxed = new java.util.ArrayList<>(values);
            boxed.add(this.createValue.invoke(null, this.widthAccessor, width));
            boxed.add(this.createValue.invoke(null, this.heightAccessor, height));
            return List.copyOf(boxed);
        }

        private static Method either(Class<?> type, String name, String olderName) throws NoSuchMethodException {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException e) {
                return type.getMethod(olderName);
            }
        }

        private static Object accessor(Class<?> display, String name) throws ReflectiveOperationException {
            Field field = display.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        }

        List<?> values(Matrix4f matrix, int duration) throws ReflectiveOperationException {
            Object transformation = this.decompose.newInstance(matrix);
            return values(this.translation.invoke(transformation), this.leftRotation.invoke(transformation),
                    this.scale.invoke(transformation), this.rightRotation.invoke(transformation), duration);
        }

        List<?> values(Object translation, Object leftRotation, Object scale, Object rightRotation, int duration) throws ReflectiveOperationException {
            return List.of(
                    this.createValue.invoke(null, this.startAccessor, 0),
                    this.createValue.invoke(null, this.durationAccessor, duration),
                    this.createValue.invoke(null, this.translationAccessor, translation),
                    this.createValue.invoke(null, this.leftRotationAccessor, leftRotation),
                    this.createValue.invoke(null, this.scaleAccessor, scale),
                    this.createValue.invoke(null, this.rightRotationAccessor, rightRotation));
        }
    }

    /**
     * Whether display entities can be given a new transformation on this server.
     */
    boolean canAnimate() {
        return this.poses != null;
    }

    /**
     * The synced values that take a display entity to a transformation.
     *
     * @param matrix   the transformation, as a matrix
     * @param duration the ticks the client takes to get there from where the entity is, 0 to jump
     */
    List<?> poseValues(Matrix4f matrix, int duration) {
        try {
            return this.poses.values(matrix, duration);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The synced values of a display entity, completed with a culling box of the given size around and above its
     * position. Returned unchanged on a server that does not allow it.
     */
    List<?> withCullingBox(List<?> values, float width, float height) {
        if (this.poses == null) {
            return values;
        }
        try {
            return this.poses.withCullingBox(values, width, height);
        } catch (ReflectiveOperationException e) {
            return values;
        }
    }

    /**
     * The synced values that take a display entity to a transformation given by its four parts.
     */
    List<?> poseValues(Vector3f translation, Quaternionf leftRotation, Vector3f scale, Quaternionf rightRotation, int duration) {
        try {
            return this.poses.values(translation, leftRotation, scale, rightRotation, duration);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Entity ids come from the server's own counter, so that none is ever shared with a real entity. Paper hands
     * them out through its unsafe values up to 1.21, and through the worlds since Minecraft 26.
     */
    private static MethodHandle entityIdSource(MethodHandles.Lookup lookup, String craftBukkit) throws ReflectiveOperationException {
        try {
            return lookup.unreflect(UnsafeValues.class.getMethod("nextEntityId")).bindTo(Bukkit.getUnsafe());
        } catch (NoSuchMethodException e) {
            Method handle = Class.forName(craftBukkit + ".CraftWorld").getMethod("getHandle");
            Object level = handle.invoke(Bukkit.getWorlds().get(0));
            return lookup.unreflect(handle.getReturnType().getMethod("getNextEntityId")).bindTo(level);
        }
    }

    /**
     * @throws ReflectiveOperationException when this server does not expose what the packet display needs
     */
    static NmsBridge create() throws ReflectiveOperationException {
        return new NmsBridge();
    }

    /**
     * The server-side object of an entity.
     */
    Object handle(Entity entity) {
        try {
            return this.getHandle.invoke(entity);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    /**
     * The entity type of a server-side entity, as the spawn packet wants it.
     */
    Object type(Object handle) {
        try {
            return this.getType.invoke(handle);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    /**
     * The synced values of a server-side entity that differ from the defaults of its type: what a client is sent
     * when it starts seeing the entity.
     */
    List<?> syncedValues(Object handle) {
        try {
            List<?> values = (List<?>) this.getNonDefaultValues.invoke(this.getEntityData.invoke(handle));
            return values == null ? List.of() : List.copyOf(values);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    Object spawnPacket(int id, UUID uuid, double x, double y, double z, float pitch, float yaw, Object type) {
        try {
            return this.newAddEntity.invoke(id, uuid, x, y, z, pitch, yaw, type, 0, this.noMovement, 0.0d);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    Object dataPacket(int id, List<?> values) {
        try {
            return this.newSetData.invoke(id, values);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    Object removePacket(int[] ids) {
        try {
            return this.newRemove.invoke(ids);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    /**
     * A client disconnects on a bundle of more than 4096 packets ("Too many packets in a bundle"), and a proxy or
     * ViaVersion may add some on the way: a bundle never holds more than this.
     */
    private static final int BUNDLE_LIMIT = 2000;

    /**
     * One packet the client applies in a single frame; more packets than a bundle may hold go in several bundles
     * sent one after the other, given back as a list that {@link #send} sends in order.
     */
    Object bundle(List<Object> packets) {
        try {
            if (packets.size() <= BUNDLE_LIMIT) {
                return this.newBundle.invoke((Iterable<Object>) packets);
            }
            List<Object> bundles = new java.util.ArrayList<>();
            for (int from = 0; from < packets.size(); from += BUNDLE_LIMIT) {
                bundles.add(this.newBundle.invoke((Iterable<Object>) new java.util.ArrayList<>(packets.subList(from, Math.min(packets.size(), from + BUNDLE_LIMIT)))));
            }
            return bundles;
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    void send(Player player, Object packet) {
        try {
            Object connection = this.getConnection.invoke(this.getHandle.invoke(player));
            if (connection == null) {
                return;
            }
            if (packet instanceof List<?> bundles) {
                for (Object bundle : bundles) {
                    this.send.invoke(connection, bundle);
                }
            } else {
                this.send.invoke(connection, packet);
            }
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    /**
     * An entity id no entity of the server uses or will use.
     */
    int nextEntityId() {
        try {
            return (int) this.nextEntityId.invoke();
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    private static RuntimeException failure(Throwable e) {
        if (e instanceof RuntimeException runtime) {
            return runtime;
        }
        if (e instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(e);
    }
}
