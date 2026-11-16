package me.general_breddok.blockdisplaycreator.custom.block;

import com.jeff_media.customblockdata.CustomBlockData;
import lombok.Getter;
import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.commandparser.CommandLine;
import me.general_breddok.blockdisplaycreator.common.DeprecatedFeatureAdapter;
import me.general_breddok.blockdisplaycreator.custom.AutomaticCommandDisplaySummoner;
import me.general_breddok.blockdisplaycreator.custom.CommandBundle;
import me.general_breddok.blockdisplaycreator.custom.block.option.CustomBlockBreakOption;
import me.general_breddok.blockdisplaycreator.custom.block.option.CustomBlockOption;
import me.general_breddok.blockdisplaycreator.custom.block.option.CustomBlockPlaceOption;
import me.general_breddok.blockdisplaycreator.data.manager.PersistentDataTypes;
import me.general_breddok.blockdisplaycreator.custom.ConfiguredCollision;
import me.general_breddok.blockdisplaycreator.custom.ConfiguredInteraction;
import me.general_breddok.blockdisplaycreator.entity.interaction.InteractionSummoner;
import me.general_breddok.blockdisplaycreator.entity.GroupSummoner;
import me.general_breddok.blockdisplaycreator.entity.display.TranslationVectorAdjustable;
import me.general_breddok.blockdisplaycreator.event.custom.block.CustomBlockBreakEvent;
import me.general_breddok.blockdisplaycreator.event.custom.block.CustomBlockPlaceEvent;
import me.general_breddok.blockdisplaycreator.file.config.value.StringMessagesValue;
import me.general_breddok.blockdisplaycreator.placeholder.universal.CustomBlockPlaceholder;
import me.general_breddok.blockdisplaycreator.placeholder.universal.LocationPlaceholder;
import me.general_breddok.blockdisplaycreator.render.DisplayRenderer;
import me.general_breddok.blockdisplaycreator.rotation.DirectedVector;
import me.general_breddok.blockdisplaycreator.rotation.EntityRotation;
import me.general_breddok.blockdisplaycreator.util.ChatUtil;
import me.general_breddok.blockdisplaycreator.util.EntityUtil;
import me.general_breddok.blockdisplaycreator.util.EventUtil;
import me.general_breddok.blockdisplaycreator.util.ItemUtil;
import me.general_breddok.blockdisplaycreator.util.OperationUtil;
import me.general_breddok.blockdisplaycreator.world.WorldSelection;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Implementation of {@link CustomBlockService} for managing custom blocks.
 * <p>
 * Handles storage and retrieval of custom block data using {@link CustomBlockData},
 * allowing block data to be stored per location without relying on a database.
 * </p>
 */
@Getter
public class BDCCustomBlockService implements CustomBlockService {

    /**
     * Storage for all custom blocks managed by this service.
     */
    CustomBlockStorage storage;

    /**
     * Creates a new instance of the service with the specified storage.
     *
     * @param storage the storage instance to use for custom blocks
     */
    public BDCCustomBlockService(CustomBlockStorage storage) {
        this.storage = storage;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public CustomBlockStorage getStorage() {
        return storage;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCustomBlockOnLocation(@NotNull Location location, Object... data) {
        CustomBlockData customBlockData = new CustomBlockData(location.getBlock(), BlockDisplayCreator.getInstance());

        return customBlockData.has(CustomBlockKey.NAME)
                && customBlockData.has(CustomBlockKey.BLOCK_ROTATION)
                && customBlockData.has(CustomBlockKey.DISPLAY_UUID)
                && customBlockData.has(CustomBlockKey.CUSTOM_BLOCK_UUID);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public CustomBlock getCustomBlock(@NotNull Location location, Object... data) {
        if (!isCustomBlockOnLocation(location)) {
            return null;
        }

        // Fetch the abstract custom block from storage
        CustomBlockData customBlockData = new CustomBlockData(location.getBlock(), BlockDisplayCreator.getInstance());

        String blockName = customBlockData.get(CustomBlockKey.NAME, PersistentDataType.STRING);

        AbstractCustomBlock abstractCustomBlock = storage.getAbstractCustomBlock(blockName);

        if (abstractCustomBlock == null) {
            return null;
        }

        List<Display> displays = new ArrayList<>();
        List<Interaction> interactions = new ArrayList<>();
        List<Shulker> collisions = new ArrayList<>();


        // Fetch display entity vehicles and their passengers
        UUID[] vehiclesUuids = customBlockData.get(CustomBlockKey.DISPLAY_UUID, PersistentDataTypes.UUID_ARRAY);

        for (UUID vehiclesUuid : vehiclesUuids) {
            Entity displayEntity = Bukkit.getEntity(vehiclesUuid);

            // If display entity was killed, or something else happened - skip it
            if (displayEntity == null) {
                continue;
            }

            Display display = (Display) displayEntity;
            displays.addAll(display.getPassengers().stream().map(Display.class::cast).collect(OperationUtil.toArrayList()));
            displays.add(display);
        }


        // Fetch interaction entities
        UUID[] interactionUuids = customBlockData.get(CustomBlockKey.INTERACTION_UUID, PersistentDataTypes.UUID_ARRAY);

        if (interactionUuids != null) {
            for (UUID interactionUuid : interactionUuids) {
                Entity interactionEntity = Bukkit.getEntity(interactionUuid);

                // If interaction entity was killed, or something else happened - skip it
                if (interactionEntity == null) {
                    continue;
                }

                interactions.add((Interaction) interactionEntity);
            }
        }


        // Fetch collision entities
        UUID[] collisionUuids = customBlockData.get(CustomBlockKey.COLLISION_UUID, PersistentDataTypes.UUID_ARRAY);

        if (collisionUuids != null) {
            for (UUID collisionUuid : collisionUuids) {
                Entity colisionEntity = Bukkit.getEntity(collisionUuid);

                if (colisionEntity != null) {
                    collisions.add((Shulker) colisionEntity);
                }
            }
        }


        if (abstractCustomBlock.getSaveSystem().equals("item")) {

            GroupSummoner<Display> displaySummoner = abstractCustomBlock.getDisplaySummoner();
            if (displaySummoner instanceof AutomaticCommandDisplaySummoner automaticCommandDisplaySummoner) {
                if (customBlockData.has(CustomBlockKey.DISPLAY_SPAWN_COMMAND)) {
                    List<CommandLine> commandLines = Arrays.stream(customBlockData.get(CustomBlockKey.DISPLAY_SPAWN_COMMAND, PersistentDataTypes.COMMAND_ARRAY)).collect(OperationUtil.toArrayList());
                    automaticCommandDisplaySummoner.setCommands(commandLines);
                }
            }

            if (customBlockData.has(CustomBlockKey.ITEM)) {
                ItemStack item = customBlockData.get(CustomBlockKey.ITEM, PersistentDataTypes.ITEM);
                abstractCustomBlock.setItem(item);
            }
        }


        // Fetch rotation
        CustomBlockRotation rotation = customBlockData.get(CustomBlockKey.BLOCK_ROTATION, PersistentDataTypes.CUSTOM_BLOCK_ROTATION);
        // Fetch UUID
        UUID uuid = customBlockData.get(CustomBlockKey.CUSTOM_BLOCK_UUID, PersistentDataTypes.UUID);

        uuid = DeprecatedFeatureAdapter.checkMissingCustomBlockUUID(uuid);

        // Construct and return the custom block
        return new BDCCustomBlock(abstractCustomBlock, location, rotation, displays, interactions, collisions, uuid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public CustomBlock getCustomBlock(@NotNull Entity entity, Object... data) {
        PersistentDataContainer interactionPDC = entity.getPersistentDataContainer();
        // Check if the entity has the necessary custom block data (location)
        if (!interactionPDC.has(CustomBlockKey.LOCATION, PersistentDataTypes.LOCATION)) {
            return null;
        }

        Location blockLocation = interactionPDC.get(CustomBlockKey.LOCATION, PersistentDataTypes.LOCATION);

        return getCustomBlock(blockLocation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CustomBlock placeBlock(@NotNull AbstractCustomBlock abstractCustomBlock, @NotNull Location location, @NotNull CustomBlockRotation rotation, @Nullable Player player, CustomBlockOption... options) throws IllegalArgumentException {
        Material centralMaterial = abstractCustomBlock.getCentralMaterial();
        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        LocationPlaceholder locationPlaceholder = new LocationPlaceholder(location);

        // Checking if a location has a world
        if (world == null) {
            throw new IllegalArgumentException("Location's world cannot be null");
        }

        // Parsing options
        boolean replaceCustomBlock = false;
        boolean breakSolidMaterial = false;
        boolean silentPlace = false;
        boolean loadChunk = false;

        // Iterate through provided options and set flags accordingly
        for (CustomBlockOption option : options) {

            if (option == null) {
                continue;
            }

            if (option == CustomBlockPlaceOption.REPLACE_CUSTOM_BLOCK) {
                replaceCustomBlock = true;
            } else if (option == CustomBlockPlaceOption.BREAK_SOLID_MATERIAL) {
                breakSolidMaterial = true;
            } else if (option == CustomBlockPlaceOption.SILENT_PLACE) {
                silentPlace = true;
            } else if (option == CustomBlockPlaceOption.LOAD_CHUNK) {
                loadChunk = true;
            }
        }

        // Check if the chunk is loaded on the location
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            // If the option is set, load the chunk, otherwise throw an error
            if (loadChunk) {
                location.getChunk().load();
            } else {
                throw new IllegalArgumentException(locationPlaceholder.apply(StringMessagesValue.CUSTOM_BLOCK_PLACE_FAIL_REASON_CHUNK_NOT_LOADED));
            }
        }

        Block block = location.getBlock();
        Material originalMaterial = block.getType();

        // Check if a solid material is present at the location
        if (!(WorldSelection.isEphemeral(originalMaterial) || WorldSelection.isSingleLayerSnow(block))) {
            // If the option is set, break the solid material, otherwise throw an error
            if (breakSolidMaterial) {
                block.setType(centralMaterial);
            } else {
                throw new IllegalArgumentException(locationPlaceholder.apply(StringMessagesValue.CUSTOM_BLOCK_PLACE_FAIL_REASON_SOLID_BLOCK));
            }
        } else {
            // Set the central block to the required material
            block.setType(centralMaterial);
        }


        // Check if a custom block is already present at the location
        if (this.isCustomBlockOnLocation(location)) {
            // If the option is set, break the existing custom block, otherwise throw an error
            if (replaceCustomBlock) {
                this.breakBlock(this.getCustomBlock(location), player);
            } else {
                throw new IllegalArgumentException(locationPlaceholder.apply(StringMessagesValue.CUSTOM_BLOCK_PLACE_FAIL_REASON_CUSTOM_BLOCK_PRESENT));
            }
        }


        List<UUID> displayVehicleUuids = new ArrayList<>();

        // A block shown through packets has no display entity: its model only has to be readable
        DisplayRenderer renderer = BlockDisplayCreator.getInstance().getDisplayRenderer();
        boolean packetDisplay = renderer != null && renderer.renders(abstractCustomBlock);
        List<Display> displays = packetDisplay ? new ArrayList<>() : summonDisplays(abstractCustomBlock, location, rotation, displayVehicleUuids);

        // If no display entities were summoned, something went wrong - revert the block change and log an error
        if (packetDisplay ? !renderer.prepare(abstractCustomBlock.getName(), location) : displays.isEmpty()) {
            block.setType(originalMaterial);
            ChatUtil.log("&6[BlockDisplayCreator] &4Something went wrong, custom block at location " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ() + " in %s world was not placed due to missing display entities. You may have specified the spawn command incorrectly.", world.getName());
            return null;
        }


        List<Interaction> interactions = summonInteractions(abstractCustomBlock, location, rotation);
        List<Shulker> collisions = summonCollisions(abstractCustomBlock, location, rotation);


        // Construct the raw custom block object
        CustomBlock rawCustomBlock = new BDCCustomBlock(abstractCustomBlock, location, rotation, displays, interactions, collisions, UUID.randomUUID());

        // Create and store custom block data on the location of placement
        CustomBlockData customBlockData = createCustomBlockData(rawCustomBlock, displayVehicleUuids);

        // Trigger the custom block place event and handle cancellation
        if (!triggerCustomBlockPlaceEvent(rawCustomBlock, player, customBlockData)) {
            return null;
        }

        if (packetDisplay) {
            renderer.placed(abstractCustomBlock.getName(), location, rotation, customBlockData);
        }

        // Play placement sound if the option is not set to silent
        if (!silentPlace) {
            abstractCustomBlock.playPlaceSound(location);
        }

        // Execute place commands if any are set
        CustomBlockStageSettings stageSettings = abstractCustomBlock.getStageSettings();
        if (stageSettings != null) {
            CommandBundle placeCommandBundle = stageSettings.getPlaceSettings().getCommandBundle();
            if (placeCommandBundle != null) {
                CommandBundle.CommandSource commandSource = placeCommandBundle.getCommandSource();
                if (commandSource == CommandBundle.CommandSource.PLAYER && player != null) {
                    placeCommandBundle.execute(player, new CustomBlockPlaceholder(rawCustomBlock));
                } else if (commandSource == CommandBundle.CommandSource.CONSOLE) {
                    placeCommandBundle.execute(ChatUtil.CONSOLE, new CustomBlockPlaceholder(rawCustomBlock));
                }
            }
        }

        return rawCustomBlock;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean breakBlock(@NotNull CustomBlock customBlock, @Nullable Player player, CustomBlockOption... options) throws IllegalArgumentException {
        Location location = customBlock.getLocation();
        World world = location.getWorld();
        LocationPlaceholder locationPlaceholder = new LocationPlaceholder(location);

        // Checking if a location has a world
        if (world == null) {
            throw new IllegalArgumentException("Location's world cannot be null");
        }

        // Check if a custom block is present at the location
        if (!isCustomBlockOnLocation(location)) {
            throw new IllegalArgumentException(locationPlaceholder.apply(StringMessagesValue.CUSTOM_BLOCK_BREAK_FAIL_REASON_NO_CUSTOM_BLOCK));
        }

        // Parsing options
        boolean dropItem = false;
        boolean silentBreak = false;

        // Iterate through provided options and set flags accordingly
        for (CustomBlockOption option : options) {

            if (option == null) {
                continue;
            }

            if (option == CustomBlockBreakOption.DROP_ITEM) {
                dropItem = true;
            } else if (option == CustomBlockBreakOption.SILENT_BREAK) {
                silentBreak = true;
            }
        }

        // Execute break commands if any are set (before the block is actually broken)
        CustomBlockStageSettings stageSettings = customBlock.getStageSettings();
        if (stageSettings != null) {
            CommandBundle breakCommandBundle = stageSettings.getBreakSettings().getCommandBundle();
            if (breakCommandBundle != null) {
                breakCommandBundle.execute(player, new CustomBlockPlaceholder[]{new CustomBlockPlaceholder(customBlock)});
            }
        }

        // Create custom block data instance for clearing later
        CustomBlockData customBlockData = new CustomBlockData(location.getBlock(), BlockDisplayCreator.getInstance());

        // Trigger the custom block break event and handle cancellation
        if (!triggerCustomBlockBreakEvent(customBlock, player)) {
            return false;
        }

        // where the block really stands: an operator may have moved it from where it was placed
        DisplayRenderer shown = BlockDisplayCreator.getInstance().getDisplayRenderer();
        Location dropAt = shown == null ? null : shown.dropPoint(location);
        if (dropAt == null) {
            dropAt = location;
        }

        // Remove all entities associated with the custom block
        customBlock.getDisplays().forEach(Entity::remove);
        removeStrayDisplays(location, customBlock.getName());
        customBlock.getInteractions().forEach(Entity::remove);
        customBlock.getCollisions().forEach(collision -> {
            Entity vehicle = collision.getVehicle();
            collision.remove();

            if (vehicle != null) {
                vehicle.remove();
            }
        });

        // Set the block to air to "break" it
        location.getBlock().setType(Material.AIR);

        // Clear all custom block data from the block
        clearCustomBlockData(customBlockData);

        // Drop the item if the option is set
        if (dropItem) {
            ItemStack customBlockItem = customBlock.getItem();
            // Ensure only one item is dropped
            customBlockItem.setAmount(1);

            // If stage settings are defined, use them to determine drop behavior
            if (stageSettings != null) {
                CustomBlockBreakSettings.DropMode dropMode = stageSettings.getBreakSettings().getDropMode();

                if (dropMode == CustomBlockBreakSettings.DropMode.INVENTORY && player != null) {

                    // Try to add the item to the player's inventory, and if full, drop it on the ground
                    ItemUtil.distributeItem(player, customBlockItem);
                } else if (dropMode == CustomBlockBreakSettings.DropMode.ON_GROUND) {

                    // Drop the item naturally on the ground
                    world.dropItemNaturally(dropAt, customBlockItem);
                }
            } else {
                // Drop the item naturally on the ground
                world.dropItemNaturally(dropAt, customBlockItem);
            }
        }

        // Play break sound if the option is not set to silent
        if (!silentBreak) {
            customBlock.playBreakSound(dropAt);
        }

        return true;
    }

    /**
     * Summons the display entities of a custom block and gives them their rotation and block data.
     *
     * @param displayVehicleUuids receives the UUID of each display entity that rides no other
     */
    public List<Display> summonDisplays(AbstractCustomBlock abstractCustomBlock, Location location, CustomBlockRotation rotation, List<UUID> displayVehicleUuids) {
        // Summon a temporary interaction entity to serve as the center for display entities
        Interaction centerEntity = location.getWorld().spawn(location, Interaction.class);

        // Summon display entities and iterate through them to apply the necessary settings
        List<Display> displays = abstractCustomBlock.spawnDisplay(location, centerEntity, display -> {
            // Store the UUID of the display vehicle if it's not on another vehicle.
            if (!display.isInsideVehicle()) {
                displayVehicleUuids.add(display.getUniqueId());
            }

            // Set persistent and invulnerable so they don't despawn or die
            display.setPersistent(true);
            display.setInvulnerable(true);

            // Adjust display rotation and translation depending on the settings selected
            rotation.adjustDisplayRotation(display, abstractCustomBlock.getSidesCount());
            applyDisplayTranslationRotation(abstractCustomBlock.getDisplaySummoner(), display);

            // Set custom block data for the display entity
            CustomBlockKey.holder(display)
                    .setServiceClass(BDCCustomBlockService.class.getName())
                    .setName(abstractCustomBlock.getName())
                    .setLocation(location);

            // Add scoreboard tags for easy identification and use in data packs
            display.addScoreboardTag("custom-block");
            display.addScoreboardTag("custom-block-name:" + abstractCustomBlock.getName());
            display.addScoreboardTag("custom-block-display");
            display.addScoreboardTag("custom-block-location:" + location.toVector());
            return true;
        });

        centerEntity.remove();
        return displays;
    }

    public static void applyDisplayTranslationRotation(@NotNull GroupSummoner<Display> displaySummoner, Display display) {
        if (displaySummoner instanceof TranslationVectorAdjustable translationVectorAdjustable) {
            DirectedVector translation = translationVectorAdjustable.getTranslation();
            if (translation != null) {
                EntityRotation displayRotation = new EntityRotation(display);
                displayRotation.addRotation(translation.getYaw(), translation.getPitch());
                displayRotation.applyToEntity(display);
            }
        }
    }

    /**
     * Summons the interaction entities of a custom block and gives them their rotation, offset and block data.
     */
    public List<Interaction> summonInteractions(AbstractCustomBlock abstractCustomBlock, Location location, CustomBlockRotation rotation) {
        return abstractCustomBlock.getConfiguredInteractions().stream()
                .map(configuredInteraction -> isUseless(configuredInteraction) ? null : summonInteraction(abstractCustomBlock, configuredInteraction, location, rotation))
                .filter(Objects::nonNull).collect(OperationUtil.toArrayList());
    }

    /**
     * Summons one interaction entity of a custom block, as its file places it, with its rotation, offset and block data.
     */
    @Nullable
    private static Interaction summonInteraction(AbstractCustomBlock abstractCustomBlock, ConfiguredInteraction configuredInteraction, Location location, CustomBlockRotation rotation) {
        Interaction interaction = configuredInteraction.summon(location);

        // If the interaction failed to summon, skip it
        if (interaction == null) {
            return null;
        }
        // Adjusts the rotation and offset of the interaction depending on the settings selected
        rotation.adjustInteractionRotation(interaction, configuredInteraction.getOffset(), abstractCustomBlock.getSidesCount());

        // Get the interaction identifier that was specified in the block file as its section name.
        String configuredInteractionIdentifier = configuredInteraction.getIdentifier();

        // Set custom block data for the interaction entity
        CustomBlockKey.holder(interaction)
                .setServiceClass(BDCCustomBlockService.class.getName())
                .setName(abstractCustomBlock.getName())
                .setLocation(location)
                .setInteractionIdentifier(configuredInteractionIdentifier);

        // Add scoreboard tags for easy identification and use in data packs
        interaction.addScoreboardTag("custom-block");
        interaction.addScoreboardTag("custom-block-name:" + abstractCustomBlock.getName());
        interaction.addScoreboardTag("custom-block-interaction");
        interaction.addScoreboardTag("custom-block-interaction-id:" + configuredInteractionIdentifier);
        interaction.addScoreboardTag("custom-block-location:" + location.toVector());

        return interaction;
    }

    /**
     * Summons the collision entities of a custom block and gives them their rotation, offset and block data.
     */
    public List<Shulker> summonCollisions(AbstractCustomBlock abstractCustomBlock, Location location, CustomBlockRotation rotation) {
        return abstractCustomBlock.getConfiguredCollisions().stream().map(configuredCollision -> {
            if (isUseless(configuredCollision, abstractCustomBlock)) {
                return null;
            }
            Shulker collision = configuredCollision.summon(location);

            // If the collision failed to summon, skip it
            if (collision == null) {
                return null;
            }

            // Get the collision identifier that was specified in the block file as its section name.
            String configuredCollisionIdentifier = configuredCollision.getIdentifier();

            // Adjusts the rotation and offset of the collision depending on the settings selected
            rotation.adjustCollisionRotation(collision, configuredCollision.getOffset(), abstractCustomBlock.getSidesCount());

            // Set custom block data for the collision entity
            CustomBlockKey.holder(collision)
                    .setServiceClass(BDCCustomBlockService.class.getName())
                    .setName(abstractCustomBlock.getName())
                    .setLocation(location)
                    .setCollisionIdentifier(configuredCollisionIdentifier);

            // Add scoreboard tags for easy identification and use in data packs
            collision.addScoreboardTag("custom-block");
            collision.addScoreboardTag("custom-block-name:" + abstractCustomBlock.getName());
            collision.addScoreboardTag("custom-block-collision");
            collision.addScoreboardTag("custom-block-collision-id:" + configuredCollisionIdentifier);
            collision.addScoreboardTag("custom-block-location:" + location.toVector());

            return collision;
        }).filter(Objects::nonNull).collect(OperationUtil.toArrayList());
    }

    /**
     * An interaction without width nor height can be neither clicked nor hit: it is not summoned.
     */
    public static boolean isUseless(ConfiguredInteraction interaction) {
        return interaction.getSummoner() instanceof InteractionSummoner summoner
                && summoner.getInteractionWidth() <= 0 && summoner.getInteractionHeight() <= 0;
    }

    /**
     * A collision without size is a full block. Where the central block of the custom block is solid, it stops
     * players and entities by itself: the collision is not summoned.
     */
    public static boolean isUseless(ConfiguredCollision collision, AbstractCustomBlock abstractCustomBlock) {
        Material central = abstractCustomBlock.getCentralMaterial();
        return collision.getSize() <= 0 && central != null && central.isSolid();
    }

    /**
     * Describes the interactions and collisions a custom block is defined with. Stored with a placed block, it
     * tells whether its hitbox entities still match its file.
     */
    public static String hitboxSignature(AbstractCustomBlock abstractCustomBlock) {
        StringBuilder signature = new StringBuilder();
        for (ConfiguredInteraction interaction : abstractCustomBlock.getConfiguredInteractions()) {
            if (isUseless(interaction)) {
                continue;
            }
            signature.append("i:").append(interaction.getIdentifier()).append('=');
            if (interaction.getSummoner() instanceof InteractionSummoner summoner) {
                signature.append(summoner.getInteractionWidth()).append('x').append(summoner.getInteractionHeight());
            }
            signature.append('@').append(interaction.getOffset()).append(';');
        }
        for (ConfiguredCollision collision : abstractCustomBlock.getConfiguredCollisions()) {
            if (isUseless(collision, abstractCustomBlock)) {
                continue;
            }
            signature.append("c:").append(collision.getIdentifier()).append('=').append(collision.getSize())
                    .append('@').append(collision.getOffset()).append(';');
        }
        return signature.toString();
    }

    /**
     * Replaces the interaction and collision entities of a placed custom block when its file no longer describes
     * the ones it was placed with. Nothing happens while one of the entities to replace is not loaded: it would be
     * left behind.
     *
     * @param location the location the block was placed at, as stored on its entities
     * @return true if the hitbox entities were replaced
     */
    public boolean refreshHitbox(@NotNull Location location) {
        CustomBlockData customBlockData = new CustomBlockData(location.getBlock(), BlockDisplayCreator.getInstance());
        if (!customBlockData.has(CustomBlockKey.NAME) || !customBlockData.has(CustomBlockKey.BLOCK_ROTATION)) {
            return false;
        }
        AbstractCustomBlock abstractCustomBlock = storage.getAbstractCustomBlock(customBlockData.get(CustomBlockKey.NAME, PersistentDataType.STRING));
        if (abstractCustomBlock == null) {
            return false;
        }
        String signature = hitboxSignature(abstractCustomBlock);
        if (signature.equals(customBlockData.get(CustomBlockKey.HITBOX, PersistentDataType.STRING))) {
            return false;
        }

        List<Entity> outdated = new ArrayList<>();
        for (org.bukkit.NamespacedKey key : List.of(CustomBlockKey.INTERACTION_UUID, CustomBlockKey.COLLISION_UUID)) {
            UUID[] uuids = customBlockData.get(key, PersistentDataTypes.UUID_ARRAY);
            if (uuids == null) {
                continue;
            }
            for (UUID uuid : uuids) {
                Entity entity = Bukkit.getEntity(uuid);
                if (entity == null) {
                    return false;
                }
                outdated.add(entity);
            }
        }
        CustomBlockRotation rotation = customBlockData.get(CustomBlockKey.BLOCK_ROTATION, PersistentDataTypes.CUSTOM_BLOCK_ROTATION);

        for (Entity entity : outdated) {
            Entity vehicle = entity instanceof Shulker ? entity.getVehicle() : null;
            entity.remove();
            if (vehicle != null) {
                vehicle.remove();
            }
        }
        List<Interaction> interactions = summonInteractions(abstractCustomBlock, location, rotation);
        List<Shulker> collisions = summonCollisions(abstractCustomBlock, location, rotation);

        customBlockData.set(CustomBlockKey.INTERACTION_UUID, PersistentDataTypes.UUID_ARRAY, interactions.stream().map(Entity::getUniqueId).toArray(UUID[]::new));
        customBlockData.set(CustomBlockKey.COLLISION_UUID, PersistentDataTypes.UUID_ARRAY, collisions.stream().map(Entity::getUniqueId).toArray(UUID[]::new));
        customBlockData.set(CustomBlockKey.HITBOX, PersistentDataType.STRING, signature);

        return true;
    }

    /**
     * The most square click boxes one click box of a file is cut into, when a tipped model lies along it.
     */
    private static final int MAX_CLICK_PIECES = 6;
    /**
     * Stored with a moved block once its click boxes follow the shape of its tipped model.
     */
    public static final String HITBOX_LAYOUT_VERSION = "2";
    private static final double[] NO_SHIFT = {0, 0, 0, 0, 0, 1};

    /**
     * Lays out the interaction and collision entities of a custom block the way its model was moved by an operator:
     * the model goes from one pose to another (a position, a yaw, a pitch and a size factor).
     * <p>
     * A collision goes with the model as if it were fixed to it. A click box is summoned again from the file and
     * given the shape of what the moved model covers. It stays a straight box with a square base, so a box tipped
     * over (a snowman of 1 by 3 lying down) is cut along its length into square boxes (three of 1 by 1).
     *
     * @param location where the block was placed, as stored on its entities
     * @param base     the position, yaw and pitch of the model before any shift
     * @param from     the shift (x, y, z, yaw, pitch, size factor) the collisions are laid out for
     * @param to       the shift they are laid out for afterwards
     * @return false when an entity of the block was not loaded: nothing is moved then
     */
    public boolean layHitbox(@NotNull CustomBlockData data, @NotNull Location location, double[] base, double[] from, double[] to) {
        // nothing is moved unless every entity of the block is loaded: the others would be left behind
        List<Entity> interactions = loadedEntities(data, CustomBlockKey.INTERACTION_UUID);
        List<Entity> collisions = loadedEntities(data, CustomBlockKey.COLLISION_UUID);
        if (interactions == null || collisions == null) {
            return false;
        }
        moveRigidly(collisions, base, from, to);

        String name = data.get(CustomBlockKey.NAME, PersistentDataType.STRING);
        AbstractCustomBlock abstractCustomBlock = name == null ? null : storage.getAbstractCustomBlock(name);
        CustomBlockRotation rotation = data.get(CustomBlockKey.BLOCK_ROTATION, PersistentDataTypes.CUSTOM_BLOCK_ROTATION);
        if (abstractCustomBlock == null || rotation == null) {
            // without its file, a click box can only go with the model as it is
            moveRigidly(interactions, base, from, to);
            return true;
        }

        interactions.forEach(Entity::remove);
        org.joml.Quaterniond yawFrom = new org.joml.Quaterniond().rotationY(-Math.toRadians(base[3]));
        org.joml.Quaterniond yawTo = new org.joml.Quaterniond().rotationY(-Math.toRadians(base[3] + to[3]));
        org.joml.Quaterniond turn = poseOrientation(base, to).mul(poseOrientation(base, NO_SHIFT).invert());
        // what is left of the turn once the yaw is taken out: the model tipped over in its own axes
        org.joml.Quaterniond tilt = new org.joml.Quaterniond(yawTo).invert().mul(turn).mul(yawFrom);
        List<UUID> uuids = new ArrayList<>();
        for (ConfiguredInteraction configured : abstractCustomBlock.getConfiguredInteractions()) {
            if (isUseless(configured)) {
                continue;
            }
            Interaction first = summonInteraction(abstractCustomBlock, configured, location, rotation);
            if (first == null) {
                continue;
            }
            List<double[]> boxes = clickBoxes(first, base, to, yawFrom, yawTo, tilt);
            for (int i = 0; i < boxes.size(); i++) {
                Interaction piece = i == 0 ? first : summonInteraction(abstractCustomBlock, configured, location, rotation);
                if (piece == null) {
                    continue;
                }
                double[] box = boxes.get(i);
                piece.setInteractionWidth((float) box[3]);
                piece.setInteractionHeight((float) box[4]);
                Location at = piece.getLocation();
                piece.teleport(new Location(at.getWorld(), box[0], box[1], box[2], at.getYaw(), at.getPitch()));
                uuids.add(piece.getUniqueId());
            }
        }
        data.set(CustomBlockKey.INTERACTION_UUID, PersistentDataTypes.UUID_ARRAY, uuids.toArray(UUID[]::new));
        data.set(CustomBlockKey.HITBOX_LAYOUT, PersistentDataType.STRING, HITBOX_LAYOUT_VERSION);
        return true;
    }

    /**
     * The click boxes one interaction of the file becomes once the model is moved: x, y, z of the middle of its
     * floor, then its width and height.
     */
    static List<double[]> clickBoxes(Interaction interaction, double[] base, double[] to,
                                     org.joml.Quaterniond yawFrom, org.joml.Quaterniond yawTo, org.joml.Quaterniond tilt) {
        Location at = interaction.getLocation();
        return clickBoxes(at.getX(), at.getY(), at.getZ(), interaction.getInteractionWidth(), interaction.getInteractionHeight(),
                base, to, yawFrom, yawTo, tilt);
    }

    static List<double[]> clickBoxes(double x, double y, double z, double width, double height, double[] base, double[] to,
                                     org.joml.Quaterniond yawFrom, org.joml.Quaterniond yawTo, org.joml.Quaterniond tilt) {
        double size = to[5];
        // the middle of the box, from the model's origin, in the model's own axes; its square base is taken to
        // turn with the model's yaw without growing, as a click box always did
        org.joml.Vector3d middle = yawFrom.transformInverse(new org.joml.Vector3d(x - base[0], y + height / 2 - base[1], z - base[2]));
        double[] half = {width / 2, height / 2, width / 2};
        org.joml.Vector3d[] columns = new org.joml.Vector3d[3];
        for (int j = 0; j < 3; j++) {
            columns[j] = tilt.transform(new org.joml.Vector3d(j == 0 ? 1 : 0, j == 1 ? 1 : 0, j == 2 ? 1 : 0));
        }
        double[] reach = reach(columns, half);

        // a box that now lies along one horizontal axis is cut into square ones along it
        int longAxis = reach[0] >= reach[2] ? 0 : 2;
        double longHalf = Math.max(reach[0], reach[2]);
        double shortHalf = Math.min(reach[0], reach[2]);
        int pieces = 1;
        int cut = -1;
        if (shortHalf > 1.0E-3 && longHalf > shortHalf * 1.25) {
            pieces = (int) Math.min(MAX_CLICK_PIECES, Math.ceil(longHalf / shortHalf - 0.15));
            // the side of the box that reaches the furthest along that axis is the one cut
            double most = -1;
            for (int j = 0; j < 3; j++) {
                double along = Math.abs(component(columns[j], longAxis)) * half[j];
                if (along > most) {
                    most = along;
                    cut = j;
                }
            }
        }

        List<double[]> boxes = new ArrayList<>(pieces);
        for (int i = 0; i < pieces; i++) {
            double[] part = half.clone();
            org.joml.Vector3d centre = new org.joml.Vector3d(middle);
            if (pieces > 1) {
                part[cut] = half[cut] / pieces;
                double offset = half[cut] * ((2 * i + 1.0) / pieces - 1);
                centre.add(cut == 0 ? offset : 0, cut == 1 ? offset : 0, cut == 2 ? offset : 0);
            }
            double[] partReach = reach(columns, part);
            tilt.transform(centre);
            yawTo.transform(centre).mul(size).add(base[0] + to[0], base[1] + to[1], base[2] + to[2]);
            double boxWidth = 2 * Math.max(partReach[0], partReach[2]) * size;
            double boxHeight = 2 * partReach[1] * size;
            boxes.add(new double[]{centre.x, centre.y - boxHeight / 2, centre.z, boxWidth, boxHeight});
        }
        return boxes;
    }

    /**
     * How far a box of these half sizes reaches along each axis once turned (the columns of the turn).
     */
    private static double[] reach(org.joml.Vector3d[] columns, double[] half) {
        double[] reach = new double[3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                reach[i] += Math.abs(component(columns[j], i)) * half[j];
            }
        }
        return reach;
    }

    private static double component(org.joml.Vector3d vector, int axis) {
        return axis == 0 ? vector.x : axis == 1 ? vector.y : vector.z;
    }

    /**
     * The entities of a block listed under a key, or null when one of them is not loaded.
     */
    @Nullable
    private static List<Entity> loadedEntities(CustomBlockData data, org.bukkit.NamespacedKey key) {
        List<Entity> entities = new ArrayList<>();
        UUID[] uuids = data.get(key, PersistentDataTypes.UUID_ARRAY);
        if (uuids == null) {
            return entities;
        }
        for (UUID uuid : uuids) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity == null) {
                return null;
            }
            entities.add(entity);
        }
        return entities;
    }

    /**
     * Moves entities of a block as if they were fixed to its model, from one shift to another.
     */
    private static void moveRigidly(List<Entity> entities, double[] base, double[] from, double[] to) {
        org.joml.Quaterniond before = poseOrientation(base, from);
        org.joml.Quaterniond turn = poseOrientation(base, to).mul(before.invert());
        org.joml.Vector3d offset = new org.joml.Vector3d();
        double factor = from[5] == 0 ? 1 : to[5] / from[5];
        org.bukkit.attribute.Attribute scaleAttribute = factor == 1 ? null : me.general_breddok.blockdisplaycreator.version.VersionCompat.getScaleAttribute();
        for (Entity entity : entities) {
            Entity vehicle = entity instanceof Shulker ? entity.getVehicle() : null;
            Entity moved = vehicle != null ? vehicle : entity;
            Location at = moved.getLocation();
            turn.transform(offset.set(at.getX() - base[0] - from[0], at.getY() - base[1] - from[1], at.getZ() - base[2] - from[2])).mul(factor);
            if (factor != 1) {
                if (entity instanceof Interaction interaction) {
                    interaction.setInteractionWidth((float) (interaction.getInteractionWidth() * factor));
                    interaction.setInteractionHeight((float) (interaction.getInteractionHeight() * factor));
                } else if (entity instanceof Shulker shulker && scaleAttribute != null && shulker.getAttribute(scaleAttribute) != null) {
                    shulker.getAttribute(scaleAttribute).setBaseValue(shulker.getAttribute(scaleAttribute).getBaseValue() * factor);
                }
            }
            Location target = new Location(at.getWorld(), base[0] + to[0] + offset.x, base[1] + to[1] + offset.y, base[2] + to[2] + offset.z,
                    at.getYaw(), at.getPitch());
            if (vehicle != null) {
                EntityUtil.teleportEntityWithPassengers(vehicle, target);
            } else {
                moved.teleport(target);
            }
        }
    }

    private static org.joml.Quaterniond poseOrientation(double[] base, double[] shift) {
        double yaw = Location.normalizeYaw((float) (base[3] + shift[3]));
        double pitch = Location.normalizePitch((float) (base[4] + shift[4]));
        return new org.joml.Quaterniond().rotationYXZ(-Math.toRadians(yaw), Math.toRadians(pitch), 0);
    }

    /**
     * Removes the real display entities that still carry a custom block's data although the block does not list them:
     * what an old placement or a partial conversion left behind would stay on screen, with nothing to click, once
     * the block is broken.
     */
    public static void removeStrayDisplays(@NotNull Location location, @NotNull String name) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        for (Entity entity : world.getNearbyEntities(location, 2, 4, 2, found -> found instanceof Display)) {
            if (isDisplayOf(entity, location.getBlockX(), location.getBlockY(), location.getBlockZ(), name)) {
                removeWithPassengers(entity);
            }
        }
    }

    /**
     * Whether a display entity was summoned for the custom block of this name at this block position.
     */
    public static boolean isDisplayOf(@NotNull Entity entity, int x, int y, int z, @Nullable String name) {
        CustomBlockKey.DataHolder holder = CustomBlockKey.holder(entity);
        Location stored = holder.getLocation();
        return stored != null && stored.getBlockX() == x && stored.getBlockY() == y && stored.getBlockZ() == z
                && (name == null || name.equals(holder.getName()));
    }

    public static void removeWithPassengers(@NotNull Entity entity) {
        for (Entity passenger : new ArrayList<>(entity.getPassengers())) {
            removeWithPassengers(passenger);
        }
        entity.remove();
    }

    /**
     * Creates a {@link CustomBlockData} from its location and stores all block data there
     *
     * @param customBlock         the custom block to create data for
     * @param displayVehicleUuids list of UUIDs of display vehicles associated with the custom block
     * @return a {@link CustomBlockData} instance containing the custom block's data
     */
    public static CustomBlockData createCustomBlockData(CustomBlock customBlock, List<UUID> displayVehicleUuids) {
        CustomBlockData customBlockData = new CustomBlockData(customBlock.getLocation().getBlock(), BlockDisplayCreator.getInstance());
        customBlockData.set(CustomBlockKey.NAME, PersistentDataType.STRING, customBlock.getName());
        customBlockData.set(CustomBlockKey.SERVICE_CLASS, PersistentDataType.STRING, BDCCustomBlockService.class.getName());
        customBlockData.set(CustomBlockKey.DISPLAY_UUID, PersistentDataTypes.UUID_ARRAY, displayVehicleUuids.toArray(UUID[]::new));
        customBlockData.set(CustomBlockKey.INTERACTION_UUID, PersistentDataTypes.UUID_ARRAY, customBlock.getInteractions().stream().map(Entity::getUniqueId).toList().toArray(UUID[]::new));
        customBlockData.set(CustomBlockKey.COLLISION_UUID, PersistentDataTypes.UUID_ARRAY, customBlock.getCollisions().stream().map(Entity::getUniqueId).toList().toArray(UUID[]::new));
        customBlockData.set(CustomBlockKey.BLOCK_ROTATION, PersistentDataTypes.CUSTOM_BLOCK_ROTATION, customBlock.getRotation());
        customBlockData.set(CustomBlockKey.HITBOX, PersistentDataType.STRING, hitboxSignature(customBlock));
        customBlockData.set(CustomBlockKey.CUSTOM_BLOCK_UUID, PersistentDataTypes.UUID, customBlock.getUuid());
        if (customBlock.getSaveSystem().equals("item")) {
            GroupSummoner<Display> displaySummoner = customBlock.getDisplaySummoner();
            if (displaySummoner instanceof AutomaticCommandDisplaySummoner automaticCommandDisplaySummoner) {
                customBlockData.set(CustomBlockKey.DISPLAY_SPAWN_COMMAND, PersistentDataTypes.COMMAND_ARRAY, automaticCommandDisplaySummoner.getCommands().toArray(CommandLine[]::new));
            }
        }
        return customBlockData;
    }

    /**
     * Triggers the {@link CustomBlockPlaceEvent} and handles cancellation
     *
     * @param customBlock     the custom block that was placed
     * @param player          the player who placed the block (nullable)
     * @param customBlockData the custom block data associated with the block's location
     * @return true if the event was not cancelled, false if it was
     */
    private static boolean triggerCustomBlockPlaceEvent(CustomBlock customBlock, Player player, CustomBlockData customBlockData) {
        CustomBlockPlaceEvent customBlockPlaceEvent = new CustomBlockPlaceEvent(customBlock, player);
        // If the event is canceled, remove all entities and clear the block data
        if (!EventUtil.call(customBlockPlaceEvent)) {
            customBlock.getDisplays().forEach(Entity::remove);
            customBlock.getInteractions().forEach(Entity::remove);
            customBlock.getCollisions().forEach(Entity::remove);
            clearCustomBlockData(customBlockData);
            customBlock.getLocation().getBlock().setType(Material.AIR);
            return false;
        }
        return true;
    }

    /**
     * Triggers the {@link CustomBlockBreakEvent} and handles cancellation
     *
     * @param customBlock the custom block that is being broken
     * @param player      the player who is breaking the block (nullable)
     * @return true if the event was not cancelled, false if it was
     */
    private static boolean triggerCustomBlockBreakEvent(CustomBlock customBlock, Player player) {
        CustomBlockBreakEvent customBlockBreakEvent = new CustomBlockBreakEvent(customBlock, player);
        return EventUtil.call(customBlockBreakEvent);
    }

    /**
     * Clears all custom block data from the provided {@link CustomBlockData} instance
     *
     * @param customBlockData the custom block data to clear
     */
    public static void clearCustomBlockData(CustomBlockData customBlockData) {
        customBlockData.remove(CustomBlockKey.NAME);
        customBlockData.remove(CustomBlockKey.SERVICE_CLASS);
        customBlockData.remove(CustomBlockKey.DISPLAY_UUID);
        customBlockData.remove(CustomBlockKey.INTERACTION_UUID);
        customBlockData.remove(CustomBlockKey.COLLISION_UUID);
        customBlockData.remove(CustomBlockKey.BLOCK_ROTATION);
        customBlockData.remove(CustomBlockKey.CUSTOM_BLOCK_UUID);
        customBlockData.remove(CustomBlockKey.DISPLAY_SPAWN_COMMAND);

        customBlockData.remove(CustomBlockKey.ITEM);
        customBlockData.remove(CustomBlockKey.DISPLAY_SPAWN_COMMAND);
        customBlockData.remove(CustomBlockKey.HITBOX);
        customBlockData.remove(CustomBlockKey.RENDER);
        customBlockData.remove(CustomBlockKey.SHIFT);

        DisplayRenderer renderer = BlockDisplayCreator.getInstance().getDisplayRenderer();
        Block block = customBlockData.getBlock();
        if (renderer != null && block != null) {
            renderer.removed(block);
        }
    }
}
