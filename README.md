# Nvidium

[![Build](https://github.com/denfry/nvidium/actions/workflows/build.yml/badge.svg)](https://github.com/denfry/nvidium/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/denfry/nvidium?include_prereleases&sort=semver)](https://github.com/denfry/nvidium/releases)
[![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](LICENSE.txt)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-brightgreen.svg)](https://www.minecraft.net/)
[![Sodium](https://img.shields.io/badge/requires-Sodium%200.4.10-orange.svg)](https://modrinth.com/mod/sodium)

**Nvidium is a GPU-driven terrain rendering backend for [Sodium](https://modrinth.com/mod/sodium)** that uses NVIDIA
mesh shaders to draw enormous amounts of Minecraft terrain at very high frame rates and render distances. It moves
chunk culling and geometry generation onto the GPU, slashing the CPU cost of large render distances.

> [!NOTE]
> This is a community **fork** of [MCRcortex/nvidium](https://github.com/MCRcortex/nvidium). See
> [Differences from upstream](#differences-from-upstream) for what this fork changes. All original credit goes to
> [Cortex](https://github.com/MCRcortex) and the Nvidium contributors.

---

## Features

- **Mesh-shader terrain pipeline** — geometry is generated on the GPU via `GL_NV_mesh_shader`, not the CPU.
- **GPU-driven occlusion culling** — region/section visibility is resolved on-device using representative fragment tests.
- **Bindless rendering** — bindless multi-draw-indirect and unified buffer memory keep draw-call overhead near zero.
- **Massive render distances** — terrain is kept resident in VRAM via a sparse, segmented allocator.
- **Sodium integration** — drops into Sodium's render path; no separate world renderer to configure.
- **Iris compatibility groundwork** — staged shader-pack integration (see [`docs/IRIS_INTEGRATION.md`](docs/IRIS_INTEGRATION.md)).

## Hardware & software support

| Requirement | Detail |
|---|---|
| GPU | **NVIDIA Turing or newer** (GTX 1600 series / RTX 2000 series and up) |
| Mod | [Sodium](https://modrinth.com/mod/sodium) **0.4.10** (for the 1.20.1 build) |
| Loader | [Fabric Loader](https://fabricmc.net/) ≥ 0.14 |
| Minecraft | **1.20.1** (current focus — see [Versions](#versions)) |
| Java | 17 |

Nvidium checks for the GPU features it needs at startup and disables itself automatically if they are missing, so it is
safe to ship in a modpack alongside non-NVIDIA users.

### Why AMD / Intel GPUs are not supported

Nvidium's entire pipeline is built on **vendor-specific NVIDIA OpenGL extensions** — chiefly `GL_NV_mesh_shader`, plus
`GL_NV_bindless_multi_draw_indirect`, `GL_NV_representative_fragment_test`, `GL_NV_vertex_buffer_unified_memory`,
`GL_NV_uniform_buffer_unified_memory`, `GL_NV_gpu_shader5` and `GL_NV_bindless_texture`.

Mesh shaders in **OpenGL** exist only as these `GL_NV_*` extensions. AMD and Intel do expose mesh shaders, but only
through **Vulkan** (`VK_EXT_mesh_shader`), and Minecraft renders through OpenGL — you cannot mix a Vulkan extension into
an OpenGL context. Until AMD/Intel ship a mesh-shader path in their OpenGL drivers (or a cross-vendor `GL_EXT_mesh_shader`
is ratified), Nvidium cannot run on those GPUs without a complete second rendering backend. Simply disabling the
capability check makes the mod load and then crash on world join.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) and the [Fabric API](https://modrinth.com/mod/fabric-api).
2. Install a compatible [Sodium](https://modrinth.com/mod/sodium) build (0.4.10 for Minecraft 1.20.1).
3. Download the latest Nvidium `.jar` from the [Releases](https://github.com/denfry/nvidium/releases) page.
4. Drop all three jars into your `mods/` folder.

> For the original, officially distributed releases see Nvidium on
> [Modrinth](https://modrinth.com/mod/nvidium).

## Building from source

This fork uses [Stonecutter](https://stonecutter.kikugie.dev/) to manage multiple Minecraft versions from a single
source tree. Only the **1.20.1** node is registered right now.

```bash
# Compile-check the active version (fast after the first decompile):
./gradlew :1.20.1:compileJava

# Full build — produces the distributable jar:
./gradlew build
# -> versions/1.20.1/build/libs/nvidium-<version>.jar

# Switch the active version (when more nodes are registered):
./gradlew "Set active project to 1.20.1"
```

The first build runs a Minecraft decompile and can take several minutes; subsequent builds are incremental.

## Versions

The repository carries per-version configuration for Sodium generations G0.4–G0.8 under `versions/<node>/`, but only
**1.20.1** (Sodium 0.4.10) is currently registered in `settings.gradle` — the strategy is "perfect 1.20.1 first, then
add the rest." Re-register the other nodes when resuming multi-version work.

## Differences from upstream

This fork tracks [MCRcortex/nvidium](https://github.com/MCRcortex/nvidium) and adds:

- **Stonecutter multi-version scaffolding** for building across Sodium generations from one tree.
- **Robustness hardening** — graceful fallback on a corrupt config, fail-loud allocator bounds checks, and reduced
  per-chunk allocation on the upload path.
- **Iris integration groundwork** — a staged plan and state facade (see [`docs/IRIS_INTEGRATION.md`](docs/IRIS_INTEGRATION.md)).

See the [CHANGELOG](CHANGELOG.md) for a full history.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for the build setup, project layout, and the
**hardware-testing constraint** (rendering changes need real NVIDIA hardware to validate).

## License

Licensed under the **GNU Lesser General Public License v3.0** — see [LICENSE.txt](LICENSE.txt).

## Credits

- [Cortex](https://github.com/MCRcortex) and the [Nvidium](https://github.com/MCRcortex/nvidium) contributors — the
  original mod this fork is based on.
- [CaffeineMC](https://github.com/CaffeineMC) — [Sodium](https://github.com/CaffeineMC/sodium), which Nvidium extends.
