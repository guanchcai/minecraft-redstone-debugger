# redstone debugger

A Fabric mod (Minecraft 26.3) for debugging redstone. You can pause, slow down and step redstone while players, mobs and fluids keep running normally. You can also copy a contraption into a separate void dimension, the VENV, to test it.

## Commands

All commands are under `/rdb` and need operator permission (cheats on in singleplayer).

### Selection and VENV

| Command | What it does |
|---|---|
| `/rdb wand` | Get the selection wand (left-click: corner 1, right-click: corner 2) |
| `/rdb pos1 [<pos>]`, `/rdb pos2 [<pos>]` | Set a corner of the selection (default: where you stand) |
| `/rdb sel` | Show the selection |
| `/rdb expand [<dir>] [<n>]` | Push a face out by n (default: the direction you look, 1) |
| `/rdb shrink [<dir>] [<n>]` | Pull a face in by n |
| `/rdb shift [<dir>] [<n>]` | Move the whole box n blocks |
| `/rdb fit` | Shrink the box to the blocks inside it |
| `/rdb desel` | Clear the selection |
| `/rdb capture [<from> <to>]` | Copy the selection (or the given box) into the VENV and go there |
| `/rdb goto` | Go to the contraption in the VENV |
| `/rdb return` | Go back to where you were before entering the VENV |
| `/rdb reset` | Restore the VENV contraption to its captured state |
| `/rdb clear` | Empty the VENV |

The largest capture size is set by the gamerule `redstone-debugger:max_capture_volume`.

### Redstone debugger

| Command | What it does |
|---|---|
| `/rdb start [<speed>]` | Turn the debugger on, or change its speed: redstone runs once every `<speed>` game ticks (default 2; 1 = normal speed) |
| `/rdb stop` | Turn the debugger off |
| `/rdb pause` | Freeze redstone. Players, mobs and fluids keep running. |
| `/rdb resume` | Unfreeze redstone |
| `/rdb step [<n>]` | While paused, run n redstone ticks (2n game ticks; default 1) |
| `/rdb checkStatus` | Show the debugger state |

While redstone is frozen:
- scheduled ticks (repeaters, comparators, torches, buttons...) wait, keeping their remaining delay;
- neighbour updates (redstone dust, lamps, doors) are held and replayed on the next tick redstone runs;
- pistons, both starting and mid-push, stay where they are.

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
