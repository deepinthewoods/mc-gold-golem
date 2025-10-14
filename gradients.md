# Gradient Sampling System (Gold Golem)

This document summarizes the gradient selection system used by the Gold Golem when placing blocks across a 1D span (e.g., center → edge of a path).

## Overview
- Treat both the placement span (blocks to place) and the gradient slots as 1D grids.
- Resize the gradient grid to the placement grid, then sample the gradient at each block center using a configurable sampling window.
- A single slider controls the window width W (in gradient-slot units). Larger W increases randomness and blends across more gradient slots.

## Effective Gradient Size (G)
- Ignore trailing empty slots on the right side of the gradient.
- Let G be the count of non-empty slots: slots are indexed i ∈ [0, G−1].

## Coordinate Mapping
- For a placement index b over B positions, map to the continuous gradient coordinate:
  - s = (b / max(1, B−1)) × (G−1)
  - This puts b=0 at g0 and b=B−1 at g(G−1).

## Windowed Sampling
- Window width W is in “gradient-slot units” (controlled by the slider).
- Draw a deterministic uniform offset u ∈ [−W/2, +W/2] per block position.
- Sample at s' = s + u, then pick bin i = round(s') clamped to [0..G−1].
- Resulting probabilities equal the overlap between the window and each bin (proportional mixing by geometric overlap).

## Edge Handling (Reflection)
To avoid edge bias when the window extends beyond the gradient domain, reflect out-of-bounds samples back into the domain (triangle-wave fold):
- Domain in continuous space is [a, b] = [−0.5, G−0.5] (bin i spans [i−0.5, i+0.5]).
- Let L = b − a (= G) and y = (s' − a) mod (2L). If y < 0 then y += 2L.
- If y ≤ L, r = y; else r = 2L − y.
- Reflected sample is s_ref = a + r.
- Choose i = round(s_ref) and clamp to [0..G−1].
- This preserves symmetry near edges and prevents clamping bias.

## Determinism
- Generate u via a stable RNG (or hash → float) keyed by worldSeed, golemId, and block position. This keeps placement server‑authoritative and reproducible.

## Slider Semantics (W)
- Units: gradient-slot units.
- Range: integer 0..G (cap at G).
- Behavior:
  - W = 0 → stripes (deterministic nearest slot).
  - W = 1 → proportional mixing between adjacent slots (classic linear case).
  - 1 < W < G → multi-stop mixing over a wider window.
  - W ≥ G → effectively uniform random among all non-empty slots.

## Detents/Markers
Show helpful markers above the slider; recompute live when gradient slots or placement span change.
- Gradient detents (e.g., gold): integers W = 0, 1, …, G.
- Block detents (e.g., cyan): multiples of Δs up to G, where
  - Δs = (G−1) / max(1, B−1) is the per-block step in gradient space for the current span B.
  - Place detents at W ≈ n·Δs (snap to nearest slider tick), n ≥ 1.
- Suggested labels:
  - 0 = Stripes, 1 = Proportional, G = All.

## Examples
- G = 3 (g0,g1,g2), B = 4 (b0..b3):
  - b0 → s=0 ⇒ g0; b3 → s=2 ⇒ g2.
  - b1 → s=2/3: with W=1, P(g0)=1/3, P(g1)=2/3; with W=0, g1 only.

## Pseudocode
```
G = countNonEmptySlots(gradient)
for each placement index b in [0..B-1]:
  s = (b / max(1, B-1)) * (G - 1)
  W = clamp(sliderValue, 0, G)
  u = deterministicUniform(worldSeed, golemId, blockPos) * W - (W / 2)
  sPrime = s + u
  // reflect into [−0.5, G−0.5]
  a = -0.5; bnd = G - 0.5; L = bnd - a // = G
  y = (sPrime - a) % (2*L); if (y < 0) y += 2*L
  r = (y <= L) ? y : (2*L - y)
  sRef = a + r
  i = clamp(round(sRef), 0, G-1)
  place(gradient[i])
```

## Notes
- Works for any smaller gradient (trailing empty slots ignored).
- Produces smooth fades over many blocks and naturally mixes more than two stops when W > 1.
- Changing B (span length) or G (effective gradient size) updates detents so users can align W with their layout.

