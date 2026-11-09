# Frontend

This project was generated using [Angular CLI](https://github.com/angular/angular-cli) version 22.2.5.

## Development server

To start a local development server, run:

```bash
ng serve
```

Once the server is running, open your browser and navigate to `http://localhost:4200/`. The application will automatically reload whenever you modify any of the source files.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the project run:

```bash
ng build
```

This will compile your project and store the build artifacts in the `dist/` directory. By default, the production build optimizes your application for performance and speed.

## Running unit tests

To execute unit tests with the [Vitest](https://vitest.dev/) test runner, use the following command:

```bash
ng test
```

## Running end-to-end tests

For end-to-end (e2e) testing, run:

```bash
ng e2e
```

Angular CLI does not come with an end-to-end testing framework by default. You can choose one that suits your needs.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.

## Homepage ambient background

The public home page (`/`) has a quiet dot texture behind it: a grid of small grey dots. With a mouse, dots near the pointer split into four small coloured points and settle back. It is a decorative layer (story SAH-39, decision DEC-024). It appears on the **home page only**: not in the app shell, not on sign-in or sign-up, and not on any signed-in screen.

Code: `src/app/shared/ui/kinetic-background/` (`app-kinetic-background`, mounted once in `HomePage`). Colours are the theme tokens `--bg-base`, `--dot-base`, `--node-a`, `--node-b`, `--node-c`, `--node-d` in `src/styles/_tokens.scss`, read at run time with `getComputedStyle`. Do not add the component to the shell or to another page, and do not add a library for it.

How a block on the home page takes part:

- **`data-surface`**: the pointer over this element, or anything inside it, never splits dots. Put it on text blocks, cards, forms (the enquiry form), the FAQ, the pricing cards and the compare tables. Dots that are already split return by themselves. Anything inside `.cdk-overlay-container` (the enquiry dialog and its backdrop) counts as a surface without an attribute.
- **`data-dot-exclude`**: dots within 120 px of this element (measured from its edge, with a smooth curve) fade out, and so do split points there. Put it on the header and navigation only. The zone is measured again on resize, when the element changes size (`ResizeObserver`) and once per frame after a scroll.
- **`.surface-solid`** (solid white) and **`.surface-glass`** (white at 85% with a 16 px blur; solid white where the browser cannot blur): use on a block that holds text over open background, so no text sits on the dots. They are painted by a `::before` pseudo-element that sticks out 12 px sideways and 10 px up and down, so they add no size and no text moves. Use `.surface-solid` for any block that has `--ink-3` text (eyebrows, small grey text, the footer): only solid white keeps 4.5:1. Use `.surface-glass` only for `--ink`, `--ink-2` and `--pri-text` text (the hero block). Do not put the class on an element that scrolls, is already a stacking context with its own background, or already uses `::before`: wrap it in a plain `<div>` and put the class on the wrapper. Cards that already have `.surf` need neither class. Both classes are for the home page only.

Behaviour you should know:

- The layer is a fixed canvas with `pointer-events: none`, `aria-hidden="true"`, a radial mask and `z-index: 0`. The home content (`.pubwrap`) has `z-index: 10; isolation: isolate`. Material overlays stay above both.
- Touch devices (no `pointer: fine`) and `prefers-reduced-motion: reduce` get the still grid only: no pointer listener and no animation frame. The setting is followed live, without a reload. The dark theme draws nothing (no dark tokens yet), but the hero panel `.surface-glass` has a dark value (`--glass-surface`, same as `--glass-hi`) so its light text stays readable. In still mode (touch or reduced motion) the `data-dot-exclude` zone is measured on resize, not on scroll: the dots near the header can be up to about 6 px off after a scroll (accepted).
- The animation frame loop stops when every dot is at rest and restarts on a pointer move, scroll or resize. The pixel ratio is at most 2. Nothing runs in Angular change detection: do not use `@HostListener` or template event bindings here.
- The host carries `data-state="off|still|idle|running"` for tests and tools. It is written with `setAttribute` only when the state changes.
- Look of a split: the dot fades out as the split grows, the four points (top-left `--node-a`, top-right `--node-b`, bottom-left `--node-c`, bottom-right `--node-d`) have radius 1.25 px times the split and sit 11 px times the split away on the diagonals. Their opacity is the token alpha times the split (the story left this open; this is the default chosen). Near `data-dot-exclude` the opacity is also multiplied by the fade curve.
- Inputs: `interactionRadius` (90, never more than 90), `gridSpacing` (28), `friction` (0.75), `enabled` (true). The `KineticBackgroundConfig` interface and the constants are in `kinetic-background.config.ts`.

## Fonts (self-hosted)

The app uses Figtree, Noto Sans Devanagari and Caveat from its own origin. No request goes to Google (story SAH-41, DPDP).

- The woff2 files are in `src/assets/fonts/<family>/` and are declared in `src/styles/_fonts.scss`. The `url()` paths are relative, so the build hashes the files into `media/`. A changed file gets a new name and browsers fetch it.
- The licences (SIL OFL 1.1) are in `src/assets/fonts/<family>/OFL.txt`. The build ships them to `fonts/licenses/<family>/OFL.txt` (`angular.json` assets).
- To update a font, follow `src/assets/fonts/README.md`. It lists the source, version and SHA-256 of every file.
- Never add a Google Fonts link or a Google font host back. `src/styles/no-third-party-fonts.spec.ts` fails if it finds one in `src`.
- The production server must send `Cache-Control: public, max-age=31536000, immutable` for `/media/*` and `no-cache` for `index.html` (go-live condition GL-41-1, `docs/architecture/SAH-41-self-hosted-fonts.md` section 7).
