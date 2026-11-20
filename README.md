## BlockDisplayCreator
[![](https://img.shields.io/badge/Spigot-F7FF00?logo=spigotmc&logoSize=auto)](https://www.spigotmc.org/resources/blockdisplaycreator.114877/)
![Spigot Download Size](https://img.shields.io/spiget/download-size/114877?style=flat-square)
![Spigot Downloads](https://img.shields.io/spiget/downloads/114877?label=Spigot%20Downloads&style=flat-square)

BlockDisplayCreator is a versatile plugin designed to work with [bdengine](https://block-display.com/), allowing you to create highly customizable custom blocks for decorating Minecraft server.

# Features of BlockDisplayCreator

- 🧱 **Custom blocks** with fully configurable display and easy import from **BdEngine**
- 🌍 **Support for all Minecraft versions starting from 1.19.4** — stable and future-proof
- ⚡ **Asynchronous block loading** for smooth server performance
- 🔄 **360° rotation** and placement on horizontal sides — full orientation control
- 🛠️ **Configurable count of block sides**
- 🌈 **Gradient support and PlaceholderAPI integration** — dynamic and beautiful effects
- 🛡️ **Integration with WorldGuard and SuperiorSkyblock2**: custom flags and permissions for advanced protection
- 🎭 **Customizable head-textured items** — perfect for decorative or unique blocks
- 📝 **Custom placeholders** for blocks and their entities
- 🎯 **Hitboxes of any size** with commands triggered on click
- 🚧🟪 **Collisions** to make blocks solid
- 🎵 **Fully customizable block sounds**
- 🔑 **Block-specific permissions** — fine-tuned access control
- 💬 **Fully configurable messages**
- 🎁 **Multiple drop and placement modes**
- ⛏️ **Commands on block placement and breaking** — automate interactions
- 🖱️ **Quick select with mouse wheel** *(available in Minecraft 1.21.4+)*
- 🧭 **Convenient commands** for giving, placing, breaking, and editing blocks with many parameters
- 📦 **Datapack compatibility**: commands can be executed from command blocks, and entities receive their own Scoreboard tags
- 🔧 **Special parameters for version compatibility** — consistent behavior across Minecraft versions  

# SkyHand fork

Branch `skyhand` of this fork. On top of the plugin above, custom blocks can be shown through packets (`packet-display` in `config.yml`), animated from their file, and driven by these commands:

- `/bdc import <number or link on block-display.com> <name> [click|loop|place] [turn]` creates the file of a block from a model of the site.
- `/bdc animation play|stop|list ...` plays an animation of a placed block for chosen players or for everyone.
- `/bdc custom-block place <block> <x> <y> <z> [world] [face] [yaw] [options]` places a block from a player, a command block or the console.

## Placing from the console

```
/bdc custom-block place <block> <x> <y> <z> [world] [face] [yaw] [options]
```

- The centre of the model goes on the coordinates given, whoever runs the command: write `10.5 64 20.5` for the middle of the block `10 64 20`. The block it occupies is the one those coordinates are in.
- `world`: the sender's world, the main world for the console. A name that is not a world is refused (`No world named '...'`).
- `face`: the face the block is attached to (`up` by default).
- `yaw`: degrees, `0` by default. The model is turned by it, snapped to the `sides-count` of its file, as when a player places it facing that way. Its click boxes and collision turn with it.
- `options`: `replace_custom_block`, `load_chunk`, `break_solid_material`, `silent_place`.
- Chunk data, packet display, click boxes and collisions are the same as for a block placed by a player. The block is placed on the next tick; an unknown block, an unloaded chunk or an occupied spot gives a message on the console.

## Commands run by a click

The commands of an interaction (`interactions.<name>.command`, run by the player or by the console according to `command-source`) may use, besides `%player%` (the player who clicked) and PlaceholderAPI placeholders:

| Placeholder | Replaced by |
|---|---|
| `{x}` `{y}` `{z}` | the block coordinates the custom block was placed in (whole numbers; not where a gizmo moved the model) |
| `{world}` | the name of its world |

For example `bdc animation play %player% open {x} {y} {z} {world}`. The same placeholders work in the place and break commands of a block.

A collision of a custom block is an invisible shulker that often stands in front of its click box (on a big model above all): a right click on it counts as a click on the nearest click box of that block.

## Blocks nobody breaks by hitting them

`breakable: false` at the root of a block file: no hit breaks its placed blocks, not even from an operator, who is told how to remove one instead (`protected.break` in `messages.yml`, with `%x%` `%y%` `%z%` `%world%`). `/bdc custom-block break <x> <y> <z> [world]` still removes it. Meant for characters and props other features rely on (quest NPCs, puzzle pieces).
