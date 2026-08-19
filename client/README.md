# Skyipelago client (NeoForge 1.21.1)

Archipelago client for Skyipelago. Completing a mapped FTB Quests quest sends a `LocationChecks` packet.

## Commands

```
/ap connect <host[:port]> <slot> [password]
/ap disconnect
/ap status
/ap mailbox
```

Aliases: `/archipelago`, `/skyipelago`. Default port is `38281`.

## Build

Requires **Java 21** (NeoForge 1.21.1). Newer JDKs such as 26 will not run this Gradle:

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew build
```

The run configs copy `../pack-dev/config` into `run/config` so the dummy quest chapter is present.

Required mods (pulled in for `runClient` / `runServer`):

- FTB Library 2101.1.35
- FTB Teams 2101.1.9
- FTB Quests 2101.1.32
- Architectury 13.0.8

## Mapping

Quest hex ids → AP location ids live in `src/main/resources/data/skyipelago/quest_to_location.json`, generated from `../data/` by `../tools/generate.py`.
