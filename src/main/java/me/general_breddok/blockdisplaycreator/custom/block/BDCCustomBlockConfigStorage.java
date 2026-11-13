package me.general_breddok.blockdisplaycreator.custom.block;

import me.general_breddok.blockdisplaycreator.custom.AutomaticCommandDisplaySummoner;
import me.general_breddok.blockdisplaycreator.commandparser.SummonCommandLine;
import me.general_breddok.blockdisplaycreator.commandparser.CommandLine;
import java.util.Map;
import java.util.HashMap;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.command.capi.BlockDisplayCreatorCAPICommand;
import me.general_breddok.blockdisplaycreator.data.yaml.YamlConfigFile;
import me.general_breddok.blockdisplaycreator.file.config.loader.CustomBlockConfigurationFile;
import me.general_breddok.blockdisplaycreator.file.config.loader.CustomBlockFileRepository;
import me.general_breddok.blockdisplaycreator.file.config.loader.CustomBlockRepository;
import me.general_breddok.blockdisplaycreator.file.config.loader.CustomBlockYamlFile;
import me.general_breddok.blockdisplaycreator.file.exception.CustomBlockLoadException;
import me.general_breddok.blockdisplaycreator.util.ChatUtil;
import me.general_breddok.blockdisplaycreator.util.FileUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

@Getter
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BDCCustomBlockConfigStorage implements CustomBlockStorage {
    List<AbstractCustomBlock> abstractCustomBlocks;
    /**
     * For each custom block, its summon commands as text, each cut in two around its coordinates. Writing the
     * data of a large model as text takes time: it is done here, when the files are read off the main thread,
     * rather than each time the model is summoned.
     */
    volatile Map<String, List<String[]>> summonTexts = Map.of();
    @Getter
    CustomBlockRepository customBlockRepository;
    final JavaPlugin plugin;

    public BDCCustomBlockConfigStorage(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.abstractCustomBlocks = new ArrayList<>();
    }

    @Override
    public void reloadAll(@Nullable Runnable onComplete) {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            ChatUtil.log("&6[BlockDisplayCreator] &eInitializing blocks...");

            CustomBlockRepository tempCustomBlockRepository = new CustomBlockFileRepository(this.plugin);
            List<AbstractCustomBlock> tempAbstractCustomBlocks = new ArrayList<>();

            for (CustomBlockConfigurationFile configurationFile : tempCustomBlockRepository.getFiles()) {
                try {
                    tempAbstractCustomBlocks.add(configurationFile.getAbstractCustomBlock());
                } catch (CustomBlockLoadException e) {
                    ChatUtil.log(e.getMessage());
                }
            }


            ChatUtil.log("&6[BlockDisplayCreator] &eInitialization complete, %d blocks initialized", tempAbstractCustomBlocks.size());
            Map<String, List<String[]>> tempSummonTexts = new HashMap<>();
            for (AbstractCustomBlock abstractCustomBlock : tempAbstractCustomBlocks) {
                List<String[]> texts = summonTexts(abstractCustomBlock);
                if (texts != null) {
                    tempSummonTexts.put(abstractCustomBlock.getName(), texts);
                }
            }

            Bukkit.getScheduler().runTask(this.plugin, () -> {
                this.customBlockRepository = tempCustomBlockRepository;
                this.abstractCustomBlocks = tempAbstractCustomBlocks;
                this.summonTexts = tempSummonTexts;

                BlockDisplayCreatorCAPICommand bdcCommand = BlockDisplayCreator.getInstance().getBdcCommand();
                if (bdcCommand != null) {
                    bdcCommand.reloadSuggestions();
                }

                if (onComplete != null) {
                    onComplete.run();
                }
            });
        });
    }

    /**
     * The summon commands of a custom block as text, or null when one of them is not a plain summon command.
     */
    @Nullable
    private static List<String[]> summonTexts(AbstractCustomBlock abstractCustomBlock) {
        if (!(abstractCustomBlock.getDisplaySummoner() instanceof AutomaticCommandDisplaySummoner summoner) || summoner.getCommands() == null) {
            return null;
        }
        List<String[]> texts = new ArrayList<>();
        for (CommandLine command : summoner.getCommands()) {
            if (!(command instanceof SummonCommandLine summon)) {
                return null;
            }
            texts.add(new String[]{"minecraft:summon " + summon.getSummonEntity().toString().toLowerCase(), summon.getNbtArg().toString()});
        }
        return texts;
    }

    /**
     * The custom block of a name as the storage holds it, not a copy: to be read only.
     */
    @Nullable
    public AbstractCustomBlock peek(@NotNull String name) {
        for (AbstractCustomBlock abstractCustomBlock : this.abstractCustomBlocks) {
            if (abstractCustomBlock.getName().equals(name)) {
                return abstractCustomBlock;
            }
        }
        return null;
    }

    @Override
    public void reload(@NotNull String name, @Nullable Runnable onComplete) throws NoSuchElementException {
        if (!getNames().contains(name)) {
            throw new NoSuchElementException("Block \"" + name + "\" is not in storage");
        }

        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            this.abstractCustomBlocks.removeIf(abstractCustomBlock -> abstractCustomBlock.getName().equals(name));

            try {
                CustomBlockConfigurationFile file = this.customBlockRepository.getFile(name);
                file.reload();

                this.abstractCustomBlocks.add(file.getAbstractCustomBlock());
            } catch (CustomBlockLoadException | IllegalArgumentException e) {
                ChatUtil.log("&c" + e.getMessage());
            }
            Map<String, List<String[]>> updatedSummonTexts = new HashMap<>(this.summonTexts);
            AbstractCustomBlock reloaded = peek(name);
            List<String[]> texts = reloaded == null ? null : summonTexts(reloaded);
            if (texts == null) {
                updatedSummonTexts.remove(name);
            } else {
                updatedSummonTexts.put(name, texts);
            }
            this.summonTexts = updatedSummonTexts;

            Bukkit.getScheduler().runTask(this.plugin, () -> {
                BlockDisplayCreatorCAPICommand bdcCommand = BlockDisplayCreator.getInstance().getBdcCommand();
                if (bdcCommand != null) {
                    bdcCommand.reloadSuggestions();
                }

                if (onComplete != null) {
                    onComplete.run();
                }
            });
        });
    }

    public void load(@NotNull String name) {
        if (FileUtil.containsFile(this.customBlockRepository.getPath(), name + ".yml")) {
            throw new IllegalStateException("A custom block with the name " + name + " already exists.");
        }

        Path cutsomBlockFilePath = this.customBlockRepository.getPath().resolve(name + ".yml");

        CustomBlockConfigurationFile customBlockFile = new CustomBlockYamlFile(new YamlConfigFile(cutsomBlockFilePath));
        this.customBlockRepository.addFile(customBlockFile);

    }

    @Override
    public void addAbstractCustomBlock(AbstractCustomBlock abstractCustomBlock) {
        if (this.containsAbstractCustomBlock(abstractCustomBlock.getName())) {
            throw new IllegalStateException("Block named " + abstractCustomBlock.getName() + " already exists in storage");
        }
        this.abstractCustomBlocks.add(abstractCustomBlock);
    }

    @Override
    public void removeAbstractCustomBlock(String name) throws NoSuchElementException {
        if (!this.containsAbstractCustomBlock(name)) {
            throw new NoSuchElementException("Block named " + name + " does not exists in storage");
        }
        this.abstractCustomBlocks.remove(name);
    }

    @Override
    @Nullable
    public AbstractCustomBlock getAbstractCustomBlock(@NotNull String name, Object... data) {
        for (AbstractCustomBlock abstractCustomBlock : abstractCustomBlocks) {
            if (abstractCustomBlock.getName().equals(name)) {
                AbstractCustomBlock cloned = abstractCustomBlock.clone();
                cloned.getItem().setAmount(1);
                return cloned;
            }
        }
        return null;
    }

    @Override
    public boolean containsAbstractCustomBlock(@NotNull String name) {
        return abstractCustomBlocks.stream().anyMatch(element -> element.getName().equals(name));
    }

    @NotNull
    public List<AbstractCustomBlock> getAbstractCustomBlocks() {
        return abstractCustomBlocks.stream()
                .map(AbstractCustomBlock::clone)
                .toList();
    }
}
