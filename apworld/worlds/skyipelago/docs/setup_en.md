# Skyipelago Setup

## Install

1. Install [Archipelago](https://github.com/ArchipelagoMW/Archipelago/releases).
2. Copy `apworld/worlds/skyipelago` into your Archipelago `custom_worlds` / `worlds` folder, or zip the `skyipelago` directory as `skyipelago.apworld` and install it from the launcher.
3. Install the Skyipelago NeoForge 1.21.1 jar plus FTB Library, FTB Teams, and FTB Quests 2101.x.
4. Copy `pack-dev/config/ftbquests` into your Minecraft instance `config/ftbquests`.

## Generate and connect

1. Create a YAML:

```yaml
description: Skyipelago slice
game: Skyipelago
name: Player1
Skyipelago: {}
```

2. Generate and host the room.
3. In Minecraft, run:

```
/ap connect <host[:port]> <slot> [password]
```

4. Complete mapped FTB quests. Each one sends a check.
