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

A collision of a custom block is an invisible shulker that often stands in front of its click box (on a big model above all): a right click on it counts as a click on the nearest click box of that block (main hand only: the client sends one click per hand on a shulker).

## Blocks nobody breaks by hitting them

`breakable: false` at the root of a block file: no hit breaks its placed blocks, not even from an operator, who is told how to remove one instead (`protected.break` in `messages.yml`, with `%x%` `%y%` `%z%` `%world%`). `/bdc custom-block break <x> <y> <z> [world]` still removes it. Meant for characters and props other features rely on (quest NPCs, puzzle pieces).

The same holds for one placed block made unbreakable from its admin panel (below), and for the central block of a full block (`BLOCK_` models): hitting the central block no longer breaks a block its file or its panel protects.

## Breaking a model and taking its item by aiming at it

Those with `admin-aim.permission` (`config.yml`, `bdc.gizmo.admin` by default) act on a placed model by aiming at what it draws, the way the gizmo picks it, whether it has a click box or not (a decoration given none so that it never takes the clicks of a PvP arena):

- A swing (left click) breaks it as a hit on its click box would: its item drops unless in creative mode, its break sound plays, a file with `breakable: false` or a block made unbreakable from its panel is refused with `protected.break`, and the regions, islands and permissions of the file still decide.
- A middle click puts its item in the hand, as a middle click on its click box does (the one in the inventory, a new one in creative mode).

The model counts when the line of sight meets one of its parts (what each part really draws at rest, not the box around the whole model) within `admin-aim.reach` blocks (5), closer than the block or the entity the crosshair is on. A swing that hits a player or a mob stays a hit on him; a click on a block standing in front of the model stays on the block, and when the client starts breaking a block behind the model, the model is hit and the block is not damaged. While a gizmo is open its clicks are the gizmo's. A button held down breaks one model every `admin-aim.break-delay` ticks (5).

`admin-aim.break` and `admin-aim.pick` turn either off, `admin-aim.break-game-modes` lists the game modes a swing breaks in (survival and creative). The game client only tells the server of a middle click when its crosshair is on a block or an entity: in the air, use the copy button of the gizmo (below).

Nothing of it runs on any tick. A swing or a click of a player without the permission costs a lookup (whether a player has it is asked once a second); an administrator's swing or middle click looks only at the models he is sent close to him.

## The gizmo

Shift + right click on a custom block shown through packets, where the player may place and break custom blocks, opens its gizmo: arrows and squares move it, arcs turn and tip it, the white knob sets its size, the red button puts it back where it was placed. Its texts are in `messages.yml` (`gizmo`).

- The tool is drawn after what the model really draws, at the size the block has: a flat item (a flower, a stick, a torch) counts one pixel thick however stretched, a head half a block. A small model gets a small tool (1 at least, 6 at most).
- The size bar goes from `gizmo.size-min` to `gizmo.size-max` of the file's size (`config.yml`, 75 % to 125 %), from `gizmo.admin-size-min` to `gizmo.admin-size-max` for those with `bdc.gizmo.admin` (10 % to 1000 %, logarithmic: 100 % in the middle). A block standing upright grows from its floor: it neither sinks into the ground nor floats. A tipped block grows around its middle.
- For those with `bdc.gizmo.admin`, the strip also has, from left to right: a cartography table that puts the block's item in their hand (the one in the inventory, else a new one, in any game mode), the command block of the admin panel (below), and at the right end a target that puts the tool on the block's click box.

### The gizmo of the click box

The target of the strip switches the tool between the model and its click box (its plate turns light blue); the hitbox page of the panel opens it there too ("Set with the gizmo"). On the click box the tool stands in the middle of the box and draws its outline, for its administrator alone:

- The arrows and squares move the box, by the same steps as the model (scroll while holding: 1/16 to 1 block; Shift + F: free; F: the model's axes or the world's).
- The white cubes on its sides set its width (its base stays square, around its middle), the one on its top its height (its floor stays). Their steps are the arrows'.
- A right click on a part puts back what it changes as the click box of the file has it (or the one around the model, for a file without any); the red button gives the block the click boxes of its file back.
- Each part dropped gives the block that click box: its hitbox is summoned again at once, as from the hitbox page. The box is the custom click box of the hitbox page (one box with a square base), now with an offset on x and z in the model's axes: it turns, grows and shrinks with the model.

## Settings of one placed block (admin panel)

What is set here belongs to ONE placed block, stored with it (`custom-block-settings` in its chunk data) and gone when it is broken: the other blocks of the same file keep what their file gives them. Permission `bdc.gizmo.admin` (op).

Open it with the command block at the left end of the gizmo's strip, or with `/bdc settings` aiming at a block (its click box, its central block or what it draws; else the block your gizmo is open on). Each click applies at once.

| Icon | What it sets |
|---|---|
| Size | 10 % to 1000 % of the file's size (`gizmo.admin-size-min/max`). Left/right click ±10 %, Shift + left click to type a value, Shift + right click back to 100 %. Click boxes and collisions follow. |
| View distance | How far players are sent this block, around it, above and below (counted in chunks around it, like `display.view-distance`). ±8 blocks, Shift + left click to type, Shift + right click back to the file's. |
| Loops | Stops the loop animations of this block (it stays at rest, fewer packets). |
| Breakable | Unbreakable: no hit breaks it, not even from an operator (who is told how to remove it). |
| Gizmo | Reserved to administrators: players who may build there can no longer move or turn it (they are told so). |
| Note | A note for the staff, shown in the panel and in `/bdc settings list`. |
| Click commands | Commands run when a player clicks this block, with `%player%`, PlaceholderAPI and `{x} {y} {z} {world}`. |
| Run by | The console (anything: tp, heal...) or the player who clicks (only what he may do). |
| File's commands | Kept, or replaced by the block's own. Shown only when the file has some. |
| Click animation | Plays, or not, the click animation of the file. Shown only when the file has one. |
| Cooldown | Per player, between two clicks that do something. Left click goes to the next of `gizmo.admin-cooldowns`, right click to the one before, Shift + left click to type a duration (`90s`, `5m`, `2h30m`, `1d`). From one minute on, the wait outlives restarts and the player is told how long is left (`cooldown.wait` in `messages.yml`). |
| Permission | The permission a player needs for his click to do anything (`placed-denied.interact` in `messages.yml` otherwise). |
| Hitbox | Opens the hitbox page (below). |
| Copy / Paste | Copies these settings to paste them on another block (the size is not copied; a click box drawn for another model is drawn again around the one it is pasted on). |
| Reset | Shift + click: the block follows its file again (its place and size stay: the red button of the gizmo puts them back). |
| Break | Shift + click: removes the block, even unbreakable, and gives its item to you. |

Values to type are written with a command the panel suggests in the chat, on the block whose panel you opened last:

```
/bdc settings size <percent|default>
/bdc settings view-distance <blocks|default>
/bdc settings hitbox <width> <height> [floor] [x] [z]   /bdc settings hitbox file|model|none
/bdc settings collision on|off
/bdc settings command add <command>      /bdc settings command remove <number>      /bdc settings command clear
/bdc settings cooldown <duration|none>
/bdc settings permission <permission|none>
/bdc settings note <text|none>
/bdc settings list [radius]              (blocks with settings within the radius, 64 by default; click a line to teleport)
```

The texts and icons of the panel are in `messages.yml` (`gizmo-admin`), its limits, cooldown steps and sounds in `config.yml` (`gizmo`).

### Hitbox page

The click boxes and the collision of this one block (the other blocks of the same file keep those of their file):

| Icon | What it sets |
|---|---|
| Click box of the file | The click boxes its file gives it (none for a file without any). |
| Custom click box | One box with a square base, in place of those of the file (or for a file without any). It starts as the file's box (around the file's boxes when it has several, else around what the model draws), runs what the file's first click box runs (its commands, its click animation) and grows, shrinks and turns with the block. Width, height, floor, offset X and offset Z (along the red and blue arrows of the gizmo, in the model's axes): left/right click ±0.1 block, Shift ±1; "Around the model", "Around the file's" and "Type the values" set it at once. |
| No click box | Nothing of it can be clicked nor hit by players: its panel opens again with `/bdc settings` aiming at what it draws, and its Break button removes it (or a swing of an administrator aiming at it). |
| Collision | The collisions of its file, or none (players walk through it). Shown only when its file has some. |
| See the hitbox | Draws its click boxes (white) and collisions (orange) for you alone, while the page is open and `gizmo.hitbox-outline.seconds` more (`config.yml`: particles, colors). |
| Set with the gizmo | Closes the page and opens the gizmo of the click box on this block (above). |

Values are in blocks as the block stands now (its size included), up to `gizmo.hitbox-max` (32). The entities are summoned again at once; a block one of whose entities is not loaded gets its new hitbox when it is next read.
