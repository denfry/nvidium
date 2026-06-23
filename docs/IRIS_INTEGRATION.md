# Nvidium ↔ Iris shader integration — feasibility & design (1.20.1)

Status: **research / design**. Nothing here is implemented yet. This document scopes
what "deep Iris integration" actually requires, why it is hard, and the only realistic
path to it, so implementation work can be staged instead of guessed.

## TL;DR

- Today Nvidium **disables itself** when an Iris shaderpack is active
  (`IrisCheck.checkIrisShouldDisable()` → `Nvidium.IS_ENABLED = false` in
  `MixinRenderSectionManager`). With shaders on, vanilla Sodium+Iris draws terrain.
- The Iris maintainers consider drop-in Nvidium support **not possible** with the current
  architecture (Iris issue #2320, closed *not planned*: "because it's not possible").
- The root cause is **architectural, not a bug**: Iris drives terrain by compiling the
  shaderpack's *vertex+fragment* GLSL and binding it to a programmable draw. Nvidium draws
  terrain with **NV mesh shaders**, which have **no vertex-shader stage to inject the pack's
  program into**, and writes a **single pre-lit colour** instead of the shaderpack's
  G-buffer outputs.
- Real integration therefore means building a **second, non-mesh-shader terrain pass**
  inside Nvidium that can host Iris-compiled programs — modelled on how Iris integrates
  Distant Horizons, but harder. This sacrifices Nvidium's core advantage *for the shader
  path only* and is a multi-month, hardware-iterative effort.

## What Iris needs from a terrain renderer

From the Iris↔Distant Horizons integration (the closest precedent — DH is also a fully
custom terrain renderer) and Iris's Sodium terrain path:

1. **Programmable vertex+fragment stage.** Iris compiles the shaderpack's
   `gbuffers_terrain` / `shadow` / `dh_terrain` programs and binds them. The renderer must
   draw through *Iris's* program, not its own fixed shader.
2. **G-buffer MRT output.** The fragment program writes albedo/normal/specular/lightmap to
   multiple render targets (deferred), not one final colour.
3. **Vertex attributes the pack asks for.** Iris's `FormatAnalyzer` selects attributes per
   pack — typically **normal, midTexCoord (midUV), block ID (`mc_Entity`)**, tangent — on
   top of position/colour/UV/lightmap.
4. **A shadow pass.** Geometry must be re-rendered from the light's POV into Iris shadow
   maps. `IrisApi` exposes whether the shadow pass is currently active.
5. **Iris uniforms/samplers + framebuffer hand-off.** `iris_ModelViewMatrix`,
   `iris_ProjectionMatrix`, model/camera offsets, fog, plus rendering into Iris-managed
   framebuffers (the DH layer wraps these via `DhFrameBufferWrapper` /
   `IrisLodRenderProgram`).

## What Nvidium provides today

Terrain pipeline (`RenderPipeline`, `shaders/terrain/*`):

- **Mesh-shader pipeline.** `mesh.glsl` (NV_mesh_shader) pulls a compact vertex, transforms
  by `MVP`, and emits triangles. There is **no vertex shader stage**.
- **Compact vertex** (`CompactChunkVertex`, 20 B, decoded in `mesh.glsl`): position
  (`a,b,c`), flags (`d`: mipping + alpha-cutoff), tint RGBA (`e,f`), UV (`g,h`), lightmap
  (`i,j`). **No normal, no tangent, no block ID, no midTexCoord.**
- **Single colour output** (`frag.frag` → `layout(location=0) out vec4 colour`) with
  **lighting, AO, tint and fog already baked in** (`tint *= sampleLight(...)`,
  `computeFog(...)`). Nothing is separable into a G-buffer.
- **Fixed shaders** loaded from the jar; the shaderpack's GLSL never runs.

## The gap (why it can't "just work")

| Iris needs | Nvidium has | Gap |
|---|---|---|
| Vertex+fragment program injection | Mesh shader (no VS stage) | **Structural** — can't bind a VS where there is none |
| MRT G-buffer outputs | One pre-lit colour | Rewrite fragment output; un-bake lighting |
| normal / midUV / blockID / tangent | pos/uv/light/tint only | Extend vertex format (more VRAM/vertex) |
| Shadow pass geometry | none | Add a second light-POV terrain pass |
| Iris uniforms + framebuffers | own scene UBO + own FBO | Adapter layer |

The first row is the killer: Iris's entire model is "compile the pack's program and draw with
it." A mesh-shader draw has nowhere to put that program. DH integrates cleanly precisely
because DH uses a **traditional vertex/index + programmable draw**, so Iris can supply its own
`IrisLodRenderProgram`. Nvidium's mesh-shader path is exactly what makes it incompatible.

## The only realistic architecture

Add a **parallel "compatibility" terrain pass** that activates only when an Iris pack is in
use, leaving the fast mesh-shader path untouched for the no-shader case:

1. **Vertex-pulling draw (no mesh shader).** Reuse the existing GPU-resident geometry arena,
   but render it with a *traditional* `MultiDrawIndirect` + vertex shader pipeline so Iris
   can bind its compiled `gbuffers_terrain` program. Region/section culling can still be the
   GPU-driven Nvidium logic; only the final draw stage changes. (Nvidium historically had a
   vertex-pulling path — this is a return to one for the shader case.)
2. **Extend the vertex format** with normal + block ID (+ midUV/tangent when the pack asks).
   Normals can be derived per-face at meshing time. This grows the stride (VRAM cost) and is
   only needed on the compat path.
3. **G-buffer output.** Drive Iris's framebuffers; let the pack's fragment program produce the
   deferred outputs. Drop Nvidium's baked lighting/fog on this path.
4. **Shadow pass.** Re-issue the indirect terrain draw from the light POV with Iris's shadow
   program, gated by `IrisApi` shadow-pass state.
5. **Iris adapter.** Map Iris uniforms/samplers and wrap framebuffers, mirroring
   `DHCompatInternal` / `IrisLodRenderProgram`.

Trade-off: on the shader path Nvidium loses the mesh-shader draw advantage (keeps only its
culling/streaming/VRAM management). That is the unavoidable price of programmable-shader
compatibility, and is still potentially a win over vanilla Sodium because Nvidium's culling and
huge-render-distance streaming remain.

## Staged plan

- **S0 (done):** graceful disable under shaders — already correct.
- **S1:** Iris phase/state plumbing — detect pack-in-use and shadow-pass state via `IrisApi`,
  surface it to `RenderPipeline`. Pure scaffolding, no behaviour change. *Build-verifiable.*
- **S2:** vertex-format extension behind a flag (normal + block ID), meshing-time derivation,
  mesh-shader decode updated. *Build-verifiable; needs in-game visual check on NVIDIA.*
- **S3:** parallel vertex-pulling draw of the existing arena (no Iris yet) reaching visual
  parity with the mesh-shader path. *Needs hardware.*
- **S4:** bind Iris programs + G-buffer output on the compat path. *Needs hardware + shaderpacks.*
- **S5:** shadow pass. *Needs hardware + shaderpacks.*

Each stage past S1 requires iterating on real NVIDIA (Turing+) hardware with shaderpacks; it
cannot be validated by compilation alone.

## Recommendation

This is a genuine research/engineering project, not a quick fix, and Iris upstream will not meet
it halfway. Recommended next concrete step is **S1** (low-risk, build-verifiable scaffolding) so
the decision to invest in S2+ can be made against a real foundation — or to consciously keep the
current graceful-disable behaviour and spend effort where the ROI is clearer (VRAM, FPS,
stability).

## Sources

- Iris issue #2320 "Adding Nvidium Compatibility" (closed *not planned*; maintainer: "not possible").
- Iris ↔ Distant Horizons integration (`DHCompatInternal`, `IrisLodRenderProgram`, shadow programs).
- Iris terrain format selection (`FormatAnalyzer`: normal / midUV / block ID).
- Nvidium source: `shaders/terrain/{mesh.glsl,frag.frag}`, `RenderPipeline`, `IrisCheck`,
  `SodiumResultCompatibility`.
