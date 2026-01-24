# Implementation Plan: Placement Planner Performance Fixes

## Scope and relevant files
- `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/PlacementPlanner.java`
  - Contains `selectNextBlock`, `findPlacementPosition`, `getValidStandPositions`, `canPathTo`, and queue management.
- `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/TerraformingBuildStrategy.java`
- `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/TreeBuildStrategy.java`
- `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/TowerBuildStrategy.java`
- `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/WallBuildStrategy.java`
  - All call into `PlacementPlanner` and may need small adjustments to new planner behavior.

## Implementation plan
1) Baseline and guardrails
   - Add lightweight counters in `PlacementPlanner` (ticks, pathfinding calls per tick, cache hits) gated behind a debug flag.
   - Verify how `PlacementPlanner` state is serialized to NBT and update any new fields.

2) Replace global scans in `selectNextBlock`
   - Keep `remainingBlocks` as a queue sorted once on `setBlocks`.
   - Add a small deferred/skip map, e.g. `Map<BlockPos, Long> skipUntilTick`, to temporarily ignore unreachable blocks.
   - `selectNextBlock` should pop from `remainingBlocks` and only attempt a limited number of candidates per tick
     (ex: 1-3) before yielding, rather than scanning the full deque.

3) Make placement search incremental and early-exit
   - Replace `getValidStandPositions` and list sorting with an outward search that yields positions by increasing
     distance from the golem (or from the target if easier), stopping on the first valid stand position that is
     pathable.
   - Implement a generator-style method (iterate by radius or by precomputed offsets) so we do not build large lists.
   - For the teleport fallback, only collect enough positions to select a valid teleport stand when retries are
     exhausted.

4) Pathfinding budget and caching
   - Add a per-tick budget (e.g. `MAX_PATHFINDS_PER_TICK = 1` or `2`).
   - Track the current server tick (`world.getTime()`) and reset the budget each tick.
   - Add a short-lived cache for `canPathTo` results, e.g. `Map<BlockPos, PathCheck>` with expiration ticks
     to avoid repeat A* for the same stand positions.

5) Fix the risky heuristic in `canPathTo`
   - Remove the `dist < 4` auto-true.
   - Replace with a cheap, reliable check: e.g. a direct line-of-sight / raycast or a simple
     unobstructed horizontal scan, then fall back to the pathfinder only if needed.
   - Use Fabric/Yarn docs to confirm correct raycast / pathing API usage for 1.21.10.

6) Reduce teleport reliance
   - Gate teleport to only happen after a minimum number of failed attempts and when a recent path check failed.
   - Avoid teleporting when `currentStandPos` is within reach but pathable checks were skipped due to budget.

7) Sorting overhead in `setBlocks` and `addBlocks`
   - In `setBlocks`, keep the initial sort as-is.
   - In `addBlocks`, merge new blocks into the existing sorted deque without resorting the entire list
     (or append and only sort new blocks if the selection logic no longer relies on global order).

8) Update NBT/readView state
   - Persist any new cache/budget fields if needed, or ensure they are safely reset on load.

9) Validation
   - Run `cmd /c gradlew build` after code changes.
   - Use debug counters to confirm pathfinding calls per tick remain within budget on large builds.

## API lookups needed
- Confirm the correct raycast API (`World.raycast`/`RaycastContext`) and pathing calls for 1.21.10.
- Validate any navigation/path APIs used for `Path.reachesTarget()` and related checks.

## Expected outcomes
- `selectNextBlock` no longer performs O(N*M) scans with embedded pathfinding.
- `findPlacementPosition` stops early and avoids 2,000+ checks per block.
- Pathfinding calls are bounded per tick with caching and safer heuristics.
- Teleportation becomes a fallback instead of a common pathing failure mode.
