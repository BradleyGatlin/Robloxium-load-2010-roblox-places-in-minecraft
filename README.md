Robloxium

This build intentionally replaces the previous Alpha 1.x/2.x coordinate and player architecture.

Core Architecture

Robloxium's guest runtime uses Roblox studs internally. Conversion to Minecraft blocks happens only at the host bridge boundary.

CFrame rotations are never scaled.
Part sizes are never scaled inside the guest runtime.
Roblox and Minecraft coordinate systems remain separate until the bridge converts between them.
Runtime
RobloxGame / DataModel / Workspace — implemented
R6 Character + Humanoid + Motor6D — infrastructure exists; currently intended for future NPC support
RBXLX/XML typed property loader — implemented
Minecraft collision bridge — implemented
2010 R6 animation — not yet implemented; NPCs are not currently part of the runtime
Blaze3D/Fabric 26.2 world rendering — VULKAN REQUIRED. This build will not run its Roblox world renderer on OpenGL.

Important: Robloxium's world renderer is explicitly Vulkan-only. You must launch Minecraft with the Vulkan backend enabled.

Commands
/robloxium load <file> — loads a place from config/robloxium/places
/robloxium unload — unloads the current place
/robloxium info — displays Robloxium runtime information
/robloxium roblox2010 start|stop|status — legacy 2010 client control; not currently required

The supplied normal 2010 client remains bundled under:

src/main/resources/robloxium/client2010/

2010 Place Compatibility

The XML loader preserves the entire serialized instance/property tree instead of discarding unknown 2010 classes.

Typed compatibility currently covers:

Legacy Parts, SpawnLocations, Wedges, CornerWedges, Trusses, and Seats
Weld, Motor, Glue, Snap, and related joints
Scripts and LocalScripts
Humanoids and Players
SpecialMesh and legacy mesh variants
Decals and Textures
Sounds
Cameras
ValueBase variants
Common GUI objects
Legacy service objects
Legacy physics properties such as Velocity, RotVelocity, Friction, Elasticity, Locked, and Shape
Lighting properties including Brightness, Ambient, OutdoorAmbient, ColorShift_Top, ColorShift_Bottom, ShadowColor, TimeOfDay, ClockTime, GeographicLatitude, and optional environment scales

Unknown classes and properties are also retained in the DataModel using their original names and raw typed values.

This means an old place is not silently stripped just because Robloxium does not yet have specialized runtime or rendering support for a particular class.

Shadows

Custom Roblox sun shadows are currently disabled because the implementation was too buggy.

The system previously read the sun position from RBXL/RBXLX lighting data and used it to determine the Roblox shadow direction. It is currently disabled for this build.

Vulkan Renderer

Robloxium's world renderer is explicitly Vulkan-only.

The renderer:

Uses Minecraft 26.2's Blaze3D RenderSetup / RenderPipeline abstraction.
Does not import or call com.mojang.blaze3d.opengl.*.
Checks the active GpuDevice backend before submitting Roblox geometry.
Throws a deliberately obvious ROBLOXIUM VULKAN ERROR if the active backend is OpenGL.
Uses Sampler0 for Roblox textures.
Uses .useLightmap() to supply Sampler2, preventing the Vulkan Missing sampler Sampler2 failure.
Uses the same Vulkan-safe submission path for Roblox geometry, materials, SurfaceTypes, and other world rendering.

Minecraft 26.2 provides the Blaze3D rendering abstraction for its supported graphics backends. Robloxium uses that abstraction while deliberately requiring Vulkan for this build.

Vulkan World Rendering

Robloxium's 2010 Roblox geometry uses dedicated Blaze3D world pipelines with:

Position
UV coordinates
Vertex colour
Roblox texture sampling through Sampler0

Roblox lighting, reflectance, and the current shadow-receiver shading are baked into vertex colour rather than relying on Minecraft's lightmap sampler.

Separate pipeline states are used for:

Opaque geometry
Translucent geometry
Surface overlays
Sky geometry

The renderer provides a dedicated 2010 Roblox world-rendering path using Minecraft 26.2's Blaze3D/Vulkan architecture.
