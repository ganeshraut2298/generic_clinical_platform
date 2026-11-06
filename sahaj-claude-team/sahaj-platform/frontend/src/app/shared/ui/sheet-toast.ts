import { MatSnackBarConfig } from '@angular/material/snack-bar';

/** The width up to which a sheet opens as a bottom sheet (`_console.scss`, `.sheet-panel`). */
export const BOTTOM_SHEET_MAX_WIDTH = 620;

/**
 * Snack bar settings for a toast raised while a sheet is open. On a phone the sheet is a bottom
 * sheet and its footer buttons sit at the bottom edge, exactly where the default toast is drawn; a
 * nurse who retries within the toast's time would tap the toast. There the toast goes to the top.
 * Wider screens keep the default place (bottom, centred).
 *
 * `announce: false` sets the snack bar's live region to off: the toast stays visible, but a screen
 * reader does not read it. Use it where the same message is already announced by an alert or a live
 * line in the sheet (one refusal would otherwise be read up to three times).
 */
export function sheetToast(duration: number, options: { announce?: boolean } = {}): MatSnackBarConfig {
  const phone =
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia(`(max-width: ${BOTTOM_SHEET_MAX_WIDTH}px)`).matches;
  const config: MatSnackBarConfig = { duration, verticalPosition: phone ? 'top' : 'bottom' };
  return options.announce === false ? { ...config, politeness: 'off' } : config;
}
