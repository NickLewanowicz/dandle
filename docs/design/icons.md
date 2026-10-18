# Icons & mark — asset documentation (Brief 03 §2, §4.1)

Date: 2026-10-17. Owner: asset-designer (AI). Scope of this doc: launcher/app mark
(generated via Replicate), the pipeline used, the vector glyph grid rules, and the
breast-side badge spec. Glyph XML implementation and code are the android-engineer's.

## 1. Launcher / app mark

**Deliverable:** `docs/design/launcher-mark.png` — 512×512 RGBA (content ≥432px,
clean alpha edges, 1px feather). Readability strip: `docs/design/launcher-mark-preview.png`
(48 / 96 / 192 px). Candidate record: `docs/design/candidates/candidate-1..6.png`.

### 1.1 Generation

- **Model:** `black-forest-labs/flux-schnell` (Replicate), 1 image per prompt, 1:1,
  PNG, 4 steps, `go_fast`. Chosen for cost (~$0.003/image) and flat-graphic strength.
- **Spend:** 6 images × $0.003 = **$0.018** (of the ≤$0.25 asset budget). All 6
  predictions succeeded; no billable failures (rate-limit 429s were rejected before
  prediction creation, retried with backoff). Exact prediction times: 0.61s, 0.62s,
  0.61s, 0.63s, 0.61s, 0.60s. No cost field is returned per prediction; the figure
  uses Replicate's published $0.003/image price for this model.
- **Token handling:** read at runtime from `~/Documents/Projects/ai-image-transformer/.env`
  (`REPLICATE_API_TOKEN`); never printed, never copied into the repo.

**Base prompt** (shared by all 6 candidates):

> Simple flat app icon design: a single motif of a crescent moon cradling a baby
> bottle. One simple rounded shape, minimal, flat 2-tone vector style, colors indigo
> (#3F51B5) and cream (#FFF8E7) only. Centered composition. Solid uniform mid-gray
> (#808080) background covering the entire frame. No text, no letters, no numbers,
> no watermark, no photorealism, no busy gradients, no extra elements. Icon-appropriate
> for a mobile launcher icon, must read clearly at small sizes (48dp).

**Composition variations** (appended, one per candidate):

1. Crescent moon on the left, bottle resting in its curve, tilted slightly right.
2. Crescent moon opens upward like a cradle, bottle nestled horizontally in the arc.
3. Large thick crescent wrapping around a small upright bottle at center.
4. Thin elegant crescent under an upright bottle, bottle standing in the crescent's bowl.
5. Crescent moon on the right, bottle tucked into its curve, tilted slightly left.
6. Crescent and bottle interlocked as one continuous rounded silhouette.

(Note: flux reorders composition freely; the rendered results do not map 1:1 to the
variation text. Judge the output, not the prompt.)

### 1.2 Candidate review & winner

| Candidate | Verdict |
|---|---|
| 1 | Drop shadow + 3rd tone + odd milk-blob artifact → rejected |
| 2 | Clean flat but collar has slit artifacts; reads "bottle in front of moon", not cradled → runner-up |
| 3 | Cleanest read, but single-tone (cream only, no indigo) → runner-up |
| 4 | Circular badge, 3–4 tones, thin white bottle outline would vanish at 48px → runner-up (busy) |
| 5 | Background not uniform (white tile corners) → unusable for clean keying |
| **6** | **Winner: true 2-tone (indigo bottle + cream crescent/cap/nipple), cradle composition, no shadow, no extra elements** |

Winner rationale: it is the only candidate that is simultaneously (a) genuinely
2-tone in the brief's palette family, (b) a single legible "moon cradles bottle"
motif, and (c) artifact-free. It survives the squint test at 48px: silhouette =
crescent + upright bottle, no detail loss (verified in `launcher-mark-preview.png`).

### 1.3 Background removal pipeline (reproducible)

Script logic (run ad hoc via PIL; the winner only — candidates are kept as-is):

1. Flood-fill from all four image edges over pixels within RGB distance 30 of the
   sampled background (104,132,173) — connectivity prevents eating same-hued
   pixels inside the motif.
2. Alpha: 255 for all non-flood pixels **except** a 2px boundary ring, which ramps
   on background distance (unmix `fg = (px − (1−a)·bg) / a`) for antialiased edges.
   (First attempt ramped alpha over the whole motif and made the bottle body
   semi-transparent — its blue is close to the gray-blue background in RGB space.
   Hard-interior + ring-only ramp fixes this.)
3. Palette snap to exactly two tones: cream `#FFF8E7` and indigo `#5C6BC0`
   (Material Indigo 400, same family as `#3F51B5`). Boundary-ring pixels inherit
   the palette label of the nearest interior pixel (prevents a dark hairline
   fringe where bg-blend pixels misclassify).
4. Alpha feather: 0.7px Gaussian (≈1px feather per spec).
5. Crop to content bbox, square-pad, scale content to 90% of a 512×512 canvas.

### 1.4 Downstream (android-engineer)

Ship as adaptive-icon foreground from this PNG (or vector-trace it if the engineer
prefers) + monochrome layer (single-tone silhouette of the same mark). Dark-bg and
light-bg composites verified; edges are standard 1px antialiasing, no halo.

## 2. Vector action glyph grid rules (Brief 03 §2.1 — binding for all six glyphs)

- Canvas: **24×24dp** grid; strokes **2dp**, rounded caps and joins.
- Live area: **20×20dp** centered (2dp padding all around); nothing may bleed outside.
- Six glyphs: feeding, diaper, sleep, notes, pumping, tummy time — hand-built vector
  XML (SVG→Android VectorDrawable), **single-color, themeable** (`android:tint`).
- **Silhouette test:** squint — all six must be distinguishable at 24dp and at a
  glance in a timeline row. If two glyphs share a silhouette class (e.g. two round
  containers), differentiate by outline feature, not interior detail.
- Detail inside the silhouette is secondary: glyphs must survive single-tone
  rendering at 24dp in both light and dark containers.

## 3. Breast-side badge spec (Brief 03 §2.4)

- Applies to: feeding-on-breast glyphs and pumping glyphs.
- Rendering: small dot-badge at **~12dp**, anchored **bottom-trailing** of the 24dp
  glyph, overlapping the glyph's corner.
- States: **L** = 1 dot, **R** = 2 dots (or mirrored single-dot placement — engineer
  picks the concrete encoding, but it must be glanceable and consistent across
  feeding and pumping), **L+R** = both. Use the type's on-container tone for dots.
- Timeline rows: badge sits inside the leading glyph chip, never outside it.

## 4. Pumping breast-side convention — research finding (Brief 03 §1)

The old app (`.scratch/babybuddy-for-android`) has **no pumping-side convention at all**:

- `TimerControllersV2.kt` `PumpingLoggingController` (lines ~831–887): UI is only an
  amount picker (`HorizontalDecIncEditor`) + free-text notes; `createEntry` POSTs
  `start/end/amount/notes` — no side anywhere.
- `pumping_logging_entry.xml`: confirms — no side control in the pumping layout.
- `TimeEntries.kt` `PumpingEntry`: fields are `id/child/start/end/notes/amount` only.
- The "Left breast / Right breast / Both breasts" strings exist **only as Feeding
  methods** (feeding form spinner), never applied to pumping.

So there is nothing to match; Brief 03 §1's new convention stands, and the
Maintainer-approved choice is the **`notes` prefix `[Left]` / `[Right]` / `[Both]`**
(stripped from visible note text, rendered as the §3 badge). Suggested follow-up for
the spec owner: note this finding in `docs/research/babybuddy-api.md` or the flows
spec for pumping when §5 is updated (v0.4).
