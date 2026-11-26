package me.general_breddok.blockdisplaycreator.command;

import com.jeff_media.customblockdata.CustomBlockData;
import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.commandparser.argument.TargetSelectorType;
import me.general_breddok.blockdisplaycreator.custom.block.*;
import me.general_breddok.blockdisplaycreator.custom.block.option.CustomBlockPlaceOption;
import me.general_breddok.blockdisplaycreator.file.config.value.StringMessagesValue;
import me.general_breddok.blockdisplaycreator.permission.DefaultPermissions;
import me.general_breddok.blockdisplaycreator.placeholder.universal.LocationPlaceholder;
import me.general_breddok.blockdisplaycreator.util.ChatUtil;
import me.general_breddok.blockdisplaycreator.util.CommandUtil;
import me.general_breddok.blockdisplaycreator.util.ItemUtil;
import me.general_breddok.blockdisplaycreator.world.WorldSelection;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class BlockDisplayCreatorSpigotCommand implements TabExecutor {

    private final BlockDisplayCreator plugin;
    private final CustomBlockService service;

    public BlockDisplayCreatorSpigotCommand(BlockDisplayCreator plugin, CustomBlockService service) {
        this.plugin = plugin;
        this.service = service;
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        /*if (!sender.hasPermission(DefaultPermissions.BDC.Command.BASE)) {
            ChatUtil.sendMessage(sender, "&cYou do not have permission to use this command");
            return true;
        }*/

        String arg1 = null;
        String arg2 = null;
        String arg3 = null;
        String arg4 = null;
        String arg5 = null;
        String arg6 = null;
        String arg7 = null;
        String arg8 = null;
        String arg9 = null;
        Player player = null;

        try {
            arg1 = args[0];
            arg2 = args[1];
            arg3 = args[2];
            arg4 = args[3];
            arg5 = args[4];
            arg6 = args[5];
            arg7 = args[6];
            arg8 = args[7];
            arg9 = args[8];
        } catch (ArrayIndexOutOfBoundsException ignored) {
        }

        if (sender instanceof Player) {
            player = (Player) sender;
        }

        if (arg1 == null) {
            ChatUtil.sendMessage(sender, "&cYou did not enter any arguments");
            return true;
        }

        String finalArg5 = arg5;
        String finalArg3 = arg3;
        switch (arg1) {
            case "custom-block" -> {

                if (arg2 == null) {
                    return false;
                }

                final CustomBlockStorage storage = service.getStorage();

                switch (arg2) {
                    /*case "create" -> {

                        ChatUtil.log("1");

                        if (!sender.hasPermission(DefaultPermissions.BDC.Command.CREATE_CB)) {
                            ChatUtil.sendMessage(sender, "&cYou do not have permission to use this command");
                            return true;
                        }

                        if (arg3 == null) {
                            ChatUtil.sendMessage(sender, "&cYou didn't specify a name for the new block!");
                            return true;
                        }

                        if (arg4 == null) {
                            ChatUtil.sendMessage(sender, "&cYou didn't specify a BDEngine model id for the display of " + arg3 + " block!");
                            return true;
                        }

                        if (arg5 == null) {
                            ChatUtil.sendMessage(sender, "&cYou didn't specify an item material for the " + arg3 + " block!");
                            return true;
                        }

                        if (FileUtil.containsFile(BlockDisplayCreator.getInstance().getDataFolder().toPath().resolve("custom-blocks"), arg3 + ".yml")) {
                            ChatUtil.sendMessage(sender, "&cA custom block with the name " + arg3 + " already exists.");
                            return true;
                        }

                        int modelId;

                        try {
                            modelId = Integer.parseInt(arg4);
                        } catch (NumberFormatException e) {
                            ChatUtil.sendMessage(sender, "&cThe BDEngine model id must be a number!");
                            return true;
                        }

                        if (ItemUtil.BLOCK_MATERIAL_NAMES.stream().noneMatch(materialName -> materialName.equalsIgnoreCase(finalArg5))) {
                            ChatUtil.sendMessage(sender, "&cThe item material " + finalArg5 + " is not a valid block material!");
                            return true;
                        }

                        Material itemMaterial = Material.getMaterial(finalArg5.toUpperCase());

                        float interactionWidth = 1.001f;
                        float interactionHeight = 1.001f;

                        if (arg6 != null) {
                            try {
                                interactionWidth = Float.parseFloat(arg6);
                            } catch (NumberFormatException e) {
                                ChatUtil.sendMessage(sender, "&cThe interaction width must be a number!");
                                return true;
                            }
                        }

                        if (arg7 != null) {
                            try {
                                interactionHeight = Float.parseFloat(arg7);
                            } catch (NumberFormatException e) {
                                ChatUtil.sendMessage(sender, "&cThe interaction height must be a number!");
                                return true;
                            }
                        }

                        BDEModel model;

                        ChatUtil.log("2");

                        try {
                            model = new NetworkBDEModel(modelId);
                        } catch (
                                InvalidResponseException e) {
                            throw new RuntimeException(e);
                        }

                        ChatUtil.log("3");

                        List<String> summonCommands = model.getSummonCommands();

                        CustomBlockFileRepository.saveAbstractCustomBlock(BlockDisplayCreator.getInstance().getDataFolder().toPath(), arg3, summonCommands, itemMaterial, interactionWidth, interactionHeight);

                        ChatUtil.log("4");

                        storage.reload(arg3);

                        ChatUtil.log("5");
                        ChatUtil.sendMessage(sender, StringMessagesValue.COMMAND_CUSTOM_BLOCK_CREATE_SUCCESS.replace("%customblock_name%", arg3));


                    }*/

                    case "place" -> place(sender, args, storage);

                    case "break" -> breakAt(sender, args);

                    case "give" -> {

                        if (!sender.hasPermission(DefaultPermissions.BDC.Command.GIVE_CB)) {
                            ChatUtil.sendMessage(sender, "&cYou do not have permission to use this command");
                            return true;
                        }

                        if (arg3 == null) {
                            ChatUtil.sendMessage(sender, "&cYou didn't specify a block!");
                            return true;
                        }

                        if (!storage.containsAbstractCustomBlock(arg3)) {
                            ChatUtil.sendMessage(sender, StringMessagesValue.COMMAND_BLOCK_NOT_EXISTS.replace("%customblock_name%", arg3));
                            return true;
                        }

                        AbstractCustomBlock abstractCustomBlock = storage.getAbstractCustomBlock(arg3);
                        ItemStack customBlockItem = abstractCustomBlock.getItem();

                        byte amount = 1;

                        if (arg5 != null) {
                            try {
                                amount = Byte.parseByte(arg5);
                            } catch (NumberFormatException ignore) {
                                ChatUtil.sendMessage(sender, "&cAmount must be a number and must be in the range 1-127");
                                return true;
                            }

                            if (amount < 1) {
                                ChatUtil.sendMessage(sender, "&cAmount must be a number and must be in the range 1-127");
                                return true;
                            }
                        }

                        customBlockItem.setAmount(amount);
                        // "silent" after the amount: the recipient gets no message (the command that gives tells him itself)
                        boolean silent = "silent".equalsIgnoreCase(arg6);

                        if (arg4 == null) {
                            if (player == null) {
                                ChatUtil.sendMessage(sender, StringMessagesValue.COMMAND_CUSTOM_BLOCK_GIVE_NO_PLAYER);
                                return true;
                            } else {
                                CommandUtil.applyPlaceholdersForItem(customBlockItem, player);
                                CommandUtil.applyPlaceholdersForCommand(abstractCustomBlock, customBlockItem, player);

                                ItemUtil.distributeItem(player, customBlockItem);

                                ChatUtil.sendMessage(player,
                                        StringMessagesValue.COMMAND_CUSTOM_BLOCK_GIVE_PLAYER_RECEIVE
                                                .replace("%customblock_name%", arg3)
                                                .replace("%amount%", String.valueOf(amount))
                                );
                            }
                        } else {
                            switch (arg4) {
                                case "@a" -> {
                                    for (Player recipient : Bukkit.getOnlinePlayers()) {
                                        CommandUtil.applyPlaceholdersForItem(customBlockItem, recipient);
                                        CommandUtil.applyPlaceholdersForCommand(abstractCustomBlock, customBlockItem, recipient);

                                        ItemUtil.distributeItem(recipient, customBlockItem);
                                        notifyRecipient(recipient, arg3, amount, silent);
                                    }
                                    ChatUtil.sendMessage(sender, "&bYou have given the &l%s&ox%s&r&b block to all players", arg3, amount);
                                    return true;
                                }
                                case "@s" -> {
                                    if (player == null) {
                                        ChatUtil.sendMessage(sender, "&cThe sender of the command must be a player!");
                                        return true;
                                    }

                                    CommandUtil.applyPlaceholdersForItem(customBlockItem, player);
                                    CommandUtil.applyPlaceholdersForCommand(abstractCustomBlock, customBlockItem, player);

                                    ItemUtil.distributeItem(player, customBlockItem);

                                    ChatUtil.sendMessage(sender, "&bYou have received the &l%s&ox%s&r&b block", arg3, amount);
                                    return true;
                                }
                                case "@r" -> {
                                    Player randomPlayer = TargetSelectorType.getRandomPlayer();

                                    if (randomPlayer == null) {
                                        ChatUtil.sendMessage(sender, "&cThe server is empty!");
                                        return true;
                                    }

                                    CommandUtil.applyPlaceholdersForItem(customBlockItem, randomPlayer);
                                    CommandUtil.applyPlaceholdersForCommand(abstractCustomBlock, customBlockItem, randomPlayer);

                                    ItemUtil.distributeItem(randomPlayer, customBlockItem);
                                    notifyRecipient(randomPlayer, arg3, amount, silent);

                                    ChatUtil.sendMessage(sender, "&bYou have given the &l%s&ox%s&r&b block to random player %s", arg3, amount, randomPlayer.getName());
                                    return true;
                                }
                                case "@p" -> {
                                    if (player == null) {
                                        ChatUtil.sendMessage(sender, "&cThe sender of the command must be a player!");
                                        return true;
                                    }

                                    Player nearestPlayer = TargetSelectorType.getNearestPlayer(player.getLocation(), player);

                                    CommandUtil.applyPlaceholdersForItem(customBlockItem, nearestPlayer);
                                    CommandUtil.applyPlaceholdersForCommand(abstractCustomBlock, customBlockItem, nearestPlayer);

                                    ItemUtil.distributeItem(nearestPlayer, customBlockItem);
                                    notifyRecipient(nearestPlayer, arg3, amount, silent);

                                    ChatUtil.sendMessage(sender, "&bYou have given the &l%s&ox%s&r&b block to nearest player %s", arg3, amount, nearestPlayer.getName());
                                    return true;
                                }
                                default -> {
                                    Player recipient = Bukkit.getPlayer(arg4);

                                    if (recipient == null) {
                                        ChatUtil.sendMessage(sender, "&cYou specified a non-existent selector or player name", arg4);
                                        return true;
                                    }

                                    CommandUtil.applyPlaceholdersForItem(customBlockItem, recipient);
                                    CommandUtil.applyPlaceholdersForCommand(abstractCustomBlock, customBlockItem, recipient);

                                    ItemUtil.distributeItem(recipient, customBlockItem);
                                    notifyRecipient(recipient, arg3, amount, silent);

                                    ChatUtil.sendMessage(sender, "&bYou have given the &l%s&ox%s&r&b block to player %s", arg3, amount, recipient.getName());
                                    return true;
                                }
                            }
                        }
                    }
                    /*case "editfile" -> {

                        if (arg3 == null) {
                            ChatUtil.sendMessage(sender, "&cYou didn't specify a block!");
                            return true;
                        }

                        if (arg4 == null) {
                            ChatUtil.sendMessage(sender, "&cYou didn't specify a parameter!");
                            return true;
                        }

                        switch (arg4) {
                            case "sides-count" -> {
                                if (arg5 == null) {
                                    return false;
                                }

                                int value = 4;

                                try {
                                    value = Integer.parseInt(arg5);
                                } catch (NumberFormatException ignored) {
                                }

                                setCustomBlockParameter(arg3, List.of(arg4), value, sender);
                            }
                            case "display" -> {
                                if (arg5 == null) {
                                    return false;
                                }

                                switch (arg5) {
                                    case "glowing" -> {
                                        if (arg6 == null) {
                                            return false;
                                        }

                                        setCustomBlockParameter(arg3, List.of(arg4, arg5), arg6, sender);
                                    }
                                    case "glow-color-override" -> {
                                        if (arg6 == null) {
                                            return false;
                                        }

                                        int value = 1;

                                        try {
                                            value = Integer.parseInt(arg6);
                                        } catch (NumberFormatException ignored) {
                                        }

                                        setCustomBlockParameter(arg3, List.of(arg4, arg5), value, sender);
                                    }
                                }
                            }
                            case "central-material" -> {

                                if (arg5 == null) {
                                    return false;
                                }

                                setCustomBlockParameter(arg3, List.of(arg4), arg5, sender);

                            }
                            case "item" -> {

                                if (arg5 == null) {
                                    return false;
                                }

                                switch (arg5) {
                                    case "material" -> {
                                        if (arg6 == null) {
                                            return false;
                                        }

                                        setCustomBlockParameter(arg3, List.of(arg4, arg5), arg6, sender);
                                    }
                                    case "name" -> {
                                        if (arg6 == null) {
                                            return false;
                                        }

                                        String name = String.join(" ", Arrays.copyOfRange(args, 5, args.length));

                                        setCustomBlockParameter(arg3, List.of(arg4, arg5), name, sender);
                                    }
                                    case "enchantments" -> {
                                        if (arg6 == null) {
                                            return false;
                                        }

                                        int level = 1;

                                        try {
                                            level = Integer.parseInt(
                                                    Optional.ofNullable(arg7).orElse("1")
                                            );
                                        } catch (NumberFormatException ignored) {
                                        }

                                        setCustomBlockParameter(arg3, List.of(arg4, arg5, arg6), level, sender);
                                    }
                                    case "item-flags" -> {
                                        if (arg6 == null) {
                                            return false;
                                        }

                                        setCustomBlockParameter(arg3, List.of(arg4, arg5), List.of(arg6), sender);
                                    }
                                    case "lore" -> {

                                        if (arg6 == null) {
                                            return false;
                                        }

                                        String lore = String.join(" ", Arrays.copyOfRange(args, 5, args.length));

                                        setCustomBlockParameter(arg3, List.of(arg4, arg5), List.of(lore), sender);
                                    }
                                    case "skullmeta" -> {

                                        if (arg6 == null) {
                                            return false;
                                        }

                                        switch (arg6) {
                                            case "url" -> {

                                                if (arg7 == null) {
                                                    return false;
                                                }

                                                setCustomBlockParameter(arg3, List.of(arg4, arg5, arg6), arg7, sender);
                                            }
                                            default -> {
                                                return false;
                                            }
                                        }
                                    }
                                    default -> {
                                        return false;
                                    }
                                }
                            }
                            *//*case "interaction" -> {

                                if (arg5 == null) {
                                    return false;
                                }

                                switch (arg5) {
                                    case "width", "height" -> {

                                        if (arg6 == null) {
                                            return false;
                                        }

                                        float value = 1.001F;

                                        try {
                                            value = Float.parseFloat(arg6);
                                        } catch (NumberFormatException ignored) {
                                        }

                                        setCustomBlockParameter(List.of(arg1, arg3, arg4, arg5), value, sender);
                                    }
                                    case "command" -> {

                                        if (arg6 == null) {
                                            return false;
                                        }

                                        String interactionCommand = String.join(" ", Arrays.copyOfRange(args, 5, args.length));

                                        setCustomBlockParameter(List.of(arg1, arg3, arg4, arg5), List.of(interactionCommand), sender);
                                    }
                                    case "command-source" -> {

                                        if (arg6 == null) {
                                            return false;
                                        }

                                        setCustomBlockParameter(List.of(arg1, arg3, arg4, arg5), arg6, sender);
                                    }
                                }
                            }*//*
                            case "sound" -> {
                                if (arg5 == null) {
                                    return false;
                                }

                                switch (arg5) {
                                    case "break", "place" -> {
                                        if (arg6 == null) {
                                            return false;
                                        }

                                        switch (arg6) {
                                            case "volume", "pitch" -> {
                                                if (arg7 == null) {
                                                    return false;
                                                }

                                                float value = 1F;

                                                try {
                                                    value = Float.parseFloat(arg7);
                                                } catch (NumberFormatException ignored) {
                                                }

                                                setCustomBlockParameter(arg3, List.of(arg4, arg5, arg6), value, sender);
                                            }
                                            case "sound-type" -> {
                                                if (arg7 == null) {
                                                    return false;
                                                }

                                                setCustomBlockParameter(arg3, List.of(arg4, arg5, arg6), arg7, sender);
                                            }
                                        }
                                    }
                                }
                            }
                            default -> {
                                return false;
                            }
                        }
                    }*/
                    default -> {
                        return false;
                    }
                }
            }
            case "import" -> importModel(sender, args);
            case "animation" -> animation(sender, args);
            case "settings" -> {
                me.general_breddok.blockdisplaycreator.render.DisplayRenderer renderer = this.plugin.getDisplayRenderer();
                if (renderer == null || renderer.gizmos() == null) {
                    ChatUtil.sendMessage(sender, "&cBlock settings need the custom blocks to be shown through packets (packet-display: true in config.yml).");
                } else {
                    renderer.gizmos().adminMenu().command(sender, args);
                }
            }
            case "erasecbdata" -> {

                if (!sender.hasPermission(DefaultPermissions.BDC.Command.ERASE_CB_DATA)) {
                    ChatUtil.sendMessage(sender, "&cYou do not have permission to use this command");
                    return true;
                }

                if (player == null) {
                    ChatUtil.sendMessage(sender, "&cThe command can only be applied by the player!");
                    return true;
                }

                if (args.length != 7) {
                    ChatUtil.sendMessage(sender, "&cYou must specify coordinates in the format <x1> <y1> <z1> <x2> <y2> <z2>");
                    return true;
                }

                try {
                    eraseCbData(new BoundingBox
                                    (
                                            Double.valueOf(arg2),
                                            Double.valueOf(arg3),
                                            Double.valueOf(arg4),
                                            Double.valueOf(arg5),
                                            Double.valueOf(arg6),
                                            Double.valueOf(arg7)
                                    ),
                            player
                    );
                } catch (NumberFormatException exception) {
                    ChatUtil.sendMessage(sender, "&cIt looks like you entered the wrong number");
                    return true;
                }
            }
            case "reload" -> {

                if (!sender.hasPermission(DefaultPermissions.BDC.Command.RELOAD)) {
                    ChatUtil.sendMessage(sender, "&cYou do not have permission to use this command");
                    return true;
                }

                if (arg2 == null) {
                    plugin.reloadConfig();
                    ChatUtil.sendMessage(sender, "&aConfig has been reloaded!");
                } else {
                    // the blocks of that name already shown, and the models shown for other plugins, take the new file
                    me.general_breddok.blockdisplaycreator.render.DisplayRenderer renderer = this.plugin.getDisplayRenderer();
                    String name = arg2;
                    this.plugin.getCustomBlockService().getStorage().reload(name, renderer == null ? null : () -> renderer.reload(name));
                    ChatUtil.sendMessage(sender, "&a%s block has been reloaded!", arg2);
                }
            }
            default -> {
                return false;
            }
        }


        return true;
    }

    private static final String PLACE_USAGE = "&6/bdc custom-block place <block> <x> <y> <z> [world] [face] [yaw] [options]";
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?");

    /**
     * /bdc custom-block place &lt;block&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; [world] [face] [yaw] [options]
     * <p>
     * Places a custom block at coordinates, from a player or from the console (a command block works too). The
     * centre of the model goes on the coordinates given, as it always did: write 10.5 64 20.5 for the middle of the
     * block 10 64 20. The world is the sender's (the main world for the console) unless one is named; the yaw
     * (degrees, 0 by default) turns the model, snapped to the sides-count of its file, as the direction of a player
     * placing it does; the face (up by default) is the face of the block it is attached to. World, face and yaw come
     * in that order and each may be left out. The block is placed on the next tick.
     */
    private void place(CommandSender sender, String[] args, CustomBlockStorage storage) {
        if (!sender.hasPermission(DefaultPermissions.BDC.Command.PLACE_CB)) {
            ChatUtil.sendMessage(sender, "&cYou do not have permission to use this command");
            return;
        }

        if (args.length < 3) {
            ChatUtil.sendMessage(sender, "&cYou didn't specify a block!");
            ChatUtil.sendMessage(sender, PLACE_USAGE);
            return;
        }

        String blockName = args[2];

        if (!storage.containsAbstractCustomBlock(blockName)) {
            ChatUtil.sendMessage(sender, StringMessagesValue.COMMAND_BLOCK_NOT_EXISTS.replace("%customblock_name%", blockName));
            return;
        }

        double x = parseCoordinate(args.length > 3 ? args[3] : null);
        double y = parseCoordinate(args.length > 4 ? args[4] : null);
        double z = parseCoordinate(args.length > 5 ? args[5] : null);

        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            ChatUtil.sendMessage(sender, "&cYou must specify valid coordinates in the format <x> <y> <z>");
            ChatUtil.sendMessage(sender, PLACE_USAGE);
            return;
        }

        int next = 6;

        // the world, when one is named
        World world = sender instanceof Entity entity ? entity.getWorld()
                : sender instanceof BlockCommandSender commandBlock ? commandBlock.getBlock().getWorld()
                : Bukkit.getWorlds().get(0);
        if (next < args.length) {
            World named = Bukkit.getWorld(args[next]);
            if (named != null) {
                world = named;
                next++;
            }
        }

        // the face the block is attached to
        BlockFace attachedFace = BlockFace.UP;
        boolean faceGiven = false;
        if (next < args.length && !NUMBER.matcher(args[next]).matches()) {
            BlockFace face = parseFace(args[next]);
            if (face != null) {
                attachedFace = face;
                faceGiven = true;
                next++;
            } else if (next == 6 && !isPlaceOption(args[next])) {
                // right after the coordinates: neither a world nor a face
                if (sender instanceof Player) {
                    ChatUtil.sendMessage(sender, StringMessagesValue.COMMAND_CUSTOM_BLOCK_PLACE_INVALID_ATTACHED_FACE.replace("%face%", args[next]));
                } else {
                    ChatUtil.sendMessage(sender, "&cNo world named '" + args[next] + "'.");
                }
                return;
            }
        }

        // the yaw
        float direction = 0;
        if (next < args.length) {
            if (NUMBER.matcher(args[next]).matches()) {
                direction = ((Float.parseFloat(args[next]) % 360f) + 360f) % 360f;
                next++;
            } else if (!isPlaceOption(args[next])) {
                ChatUtil.sendMessage(sender, faceGiven ? "&cThe direction must be a number from 0 to 360!"
                        : StringMessagesValue.COMMAND_CUSTOM_BLOCK_PLACE_INVALID_ATTACHED_FACE.replace("%face%", args[next]));
                return;
            }
        }

        List<CustomBlockPlaceOption> options = new ArrayList<>();
        for (int i = next; i < args.length; i++) {
            try {
                options.add(CustomBlockPlaceOption.valueOf(args[i].toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                ChatUtil.sendMessage(sender, "&cThe option " + args[i] + " is not a valid place option!");
                return;
            }
        }

        AbstractCustomBlock abstractCustomBlock = storage.getAbstractCustomBlock(blockName);
        CustomBlockRotation rotation = new BDCCustomBlockRotation(attachedFace, direction);
        Location location = new Location(world, x, y, z);
        LocationPlaceholder locationPlaceholder = new LocationPlaceholder(location);

        Runnable placing = () -> {
            CustomBlock customBlock;

            try {
                customBlock = this.plugin.getCustomBlockService().placeBlock(abstractCustomBlock, location, rotation, null, options.toArray(CustomBlockPlaceOption[]::new));
            } catch (IllegalArgumentException e) {
                ChatUtil.sendMessage(sender,
                        locationPlaceholder.apply(StringMessagesValue.COMMAND_CUSTOM_BLOCK_PLACE_FAILED)
                                .replace("%customblock_name%", blockName)
                                .replace("%reason%", e.getMessage())
                );
                return;
            }

            if (customBlock == null) {
                ChatUtil.sendMessage(sender, locationPlaceholder.apply(StringMessagesValue.COMMAND_CUSTOM_BLOCK_PLACE_FAILED_WITHOUT_REASON)
                        .replace("%customblock_name%", blockName)
                );
                return;
            }

            ChatUtil.sendMessage(sender,
                    locationPlaceholder.apply(StringMessagesValue.COMMAND_CUSTOM_BLOCK_PLACE_PLACED)
                            .replace("%customblock_name%", blockName)
            );
        };

        // one tick later, outside this command: the model of a block is read the first time by summoning it, and a
        // command run from a command waits for the end of the first one
        Bukkit.getScheduler().runTask(this.plugin, placing);
    }

    private static final String BREAK_USAGE = "&6/bdc custom-block break <x> <y> <z> [world]";

    /**
     * /bdc custom-block break &lt;x&gt; &lt;y&gt; &lt;z&gt; [world]
     * <p>
     * Breaks the custom block stored in the block x y z (the block a model was placed in: 10 64 20 for a model placed
     * at 10.5 64 20.5), from a player or from the console, without dropping its item. The chunk is loaded first and
     * the block is broken a second later, once its interactions are back in the world.
     */
    private void breakAt(CommandSender sender, String[] args) {
        if (!sender.hasPermission(DefaultPermissions.BDC.Command.BREAK_CB)) {
            ChatUtil.sendMessage(sender, "&cYou do not have permission to use this command");
            return;
        }
        double x = parseCoordinate(args.length > 2 ? args[2] : null);
        double y = parseCoordinate(args.length > 3 ? args[3] : null);
        double z = parseCoordinate(args.length > 4 ? args[4] : null);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            ChatUtil.sendMessage(sender, "&cYou must specify valid coordinates in the format <x> <y> <z>");
            ChatUtil.sendMessage(sender, BREAK_USAGE);
            return;
        }
        World world = sender instanceof Entity entity ? entity.getWorld()
                : sender instanceof BlockCommandSender commandBlock ? commandBlock.getBlock().getWorld()
                : Bukkit.getWorlds().get(0);
        if (args.length > 5) {
            world = Bukkit.getWorld(args[5]);
            if (world == null) {
                ChatUtil.sendMessage(sender, "&cNo world named '" + args[5] + "'.");
                return;
            }
        }
        Location location = new Location(world, Math.floor(x), Math.floor(y), Math.floor(z));
        world.getChunkAt(location).load();
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            CustomBlock customBlock = this.plugin.getCustomBlockService().getCustomBlock(location);
            String where = location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ() + " (" + location.getWorld().getName() + ")";
            if (customBlock == null) {
                ChatUtil.sendMessage(sender, "&cNo custom block in " + where);
                return;
            }
            try {
                this.plugin.getCustomBlockService().breakBlock(customBlock, null, me.general_breddok.blockdisplaycreator.custom.block.option.CustomBlockBreakOption.SILENT_BREAK);
                ChatUtil.sendMessage(sender, "&aBroke " + customBlock.getName() + " in " + where);
            } catch (IllegalArgumentException e) {
                ChatUtil.sendMessage(sender, "&cCould not break the custom block in " + where + ": " + e.getMessage());
            }
        }, 20L);
    }

    @Nullable
    private static BlockFace parseFace(String text) {
        try {
            return BlockFace.valueOf(text.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isPlaceOption(String text) {
        try {
            CustomBlockPlaceOption.valueOf(text.toUpperCase(java.util.Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> result = new ArrayList<>();
        /*Material[] materials = Material.values();
        List<Material> blockMaterials = Arrays.stream(materials).filter(Material::isBlock).toList();
        List<Enchantment> enchantments = List.of(Enchantment.values());
        List<ItemFlag> itemFlags = List.of(ItemFlag.values());
        Sound[] sounds = Sound.values();*/

        List<String> blocksNames = service.getStorage().getNames();
        Location lookingLocation = null;


        String arg1 = null;
        String arg2 = null;
        String arg3 = null;
        String arg4 = null;
        String arg5 = null;
        String arg6 = null;
        String arg7 = null;
        Player player = null;

        try {
            arg1 = args[0];
            arg2 = args[1];
            arg3 = args[2];
            arg4 = args[3];
            arg5 = args[4];
            arg6 = args[5];
            arg7 = args[6];
        } catch (ArrayIndexOutOfBoundsException ignored) {
        }

        if (sender instanceof Player) {
            player = (Player) sender;
        }

        if (player != null) {
            Block targetBlockExact = player.getTargetBlockExact(5);

            if (targetBlockExact != null) {
                lookingLocation = targetBlockExact.getLocation();
            } else {
                lookingLocation = player.getLocation();
            }
        }

        if (args.length == 1) {
            StringUtil.copyPartialMatches(arg1, List.of(
                    "custom-block",
                    "import",
                    "animation",
                    "settings",
                    "erasecbdata",
                    "reload"
            ), result);
        } else if (arg1.equals("animation")) {
            animationTab(args, lookingLocation, result);
        } else if (arg1.equals("settings")) {
            me.general_breddok.blockdisplaycreator.render.DisplayRenderer renderer = this.plugin.getDisplayRenderer();
            if (renderer != null && renderer.gizmos() != null) {
                result.addAll(renderer.gizmos().adminMenu().tab(sender, args));
            }
        } else if (arg1.equals("import")) {
            if (args.length == 2) {
                result.add("<number>");
            } else if (args.length == 3) {
                result.add("<name>");
            } else if (args.length == 4) {
                StringUtil.copyPartialMatches(args[3], List.of("click", "loop", "place"), result);
            } else if (args.length == 5) {
                StringUtil.copyPartialMatches(args[4], List.of("0", "90", "180", "270"), result);
            }
        } else if (args.length == 2) {
            if (arg1.equals("erasecbdata")) {
                if (player != null) {
                    StringUtil.copyPartialMatches(arg2, Collections.singletonList(lookingLocation.getBlockX() + " " + lookingLocation.getBlockY() + " " + lookingLocation.getBlockZ()), result);
                }
            } else if (arg1.equals("custom-block")) {
                StringUtil.copyPartialMatches(arg2, List.of(
                        "give",
                        "place",
                        "break"
                        /*"create",
                        "set"*/
                ), result);
            } else if (arg1.equals("reload")) {
                StringUtil.copyPartialMatches(arg2, blocksNames, result);
            }
        } else if (args.length == 3) {
            if (arg1.equals("erasecbdata")) {
                if (player != null) {
                    StringUtil.copyPartialMatches(arg3, Collections.singletonList(lookingLocation.getBlockY() + " " + lookingLocation.getBlockZ()), result);
                }
            } else if (arg2.equals("give") || arg2.equals("place")) {
                StringUtil.copyPartialMatches(arg3, blocksNames, result);
            }
        } else if (args.length == 4) {
            if (arg1.equals("erasecbdata")) {
                if (player != null) {
                    StringUtil.copyPartialMatches(arg4, Collections.singletonList("" + lookingLocation.getBlockZ()), result);
                }
            } /*else if (arg2.equals("set")) {

                StringUtil.copyPartialMatches(arg4, List.of(
                        "central-material",
                        "item",
                        *//*"interactions",*//*
                        "sound",
                        "display",
                        "sides-count"
                ), result);
            }*/ else if (arg2.equals("give")) {

                List<String> tabComplete = new ArrayList<>();

                tabComplete.addAll(List.of(
                        "@a",
                        "@p",
                        "@r",
                        "@s"
                ));

                tabComplete.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());

                StringUtil.copyPartialMatches(arg4, tabComplete, result);
            } else if (arg2.equals("place")) {
                if (player != null) {
                    StringUtil.copyPartialMatches(arg4, Collections.singletonList(lookingLocation.getBlockX() + " " + lookingLocation.getBlockY() + " " + lookingLocation.getBlockZ()), result);
                }
            }
        } else if (args.length == 5) {
            if (arg1.equals("erasecbdata")) {
                if (player != null) {
                    StringUtil.copyPartialMatches(arg5, Collections.singletonList(lookingLocation.getBlockX() + " " + lookingLocation.getBlockY() + " " + lookingLocation.getBlockZ()), result);
                }
            } else if (arg2.equals("place")) {
                if (player != null) {
                    StringUtil.copyPartialMatches(arg5, Collections.singletonList(lookingLocation.getBlockY() + " " + lookingLocation.getBlockZ()), result);
                }
            }
            /*else if (arg2.equals("create")) {
                StringUtil.copyPartialMatches(arg5, ItemUtil.BLOCK_MATERIAL_NAMES, result);
            }*/
            /*else if (arg4.equals("central-material")) {
                StringUtil.copyPartialMatches(arg5, blockMaterials.stream().map(Object::toString).toList(), result);
            } else if (arg4.equals("item")) {
                StringUtil.copyPartialMatches(arg5, List.of(
                        "material",
                        "name",
                        "enchantments",
                        "item-flags",
                        "lore",
                        "skullmeta"
                ), result);
            } */
            /*}else if (arg4.equals("interactions")) {
                StringUtil.copyPartialMatches(arg5, List.of(
                        "width",
                        "height",
                        "command",
                        "command-source"
                ), result);
            }*//* else if (arg4.equals("display")) {
                StringUtil.copyPartialMatches(arg5, List.of(
                        "glowing",
                        "glow-color-override"
                ), result);
            } else if (arg4.equals("sound")) {
                StringUtil.copyPartialMatches(arg5, List.of(
                        "break",
                        "place"
                ), result);
            }*/
        } else if (args.length == 6) {
            if (arg1.equals("erasecbdata")) {
                if (player != null) {
                    StringUtil.copyPartialMatches(arg6, Collections.singletonList(lookingLocation.getBlockX() + " " + lookingLocation.getBlockY() + " " + lookingLocation.getBlockZ()), result);
                }
            } else if (arg2.equals("place")) {
                if (player != null) {
                    StringUtil.copyPartialMatches(arg6, Collections.singletonList("" + lookingLocation.getBlockZ()), result);
                }
            }

            /*else if (arg5.equals("material")) {
                StringUtil.copyPartialMatches(arg6, Arrays.stream(materials).map(Object::toString).toList(), result);
            } else if (arg5.equals("enchantments")) {
                StringUtil.copyPartialMatches(arg6, enchantments.stream().map(enchantment -> enchantment.getKey().getKey().toUpperCase()).toList(), result);
            } else if (arg5.equals("item-flags")) {
                StringUtil.copyPartialMatches(arg6, itemFlags.stream().map(Object::toString).toList(), result);
            } else if (arg5.equals("skullmeta")) {
                StringUtil.copyPartialMatches(arg6, List.of("url"), result);
            } else if (arg5.equals("command-source")) {
                StringUtil.copyPartialMatches(arg6, List.of("player", "console"), result);
            } else if (arg5.equals("glowing")) {
                StringUtil.copyPartialMatches(arg6, List.of("true", "false"), result);
            } else if (arg5.equals("break") || arg5.equals("place")) {
                StringUtil.copyPartialMatches(arg6, List.of("sound-type", "volume", "pitch"), result);
            }*/
        } else if (args.length == 7) {
            if (arg1.equals("erasecbdata")) {
                if (player != null) {
                    StringUtil.copyPartialMatches(arg7, Collections.singletonList(lookingLocation.getBlockX() + " " + lookingLocation.getBlockY() + " " + lookingLocation.getBlockZ()), result);
                }
            } else if (arg2.equals("place")) {
                List<String> next = new ArrayList<>(List.of("up", "down", "north", "south", "west", "east"));
                Bukkit.getWorlds().forEach(world -> next.add(world.getName()));
                StringUtil.copyPartialMatches(arg7, next, result);
            }
                /*else if (arg6.equals("sound-type")) {
                StringUtil.copyPartialMatches(arg7, Arrays.stream(sounds).map(Enum::name).toList(), result);
            }*/
        } else if (args.length == 8 && arg1.equals("custom-block") && arg2.equals("place")) {
            // after a world: a face or a yaw; after a face: a yaw
            List<String> next = new ArrayList<>(List.of("0", "90", "180", "270"));
            if (Bukkit.getWorld(arg7) != null) {
                next.addAll(List.of("up", "down", "north", "south", "west", "east"));
            }
            StringUtil.copyPartialMatches(args[7], next, result);
        }


        return result;
    }


    /**
     * /bdc import &lt;number or link of the model on block-display.com&gt; &lt;name&gt; [click|loop|place] [turn]
     * <p>
     * Creates the file of a custom block from a model of the site: its display entities, a click box fitted to
     * it and its animation if it has one. The download and the conversion run off the main thread.
     */
    private void importModel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bdc.command.import")) {
            ChatUtil.sendMessage(sender, "&cYou do not have the permission to import models.");
            return;
        }
        java.util.regex.Matcher number = args.length < 3 ? null : java.util.regex.Pattern.compile("(\\d+)/?$").matcher(args[1]);
        if (number == null || !number.find()) {
            ChatUtil.sendMessage(sender, "&6/bdc import <number of the model on block-display.com> <name> [click|loop|place] [turn]");
            ChatUtil.sendMessage(sender, "&7The animation of the model, if it has one, plays on a right click (click), all the time (loop) or when the block is placed (place). By default click.");
            return;
        }
        int id = Integer.parseInt(number.group(1));
        String name = args[2];
        if (!name.matches("[A-Za-z0-9_]{1,40}")) {
            ChatUtil.sendMessage(sender, "&cThe name takes letters, digits and underscores only.");
            return;
        }
        String trigger = args.length > 3 ? args[3].toLowerCase() : "click";
        if (!List.of("click", "loop", "place").contains(trigger)) {
            ChatUtil.sendMessage(sender, "&cThe animation plays on click, loop or place, not on '" + args[3] + "'.");
            return;
        }
        int turn = 180;
        if (args.length > 4) {
            try {
                turn = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                ChatUtil.sendMessage(sender, "&cThe turn is a number of degrees, 0, 90, 180 or 270 for instance.");
                return;
            }
        }

        CustomBlockStorage storage = this.plugin.getCustomBlockService().getStorage();
        java.nio.file.Path repository = this.plugin.getDataFolder().toPath().resolve("custom-blocks");
        boolean replaced = java.nio.file.Files.exists(repository.resolve(me.general_breddok.blockdisplaycreator.importer.SiteModelImporter.FOLDER).resolve(name + ".yml"));
        if (!replaced && storage.containsAbstractCustomBlock(name)) {
            ChatUtil.sendMessage(sender, "&cA custom block named " + name + " already exists.");
            return;
        }
        ChatUtil.sendMessage(sender, "&6Importing the model " + id + "...");
        int finalTurn = turn;
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            me.general_breddok.blockdisplaycreator.importer.SiteModelImporter.Imported imported;
            try {
                imported = me.general_breddok.blockdisplaycreator.importer.SiteModelImporter.importModel(repository, id, name, trigger, finalTurn);
            } catch (java.io.IOException | RuntimeException e) {
                String reason = e instanceof java.io.IOException && e.getMessage() != null ? e.getMessage() : e.toString();
                Bukkit.getScheduler().runTask(this.plugin, () -> ChatUtil.sendMessage(sender, "&cThe model could not be imported: " + reason));
                return;
            }
            Bukkit.getScheduler().runTask(this.plugin, () -> storage.reloadAll(() -> {
                if (this.plugin.getDisplayRepairListener() != null) {
                    this.plugin.getDisplayRepairListener().invalidate();
                }
                if (replaced && this.plugin.getDisplayRenderer() != null) {
                    // blocks of this name already placed take the new model
                    this.plugin.getDisplayRenderer().reload();
                }
                ChatUtil.sendMessage(sender, "&aImported " + (imported.title().isEmpty() ? "the model " + id : "\"" + imported.title() + "\"")
                        + " as &e" + name + "&a: " + imported.elements() + " display entities"
                        + (imported.ticks() > 0 ? ", animation of " + imported.ticks() + " ticks on " + imported.animatedElements() + " of them (" + trigger + ")" + (imported.notes() > 0 ? " with " + imported.notes() + " sounds" : "") : ", no animation")
                        + (imported.skipped() > 0 ? ". " + imported.skipped() + " text element(s) were left out" : "") + ".");
                ChatUtil.sendMessage(sender, "&7File: custom-blocks/import/" + name + ".yml");
                AbstractCustomBlock block = storage.getAbstractCustomBlock(name);
                if (block == null) {
                    ChatUtil.sendMessage(sender, "&cThe file was written but the block did not load: see the console.");
                } else if (sender instanceof Player player) {
                    player.getInventory().addItem(block.getItem());
                }
            }));
        });
    }

    private static final String ANIMATION_USAGE = "&6/bdc animation play <player|selector|*> <animation> <x> <y> <z> [world]&7, "
            + "&6/bdc animation stop <player|selector|*> <x> <y> <z> [world]&7, &6/bdc animation list <x> <y> <z> [world]&7, &6/bdc animation stats";

    /**
     * /bdc animation play|stop|list: plays an animation of a placed custom block for some players (the others keep
     * seeing it as it is) or for everyone (*), stops it, or lists the animations of the block. Coordinates are those
     * of the block the custom block was placed in; ~ counts from the sender. Run from the console, a success says
     * nothing, so that plugins can run it at every use.
     */
    private void animation(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bdc.command.animation")) {
            ChatUtil.sendMessage(sender, "&cYou do not have permission to use this command");
            return;
        }
        me.general_breddok.blockdisplaycreator.render.DisplayRenderer renderer = this.plugin.getDisplayRenderer();
        if (renderer == null || !renderer.isActive()) {
            ChatUtil.sendMessage(sender, "&cAnimations need the custom blocks to be shown through packets (packet-display: true in config.yml).");
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
        if (action.equals("stats")) {
            // what the players close to blocks (look-at, approach, loops with a distance) cost every tick
            ChatUtil.sendMessage(sender, "&7Nearby players pass, " + renderer.attendStats());
            return;
        }
        int blockAt = switch (action) {
            case "play" -> 4;
            case "stop" -> 3;
            case "list" -> 2;
            default -> -1;
        };
        if (blockAt < 0 || args.length < blockAt + 3) {
            ChatUtil.sendMessage(sender, ANIMATION_USAGE);
            return;
        }
        Block block = commandBlock(sender, args, blockAt);
        if (block == null) {
            return;
        }
        String where = block.getX() + " " + block.getY() + " " + block.getZ() + " in " + block.getWorld().getName();
        if (action.equals("list")) {
            java.util.Map.Entry<String, List<String>> found = renderer.animations(block);
            if (found == null) {
                ChatUtil.sendMessage(sender, "&cNo custom block shown through packets at " + where + ".");
            } else if (found.getValue().isEmpty()) {
                ChatUtil.sendMessage(sender, "&e" + found.getKey() + " &7has no animation.");
            } else {
                ChatUtil.sendMessage(sender, "&e" + found.getKey() + "&7: " + String.join(", ", found.getValue()));
            }
            return;
        }
        List<Player> players = null;
        if (!args[2].equals("*")) {
            players = new ArrayList<>();
            try {
                for (Entity entity : Bukkit.selectEntities(sender, args[2])) {
                    if (entity instanceof Player target) {
                        players.add(target);
                    }
                }
            } catch (IllegalArgumentException e) {
                ChatUtil.sendMessage(sender, "&c'" + args[2] + "' is neither an online player nor a valid selector.");
                return;
            }
            if (players.isEmpty()) {
                ChatUtil.sendMessage(sender, "&cNo online player matches '" + args[2] + "'.");
                return;
            }
        }
        boolean quiet = sender instanceof org.bukkit.command.ConsoleCommandSender;
        String forWhom = players == null ? "everyone" : players.size() == 1 ? players.get(0).getName() : players.size() + " players";
        if (action.equals("stop")) {
            int stopped = renderer.stop(block, players);
            if (stopped < 0) {
                ChatUtil.sendMessage(sender, "&cNo custom block shown through packets at " + where + ".");
            } else if (!quiet) {
                ChatUtil.sendMessage(sender, "&aStopped " + stopped + " animation(s) of the block at " + where + " for " + forWhom + ".");
            }
            return;
        }
        String name = args[3];
        switch (renderer.play(block, name, players)) {
            case PLAYED -> {
                if (!quiet) {
                    ChatUtil.sendMessage(sender, "&aPlaying &e" + name + " &aat " + where + " for " + forWhom + ".");
                }
            }
            case NO_BLOCK -> ChatUtil.sendMessage(sender, "&cNo custom block shown through packets at " + where + ".");
            case NO_ANIMATION -> {
                java.util.Map.Entry<String, List<String>> found = renderer.animations(block);
                ChatUtil.sendMessage(sender, "&c" + (found == null ? "The block" : found.getKey()) + " has no animation named '" + name + "'"
                        + (found == null || found.getValue().isEmpty() ? "." : ": " + String.join(", ", found.getValue())));
            }
            case LOOPING -> ChatUtil.sendMessage(sender, "&c" + name + " plays in a loop for everyone: it cannot be started.");
        }
    }

    private void animationTab(String[] args, @Nullable Location looking, List<String> result) {
        String typed = args[args.length - 1];
        if (args.length == 2) {
            StringUtil.copyPartialMatches(typed, List.of("play", "stop", "list", "stats"), result);
            return;
        }
        String action = args[1].toLowerCase(java.util.Locale.ROOT);
        int blockAt = action.equals("play") ? 4 : action.equals("stop") ? 3 : action.equals("list") ? 2 : -1;
        if (blockAt < 0) {
            return;
        }
        int index = args.length - 1;
        if (index == 2 && blockAt > 2) {
            List<String> targets = new ArrayList<>(List.of("*", "@a", "@p", "@s"));
            Bukkit.getOnlinePlayers().forEach(online -> targets.add(online.getName()));
            StringUtil.copyPartialMatches(typed, targets, result);
        } else if (index == 3 && blockAt == 4) {
            me.general_breddok.blockdisplaycreator.render.DisplayRenderer renderer = this.plugin.getDisplayRenderer();
            if (renderer != null) {
                StringUtil.copyPartialMatches(typed, renderer.playableAnimations(), result);
            }
        } else if (index >= blockAt && index < blockAt + 3 && looking != null) {
            // the block looked at, its remaining coordinates at once
            int[] coordinates = {looking.getBlockX(), looking.getBlockY(), looking.getBlockZ()};
            StringBuilder rest = new StringBuilder();
            for (int i = index - blockAt; i < 3; i++) {
                rest.append(i > index - blockAt ? " " : "").append(coordinates[i]);
            }
            StringUtil.copyPartialMatches(typed, List.of(rest.toString()), result);
        } else if (index == blockAt + 3) {
            List<String> worlds = new ArrayList<>();
            Bukkit.getWorlds().forEach(world -> worlds.add(world.getName()));
            StringUtil.copyPartialMatches(typed, worlds, result);
        }
    }

    /**
     * The block at three coordinates of a command, ~ counting from the sender, in the world that follows them, or
     * the sender's (the main world for the console).
     */
    @Nullable
    private Block commandBlock(CommandSender sender, String[] args, int at) {
        Location origin = sender instanceof Entity entity ? entity.getLocation()
                : sender instanceof org.bukkit.command.BlockCommandSender block ? block.getBlock().getLocation().add(0.5, 0, 0.5) : null;
        org.bukkit.World world = origin != null ? origin.getWorld() : Bukkit.getWorlds().get(0);
        if (args.length > at + 3) {
            world = Bukkit.getWorld(args[at + 3]);
            if (world == null) {
                ChatUtil.sendMessage(sender, "&cNo world named '" + args[at + 3] + "'.");
                return null;
            }
        }
        double[] position = new double[3];
        for (int i = 0; i < 3; i++) {
            String text = args[at + i];
            boolean relative = text.startsWith("~");
            if (relative && origin == null) {
                ChatUtil.sendMessage(sender, "&c~ needs a sender with a position: give the coordinates of the block.");
                return null;
            }
            try {
                double value = relative ? (text.length() == 1 ? 0 : Double.parseDouble(text.substring(1))) : Double.parseDouble(text);
                position[i] = relative ? value + (i == 0 ? origin.getX() : i == 1 ? origin.getY() : origin.getZ()) : value;
            } catch (NumberFormatException e) {
                ChatUtil.sendMessage(sender, "&c'" + text + "' is not a coordinate.");
                return null;
            }
        }
        return world.getBlockAt((int) Math.floor(position[0]), (int) Math.floor(position[1]), (int) Math.floor(position[2]));
    }

    private void eraseCbData(BoundingBox boundingBox, Player sender) {
        WorldSelection worldSelection = new WorldSelection(boundingBox, sender.getWorld());

        List<Entity> displayEntities = new ArrayList<>();
        List<Entity> interactions = new ArrayList<>();
        List<Entity> collisions = new ArrayList<>();


        sender.getWorld().getNearbyEntities(
                boundingBox,
                entity -> entity instanceof Display || entity instanceof Interaction || entity instanceof Shulker
        ).forEach(entity -> {
            if (entity instanceof Display) {
                displayEntities.add(entity);
            } else if (entity instanceof Interaction) {
                interactions.add(entity);
            } else if (entity instanceof Shulker) {
                collisions.add(entity);
            }
        });


        final int[] blocksCount = {0};

        worldSelection.getLocations().forEach(location -> {
                    CustomBlockData customBlockData = new CustomBlockData(location.getBlock(), BlockDisplayCreator.getInstance());

                    if (customBlockData.has(CustomBlockKey.NAME)) {
                        customBlockData.remove(CustomBlockKey.NAME);
                        customBlockData.remove(CustomBlockKey.SERVICE_CLASS);
                        customBlockData.remove(CustomBlockKey.LOCATION);
                        customBlockData.remove(CustomBlockKey.CUSTOM_BLOCK_UUID);
                        customBlockData.remove(CustomBlockKey.DISPLAY_UUID);
                        customBlockData.remove(CustomBlockKey.INTERACTION_UUID);
                        customBlockData.remove(CustomBlockKey.COLLISION_UUID);
                        customBlockData.remove(CustomBlockKey.INTERACTION_IDENTIFIER);
                        customBlockData.remove(CustomBlockKey.COLLISION_IDENTIFIER);
                        customBlockData.remove(CustomBlockKey.BLOCK_ROTATION);
                        customBlockData.remove(CustomBlockKey.ITEM);
                        customBlockData.remove(CustomBlockKey.DISPLAY_SPAWN_COMMAND);
                        // the rest of its data, and its display entities when they are shown through packets
                        me.general_breddok.blockdisplaycreator.custom.block.BDCCustomBlockService.clearCustomBlockData(customBlockData);
                        blocksCount[0]++;
                    }
                }
        );

        displayEntities.forEach(Entity::remove);
        interactions.forEach(Entity::remove);
        collisions.forEach(Entity::remove);

        ChatUtil.sendMessage(sender, "&6Killed %d display entities", displayEntities.size());
        ChatUtil.sendMessage(sender, "&6Killed %d interaction entities", interactions.size());
        ChatUtil.sendMessage(sender, "&6Killed %d collision entities", collisions.size());
        ChatUtil.sendMessage(sender, "&6Cleared %d custom blocks", blocksCount[0]);
    }

    private double parseCoordinate(String coord) {
        try {
            if (coord == null) {
                return Double.NaN;
            }
            return Double.parseDouble(coord);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Tells a player who was given a custom block by someone else (console, another player) that he received it,
     * with the same message as the one he gets when the command is his own. Nothing when silent or the message is empty.
     */
    private static void notifyRecipient(Player recipient, String block, byte amount, boolean silent) {
        if (silent || StringMessagesValue.COMMAND_CUSTOM_BLOCK_GIVE_PLAYER_RECEIVE == null
                || StringMessagesValue.COMMAND_CUSTOM_BLOCK_GIVE_PLAYER_RECEIVE.isBlank()) {
            return;
        }
        ChatUtil.sendMessage(recipient, StringMessagesValue.COMMAND_CUSTOM_BLOCK_GIVE_PLAYER_RECEIVE
                .replace("%customblock_name%", block)
                .replace("%amount%", String.valueOf(amount)));
    }
}

