# 70 — Widget UX review (both home-screen widgets)

**Reviewer:** ux-designer (AI) · **Date:** 2026-10-17 · **Build:** `net.babybuddy.next.debug` on emulator-5554 (Pixel 5, API 33 launcher, 1080×2280 @ 2.625d)
**Method:** visual inspection first — fresh screenshots of the live launcher, widget picker, and tap flows (all viewed this session); Glance code read afterwards to pin root causes. Pixel measurements taken from `docs/screenshots/ux-review/01-home-fresh.png`.

**Evidence (this session):** `docs/screenshots/ux-review/01-home-fresh.png` (placed widgets, running timer), `02-fresh-qa-close.png`, `03-fresh-dash-close.png` (close-ups), `04-tap-running-slot.png` (running-slot tap → app), `05-qa-longpress-menu.png` (long-press), `06/07-qa-resize-*.png` (resize attempts), `10/11/12-widget-picker-*.png` (picker + previews). Prior art re-viewed: `00-handoff/final-home-screen.png`, all of `new-app/widgets/` and `new-app/70-fixes/`.
**Resize note:** launcher resize could not be driven via adb in 2 attempts per widget (synthesized swipe does not grab the resize handles; placement unchanged, nothing broken). Space efficiency is therefore evaluated at the currently-placed size, plus reasoning from the size-mode code for other placements.

**Measured facts (from 01):** QuickActions frame ≈ 268×187dp; running Sleep accent slab = 66dp × 187dp (one full cell), square-cornered, `#414659`; icon content occupies only ~26dp of the 187dp height (~85% dead space); CareDashboard running bar `#44455A` spans x 332..1000 with text starting ~1.5dp from each edge; all dashboard text floats on the wallpaper (no widget background).

---

## Findings

### W1 · P1 — Default placement is 3×2, and at that size the spec'd 2×2 grid never renders
**Evidence:** `11-widget-picker-previews.png` (picker labels it "Quick actions 3 × 2"), `01-home-fresh.png` (placed widget ~268dp wide renders a single 4-across icon row).
**Root cause:** `app/app/src/main/res/xml/quick_actions_widget_info.xml:3` — `minWidth="180dp"` exceeds what 2 launcher cells offer (~164dp on Pixel 5), so the minWidth floor beats `targetCellWidth="2"` (verified present in the installed APK via aapt2) and placement lands at 3 columns. At ~268dp ≥ 240dp, `QuickActionsWidget.kt:91` (`wide = width >= SIZE_WIDE.width`) selects the wide single-row branch. Net: on a default placement the user never sees the 2×2 grid the UX spec (§6/§7.1) promises — labels are gone, the widget is four dots in a big frame.
**Fix spec:** set `android:minWidth="110dp"` (fits 2 cells with margin; `targetCellWidth/Height=2` stays). Re-bucket the responsive set to real placement widths — see W2.

### W2 · P1 — Space efficiency: the single-row layout stretches to fill a 2-row-tall frame (~85% dead space)
**Evidence:** `01-home-fresh.png`, `02-fresh-qa-close.png` — four 26dp glyphs vertically centered in a 187dp-tall widget; nothing else.
**Root cause:** `QuickActionsWidget.kt:68` — `SizeMode.Responsive(setOf(SIZE_NARROW(150×110), SIZE_WIDE(240×110)))` buckets on width only; `QuickActionsWidget.kt:138-143` — the wide branch is `Row(fillMaxSize)` with `defaultWeight` cells, so a row layout stretches to whatever height the host gives it.
**Fix spec (responsive contract per placed size):**
- **2×2 (narrow, ~110-180×110-200dp):** the spec grid — 2×2 cells, 24dp glyph + 12sp label each, per-cell rounded surface chips (W5/W12), tap targets ≥ 48dp.
- **4×1 / 3×1 (wide-short, height ≤ ~130dp):** one 4-across row; keep labels if height ≥ 90dp, else icon-only with the running dot; **cap the row's height to content** (`Row` + `padding(vertical=8.dp)`, no `fillMaxSize`) and center it vertically — never stretch.
- **2×3 / 3×3 / taller (height > 200dp at narrow width):** narrow grid, vertically centered, cells capped at ~72dp height with even spacing — do not stretch cells.
- Selection rule: pick the branch by **both** axes (`LocalSize` width AND height), not width alone (`QuickActionsWidget.kt:91`).

### W3 · P1 — Running-state accent renders as a giant square slab, not a state chip
**Evidence:** `01-home-fresh.png`, `02-fresh-qa-close.png` — 66×187dp `#414659` rectangle (one full cell height), moon icon floating mid-slab; `quick_actions_sleep_running.png` (same, prior session).
**Root cause:** `QuickActionsWidget.kt:171-179` — the running modifier applies `background(secondaryContainer)` to a `fillMaxSize` cell, so the accent paints the entire stretched cell.
**Fix spec:** the running cell keeps the same chip geometry as idle cells (see W5) and swaps its fill: `background(GlanceTheme.colors.primaryContainer)` on a **rounded 12dp chip** (`GlanceModifier.background` + `cornerRadius` via `androidx.glance.unit`/`RoundedCorner`-style sizing — in Glance: `backgroundImageProvider(color, cornerRadius = 12.dp)`), height capped to chip content (~64dp), 4dp outer gap between chips. Accent color per spec §7.1 is **primaryContainer**, not secondaryContainer — matches the app's teal running buttons (`04-tap-running-slot.png`).

### W4 · P1 — Elapsed time is never shown on the running slot
**Evidence:** `01-home-fresh.png` — timer running 7h15m; the widget's sleep cell shows only the moon glyph (the dashboard shows "Running: Sleep 7h 15m"). Spec §6/§7.1: *"elapsed replaces the label ('12m')"*.
**Root cause:** `WidgetStateMapper.kt:60` computes `elapsedText`, but `SlotCell` (`QuickActionsWidget.kt:181-199`) composes only `Image` + (non-compact) `Text(slot.label)` — `slot.elapsedText` is dead code; in wide mode even the label is dropped, so a running slot communicates nothing but the slab.
**Fix spec:** in `SlotCell`, when `slot.running`, render `Text(slot.elapsedText ?: label)` instead of the label (both modes; 12sp, `onPrimaryContainer`). If `elapsedText` is null (state race), fall back to the label — never an empty chip.

### W5 · P1 — Neither widget paints a background; legibility hangs on the wallpaper
**Evidence:** `01-home-fresh.png`, `03-fresh-dash-close.png` — white dashboard text and flat icons sit directly on the wallpaper; QuickActions idle icons likewise. The launcher's rounded-rect widget mask has nothing to mask.
**Root cause:** no `GlanceModifier.background(...)` on either root (`QuickActionsWidget.kt:132-134`, `CareDashboardWidget.kt:116-119`); `GlanceTheme.colors.widgetBackground` is unused anywhere in `widget/`. Text uses `onSurface` resolved from the **system** theme — dark-theme white text survives only on dark wallpapers; light theme on a dark wallpaper would be unreadable, and vice versa.
**Fix spec:** root of both widgets: `GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground)` (auto light/dark + dynamic color, identical to Google's own widgets). QuickActions idle cells: per-cell chips in `widgetBackground`-on-`surface`-contrast, i.e. `surfaceVariant`-equivalent (or `surfaceContainerHigh`) rounded 12dp, so cells read as buttons on any wallpaper — this is also what UX spec §7.1 already promises ("surface-colored rounded background"). Verify both system themes before sign-off.

### W6 · P1 — CareDashboard running row: square full-bleed bar, zero horizontal padding, wrong accent role
**Evidence:** `01-home-fresh.png` (measured: bar `#44455A` x 332..1000; glyph starts x 336, detail text ends x 996 — ~1.5dp inset each side).
**Root cause:** `CareDashboardWidget.kt:158-163` — modifier order `padding(vertical=2.dp).then(background)` paints the accent edge-to-edge with no horizontal padding and square corners; line 162 uses `secondaryContainer` though spec §7.2 says primaryContainer; text/icon keep `onSurface` (lines 167-181) instead of `onPrimaryContainer`.
**Fix spec:** running row = `backgroundImageProvider(colors.primaryContainer, cornerRadius = 12.dp)` applied **before** `padding(horizontal = 12.dp, vertical = 6.dp)`; icon + both texts tinted `onPrimaryContainer`; keep row height ≥ 40dp (tap target). Idle rows stay transparent on the widget surface (W5) — no per-row chips needed.

### W7 · P2 — Widget-picker previews show the loading layout (white grid + monogram)
**Evidence:** `11-widget-picker-previews.png`, `12-widget-picker-dashboard-preview.png` — both cards are the `glance_default_loading_layout` (white panel, empty cell grid, app monogram).
**Root cause:** neither provider XML sets `previewLayout` or `previewImage` (`quick_actions_widget_info.xml`, `care_dashboard_widget_info.xml` — verified in source and in the installed APK); the picker falls back to `initialLayout`.
**Fix spec:** add two static preview layouts under `app/src/main/res/layout/` (plain `RemoteViews`-compatible XML, no Glance): `preview_quick_actions.xml` (2×2 chips + labels, one cell in primaryContainer with "12m") and `preview_care_dashboard.xml` (child name + 4 rows + accented running row). Wire `android:previewLayout="@layout/…"` in both provider XMLs (API 31+) and generate `android:previewImage` PNGs from those layouts for API ≤ 30. Render them from our own icons — no original-app assets.

### W8 · P2 — CareDashboard is missing the child-name header (spec §7.2)
**Evidence:** `01-home-fresh.png`, `03-fresh-dash-close.png` — first visible line is "Last feed"; nothing says whose data it is (two children exist: Milo, Zoe).
**Root cause:** `CareDashboardWidget.kt:114-146` — `CareDashboardContent` renders `state.rows` directly; no header composable. The mapper has the child id but the name is never fetched.
**Fix spec:** first line `Text(childName, 11sp, colors.onSurfaceVariant, FontWeight.Medium)` with 2dp bottom padding; keep ≤ 4 rows + running row below it. Config already stores `KEY_CHILD_ID` — resolve the name in `provideGlance` from the child repository snapshot.

### W9 · P2 — "Last pump 9h · 0 mL": amount zero reads as data
**Evidence:** `01-home-fresh.png` ("Last pump 9h · 0 mL"); the seeded entry is the known failed-sync pump with amount 0 (also visible in-app as "Pump · 0 mL", `p1-2-day-groups.png`).
**Root cause:** `WidgetStateMapper.kt:104-106` — `detailText` emits "0 mL" for any non-null amount, including 0.0.
**Fix spec:** treat `amount == null || amount <= 0.0` as unknown → return `null` (row shows just "9h"). Apply the same guard in the app's timeline row builder so app and widget agree.

### W10 · P2 — Configure is unreachable after placement
**Evidence:** `05-qa-longpress-menu.png` — long-press shows resize handles only; no Configure entry.
**Root cause:** neither provider XML sets `android:widgetFeatures="reconfigurable"` (source + installed APK) — the launcher only offers re-configuration when the flag is present. Spec §7.3 promises "Long-press → Configure".
**Fix spec:** add `android:widgetFeatures="reconfigurable"` to both `appwidget-provider` elements. Keep the config-at-placement behavior as is.

### W11 · P2 — CareDashboard ignores resize (single fixed layout)
**Evidence:** structural — resize attempt not automatable (`06/07-qa-resize-*.png` note above); `CareDashboardWidget.kt` has no `sizeMode` override, so one layout serves 4×2 and 4×3; spec §7.2 promises "4×3: more breathing room and full detail lines".
**Fix spec:** `SizeMode.Responsive(setOf(DpSize(250.dp, 110.dp), DpSize(250.dp, 180.dp)))`; compact = current metrics; relaxed = row vertical padding 6dp, label 14sp, detail 13sp, icon 20dp. Both branches keep the W5/W6 background and accent treatment.

### W12 · P3 — Icon system inconsistent across surfaces
**Evidence:** `01-home-fresh.png` (26dp flat glyphs, QA) vs `03-fresh-dash-close.png` (visually smaller dashboard glyphs) vs `04-tap-running-slot.png` (app uses filled circular chips).
**Root cause:** `CareDashboardWidget.kt:169` — `size(18.dp).padding(end = 2.dp)` shrinks the image box to 16dp (padding inside the sized node); QuickActions uses 26dp untinted-background glyphs.
**Fix spec:** 24dp glyphs in both widgets; remove the inner-padding hack (use `Row` spacing instead); idle tint `onSurface`, running tint `onPrimaryContainer`. Flat glyphs are fine on the widget surface once W5 gives them a background — do not introduce circles in the widget (the app's chips are an app-side pattern).

### W13 · P3 — QuickActions label typography below the legibility floor
**Evidence:** `quick_actions_placed.png` (11sp labels visible at 2-col placement in an older build); current 3×2 placement drops labels entirely (W1/W2).
**Root cause:** `QuickActionsWidget.kt:193` — `fontSize = 11.sp`. Spec §7.2 sets "minimum body-size text" (12sp) for glanceable-at-arm's-length surfaces.
**Fix spec:** 12sp Medium in compact branches, 14sp when the cell is ≥ 72dp tall; single line, ellipsize.

### W14 · P3 — Staleness wording on hour-old rows (recorded, no beta change)
**Evidence:** `01-home-fresh.png` — "Last feed 7h · 120 mL", "Running: Sleep 7h 15m". Minute-granular staleness is documented as acceptable (spec §7.2) and refresh triggers (`WidgetRefresher.kt`, 15-min periodic job) are correct.
**Optional polish:** suffix "ago" on rows ≥ 1h ("7h ago · 120 mL") so a stale value can't be misread as recent. Needs a Maintainer-approved brief only if pursued; **not** part of the beta bar.

---

## Target look

QuickActions @ 2×2 (idle / running):

```
┌───────────────────────────────┐   ┌───────────────────────────────┐
│  surface background (W5)      │   │                               │
│  ╭───────────╮  ╭───────────╮ │   │  ╭───────────╮ ╭───────────╮  │
│  │  (moon)   │  │  (bottle) │ │   │  │ ▒(moon)▒  │ │  (bottle) │  │
│  │  Sleep    │  │  Bottle   │ │   │  │ ▒▒12m▒▒▒  │ │  Bottle   │  │
│  ╰───────────╯  ╰───────────╯ │   │  ╰───────────╯ ╰───────────╯  │
│  ╭───────────╮  ╭───────────╮ │   │  ╭───────────╮ ╭───────────╮  │
│  │  (diaper) │  │  (pump)   │ │   │  │  (diaper) │ │  (pump)   │  │
│  │  Diaper   │  │  Pump     │ │   │  │  Diaper   │ │  Pump     │  │
│  ╰───────────╯  ╰───────────╯ │   │  ╰───────────╯ ╰───────────╯  │
└───────────────────────────────┘   └───────────────────────────────┘
   cells: surfaceVariant chips,       running cell: primaryContainer
   12dp corners, 4dp gaps,            chip, elapsed replaces label,
   24dp glyph + 12sp label            rest identical
```

QuickActions @ 4×1 (wide-short) — content capped, centered, never stretched:

```
┌─────────────────────────────────────────────────────┐
│            ╭────────╮ ╭────────╮ ╭────────╮ ╭──────╮│
│            │(moon)  │ │(bottle)│ │(diaper)│ │(pump)││   ≤ 130dp tall:
│            │ Sleep  │ │ Bottle │ │ Diaper │ │ Pump ││   labels kept
│            ╰────────╯ ╰────────╯ ╰────────╯ ╰──────╯│
└─────────────────────────────────────────────────────┘
   tighter heights: icon-only chips + running dot/elapsed
```

CareDashboard @ 4×2:

```
┌─────────────────────────────────────────────────────┐
│ Milo                                                │  11sp onSurfaceVariant (W8)
│ (bottle) Last feed                    12m · 120 mL  │  13sp label / 12sp detail
│ (diaper) Last diaper                  34m · Wet     │
│ (moon)   Last nap                     2h · 45m      │
│ ╭─────────────────────────────────────────────────╮ │
│ │ (moon) Running: Sleep          12m — tap to stop│ │  primaryContainer chip, 12dp
│ ╰─────────────────────────────────────────────────╯ │  corners, 12dp h-padding (W6)
└─────────────────────────────────────────────────────┘
   widget background = GlanceTheme.colors.widgetBackground (W5)
```

---

## How to verify after the fixes

1. `adb uninstall` → fresh install → place both widgets from the picker: QuickActions must offer **2 × 2** and previews must show the real layouts (`11/12-widget-picker-*.png` re-shots).
2. Long-press each placed widget: a Configure entry must exist (`05` re-shot).
3. Start a sleep timer in-app, return to the launcher: QuickActions sleep chip shows primaryContainer + elapsed ("1m"), dashboard shows the accented rounded running row; screenshot light AND dark system themes.
4. Resize QuickActions to 4×1: one centered icon row with labels, no stretched slab; resize dashboard to 4×3: breathing-room layout.
5. Re-run widget checklist entries 9–10 (`docs/qa/FEATURE-CHECKLISTS.md`) and add a light-wallpaper/theme-variant step to them.

## Beta verdict

**Not acceptable as-is.** W1-W6 are the beta bar: the default placement never renders the spec'd layout, ~85% of the QuickActions area is dead space, the running state paints a bug-looking slab with no elapsed time, and both widgets' legibility depends on the user's wallpaper. W7-W11 (previews, child header, reconfigurable, resize awareness, 0 mL) should land in the same pass — all are small, local changes. W12-W14 follow.

---

## Resolution (Maintainer, 2026-10-17)

W1–W13 implemented (commits `af4930d`, `e78dea3`, evidence `eaa94b7`; screenshots in `docs/screenshots/new-app/80-widget-fixes/`), W14 deferred. The product owner's mid-pass P1 (massive inner padding + truncated "Bott…"/"Diap…" labels — root cause: on API 33+ Glance only applies layout params from explicit modifiers, so rows without explicit width collapsed weighted cells to content) was fixed in `e78dea3`: every row carries `fillMaxWidth`, chips `fillMaxSize` in weighted cells, 4dp outer padding. Maintainer visually verified: 2×2 fills cleanly with full labels; running slot renders a rounded primaryContainer chip with live elapsed; CareDashboard shows the child header, no "0 mL" rows, rounded running row. 187 tests green. Documented deviations: running-row padding 8dp (tap-target floor), relaxed-bucket icons 24dp (consistency with W12), API <31 corner radii square (Glance limitation). Resize-by-automation remains impossible via adb; wide/tall buckets are unit-tested but not screenshot-verified on device.
