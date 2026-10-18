# Icons & mark — asset documentation (Brief 03 §2, §4.1)

Date: 2026-10-17. Owner: asset-designer (AI). Scope of this doc: launcher/app mark
(generated via Replicate), the pipeline used, the vector glyph grid rules, and the
breast-side badge spec. Glyph XML implementation and code are the android-engineer's.

## 1. Launcher / app mark

**Deliverable:** `docs/design/launcher-mark.png` — 512×512 RGBA (content ≥432px,
clean alpha edges, 1px feather). Readability strip: `docs/design/launcher-mark-preview.png`
(48 / 96 / 192 px). Candidate record with model labels: `docs/design/candidates/`.

**Current mark = ROUND 2 winner** (`gpt-image-2-candidate-1.png`, generated with
`openai/gpt-image-2` at medium quality): a cream crescent moon cradling a leaning
baby bottle — cream body, indigo outline + collar (`#3F51B5`), warm amber nipple
accent, on exact `#808080`. Flat 3-tone (within brief's "max 3 tones"), no text,
no shadow, no border. Owner feedback on the round-1 flux-schnell mark ("pretty
ugly, too simple a model") triggered round 2.

### 1.1 Generation

**Round 2 (current)** — models `openai/gpt-image-2` and `recraft-ai/recraft-v3(-svg)`:

- 7 prompt runs (+1 schema-failed retry), all via the Replicate API with the token
  from `~/Documents/Projects/ai-image-transformer/.env` (read at runtime; never
  printed, never committed).
- **Spend accounting:** recraft-v3 raster 2 × $0.04 = $0.08 (price confirmed on
  replicate.com/pricing, "$0.04 / output image"); recraft-v3-svg 2 runs (model-page
  price $0.08/image → $0.16; price is JS-rendered, not API-readable — worst-case
  bound used); gpt-image-2 1 medium + 2 low succeeded (OpenAI published image API
  pricing mirrored by the Replicate proxy: ≈$0.064 medium, ≈$0.018 low → ≈$0.10);
  1 further gpt-image-2 run failed at validation (`background: transparent` is not
  supported by the model) — failed before generation, not billed.
- **Round-2 spend: ≈ $0.34 (worst case). Round-1 spend: $0.018 (6 × flux-schnell
  @ $0.003). TOTAL: ≈ $0.36 of the $0.70 cap** (≤ $0.70 even with generous
  pricing error bars).

**Base prompt** (all round-2 candidates; composition varied per candidate):

> Professional minimal logo design for a mobile app launcher icon: a single unified
> motif of a crescent moon cradling a baby bottle. Flat vector style, geometric,
> clean, modern, premium brand quality. Colors: indigo (#3F51B5) and cream (#FFF8E7),
> at most one subtle accent tone. Centered composition. [composition clause] Solid
> uniform flat mid-gray (#808080) background filling the entire canvas. No text, no
> letters, no photorealism, no drop shadow, no border, no extra elements. Must read
> clearly at small sizes (48dp).

Composition clauses: (a) direct crescent+bottle; (b) negative-space variant — "a baby
bottle inside a rounded indigo squircle where the crescent moon appears only as a
subtle negative-space cutout" (owner asked to try subtler crescents).

**Model notes / gotchas (for future rounds):**

- `openai/gpt-image-2` (Replicate): `openai_api_key` optional — billed through the
  Replicate token. **`background: transparent` is NOT supported** (prediction fails
  with `invalid_value`). Solid-bg + keying works; medium quality produced the best
  mark; instruction-following ("no text") held perfectly.
- `recraft-ai/recraft-v3` (raster): Replicate deployment has NO `vector_illustration`
  style (valid: `any`, `realistic_image*`, `digital_illustration*`); added TEXT to
  the image despite "no text" ("APP - LAUCHER ICON") — unreliable for logo marks.
- `recraft-ai/recraft-v3-svg`: real SVG output (kept as vector source in
  `candidates/`), but styles are limited to line-art family (`any`, `engraving`,
  `line_art`, `line_circuit`, `linocut`) → output skews line-art/sticker, not flat
  2-tone fill. Also: Replicate "png" outputs from recraft models arrive as WebP
  bytes; convert before use.

**Round 2 candidate review (all viewed):**

| Candidate | Model | Verdict |
|---|---|---|
| 1 | gpt-image-2 (medium) | **WINNER** — professional, cohesive, true cradle composition, 3 clean tones |
| 3 | gpt-image-2 (low) | runner-up — strong indigo crescent but adds a heart accent (greeting-card vibe) |
| 4 | gpt-image-2 (low, negative-space) | runner-up — clean squircle tile; crescent reduced to a bug on the bottle, and full-bleed tile complicates adaptive-icon use |
| svg-1 | recraft-v3-svg | reject — moon FACE + stars, clip-art kitsch |
| svg-2 | recraft-v3-svg | runner-up — elegant line art, but sticker-like; ticks too fine for 48dp |
| 1 | recraft-v3 (raster) | reject — contains TEXT ("APP - LAUCHER ICON") |
| 2 | recraft-v3 (raster) | runner-up — professional flat circle badge, but long-shadow style reads dated |

**Round 1 (superseded)** — `black-forest-labs/flux-schnell`, 6 candidates, $0.018;
winner kept at `candidates/round1-winner-flux-schnell.png`, all six at
`candidates/flux-schnell-candidate-1..6.png`. Owner verdict: too simple/ugly —
schnell retired from mark generation.

### 1.2 Winner rationale (round 2)

`gpt-image-2-candidate-1` is the only candidate that hits the owner's bar on every
axis: professional and cohesive (reads like a deliberate brand mark, not generated
filler); single legible "moon cradles bottle" motif; exactly 3 tones (cream/indigo/
amber — brief allows one accent); survives the squint test at 48dp (silhouette =
crescent + upright bottle with collar); zero artifacts (no text, shadow, border,
or extra elements — gpt-image-2 followed every negative constraint).

### 1.3 Background removal pipeline (reproducible)

Same as round 1, with a 3-tone palette snap (run ad hoc via PIL):

1. Flood-fill from all four edges over pixels within RGB distance 26 of the
   background (128,128,128 — gpt-image-2 rendered #808080 essentially exactly).
2. Alpha 255 for all non-flood pixels except a 2px boundary ring (ramp + bg unmix).
3. Palette snap to exactly three tones: cream `#FFF8E7`, indigo `#3F51B5`, amber
   `#F7D68C` (sampled accent). Interior pixels classify by nearest palette color;
   ring pixels inherit the nearest interior label (prevents fringe).
4. Alpha feather 0.7px Gaussian; crop to bbox; square-pad; content scaled to 90%
   of a 512×512 canvas.

### 1.4 Downstream (android-engineer)

Ship as adaptive-icon foreground from this PNG (or vector-trace) + monochrome
layer (single-tone silhouette). Verified on light and dark composites; 48px zoom
check passed (crescent, collar, nipple all legible). SVG sources for the
recraft-svg candidates exist in `candidates/` if a vector base is ever wanted.

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
