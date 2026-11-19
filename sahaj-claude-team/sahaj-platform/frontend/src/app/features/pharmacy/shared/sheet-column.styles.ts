/**
 * Component styles that make a sheet a column no taller than the screen: the header and the footer stay
 * in place and the body (`.sheet-b`) scrolls, so Save and Cancel are always in reach (QA-78-5).
 * Material's dialog pane has no height cap of its own, so on a 360 x 740 phone a tall sheet would run
 * past the screen and not scroll. Put this last in `styles` of the dialog component, so it wins over
 * the `display: block` of its own `:host`. The 92 matches the phone `.sheet` rule of the console styles.
 */
export const SHEET_COLUMN_STYLES = `
  :host {
    display: flex;
    flex-direction: column;
    min-height: 0;
    max-height: 92vh;
    max-height: 92dvh;
  }
  .sheet-h,
  .sheet-f {
    flex: none;
  }
  .sheet-b {
    flex: 1 1 auto;
    min-height: 0;
    overflow-y: auto;
    overscroll-behavior: contain;
  }
`;
