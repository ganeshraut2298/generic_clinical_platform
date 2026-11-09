# DEC-026 · SAH-41: how the font files get into the repository

Date: 9 Nov 2026. Status: DECIDED (option 1). Blocks: SAH-41 development (stories and design are done).

## Context
- SAH-41 replaces the Google Fonts link with self-hosted files for Figtree, Noto Sans Devanagari and Caveat, so visitors' IP addresses are not sent to Google (DPDP; SAH-39 security Info #2).
- The design (docs/architecture/SAH-41-self-hosted-fonts.md, section 5) has the developer download Google's css2 response, the woff2 files and each OFL.txt.
- `.claude/settings.json` lists `Bash(curl *)` and `Bash(wget *)` under **deny**. A deny rule beats any allow rule, so adding `Bash(curl:*)` to an allow list does not help. The agent was refused twice. The human's chat answer "I'll allow the download" does not change the rules.
- The tech lead will not download for the agent; that would work around a refused permission.

## Options
1. **The human downloads the files into a folder and gives the path (recommended).**
   - Pros: no permission change; the deny rule stays; the design is unchanged.
   - Cons: about 5 minutes of the human's time. The files needed: Google's css2 response for the URL in index.html lines 13-18 (request it with a Chrome user agent so it returns woff2), every `fonts.gstatic.com` woff2 URL it lists for the latin, latin-ext and devanagari blocks, and `ofl/figtree/OFL.txt`, `ofl/notosansdevanagari/OFL.txt` and `ofl/caveat/OFL.txt` from github.com/google/fonts.
2. **The human removes `Bash(curl *)` from deny for one session, then puts it back.**
   - Pros: the developer does the whole download and records SHA-256 values itself.
   - Cons: it lifts a security guard for every agent while it is off, and the human must remember to restore it.
3. **Use the `@fontsource` npm packages instead of Google's files.**
   - Pros: no download by hand; the licences come in the package.
   - Cons: it adds a new library, which is an architecture change. `npm install` needs the human's prompt. The subsets may not match Google's byte for byte, so the design needs a revision.
4. **Park SAH-41.**
   - Cons: the Google Fonts privacy finding stays open; it is a DPDP go-live item.

## Recommendation
Option 1. The developer then starts at design section 5, step 2. It checks the files and the hashes, then wires them in.

## Human answer
9 Nov 2026, AskUserQuestion: "I'll download them (Recommended)". This is option 1: the human supplies the files in a folder and gives the path, and the deny rule stays. SAH-41 waits for the path.
