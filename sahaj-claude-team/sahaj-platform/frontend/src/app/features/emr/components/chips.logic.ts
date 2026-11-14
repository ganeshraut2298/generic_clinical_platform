/**
 * Rules of the chip rows (SAH-75-6, ADR-026 D6): a CHOICE field drawn as chips is a single-choice row (pressing
 * the chosen chip again clears it) and a MULTI_CHOICE field a many-choice row. An option with `isNone` clears the
 * others of the field, and any other chip clears it. Values stay what they were: CHOICE an option key,
 * MULTI_CHOICE an array of option keys.
 */

export interface ChipOption {
  readonly key: string;
  readonly isNone?: boolean;
  readonly hint?: string | null;
}

/** A chip field with at least one option hint draws as tiles (ADR-026 D13): label in bold, the hint below. */
export function drawsAsTiles(options: readonly ChipOption[]): boolean {
  return options.some((o) => typeof o.hint === 'string' && o.hint.trim() !== '');
}

/** Single choice: the chosen chip again clears the field. */
export function toggleSingle(current: string | null, key: string): string | null {
  return current === key ? null : key;
}

/** Many choices: toggles the chip; "None" clears the others and any other chip clears "None". */
export function toggleMany(options: readonly ChipOption[], current: readonly string[], key: string): string[] {
  if (current.includes(key)) {
    return current.filter((k) => k !== key);
  }
  const isNone = options.find((o) => o.key === key)?.isNone === true;
  if (isNone) {
    return [key];
  }
  const noneKeys = new Set(options.filter((o) => o.isNone).map((o) => o.key));
  return [...current.filter((k) => !noneKeys.has(k)), key];
}

/** "✓ " before a chosen chip's text. */
export function chipText(label: string, on: boolean): string {
  return on ? `✓ ${label}` : label;
}
