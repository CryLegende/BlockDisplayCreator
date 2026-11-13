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
     * One packet the client applies in a single frame.
     */
    Object bundle(List<Object> packets) {
        try {
            return this.newBundle.invoke((Iterable<Object>) packets);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    void send(Player player, Object packet) {
        try {
            Object connection = this.getConnection.invoke(this.getHandle.invoke(player));
            if (connection != null) {
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
