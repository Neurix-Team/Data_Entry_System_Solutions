# Neurix product-tour video pipeline

Everything needed to re-record the product tour videos after the UI changes:

| Edition | Output | Narration |
|---------|--------|-----------|
| English | `docs/video/Neurix-Product-Tour.mp4` | `narration.en.mjs`, voice `en-US-AndrewMultilingualNeural` |
| Egyptian Arabic | `docs/video/Neurix-Product-Tour-AR.mp4` | `narration.ar.mjs`, voice `ar-EG-SalmaNeural`, Arabic captions and chapter cards (RTL, Cairo font) |

The tour drives the running app (Docker, `http://localhost:8082`) with Playwright, records a
1080p screencast, overlays an in-page cursor, captions and chapter cards, then mixes a neural
voice-over with a royalty-free procedural music bed. The UI itself stays in English in both
editions (the `theme` scene flips it to Arabic for a moment); only the narration, captions and
cards change with the language.

## One-time setup

```bash
cd scripts/promo-video
npm install
npx playwright install ffmpeg      # tiny helper Playwright needs for screen recording
```

Chrome must be installed (the scripts use `channel: 'chrome'`, no browser download).
Edge neural TTS and the Arabic web font need internet access.

## Picking the edition

Every step reads `NX_LANG` (`en`, the default, or `ar`). In bash: `NX_LANG=ar npm run tts`;
in PowerShell: `$env:NX_LANG='ar'; npm run tts`. Per-language files are kept apart
(`tts/<lang>/`, `narration_meta.<lang>.json`, `out/Neurix-Product-Tour[-AR].mp4`), so both
editions can be produced from the same recording session.

## Steps

| Step | Command | What it does |
|------|---------|--------------|
| 1 | `npm run seed` | Creates the isolated **Neurix Demo** team (admin `demo.admin`, 6 agents, 3 projects, custom fields, ~30 entries with attachments, backdated over 30 days). Idempotent. |
| 2 | `npm run docs` | Generates the sample PDFs / scanned pages used as attachments. |
| 3 | `npm run music` | Renders `music.wav` (172 bars ≈ 6:53 at 100 BPM, Am–F–C–G). The Arabic narration is longer: `MUSIC_BARS=240 npm run music` (≈ 9:36). |
| 4 | `npm run tts` | Synthesises one clip per scene from `narration.<lang>.mjs` (cached by text hash). |
| 5 | `npm run record` | Records the tour → `rec/tour.webm` + `timeline.json`. `DRY=1 SPEED=0.3 npm run record` runs a fast dry run without video. |
| 6 | `npm run anchors` | Finds the chapter cards in the raw recording and prints the `ANCHORS` the assembler needs. |
| 7 | `npm run assemble` | Mixes narration + ducked music, encodes H.264, writes the poster frame. |

Scene order, captions, voice-over text and card copy live in `narration.<lang>.mjs`; the actions
per scene live in `tour.mjs`; the overlay (cursor, captions, cards, zoom) lives in `lib.mjs`.

Playwright's recorder clock drifts slightly against wall time. `npm run anchors` measures the
chapter-card positions in the raw recording; pass its output to the assembler, e.g.

```bash
ANCHORS='[[0,0],[26.8,26.4],[169.2,165.4],[288.6,280.8],[363.8,358.2]]' npm run assemble
```

Demo credentials created by the seed: `demo.admin / DemoAdmin#2026`, agents `omar.hassan`,
`layla.ahmed`, `youssef.ali`, `nour.ibrahim`, `karim.fathy`, `mariam.said` with `Agent#2026`.
Delete the **Neurix Demo** team from Super Admin → Teams to remove everything.
