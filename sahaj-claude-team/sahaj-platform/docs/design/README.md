# Design source of truth: the Sahaj mockup

`docs/design/mockup/sahaj-mockup.html` is the approved interactive mockup (live copy: https://claude.ai/artifact/Hpsbpuy6PBHEJB6HFrfpxQ).
Open it in a browser. Switch role (Owner, Nurse, Front desk) at the top right, and Simple/Advanced mode in the sidebar.

**It is the reference for screens, flows, labels, roles and rules. It is not production code.**
- Rebuild screens in Angular + Angular Material using `tokens.md`. Do not copy its HTML, inline scripts or global state.
- Its data is example data. Its business rules (booking rules, tariff, EWS, MAR checks, entitlements) are the behaviour to implement on the server.
- If a story needs something the mockup does not show, or must differ from it, the business analyst raises it as an open question. A human decides and updates the mockup or the story.

Files:
- `screen-map.md` — every mockup screen, its module, phase, and how to open it.
- `tokens.md` — colours, type, radius, spacing and brand themes to map into the Angular Material theme.
- `mockup/sahaj-mockup.html` — the mockup itself (humans update it; agents only read it).
