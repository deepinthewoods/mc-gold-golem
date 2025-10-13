## Gold Golem Mod — Full Implementation Plan

### Current Status (2025-10-13)
- Build runs on 1.21.10; dev client launches.
- Dependencies: Geckolib set to `geckolib-fabric-1.21.10:5.3-alpha-1`; Owo removed.
- Entity: `GoldGolemEntity` registered with required 1.21.10 attributes (waypoint_transmit_range, step_height, movement_efficiency, gravity, safe_fall_distance, fall_damage_multiplier, jump_strength). Basic goals; right-click opens UI.
- Summoning: Pumpkin-on-gold callback spawns a golem and assigns owner.
- UI: Single-screen `GolemScreen` now uses a vanilla chest look with a dynamic control band. Top-to-bottom: label, gradient ghost row, width slider, golem inventory grid, player inventory, hotbar. Ghost slots are clickable; width uses a slider.
- Networking: Fabric Custom Payloads registered; client receiver updates the open screen.
- Rendering: Temporary `EmptyEntityRenderer` registered (entity is invisible) until GeckoLib renderer arrives.
- Persistence: Implemented with 1.21.10 storage API (ReadView/WriteView). Saves width, gradient, inventory, and owner UUID.
- Ownership: Owner-only UI enforced; in singleplayer, if the saved owner is offline, right-click claims the golem.
- Dynamic layout: No‑lib UI spec (`GolemOpenData`) drives gradient rows, golem slot count, and slider presence; handler places slots with a computed controls margin to keep hitboxes aligned with background.
- Golem inventory: 27 slots (3x9) for now.

### Scope
Adds a tameable Gold Golem for 1.21.10 (Fabric + Yarn) that:
- Summons by placing a Carved Pumpkin on a Gold Block (both removed, golem spawns).
- Follows the summoner (owner), teleports if far.
- Right-click opens a single screen: gradient controls (9 ghost slots + width slider) on top, golem inventory (56 slots) below, and player inventory at the bottom.
- Builds paths while moving, consuming its inventory according to a 9-slot gradient and path width. Replaces solid blocks underfoot. If out of blocks, stops and displays “!”.
- Uses vanilla UI screens (no external UI libs); GeckoLib for model/animations (placeholder gray box first).

### Milestones
1) Dependencies & Config
- Add GeckoLib Cloudsmith repo.
- Add: `software.bernie.geckolib:geckolib-fabric-1.21.10:5.3-alpha-1`.
- No Owo dependency (using vanilla UI).
- Validate with `gradlew build` and `gradlew runClient`.

2) Entity & Registration
- Files:
  - `ninja/trek/mc/goldgolem/world/entity/GoldGolemEntity.java`
  - `ninja/trek/mc/goldgolem/registry/GoldGolemEntities.java`
- Register with `EntityType.Builder` (1.4w × 2.7h), attributes: `MAX_HEALTH 40`, `MOVEMENT_SPEED 0.28`, `FOLLOW_RANGE 32`.
- Goals: `FollowOwnerGoal(speed=1.1, start=3, stop=12)`, `WanderAroundGoal(0.8)`, `LookAtEntityGoal(Player, 8)`.
- Ownership: `setOwner(player)`, only owner opens UI.

3) Summoning
- Mixin: inject into `CarvedPumpkinBlock#onPlaced` to detect pumpkin atop gold block.
- Server-side: break pumpkin + gold, spawn golem centered on gold; set owner if placer is player.
- Also support dispenser behavior: optional follow-up mixin if desired.

4) Inventory & Persistence
- Internal inventory: `SimpleInventory(56)`; no auto-pickup.
- NBT: save/load `PathWidth`, Inventory, and Gradient (9 Identifiers for block types).
- DataTracker: track `PathWidth` (int, 1–9). Gradient synced via packets.

5) UI (Vanilla)
- Screen handler: `GolemInventoryScreenHandler` (dynamic golem slots + player inv). Registered via `ExtendedScreenHandlerType` with `GolemOpenData` open data (entityId, gradientRows, golemSlots, slider).
- Client screen: `GolemScreen` (`HandledScreen`), chest-style layout:
  - Control band (inside the window): label (title), one or more ghost rows (9 per row), width slider.
  - Body: golem grid (rows × 9) drawn from chest body slice; slots aligned to background; then standard player inventory/hotbar at the bottom slice.
- Interactions implemented:
  - Click ghost slot with a block in hand -> C2S `set_gradient_slot` payload; server stores and S2C-syncs back.
  - Width slider -> C2S `set_path_width` payload; server clamps [1,9] and S2C-syncs back.

6) Networking & Sync
- C2S payloads: `set_gradient_slot`, `set_path_width`.
- S2C payload: `sync_gradient` (entityId, width, 9 strings). Sent on open and after changes. Nulls sanitized to empty strings.
- Client: updates the open screen when IDs match.

7) Path Building Logic
- Tick server-side when has owner:
  - Determine movement vector; compute perpendicular (nx, nz).
  - Path width N = [1..9], half = (N-1)/2. For offsets i ∈ [-half..half], map to gradient index:
    - Leftmost slot = center; rightmost = outer edges (abs(i) scaled to [0..8]).
  - For each position: target = floor(center + i*(nx,nz)) at Y under golem.
  - Replace solid blocks (skip unbreakable/void). Place chosen block if present in inventory; decrement stack.
  - If no placements occurred due to lack of blocks: stop navigation and show name “!”. Hide name when able to place again.
- Respect dimensions: none (works everywhere).

8) Rendering & Assets (GeckoLib)
- Current: temporary `EmptyEntityRenderer` registered to avoid client crash before model is ready (entity invisible).
- Next: add placeholder assets:
  - `assets/gold-golem/textures/entity/gold_golem.png` (gray box)
  - `assets/gold-golem/geo/gold_golem.geo.json` (box model)
  - `assets/gold-golem/animations/gold_golem.animation.json` (idle/walk stub)
- Then wire `GoldGolemModel` (GeoModel) + `GoldGolemRenderer` (GeoEntityRenderer) and register in `GoldGolemClient`.

### Completed
- Dependencies aligned; Owo removed; Geckolib updated.
- Entity registered with required attributes; summon-by-pumpkin implemented; owner tracked.
- Persistence implemented via ReadView/WriteView; saves gradient, width, inventory, owner.
- Owner-only UI gate implemented; SP auto-claim when previous owner is offline.
- UI overhauled to a chest-style single screen with ghost slots + slider; proper vanilla textures; hover/click hitboxes aligned with grid; labels grid-aligned.
- Dynamic layout spec added (no external UI lib), with adjustable gradient rows and golem inventory size; golem inventory set to 27 slots.
- Temporary renderer registered to stabilize client.

### Next Up
- Path logic: Implement placement algorithm and inventory consumption; handle block validation and throttling.
- Renderer: Add GeckoLib placeholder model/anim and swap off `EmptyEntityRenderer`.
- UI dynamics: Wire gradientRows to entity state; add +/- to add/remove rows dynamically; consider multi-row filler/spacing.
- UX polish: Ghost slot tooltips, invalid block feedback, show current block names; small hover highlight for ghost slots.

### Dev Flow
- Build: `gradlew build`
- Dev client: `gradlew runClient`
- Summon test: place Gold Block, then Carved Pumpkin on top.
- UI test: right-click as owner; set gradient + width; fill inventory; walk to generate paths.

### Risks & Notes
- GeckoLib coordinates vary per MC minor; confirm exact 1.21.10 artifact (currently alpha).
- Vanilla UI ghost slots need careful cursor handling; keep server authoritative.
- UI alignment: ensure future layout tweaks always adjust both handler slot positions and background slices together.
- Path placement must avoid griefing unbreakables; check hardness/replaceability.
- Performance: throttle placement rate (e.g., every few ticks) if needed.
