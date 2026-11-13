package me.general_breddok.blockdisplaycreator.render;

import org.bukkit.Bukkit;
import org.bukkit.UnsafeValues;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

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
