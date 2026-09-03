# Neurix product-tour video pipeline

Everything needed to re-record `docs/video/Neurix-Product-Tour.mp4` after the UI changes.
The tour drives the running app (Docker, `http://localhost:8082`) with Playwright, records a
1080p screencast, overlays an in-page cursor, captions and chapter cards, then mixes an
English neural voice-over with a royalty-free procedural music bed.

## One-time setup

```bash
cd scripts/promo-video
npm install
npx playwright install ffmpeg      # tiny helper Playwright needs for screen recording
```

Chrome must be installed (the scripts use `channel: 'chrome'`, no browser download).
Edge neural TTS needs internet access.

## Steps

| Step | Command | What it does |
|------|---------|--------------|
| 1 | `npm run seed` | Creates the isolated **Neurix Demo** team (admin `demo.admin`, 6 agents, 3 projects, custom fields, ~30 entries with attachments, backdated over 30 days). Idempotent. |
| 2 | `npm run docs` | Generates the sample PDFs / scanned pages used as attachments. |
| 3 | `npm run music` | Renders `music.wav` (about 5 minutes, 100 BPM, Am–F–C–G). |
| 4 | `npm run tts` | Synthesises one clip per scene from `narration.mjs` (cached by text hash). |
| 5 | `npm run record` | Records the tour → `rec/tour.webm` + `timeline.json`. `DRY=1 SPEED=0.3 npm run record` runs a fast dry run without video. |
| 6 | `npm run assemble` | Mixes narration + ducked music, encodes H.264, writes the poster frame. |

Scene order, captions and voice-over text live in `narration.mjs`; the actions per scene live
in `tour.mjs`; the overlay (cursor, captions, cards, zoom) lives in `lib.mjs`.

Playwright's recorder clock drifts slightly against wall time. Measure the chapter-card
positions in the raw recording and pass them to the assembler as anchors, e.g.

```bash
ANCHORS='[[0,0],[26.8,26.4],[169.2,165.4],[288.6,280.8],[363.8,358.2],[381.3,380.2]]' npm run assemble
```

Demo credentials created by the seed: `demo.admin / DemoAdmin#2026`, agents `omar.hassan`,
`layla.ahmed`, `youssef.ali`, `nour.ibrahim`, `karim.fathy`, `mariam.said` with `Agent#2026`.
Delete the **Neurix Demo** team from Super Admin → Teams to remove everything.
