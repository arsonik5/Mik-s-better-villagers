# Mike's Better Villagers

A Fabric mod (Minecraft 1.21.8) that replaces the vanilla villager trade GUI
with a vanilla-*styled* chat + trade window backed by a small local LLM
(2-4B class), running as a bundled `llama-server` subprocess on the player's
own GPU. Villagers also occasionally show speech-bubble text above their
heads while nearby.

## Status

Early scaffold — see `claude.md` for the architecture and build-order notes.

## Requirements

- Java 21 (the Gradle wrapper will provision this automatically via the
  Foojay toolchain resolver if it isn't already installed)
- A GPU capable of running a 2-4B parameter GGUF model at a playable token
  rate (the LLM features degrade gracefully to "vanilla trades, no chat" if
  no model is configured or available)

## Development

```
./gradlew runClient   # launch a dev client with the mod loaded
./gradlew build       # compile + run checks
```
