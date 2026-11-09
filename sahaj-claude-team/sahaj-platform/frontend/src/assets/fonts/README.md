# Self-hosted web fonts (SAH-41)

Sahaj serves its three font families from its own origin, so a visitor's IP address is not sent to Google (DPDP). The files are subset woff2 files exactly as Google Fonts served them for the URL below. They are declared in `src/styles/_fonts.scss` (relative `url()`, so the build hashes them into `media/`). The licences are in `<family>/OFL.txt` and ship to `fonts/licenses/<family>/OFL.txt`.

## Source

- Request used: the Google Fonts css2 URL: `css2?family=Figtree:wght@400;500;600;700&family=Noto+Sans+Devanagari:wght@400;500;600&family=Caveat:wght@500&display=swap`, with a desktop Chrome user agent so the response lists woff2 with `unicode-range`.
- Obtained: 9 Nov 2026. The human downloaded the response, the woff2 files and the licence texts into a local folder and gave them to the developer (decision DEC-026). The developer verified each file starts with the bytes `wOF2` and recorded the SHA-256 below. The duplicate copy of the Noto latin-ext file had the same SHA-256 as the original.
- Subsets shipped: latin, latin-ext, and devanagari (Noto only). Cyrillic blocks of Caveat were not used. The `unicode-range` values are Google's, copied unchanged.
- Subsetting and format conversion were done by Google, not by Sahaj. The font name tables are not edited. Only the local file names differ.

## Families

| Family | Weights | Version (URL path) | Upstream | Licence | Reserved Font Name |
| --- | --- | --- | --- | --- | --- |
| Figtree | 400, 500, 600, 700 | v9 | https://github.com/erikdkennedy/figtree | SIL OFL 1.1 | none declared in the OFL.txt header |
| Noto Sans Devanagari | 400, 500, 600 | v33 | https://github.com/notofonts/devanagari | SIL OFL 1.1 | none declared in the OFL.txt header |
| Caveat | 500 | v23 | https://github.com/googlefonts/caveat | SIL OFL 1.1 | none declared in the OFL.txt header |

The OFL 1.1 allows modified versions (subsetting, format conversion) when no Reserved Font Name is used for them. None of the three headers declares one.

## Files

Google serves one variable file per subset, so several weight blocks point to the same file.

| Local file | Original path (on the Google static font host) | SHA-256 | Bytes |
| --- | --- | --- | --- |
| `figtree/figtree-latin.woff2` | s/figtree/v9/_Xms-HUzqDCFdgfMm4S9DQ.woff2 | 4ba7d3d096695818fe0686be4f1e82c6b05134e18a22260336130335027462dd | 20156 |
| `figtree/figtree-latin-ext.woff2` | s/figtree/v9/_Xms-HUzqDCFdgfMm4q9DbZs.woff2 | bf7828e2c258cffcfa50a048ee388a36b95bc16b452e8d36fa797635dbe15965 | 10280 |
| `noto-sans-devanagari/noto-sans-devanagari-devanagari.woff2` | s/notosansdevanagari/v33/TuG7UUFzXI5FBtUq5a8bjKYTZjtRU6Sgv3NaV_SNmI0b8QQCQmHN5TV_9qo.woff2 | 6c354352c14e6c5507f8c0fc8985134407d09f12c1577f5706ca58e62fb4cd2f | 120056 |
| `noto-sans-devanagari/noto-sans-devanagari-latin.woff2` | s/notosansdevanagari/v33/TuG7UUFzXI5FBtUq5a8bjKYTZjtRU6Sgv3NaV_SNmI0b8QQCQmHN5DV_.woff2 | 9982931f6e9edbe1d1e1bec7b98b713dd386c7c8a0eaee2f8eaad1c9429f30ec | 25076 |
| `noto-sans-devanagari/noto-sans-devanagari-latin-ext.woff2` | s/notosansdevanagari/v33/TuG7UUFzXI5FBtUq5a8bjKYTZjtRU6Sgv3NaV_SNmI0b8QQCQmHN6jV_9qo.woff2 | 787d445aa5f02adcb61ded2fc341dd168afde6f35aa20a95c9135a54914c2e54 | 13576 |
| `caveat/caveat-latin-500.woff2` | s/caveat/v23/WnznHAc5bAfYB2QRah7pcpNvOx-pjcB9eIWpYQ.woff2 | edbfb1a768829d4e85127cd327e59faaec9701cb043e96430938f1f66acf009f | 50904 |
| `caveat/caveat-latin-ext-500.woff2` | s/caveat/v23/WnznHAc5bAfYB2QRah7pcpNvOx-pjcB9eIupYSxP.woff2 | ca0bf8aa25f03ca55b29a109e21fa49bfe8ad37a8e86425b0be225acfc956cff | 19592 |

The original paths are a record only. Nothing in the app requests them. This README sits inside `src`, so it must not contain the host names themselves: `src/styles/no-third-party-fonts.spec.ts` fails on them. Write paths, not full URLs, when you update it.

## How to update

1. Request the css2 URL above with a desktop Chrome user agent and save the response. Keep the saved css2 response outside `src` (and `public`), because it contains the Google host names and `src/styles/no-third-party-fonts.spec.ts` fails on them.
2. Download each unique woff2 URL for the latin, latin-ext and devanagari blocks. Keep Google's sharing of files across weights.
3. Replace the files in the family folder, record the new SHA-256 and bytes here, and copy the new `unicode-range` and file names into `src/styles/_fonts.scss`.
4. Check the licence header of each `OFL.txt` for a Reserved Font Name.
5. Run `npm --prefix frontend run build`. A changed file gets a new content hash in `dist/frontend/browser/media/`, so browsers fetch the new file.
