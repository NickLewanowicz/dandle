# Icons & mark — asset documentation (Brief 03 §2, §4.1)

Date: 2026-10-17. Owner: asset-designer (AI). Scope of this doc: launcher/app mark
(generated via Replicate), the pipeline used, the vector glyph grid rules, and the
breast-side badge spec. Glyph XML implementation and code are the android-engineer's.

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
