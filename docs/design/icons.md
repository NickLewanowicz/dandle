# Icons & mark — asset documentation (Brief 03 §2, §4.1)

Date: 2026-10-19 (round 4 — owner-directed "ruthless" icon overhaul). Prior rounds:
2026-10-17 (launcher rounds 1-3). Scope: launcher/app mark AND the six event glyphs.

## 0. Round 4 verdict on prior assets (ruthless audit, visual harness)

Rendered the shipped set at 24/48/96 px + tinted chips. Verdict: diaper read as a
bowl, pumping as a mortar-and-pestle, notes had a pencil stabbing the page, feeding
was a heavy blob at 24px, tummy time was abstract. Only the crescent survived.
Root cause: the original six were drawn blind, with no visual iteration loop.

## 1. Event glyphs (round 4 — SHIPPED)

**Design language:** 24dp grid, 2dp strokes, round caps/joins (Lucide/Feather
grammar — inspiration, not assets; AGENTS.md asset rule respected), one glyph per
event, optical sizing checked at 24/48/96. Tummy time is the deliberate exception:
**filled silhouette** (fills read unambiguously at 24px where the stroke prone-baby
kept failing — v1-v8 of stroke attempts read as bowl/snail/golf-club/mouse).

Final designs (source of truth = the drawable XMLs; design iterations in
`candidates/icon-round2/`, iteration harness `/tmp/icons/design.py` pattern):

| Glyph | Motif | Notes |
|---|---|---|
| Feeding | baby bottle, upright, wavy milk line | teat + collar + body + wave |
| Diaper | briefs silhouette + waistband + two corner tabs | tabs are the diaper tell (original-app-inspired) |
| Sleep | crescent moon | kept from round 1 (was already clean) |
| Notes | sticky note, folded corner, two lines | replaced page+pencil |
| Pumping | pump flange (wide funnel) + neck + collection bottle w/ ticks + one drop | ticks sell "collection" |
| Tummy time | filled silhouette: head propped on forearm, body on ground line | only filled glyph; ground line seats it |

**Recraft-v3-svg test (rejected):** 1 × $0.08 — line_art output was a thin
stick-figure in a huge canvas; normalization cost > value. Hand-drawn set shipped.

## 2. Launcher / app mark (round 4 — SHIPPED)

**Winner: `launcher-candidate-2`** — deep-indigo baby bottle with a cream
CRESCENT-MOON cutout + measurement ticks on a cream rounded square. Single motif,
reads at 48px, one idea (bottle = logging, crescent = sleep). Replaces the round-3
badge (crescent + bottle + two stars + shadows — busy, sticker-style).

Adaptive icon pipeline (PIL, `candidates/icon-round2/`):
- crop recraft canvas (black side bars, white margins), flood-fill keying of the
  cream/white background from the borders (paper texture defeated plain color
  keying) — interior cream details survive as holes that show the background layer.
- foreground: 432×432 RGBA, bottle at 58% (safe zone), `drawable-nodpi/ic_launcher_foreground.png`
- monochrome: same alpha (the cutouts keep the silhouette readable)
- background: sampled cream `#F1E2B3`
- vector foreground XML deleted (replaced by bitmap); minSdk 29 → adaptive-only is safe.

Rejected: candidate 1 (regressed to stars + tilted bottle), candidate 3 (face +
blush + stars + dots — decorative noise, off-palette coral), candidate 4 (onesie =
generic baby, not a tracker).

## 2.5 Round 5 — event glyph rework (owner-directed, SHIPPED)

Owner: diaper/tummy/pumping still not right; the EVENT-LIST feeding icon must
differentiate by side like the original app (breast silhouettes, solid = fed /
dashed = unfed, L/R corner marks — analyzed from `.scratch/babybuddy-for-android/
resources/{feeding-breast,left_breast,right_breast}.svg`, rendered via qlmanage).
Grammar: mirrored curves crossing at the cleavage; OUR curves redrawn (no copied
paths); stroke 1.4dp for this glyph (2dp merged the pair into a solid shield).

- NEW drawables: `ic_bb_feeding_left/right/both` (generated from bezier data in
  `/tmp/icons/feed_gen.py` — dashed variants are sampled dash-segment polylines,
  VectorDrawable has no dash effect); timeline/card/widgets pick them via
  `SharedUi.feedingIconRes(method)`; bottle stays for bottle/parent/self feeds
  and the quick-action button. Wired in TimelineSection, RunningTimerCard,
  CareDashboardWidget (CareRow.feedingMethod).
- Diaper: ribbed waistband with tick marks + two deep leg arcs (original grammar).
- Pumping: flange horn ANGLED up-left (the upright version read as a wine glass)
  + bottle with mL ticks + handle.
- Tummy time: crawling-baby profile (head raised right, back arc, two arms,
  ground line) — replaced the rejected filled lying figure.

Spend: $0 (all hand-drawn; recraft used only for the launcher in round 4).

### 2.1 Round 6 — feeding side variants via gpt-image-2 img2img (superseded by 2.2)

The hand-drawn breast-pair curves kept closing into a heart/shield at 24dp
(six visual iterations), and recraft-v3-svg line_art output detailed
illustrations unusable at icon size. Owner direction: feed the ORIGINAL
app's icon into gpt-image-2 as input and have it generate the variants.

- Pipeline: original `left_breast.svg` / `right_breast.svg` /
  `feeding-breast.svg` rendered to PNG (qlmanage) -> `gpt-image-2` img2img
  ("convert to clean minimal line icon, keep composition, dashed/solid
  distinction, corner letter") -> PIL: autocrop, luminance->alpha, square
  pad -> `drawable-nodpi/ic_bb_feeding_{left,right,both}.png` (256px,
  tintable; the drawable XMLs from the first attempt were removed).
- Results are clean minimal line icons preserving the original composition:
  crossing curves, dashed = unfed side, bold corner L/R, center tick for
  both. The QA matcher note stands: swatch labels render with a leading
  space (`" Black"`), matchers use substring.
- Spend: 3 x gpt-image-2 (1024, ~$0.064 each? billed as runs) ≈ **$0.19**.
  Cumulative ≈ **$0.79 of $1**.
- Two real QA bugs fixed while landing (see 1d816b4): stale-dump serving in
  `dump_with_retry` (dump file now rm'd before each attempt) and
  `tap_text`/`center_of` phantom-success on empty bounds.

### 2.2 Round 7 — traced vector recreation of the trio + pump/tummy redraw (FINAL, SHIPPED, supersedes 2.1's raster trio)

Owner verdict on round 6: the generated side variants were "still completely
unlegible." New direction: take the original app's icons, recreate them with
slight adjustments to line thickness and corner radius so it looks bespoke,
and iterate until visually almost identical. Pump and tummy time called
stupid-looking — redraw simple, with a very strict visual reviewer gate.

- **Trio** (`ic_bb_feeding_left/right/both.xml`, vector drawables replacing the
  round-6 `drawable-nodpi` rasters, same resource names so no Kotlin changes):
  traced from `.scratch/babybuddy-for-android/resources/{left,right}_breast.svg`
  and `feeding-breast.svg`. Group matrices baked into path coordinates
  (left: 4.7494×; right: 7.8372×; both: mm-space × 3.7798). Dashes
  pre-generated as M/L subpaths by arc-length walking the flattened Béziers
  (VectorDrawable has no dashArray); dash rhythm exactly the original's
  (10.0/20.0 in the 512 viewport). L/R letterform paths reused verbatim.
  Bespoke deltas: round caps/joins; otherwise overlay-identical (green-only
  diff sheets). 3 iterations each.
- **Pumping** (`ic_bb_pumping.xml`): redrawn minimal — flange ellipse with
  visible channel + concave funnel + collared collection bottle, 2dp strokes.
  7 iterations (rejected: V-bowl, open trapezoid, continuous hourglass
  walls). Strict-review FAIL v1 (rode 2.7dp high; interior whites 0.5/1.2dp
  died at 24px) → fixed to measured center (12.0, 11.88)dp, ink height
  16.27dp, ellipse ry 2.25, bottle x 9.8–14.2 → PASS on re-verdict.
- **Tummy time** (`ic_bb_tummy_time.xml`): redrawn as a single filled mass —
  prone body, back high into the shoulder, lifted round head breaking the
  back line, flat mat base; no accent lines (every accent variant muddied
  24px). 7 iterations (rejected: snail, bun+ball, twin blobs). PASS.
- **Strict review gate** (ux-designer): 4/5 first pass; pumping failed on
  measured centering + 24px interior survival, re-passed after fix. Evidence:
  `docs/design/candidates/icon-round7/` (all-five 512/48, trio overlays,
  pumping/tummy details).
- Color tokens unified to the set convention `#FF000000` (was `#1A1A1A` on
  the five; inert — all usage sites tint).
- Spend: **$0.** All tracing/redraw hand-computed; no generation APIs.

## 3. Spend

- Round 4: recraft-v3 raster 4 × $0.04 = **$0.16**; recraft-v3-svg 1 × $0.08 =
  **$0.08**. **Round 4 total: $0.24.**
- Cumulative (rounds 1-3): ≈ $0.36. **Grand total ≈ $0.60 of the $1 budget.**
- Token handling: read at runtime from `~/Documents/Projects/ai-image-transformer/.env`
  (`REPLICATE_API_TOKEN`); never printed, never committed.

## 4. Legacy docs (rounds 1-3, launcher mark v1 — superseded)

## 1. Launcher / app mark

**Current mark (owner-picked, round 3):** the **recraft-v3 candidate 1 badge design** —
slate rounded-square badge, cream crescent with soft drop shadow, line-art bottle,
two stars. The baked-in "APP - LAUCHER ICON" caption was removed with a $0 PIL
patch (see §1.3). Textless source:
`docs/design/candidates/recraft-v3-candidate-1-textless.png`.

**Deliverables (BOTH shipped; engineer picks per §1.4):**

- `docs/design/launcher-mark.png` — **badge variant**: the full rounded badge on
  transparent (page background keyed out), 512×512.
- `docs/design/launcher-mark-glyph.png` — **glyph variant**: crescent + bottle +
  stars extracted off the badge, 512×512 (the badge's soft crescent shadow is
  necessarily dropped; a small soft wedge remains where the shadow touches the
  crescent's lower edge — reads as a ground shadow).
- `docs/design/launcher-mark-preview.png` — 48 / 96 / 192 px strip, badge row (top)
  and glyph row (bottom).

### 1.1 Generation

**Round 2** — models `openai/gpt-image-2` and `recraft-ai/recraft-v3(-svg)`; 7 runs
(+1 schema-failed retry):

- **Spend:** recraft-v3 raster 2 × $0.04 = $0.08 (price confirmed on
  replicate.com/pricing); recraft-v3-svg 2 × $0.08 = $0.16 (model-page price,
  JS-rendered — worst-case bound); gpt-image-2 1 medium + 2 low succeeded
  (≈$0.064 + 2×≈$0.018 ≈ $0.10); 1 gpt-image-2 run failed pre-generation
  (`background: transparent` unsupported) — not billed. **Round-2 ≈ $0.34.**
- **Round 1:** flux-schnell, 6 × $0.003 = **$0.018** (superseded; owner: too simple).
- **Round 3 (current):** **$0.00** — PIL-only text removal, no regeneration needed.
- **TOTAL: ≈ $0.36 of the $0.70 cap.**
- Token handling: read at runtime from `~/Documents/Projects/ai-image-transformer/.env`
  (`REPLICATE_API_TOKEN`); never printed, never committed.

**Base prompt** (round 2, composition varied per candidate):

> Professional minimal logo design for a mobile app launcher icon: a single unified
> motif of a crescent moon cradling a baby bottle. Flat vector style, geometric,
> clean, modern, premium brand quality. Colors: indigo (#3F51B5) and cream (#FFF8E7),
> at most one subtle accent tone. Centered composition. [composition clause] Solid
> uniform flat mid-gray (#808080) background filling the entire canvas. No text, no
> letters, no photorealism, no drop shadow, no border, no extra elements. Must read
> clearly at small sizes (48dp).

**Model notes / gotchas (for future rounds):**

- `openai/gpt-image-2` (Replicate): `openai_api_key` optional — billed through the
  Replicate token. **`background: transparent` is NOT supported** (prediction fails
  with `invalid_value`). Solid-bg + keying works; instruction-following ("no text")
  held perfectly.
- `recraft-ai/recraft-v3` (raster): Replicate deployment has NO `vector_illustration`
  style (valid: `any`, `realistic_image*`, `digital_illustration*`); IGNORED "no text"
  and baked a caption into the image — which is the design the owner ultimately
  preferred once the caption was patched out.
- `recraft-ai/recraft-v3-svg`: real SVG output (kept as vector source in
  `candidates/`), but styles are limited to line-art family (`any`, `engraving`,
  `line_art`, `line_circuit`, `linocut`) → output skews line-art/sticker.
- Replicate "png" outputs from recraft models arrive as WebP bytes; convert before use.

### 1.2 Owner verdict (authoritative ranking) & process note

The owner reviewed all round-2 candidates and ranked:

1. **BEST: recraft-v3 candidate 1** (slate badge, cream crescent + line-art bottle +
   stars) — "just needs to have the text modified" → shipped (§1.3).
2. gpt-image-2 LOW (strong blue crescent, heart-accent bottle)
   = `candidates/gpt-image-2-candidate-3.png`.
3. gpt-image-2 MEDIUM (`candidates/gpt-image-2-candidate-1-medium-OWNER-REJECTED.png`,
   renamed) — the agent's round-2 pick — owner-ranked WORST of the three.

**Process note (binding):** when candidates differ on STYLE, present them to the
owner rather than picking unilaterally; agent ranking only filters objective
defects (text, artifacts, illegibility at 48dp).

### 1.3 Round-3 pipeline (text removal + background removal)

**Text removal ($0, PIL):** the caption sat on featureless slate inside the badge.
Final approach (after two rejected variants — masked scanline inpaint left ghost
smears from the wide soft halo): band-fill — replace the whole text band
(x 205–815, y 872–942) per-row with the median color of clean badge columns left
(x 120–190) and right (x 830–900), horizontally interpolated. The badge is locally
featureless there, so the fill is seam-free by construction. Artifact assessment:
invisible at 1×; a barely-perceptible band edge only when magnified 2× and hunted
for. No regeneration was needed (trigger in Brief never fired).

**Background removal (badge variant):** edge flood-fill over the near-white page
(251,252,253, tol 26); hard alpha inside, 2px boundary-ring ramp + unmix; 0.7px
feather; badge bbox square-padded to 512 at 96% content. Rounded corners and the
internal crescent shadow fully preserved.

**Glyph extraction (glyph variant):** within the badge (page-bg flood mask, eroded
14px to dodge the badge rim), alpha = clamp((dist(px, slate (80,98,115)) − 50) / 40)
with slate-unmixed edge colors. The alpha floor 50 is load-bearing: the badge's
soft crescent shadow sits only dist 22–35 from slate and drops out entirely, while
outline navy (dist ≈98) and cream (dist ≈210) stay solid. Median 3 despeckle +
0.6px feather; crop/square/512 at 90%.

### 1.4 Downstream (android-engineer) — badge vs glyph tradeoff

- **Badge variant** (`launcher-mark.png`): what the owner picked, verbatim. For
  adaptive icons it can be used as the **foreground inside a matching slate
  background layer** — but it is full-bleed with its own rounded corners, so the
  launcher mask (circle/squircle) will crop into it; corners of the drawn badge may
  clip. Safest pairing: use it as the **background layer** and put a
  monochrome/none foreground, accepting the mask re-crops the badge.
- **Glyph variant** (`launcher-mark-glyph.png`): motif only, safe-zone friendly —
  drop it onto any background layer (solid slate `#50617A`-ish to match, or a themed
  color) as foreground. Loses the badge surface and (mostly) the soft shadow.
- 48px check passed for both (see preview strip). Engineer decides; both assets ship.

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
