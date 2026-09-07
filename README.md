# Robloxium 3.0.0-alpha1 — FULL REWRITE

This build intentionally replaces the previous Alpha 1.x/2.x coordinate/player architecture.

## Core rule

The guest runtime stores Roblox values in Roblox studs. The host bridge converts to Minecraft blocks only at the boundary. CFrame rotations are never scaled. Part sizes are never scaled inside the guest.

## Runtime

- RobloxGame/DataModel/Workspace
- R6 Character + Humanoid + Motor6D
- RBXLX/XML typed property loader
- Minecraft collision bridge
- 2010-style R6 animation
- Blaze3D/Fabric 26.2 world rendering
- optional bundled Windows 2010 client sidecar

## Commands

- `/robloxium load <file>` from `config/robloxium/places`
- `/robloxium unload`
- `/robloxium info`
- `/robloxium customize`
- `/robloxium roblox2010 start|stop|status`

The supplied normal 2010 client remains bundled under `src/main/resources/robloxium/client2010/`.

## 2010 place compatibility pass

The XML loader now keeps the full serialized instance/property tree instead of dropping unknown 2010 classes. It has typed compatibility for legacy Parts/SpawnLocations/Wedges/Trusses/Seats, Weld/Motor/Glue/Snap joints, Scripts/LocalScripts, Humanoids/Players, SpecialMesh/mesh variants, Decals/Textures, Sounds, Cameras, ValueBase variants, common GUI objects, and old service objects. Lighting reads Brightness, Ambient, OutdoorAmbient, ColorShift_Top/Bottom, ShadowColor, TimeOfDay/ClockTime, GeographicLatitude and optional environment scales. Legacy physics fields (Velocity, RotVelocity, Friction, Elasticity, Locked, Shape) are retained and applied.

Unknown classes and properties remain in the DataModel with their original names and raw typed values so an old place is not silently stripped just because Robloxium does not yet have a specialized renderer for that class.

## 2010 compatibility bug-fix pass

This revision fixes several runtime/rendering problems in the previous compatibility build:

- shadow cache now invalidates when the Roblox sun/anchored scene changes instead of keeping stale geometry
- only static anchored geometry is baked into the one-time shadow cache, preventing moving parts from leaving stale shadows and avoiding per-frame shadow baking
- `Lighting.ShadowColor` now changes the actual shadow-light contribution instead of being stored but ignored
- `Lighting.EnvironmentSpecularScale` now participates in the CPU specular lighting path
- legacy packed Color3 values are decoded as both unsigned and signed 32-bit representations
- loader/runtime sources were manually compiled successfully for the complete main math/runtime/place layer using LuaJ stubs; the environment does not contain the Gradle executable, so the full Fabric/Minecraft build could not be run here
- the sample `myhouse.rbxl` loads successfully through the updated XML loader (1895 Workspace parts, 18 DataModel children)

## Shadow artifact fix

The stencil-shadow experiment was causing the visible black projected polygons to alpha-stack over one another, producing the large triangular artifacts seen on walls and floors. The visible overlay pass has been removed. Shadows are now applied in the Roblox part lighting path instead, using a small 4x4 subdivision only on receiver faces that actually have cached shadow geometry. This preserves the hard-edged projected silhouette without drawing separate black decals on top of every receiver.

The shadow cache is built before Roblox geometry is submitted, and the maximum projected distance is capped at the same 128-stud working radius used by the supplied Lua reference. This avoids the previous first-frame ordering bug and reduces unnecessary shadow work.


## Vulkan Sampler2 fix

The Roblox texture RenderSetup now explicitly enables the lightmap binding required by Minecraft 26.2's `RenderPipelines.TEXT` pipeline. Without that binding, the Vulkan backend could abort the frame with `IllegalStateException: Missing sampler Sampler2`. The renderer remains on Blaze3D rather than using raw OpenGL.

## Shadow implementation fix

- Roblox sun direction now follows the legacy `GetSunDirection` coordinate convention, including the 23.5-degree axial-tilt offset.
- Shadows remain receiver masks instead of visible black decal geometry, eliminating the giant alpha-stacked triangle artifact.
- Receiver sampling increased to 8x8 only for faces that actually have shadow casters.
- Intervening visible Parts are tested during shadow-cache construction so a projected shadow does not pass through another Part.
- Anchored Parts always participate; moving unanchored Parts participate while they have meaningful linear/angular velocity, matching the supplied ActiveWaiting stencil-shadow behavior.
- The 128-stud preparation/projection limit remains in place.

## Vulkan / shadow artifact fix 2

- Roblox surface decals now use `TEXT_POLYGON_OFFSET` and a tiny outward face offset to prevent Vulkan/OpenGL z-fighting that appeared as dense triangular/checkerboard patches.
- Shadow receiver cells now evaluate the shadow mask once at the cell center, so a shadow boundary cannot split a cell into a visible diagonal half-triangle.
- Removed a duplicated shadow polygon clipping pass that could duplicate one boundary edge.
- Shadow cache signatures now include exact transparency values.
- The Vulkan texture path retains the explicit lightmap binding required by the 26.2 pipeline's sampler layout.

## Vulkan-only renderer rewrite

This build makes the Robloxium 2010 world renderer explicitly **Vulkan-only**.

- Rendering goes through Minecraft 26.2's Blaze3D `RenderSetup`/`RenderPipeline` abstraction.
- Robloxium does not import or call `com.mojang.blaze3d.opengl.*`.
- The active `GpuDevice` backend is checked before Roblox geometry is submitted.
- If the active backend is OpenGL, Robloxium throws a deliberately large `ROBLOXIUM VULKAN ERROR` instead of silently rendering through OpenGL.
- `Sampler0` is the Roblox texture and `Sampler2` is supplied by `.useLightmap()`, avoiding the Vulkan `Missing sampler Sampler2` failure.
- Skybox, Parts, SurfaceTypes, materials, and the existing CPU shadow receiver shading all use the same Vulkan-safe submission path.

Minecraft 26.2's Fabric documentation recommends Blaze3D rather than raw OpenGL because 26.2 has an optional Vulkan backend. Robloxium follows that abstraction while deliberately rejecting OpenGL for this build.


## Vulkan world-pipeline rewrite

The 2010 Roblox geometry renderer no longer uses Minecraft's text pipelines (`TEXT` / `TEXT_POLYGON_OFFSET`). Robloxium now declares dedicated Blaze3D world pipelines using the position/UV/color vertex format and a single `Sampler0`. The renderer bakes Roblox lighting, shadows, and reflectance into vertex colour, so it does not bind Minecraft's `Sampler2` lightmap. Opaque, translucent, surface-overlay, and sky geometry use separate pipeline states. The renderer remains deliberately Vulkan-only and refuses to run on an OpenGL backend.
