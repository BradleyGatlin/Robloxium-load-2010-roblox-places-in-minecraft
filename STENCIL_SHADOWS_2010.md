# Roblox 2010-style shadows

Roblox's pre-dynamic-lighting renderer used stencil shadows for characters/geometry. Modern Roblox later replaced that system with dynamic lighting and eventually ShadowMap. This port therefore does **not** use Minecraft's block shadow renderer.

The Robloxium renderer now:

- computes a hard sun-space silhouette from each Roblox part;
- projects that silhouette onto receiver faces;
- clips the projection to the receiver face;
- submits the resulting triangles as a depth-tested black stencil-style pass;
- keeps the shadow cache in Roblox studs and converts to Minecraft coordinates only when submitted;
- keeps ambient light visible underneath the shadow instead of making it an opaque black decal.

The old CPU vertex mask is retained only as the lighting fallback; the visible shadow is now the projected polygon pass.

## Lua reference port notes

The supplied `StencilShadowsActors` Lua implementation was used as the reference for this pass.

Key behaviors ported:
- The custom shadow pass is submitted after normal Roblox part geometry. The previous Java renderer built a shadow cache but never submitted it, so it could spend time calculating shadows without displaying any.
- Shadow preparation is restricted to a 128-stud radius around the real Minecraft player, matching the Lua `Prepare()` gate and preventing whole-place shadow baking.
- Only opaque/mostly opaque parts participate; non-collidable parts are still valid shadow casters, matching the Lua script's use of `CastShadow` rather than `CanCollide`.
- Static anchored casters/receivers are prepared as cached projected geometry.
- The host Minecraft sun is not used; Roblox `TimeOfDay`/`GeographicLatitude` remains authoritative.
- The custom pass uses `Lighting.ShadowColor` to determine shadow darkness instead of a hard-coded black overlay.

The implementation still uses Java-side projected geometry instead of Roblox Actors/BindableEvents because those are Roblox server/client primitives that do not exist in the Minecraft host. The spatial grid and 128-stud preparation boundary are the performance equivalent of the Lua script's work queue/actor fan-out.
