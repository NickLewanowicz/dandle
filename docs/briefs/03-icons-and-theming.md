# Brief 03 — Iconography, semantic type colors, breast-side glanceability

**Status:** Approved by Maintainer. Owner directives: better icons (own or generated via Replicate, ≤$1 budget, no "AI-generated" vibe), semantic per-type colors for glanceable scanning (light+dark, system-default theme), and breast side (left/right/both) visible at a glance for feeding AND pumping.

## 1. Research findings (source-verified)

**Original app's per-type tints** (`.scratch/babybuddy-for-android/app/src/main/res/values/colors.xml`): diaper `#FFFFFFBB` (pale yellow), sleep `#FFBBBBFF` (periwinkle), feeding `#FFFFBBBB` (pale rose), pumping `#FFFF33CC` (magenta), tummy_time `#FFBBFFBB` (pale green). Owner's memory ("sleep blue, pumping purple, diaper green") ≈ this; we keep the same hue FAMILIES as the original for muscle-memory compatibility:

| Type | Hue family | Role intent |
|---|---|---|
| Sleep | Indigo/blue | calm, night |
| Feeding | Rose | warmth, food |
| Diaper | Amber/yellow | attention, quick |
| Pumping | Purple/magenta | distinct from feeding |
| Tummy time | Green | active, play |
| Note | Teal/neutral | misc |

**Breast side:** Feeding carries side in `method` (`left breast` / `right breast` / `both breasts`) — already on our wire and in the cascade, but NOT displayed at a glance (headline collapses to "Bottle · 120 mL" style only showing the *method*, which does include it when it's a breast method — verify; if displayed only as "Bottle", extend to the breast methods verbatim). **Pumping has NO side field server-side** (`core/models/pumping.py`: child/start/end/amount/notes only). Convention (new, ours): pumping side is stored as a `notes` prefix `[Left]` / `[Right]` / `[Both]` (with the prefix stripped from user-visible note text in timelines and rendered as a badge/indicator instead). Check how the old app handled pumping side (`.scratch/babybuddy-for-android` — search `TimerControllersV2.kt` / pumping save) and match or improve its convention; document whatever we do in the spec.

## 2. Icon decisions (Maintainer-approved)

1. **Action glyphs: hand-built vector XML, NOT generated raster.** Six 24dp glyphs (2dp strokes, rounded caps/joins, 20×20 live-area on 24 grid) + breast-side indicator variants. Rationale: single-color themeable vectors stay crisp at every density, can be tinted per-type (colors above), and read as native — generated raster icons at small sizes are exactly the "AI-generated app" look the owner rejects. Each glyph must be distinct at 24dp and at a glance in the timeline (silhouette test: squint — all six must differ).
2. **Launcher/app mark: generated via Replicate** (token at `~/Documents/Projects/ai-image-transformer/.env`, budget ≤ $0.25 of the $1). Prompt style: ONE simple rounded shape (crescent moon cradling a bottle, or similar single-motif mark), flat 2-tone (indigo + cream), centered on a SOLID single-color background (for clean removal), no text, no gradients-with-noise, no photorealism. Generate 4–6 candidates, pick one, remove background, ship as adaptive icon foreground (vector-trace or clean alpha PNG at 432px) + monochrome layer. The crescent+bottle monogram we shipped in Stage 5 is the fallback if generation looks generic.
3. **Widget/app backgrounds: NO generated assets.** Restraint is the brand; the system widget-surface token (W5 fix) is correct. Animated assets: none.
4. **Icon semantics for side:** feeding-on-breast glyphs get a small L / R / L+R dot-badge; pumping glyphs same. In the timeline the badge sits bottom-trailing of the 24dp glyph at ~12dp.

## 3. Color rules

- Type hues are **information, not decoration: they do NOT follow dynamic color.** Dynamic (wallpaper) theming stays for the app chrome; the six type colors are fixed hue families with light/dark M3 tone pairs (container ≈ tone 90, on-container ≈ tone 30 in light; inverted in dark) meeting ≥4.5:1 for text, ≥3:1 for icon-on-container.
- Timeline rows: leading glyph chip tinted with the type's container color (the scan layer); no full-row tinting.
- Panels/quick-action buttons: type-colored icon + subtle container, NOT filled blocks (restraint; the original used pale tints — we use M3 containers).
- Widgets: type color only on the running-chip accent and small leading icons; keep legibility rules from W5.
- Theme follows SYSTEM (already true); static palette is the source for the six pairs; add a `ThemePaletteTest` entry per type pair.

## 4. Deliverables & order

1. Asset agent: launcher mark via Replicate + background removal + adaptive icon; vector glyph set (six + side badges) as SVG→Android XML; document in `docs/design/icons.md` with the grid rules and the generation prompts used + spend.
2. Engineer: apply glyph vectors, per-type color pairs (`TypeColors.kt` + palette test), timeline/panel/widget tinting, Feeding headline+icon side display, Pumping side selector→notes-prefix→badge display (spec §3.5/§4.1/§5 updates, v0.4), capture-flows re-run (PNGs in same commit).
3. Verify: capture suite `--assert` green; maintainers view regenerated PNGs.
