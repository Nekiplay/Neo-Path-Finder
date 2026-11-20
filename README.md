# NeoPathfinder (pathfinder-only)

Fabric client mod for Minecraft Java Edition **26.2** that contains only the pathfinder:
A* over feet block positions, tick-accurate move generation, smooth key/rotation playback,
and in-world path rendering.

## What is in this build

- `com.nekiplay.neopathfinder.pathfinder` — `PathManager` (request queue + search dispatch) and
  `PathExecutor` (per-tick follower: key presses, rotation easing, jump timing, stuck/timeout detection).
- `com.nekiplay.neopathfinder.pathfinder.calculate` — `AStarPathFinder`, `Path`/`PathNode`, `PathfindingTelemetry`.
- `com.nekiplay.neopathfinder.pathfinder.movement` — moves (traverse, ascend, descend, diagonal, fall) and `CalculationContext`.
- `com.nekiplay.neopathfinder.api.PathAPI` — public static API for other mods/scripts.
- `com.nekiplay.neopathfinder.command.PathCommand` — `/path` client command.
- Path rendering in-world (green nodes + connecting line).

Everything else from the original NeoPathfinder (mining/commission/fishing macros, route miner,
config GUI, HUD, failsafes, macro recorder, route graph, tablist/scoreboard mirroring) was removed.

## Commands

```
/path <x> <y> <z>      walk to coordinates (replaces current path)
/path add <x> <y> <z>  append a waypoint
/path goto <x> <y> <z> same as bare /path
/path list             list queued waypoints
/path clear            clear the waypoint queue
/path stop             stop following
/path stats            last search telemetry (ms, expanded nodes, path length)
```

## API (all static)

```java
import com.nekiplay.neopathfinder.api.PathAPI;

// single destination
PathAPI.goTo(new BlockPos(100, 70, -20));

// or several waypoints in one run
PathAPI.addWaypoint(100, 70, -20);
PathAPI.addWaypoint(120, 70, -20);
PathAPI.start();

PathAPI.isRunning();          // queue/executor active
PathAPI.isPathing();          // executor currently following a segment
PathAPI.getWaypoints();       // List<BlockPos>, traversal order
PathAPI.getCurrentTarget();   // BlockPos or null
PathAPI.getCurrentPosition(); // segment start, or null
PathAPI.getLastTelemetry();   // PathfindingTelemetry (search/smoothing duration, nodes)
PathAPI.setSprintAllowed(false);
PathAPI.setRotationProfile(false, 500L, 2.0f); // fixed?, fixedTimeMs, distanceFactor
PathAPI.onFinish(() -> { });  // fired once when the queue completes
PathAPI.onFail(() -> { });    // fired once when a search/segment fails
PathAPI.stop("reason");
```

Methods are safe to call off the client thread: they re-dispatch onto it.

## Build

```
gradlew build
```

- JDK 25 toolchain, Fabric Loom 1.17.11, Kotlin 2.4.0, Minecraft 26.2.
- `gradle/gradle-daemon-jvm.properties` pins the Gradle daemon JVM to 21. On machines
  without a JDK 21 the file must be removed or its `toolchainVersion` changed before building.

## Known limitations

- Physics/move costs in `src/main/kotlin/com/nekiplay/neopathfinder/pathfinder/costs/ActionCost.kt`
  still contain literal constants (`0.08`, `0.98`, `0.91`, ...). Per `AGENTS.md` these are
  `TODO(extract)` items — they should be read from the 26.2 runtime instead of hardcoded.
- The oracle tests required by `AGENTS.md` §6 are not part of this build yet.

## Credits

Original Mighty Miner mod by **Tama**, **Osama**, **Nima0908**, **Mr. Shadow**, **Nathan**, **JellyLab**.
Pathfinder logic follows Baritone's approach. Updates by **GrishMahat**, **NorySight**, **ItNory**.