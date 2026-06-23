# Contributing

Thanks for your interest in improving this fork of Nvidium.

## Project layout

This fork uses [Stonecutter](https://stonecutter.kikugie.dev/) to build multiple Minecraft/Sodium versions from one
source tree.

- `src/main/java/me/cortex/nvidium/` — the mod (renderers, GL wrappers, managers, Sodium mixins).
- `src/main/resources/assets/nvidium/shaders/` — the GLSL mesh/task/fragment shaders.
- `versions/<node>/gradle.properties` — per-version dependency pins (Minecraft, Sodium, Iris, Fabric, loader, Java).
- `build.gradle` — the shared, per-node build script (Stonecutter `centralScript`).
- `settings.gradle` — registers the active Stonecutter nodes. **Only `1.20.1` is registered right now.**
- `docs/` — design notes (e.g. `IRIS_INTEGRATION.md`).

## Building

```bash
# Fast compile-check of the active node:
./gradlew :1.20.1:compileJava

# Full build (produces versions/1.20.1/build/libs/nvidium-<version>.jar):
./gradlew build
```

The first build decompiles Minecraft and may take several minutes; later builds are incremental. The 1.20.1 node uses
**Java 17** and **Yarn** mappings.

## Hardware-testing constraint

Nvidium is a GPU-driven renderer that depends on NVIDIA mesh-shader extensions. **Rendering, visual, and GPU-memory
behaviour can only be validated by running Minecraft on real NVIDIA Turing+ hardware (GTX 1600 series or newer).**

When contributing, please split work into:

- **Verifiable without a GPU** — anything that compiles, plus pure-Java logic/utility code (for example the VRAM
  allocator `SegmentedManager`, which has a `main()` fuzz harness).
- **Needs NVIDIA hardware** — anything whose *behaviour or benefit* (not just compilation) depends on the GPU pipeline.
  Don't ship behaviour changes whose benefit can only be confirmed by profiling on hardware.

State in your PR which category your change falls into and how it was tested.

## Code style

- Match the style, naming, and comment density of the surrounding code.
- Keep changes focused; unrelated refactors belong in separate PRs.
- Update [CHANGELOG.md](CHANGELOG.md) for user-facing changes.

## Commits & PRs

- Prefix commits touching a specific node with the version, e.g. `1.20.1: fix ...` (matching existing history).
- Fill in the pull-request template, including the testing checklist.

## Licensing

By contributing you agree your contributions are licensed under the project's
[LGPL-3.0](LICENSE.txt) license.
