# EMR configuration design: handoff pack for Claude Code

What is here:

- `SCREENS.md`: index of the 17 screens, build order, how to read the source files, design tokens.
- `CLAUDE_CODE_PROMPT.md`: the prompt to paste into Claude Code.
- `screens/`: one full-length screenshot per screen.
- `source/`: the design source file for each screen.
- `EMR-Configuration-Smart-Clinic.html`: all screens in one file. Open in a browser to click through.

How to use it:

1. Copy this folder into the application repository as `docs/design/emr-config/` and commit it.
2. Open Claude Code at the repository root and paste the prompt from `CLAUDE_CODE_PROMPT.md`.
3. Review the `PLAN.md` it writes before letting it build.

Add the EMR configuration BRD to the same folder if you have it. The design shows how it looks; the BRD says what the rules are.
