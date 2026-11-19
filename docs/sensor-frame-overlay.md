# Sensor frames on the ground — design, measurements and what was tried

**Date**: 2026-11-19
**Feature**: drawing where drone cameras' frames (FOV) meet the terrain, for many drones, every frame, on weak mobile GPUs
**Result**: `SensorFrameOverlay` — a GPU "shadow-map with the camera as the sun" overlay. 10 drones cost about **7 ms** a frame on a Galaxy Tab A7 Lite (PowerVR GE8320, debug build), 100 drones about 23 ms, on top of a map that alone takes ~116 ms there. A CPU path (`FootprintSolver`, `SensorFootprintEngine`) gives the frames' coordinates when an application needs numbers.

This file records the API, how it works, every approach that was tried with its measured cost, the bugs found on the way, and what is still open.

---

## 1. What was added

### Library (`worldwind`)

| Where | What |
|---|---|
| `sensor/FootprintSolver.kt` | `Sensor` (pose, FOV, range), `Footprint`, `FootprintSolver`: corner rays plus adaptive edge rays, the frame's outline as ground coordinates |
| `sensor/RayCaster.kt` | First hit of a straight ray on the terrain: max-pyramid walk, exact bilinear crossing per cell, earth curvature |
| `sensor/DemTileCache.kt` | Shared elevation tiles (256² cells, ~7.7 km at 30 m) with max pyramids, read once in the background, refreshed when the elevation model changes |
| `sensor/SensorFootprintEngine.kt` | Keeps many sensors' footprints up to date within a time budget a frame, only for sensors that moved |
| `sensor/SensorFrame.kt` | `Sensor.frameTransform` (world → frame matrix, like a shadow map's light matrix), `frameNear`/`frameFar`, `Sensor.groundScreenRect` (scissor rectangle) |
| `sensor/SensorFrameOverlay.kt` | **The renderable**: draws frames on the terrain on the GPU, optional fill and occlusion |
| `draw/DrawableSensorFrames.kt` | The overlay pass: one pass over the visible terrain tiles, per-tile frame lists, scissored draws |
| `draw/DrawableSensorFrameDepth.kt` | The occlusion depth pass: each sensor's view of the terrain into a 512² cell of a 2048² depth atlas (up to 16 sensors) |
| `render/program/SensorFrameProgram.kt` | The overlay shader |
| `draw/DrawContext.kt` | `sensorFrameDepthFramebuffer` / `sensorFrameDepthTexture` (the atlas), released on context loss |
| `util/kgl/Kgl.kt` + 6 platforms | `scissor(x, y, width, height)` — `glScissor` was missing from `Kgl` |

### Tutorials (`worldwind-tutorials`)

- `SensorFootprintTutorial` — drones over Mount St. Helens, 10 to 1000 (**More drones** / **Fewer drones** actions; buttons on Android), fps and drone count on screen, outline only.
- `ScreenText` — a line of text fixed on the screen (used for the fps).
- Registered on JVM, web, iOS and Android (menu, strings en/es, tutorial page).

### Tests (`worldwind/src/jvmTest/.../sensor/SensorFootprintTest.kt`, 9 tests)

- Rays against an independent brute force (a 0.25 m march along the true 3D ray on WGS84): flat ground within 0.2 m at 15.6 km on every bearing, hills within 3 cm over 120 random rays.
- A ridge two cells thick is not stepped over.
- A nadir footprint on flat ground is the FOV rectangle.
- The GPU frame's corners are exactly where the CPU corner rays land (within 1e-5 of the frame half-width, with roll, over hills).
- The scissor rectangle holds every ground point of 20 random frames over hills (606 points).
- Engine: small moves are skipped, the interval is honoured, the time budget holds.

---

## 2. How the overlay works

It is a shadow map with the camera standing in for the sun.

1. **CPU, per frame and drone:** one 4×4 matrix (`Sensor.frameTransform`): world → the camera's frame, so `x/w`, `y/w` run −1..1 across the frame and `z/w` is OpenGL depth between a near plane (range/1000) and the range. Plus the screen rectangle the frame's ground can cover (below).
2. **GPU, depth pass (occlusion only):** before the scene, the map's terrain tiles are rendered from each drone into its cell of the depth atlas, through the same matrix, with the sun shadows' depth shader and slope-scaled polygon offset.
3. **GPU, overlay pass:** the visible terrain tiles are drawn once more. For each pixel, the shader carries the pixel into each of the tile's frames (up to 4 per draw) and tints it: a band `outlineWidth` pixels wide inside the frame's edge in the outline colour, the inside in the fill colour where the drone sees the ground and the occluded colour where the depth map says a ridge is in the way.

What keeps it cheap:

- **One pass, per-tile frame lists:** each terrain tile is tested against every frame's volume on the CPU and carries only the frames that reach it; a tile no frame reaches is skipped.
- **Scissor:** every draw is scissored to the union of its frames' screen rectangles. `groundScreenRect` casts a 7×7 grid of rays across the frame, keeps each ray's stretch between the lowest and highest terrain height under the drone (from the elevation model's limits, cached per drone) and within range, and projects the ends.
- **Early rejection:** a pixel outside a frame (with a 5% margin) is rejected with a few comparisons in clip space before any division.
- **One derivative:** the outline's screen width from `fwidth` of a single value.

---

## 3. Using it

```kotlin
val overlay = SensorFrameOverlay().apply {
    outlineWidth = 2f            // screen pixels
    isOcclusionEnabled = false   // true needs a fill or occluded colour to show anything
}
val sensor = Sensor(
    position = Position.fromDegrees(lat, lon, altitudeAboveEllipsoid),   // ABSOLUTE
    azimuth = 120.0.degrees,     // optical axis, clockwise from north
    depression = 35.0.degrees,   // below the horizontal; negative looks up
    horizontalFov = 30.0.degrees,
    verticalFov = 20.0.degrees,
    maxRange = 10_000.0,         // metres
    roll = 0.0.degrees,          // positive: right side down
)
overlay.add(sensor, outlineColor = Color(1f, 0.3f, 0.2f, 1f))
// optional: fillColor = ..., occludedColor = ... (premultiplication is done for you)
renderableLayer.addRenderable(overlay)

// Every telemetry update, on the render thread (or before the next frame):
sensor.position.copy(newPosition); sensor.azimuth = newAzimuth; sensor.depression = newDepression
WorldWind.requestRedraw()
```

Coordinates of the corners (CPU, against elevation tiles of its own, independent of the map's view):

```kotlin
val tiles = DemTileCache(engine.globe, engine.renderResourceCache.mainScope, resolutionMeters = 30.0)
val solver = FootprintSolver(tiles).apply { minDepth = 0; maxDepth = 0 } // 4 corner rays only
val footprint = solver.solve(sensor)   // footprint.corners, footprint.cornerHits, footprint.isComplete
```

`SensorFootprintEngine` does the same for many sensors within a time budget a frame.

---

## 4. What was tried, in order, with measurements

All tablet numbers are a Galaxy Tab A7 Lite (SM-T225 and SM-T220, MediaTek MT6765, PowerVR Rogue GE8320), debug build. Desktop is a Mac with Intel Iris Plus Graphics, whose OpenGL (over Metal) timings proved too noisy to rank shader variants.

### 4.1 CPU ray marching (the starting point, `landRay` in another project)

A fixed 30 m march along the ground with bisection. Two problems found in review:
- **The curvature term had the wrong sign** (`− d²/2R`; a straight ray rises away from a curved earth, so `+`). 63 m of height wrong at 20 km.
- It can step over a ridge narrower than the step.

### 4.2 CPU, tiles + max pyramid (`RayCaster`, `DemTileCache`)

Exact first hits, no ridge missed, independent of the map. **Bug found by the tests:** mapping the ground track with one curvature radius for both latitude and longitude put diagonal rays 25 m off at 15 km (the meridian and prime-vertical radii differ by ~0.3%); the track's steps are now rescaled to each radius.

Cost on desktop JVM: ~5 µs a ray after optimisations (level persistence in the pyramid walk, piece-end reuse, tile lookup cache, reciprocals), from 8.3 µs. A footprint with a 10 m edge tolerance: ~57 rays, ~250–320 µs.

### 4.3 Full outline every frame + draped polygons (first tutorial)

Tablet: ~5.5 fps, ~50 ms of the frame solving rays, the rest dominated by redrawing draped (`CLAMP_TO_GROUND`) polygons into terrain textures every frame. Desktop: +6.5 ms CPU over the map.

### 4.4 Corners only + straight-line outline

Desktop: +1.1 ms CPU for 10 drones. Edges are straight lines between corners, not following the terrain.

### 4.5 GPU overlay, level 1 (no occlusion), 4 frames per pass

Desktop: +0.1 ms CPU. Tablet: the pass cost ~31–36 ms (3 passes over the terrain for 10 drones).

### 4.6 GPU overlay, level 2 (occlusion)

Depth atlas, hardware depth-compare samplers where available. Tablet: +4–5 ms for 10 depth maps.

### 4.7 One pass with per-tile frame lists

Tablet: only ~5 ms better than 3 passes — the cost was not where expected.

### 4.8 Diagnostics (tablet)

| Phase | fps | finding |
|---|---|---|
| culling + uploads, no draws | 8.6 (map 8.5) | CPU side negligible (~1.6 ms) |
| tiles redrawn, empty shader | 8.4 | the redraw itself ~1.5 ms |
| full overlay | 7.1 | **the shader's per-pixel math ~22 ms** |

Also learned: the "draw" CPU time includes waiting for the GPU on this device; fps (or `glFinish`-bracketed timing) is the real measure.

### 4.9 The five shader fixes, each measured alone (tablet, `glFinish`-timed overlay GPU time, 2 cycles)

| Fix | GPU ms | vs old 38.7 |
|---|---|---|
| empty shader (timing floor) | 14.1 | — |
| F1 per-tile shader variants (1–4 frames) | 39.9 | +1.2, no gain |
| F2 early clip-space rejection | 25.6 | −13.1 |
| F3 scissor to the frames' screen rectangle | 18.6 | **−20.1** |
| F4 single derivative | 36.3 | −2.4 |
| F5 mediump colour math | 38.5 | −0.2, no gain |
| **F2 + F3 + F4** | **14.2** | **−24.5, equal to the floor** |

F1 and F5 were removed. (An earlier combined run had suggested variants + mediump made things worse, and that a derivative-free outline width saved nothing; measuring each fix alone settled it.) The timed floor is inflated by `glFinish` forcing an extra framebuffer load/store on this tile-based GPU; untimed, the same empty shader cost ~1.5 ms.

### 4.10 Final, untimed (fps), map with drone icons as the baseline

| | 10 drones | 100 drones |
|---|---|---|
| map + drone icons | 116.2 ms (8.6 fps) | ~135 ms (7.4 fps) |
| + frames | **+6.8 ms** | +23 ms (~100 draws) |
| + occlusion | +5.1 ms | ~+2 ms (16 occluded) |

Desktop, 1000 drones: ~4.7 fps (map 41 fps), ~1,140 draw calls a frame — each terrain tile is reached by ~70 frames.

### 4.11 Ideas considered and not built

- **A second, lightweight `WorldWindow` / engine from the drone's view** ("SensorView"): corners from `pickTerrainPosition` on its own terrain. Set aside for the lighter tile-based CPU path; it would need GL-state-cache and render-target changes in `DrawContext` to draw.
- **Reading GPU-drawn rays back** for intercepts: stalls, view-dependent, pixel precision; no.
- **C++/JNI ray casting:** not needed once the cost moved to the GPU.
- **`RealtimeSightline`** for occlusion: 6 cube faces per sensor; the frame overlay needs one frustum-shaped depth map.

---

## 5. Limits and known issues

- **Map terrain only:** the overlay tints the map's terrain at its level of detail, and occlusion is by the map's drawn terrain — a ridge off screen does not hide the ground behind it; shapes and 3D tiles do not occlude. Not on 2D globes; assumes no vertical exaggeration.
- **Occlusion:** first 16 frames only (atlas size); needs depth textures (GLES 3 / WebGL 2 / GL 3).
- **Up to 1024 frames** per overlay (`DrawableSensorFrames.MAX_TOTAL`).
- **Many drones close together** (~70 frames per tile at 1000 drones) cost a draw per 4 frames per tile.
- **The range** is a slant distance in the overlay and a ground distance in the CPU solver.
- **Web is untested.** `docs/webgl-3d-projection-investigation.md` records that `ProjectedMediaSurface`'s per-fragment camera projection renders wrongly on WebGL for reasons still open; this overlay uses a similar projection and needs checking there.
- **iOS** has no drone-count buttons (the iOS tutorial app has no actions).
- **Release APK** in this work was signed with the local debug key; the project has no release signing config.

## 6. Temporary / development aids still in the code

- `SensorFootprintTutorial(measure = true)` cycles map / frames / occlusion every 10 s and prints `MEASURE` lines. Desktop: `WORLDWIND_SENSOR_MEASURE=true`, `WORLDWIND_SENSOR_DRONES=<n>`; run with `./gradlew :worldwind-tutorials:runJvmTutorials -Pworldwind.publishingNativeStaging=true "-Dworldwind.tutorial=Sensor footprints"` (on a JDK 25, e.g. Android Studio's JBR, for JavaFX 27).
- `DrawableSensorFrames.statTiles` / `statDraws`, `DrawableSensorFrameDepth.statDraws` counters.

## 7. Next steps

1. Measure the release build on the tablet (fps at 10 and 100 drones).
2. 100+ drones: group frames by screen area into batches, fewer draws.
3. Cheaper occlusion: 256² cells, re-render a depth map only when its drone moves.
4. Validate against recorded flights: compare corners with MISB ST 0601 tags 26–33 / 82–89 and slant range (tag 21), using `ExtractKlv.kt`.
5. Check the overlay on WebGL.
