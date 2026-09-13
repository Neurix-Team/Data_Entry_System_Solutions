# Bundled fonts

Both faces are self-hosted so the landing page renders correctly on an
air-gapped or locked-down deployment, with no request to a font CDN.

| File | Family | Axes | Used for |
|---|---|---|---|
| `archivo-latin.woff2` | Archivo | `wght` 400–800, `wdth` 75–125 | Latin display, stencil labels and body on the landing page |
| `readexpro-arabic.woff2` | Readex Pro | `wght` 300–700 | Arabic, via a `unicode-range` subset |

Both are licensed under the **SIL Open Font License 1.1** — see `OFL.txt`
(Archivo, Omnibus-Type). Readex Pro is released under the same licence by
its authors. The licence requires that this notice travel with the font
files, so keep `OFL.txt` alongside them if these are ever moved or copied.

Each file is a Latin-only or Arabic-only subset as served by Google Fonts,
which is why they are small (90 KB and 23 KB). Re-subsetting or replacing
them means re-checking `font-variation-settings` in
`src/pages/landing/landing.css` — the stencil, plate and display voices all
depend on the real `wdth` axis being present.
