# Design tokens from the mockup

Map these into the Angular Material theme (`frontend/src/styles/_tokens.scss` + a custom M3 theme). Components use tokens, never raw colours.

## Type and shape
- Font: Figtree, with Noto Sans Devanagari for Marathi and Hindi; fallback system-ui.
- Radius: cards and panels 18px (`--r`), inputs and small cards 12px (`--r-sm`), pills 999px.
- Default theme: light. Dark theme supported. Layout uses the full width of the work area.

## Colours (light / dark)

| Token | Light | Dark | Use |
|---|---|---|---|
| --bg | #ebf1f8 | #0a1322 | Page background |
| --ink | #0f1f3a | #e6edf8 | Main text |
| --ink-2 | #43546f | #a8b7cf | Secondary text |
| --ink-3 | #66778f | #8496b1 | Labels, hints |
| --pri | #1f5bc8 | #6e9ef2 | Primary (medical blue) |
| --pri-text | #1a4ca6 | #a2c1f8 | Primary text on soft backgrounds |
| --pri-soft | rgba(31,91,200,.11) | rgba(110,158,242,.17) | Selected and tinted backgrounds |
| --sec | #0e8a7e | #44c7b7 | Secondary (teal) |
| --good | #15804c | #52c98c | Success, normal values |
| --warn | #965a00 | #f1b24c | Check, due, medium risk |
| --crit | #c23131 | #ff8080 | Critical, allergy, overdue, high risk |
| --line | rgba(16,42,84,.12) | rgba(190,210,245,.13) | Borders |
| --glass-hi | rgba(255,255,255,.88) | rgba(22,38,66,.92) | Cards and panels |
| --paper | #fffdf8 | — | Printed case paper and bills |

## Brand themes (clinic picks one; only --pri changes)
Medical blue (default) #1f5bc8 · Teal #0b8782 · Green #1b8048 · Maroon #a3304b (dark-mode variants are in the mockup CSS).

## Status colours are clinical meaning, not decoration
Red = critical/allergy/overdue · Amber = check/due · Green = normal/done · Blue = info. Never rely on colour alone; always pair with text or an icon.
