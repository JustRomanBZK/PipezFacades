# Pipez Facades

**GregTech-style facade covers for [Pipez](https://www.curseforge.com/minecraft/mc-mods/pipez) pipes** — hide your pipes inside walls by disguising each side as any solid block, with the familiar GregTech cover grid for aiming.

- Minecraft **1.20.1**, **Forge 47.x**
- Requires: **Pipez** (1.20.1)
- Optional: **GregTech CEu Modern** — its crafted facade cover items work directly on Pipez pipes (both upstream GTCEu and the GregTech-Odyssey fork item formats are supported). Without GregTech you can still use any plain full block as a facade.

## How to use

| Action | Result |
| --- | --- |
| **Right-click** a pipe with a GregTech facade cover item | Installs the facade on the grid side under your crosshair |
| **Sneak + right-click** a pipe with any full block | Same, using the block itself as the facade |
| **Sneak + right-click** with an empty hand | Pops the facade off the targeted side |
| **Break the pipe** | Drops the pipe and every installed facade item |

While aiming with a facade in hand, the pipe raytraces as a full cube and a GregTech-style grid appears on the face: the **centre** cell targets the face you are looking at, the **edge** cells target the perpendicular sides, and the **corner** cells target the far side — exactly like placing GT covers. Facades are solid: you can stand on them.

Facades are purely cosmetic — pipe connections, filters and extraction are untouched.

## Design notes

The mod has **no compile-time dependency on Pipez or GregTech**:

- Pipez pipes are recognised by their `pipez:` registry namespace; interaction is handled with plain Forge events.
- GregTech facade items are recognised by their `Facade` NBT tag (both the upstream BlockState format and the Odyssey-fork ItemStack format).
- One small Mixin into Pipez's `PipeBlock` provides what events cannot: the full-cube targeting shape while aiming (GT parity) and solid collision for installed facade plates. It targets stable 1.20.1 SRG method names verified against the published Pipez jar.
- Facades are stored per side in level `SavedData`, synced to clients with a small network channel, and rendered as 1/16-thick plates using the camouflage block's own textures.

## Building

JDK 17. The Gradle wrapper is pinned to Gradle 8.1.1 (required by ForgeGradle 6).

```bash
./gradlew build
# → build/libs/pipezfacades-<version>.jar
```

## Credits & license

- Code: **LGPL-3.0** (see `LICENSE`, `COPYING`, `NOTICE`).
- The two grid overlay icons and the cover-grid side-selection math are from
  [GregTech CEu Modern](https://github.com/GregTechCEu/GregTech-Modern) (LGPL-3.0) — thank you, GTCEu team.
- [Pipez](https://github.com/henkelmax/pipez) by Max Henkel is integrated at runtime only; no Pipez code or assets are included.
