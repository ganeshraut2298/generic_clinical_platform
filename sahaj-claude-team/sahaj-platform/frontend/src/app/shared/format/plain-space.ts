/**
 * Newer ICU data puts U+202F (narrow no-break space) or U+00A0 between the time and AM/PM. The UI
 * text is "11:05 AM" with a plain space (DEC-025 G-15), so tests and screen readers see one form.
 */
export function plainSpaces(text: string): string {
  return text.replace(/[\u202F\u00A0]/g, ' ');
}
