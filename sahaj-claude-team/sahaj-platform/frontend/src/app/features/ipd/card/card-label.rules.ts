import type { CardCatalogue, CardFieldKey, CardSetup } from './card-setup.model';

/**
 * The label rules of Clinic setup → Patient card, mirrored from the server so the owner hears about a
 * problem under the right box before anything is sent (SAH-37 design note 4.1 and 4.2, DEC-032 G-1 to G-3
 * and V15). The server stays the protection: it checks every rule again on Save draft and Publish and its
 * answer wins. Texts are exactly the server's texts; none of them echoes what was typed.
 */

export const LABEL_CONTROL_TEXT = 'Label cannot contain control or text-direction characters';
export const LABEL_LONG_TEXT = 'Keep the label to 40 characters.';
export const LABEL_USED_TEXT = 'This name is already used by another field.';

/** The server refuses a raw label over this many UTF-16 units before it is normalised (V14). */
const RAW_MAX = 400;

/** Viramas of the Indian scripts; a joiner is allowed only straight after one, before a letter (IpdText). */
const VIRAMAS = '\u094D\u09CD\u0A4D\u0ACD\u0B4D\u0BCD\u0C4D\u0CCD\u0D4D\u0DCA';
const ZWNJ = 0x200c;
const ZWJ = 0x200d;

/** Categories the server refuses: Cc, Cf, Cn, Co, Cs, Zl, Zp (Format also covers every bidi mark). */
const REFUSED_CATEGORY = /^[\p{Cc}\p{Cf}\p{Cn}\p{Co}\p{Cs}\p{Zl}\p{Zp}]$/u;
const LETTER = /^\p{L}$/u;
const BLANK = /^[\s\p{Zs}]$/u;
const BLANK_RUN = /[\s\p{Zs}]+/gu;

function refused(char: string): boolean {
  if (REFUSED_CATEGORY.test(char)) {
    return true;
  }
  const cp = char.codePointAt(0) ?? 0;
  return (
    cp === 0x115f ||
    cp === 0x1160 ||
    cp === 0x3164 ||
    cp === 0xffa0 ||
    cp === 0x2800 ||
    cp === 0x180e ||
    cp === 0x034f ||
    (cp >= 0xfe00 && cp <= 0xfe0f)
  );
}

/** Mirror of `IpdText.hasControlOrBidi`, read by code point on the NFC text, before any trimming. */
export function hasControlOrBidi(text: string): boolean {
  const chars = Array.from(text.normalize('NFC'));
  for (let i = 0; i < chars.length; i += 1) {
    const cp = chars[i].codePointAt(0) ?? 0;
    if (cp === ZWNJ || cp === ZWJ) {
      const before = i > 0 ? chars[i - 1] : '';
      const after = i + 1 < chars.length ? chars[i + 1] : '';
      const placed =
        before !== '' && VIRAMAS.includes(before) && after !== '' && LETTER.test(after);
      if (!placed) {
        return true;
      }
    } else if (refused(chars[i])) {
      return true;
    }
  }
  return false;
}

/** White space and space separators removed at both ends (`IpdText.strip`); nothing inside is changed. */
export function stripEdges(text: string): string {
  const chars = Array.from(text);
  let start = 0;
  let end = chars.length;
  while (start < end && BLANK.test(chars[start])) {
    start += 1;
  }
  while (end > start && BLANK.test(chars[end - 1])) {
    end -= 1;
  }
  return chars.slice(start, end).join('');
}

/** The label as the server stores it before the standard-name rule: NFC, then stripped. */
export function normalLabel(raw: string): string {
  return stripEdges(raw.normalize('NFC'));
}

/**
 * The canonical label (G-3): NFC, stripped, and "" when it is exactly the field's own standard name
 * (case-sensitive, as `CardSetupCanonical`). A case or spacing variant ("bed") is a real label.
 */
export function canonicalLabel(raw: string, standardName: string): string {
  const label = normalLabel(raw);
  return label === standardName ? '' : label;
}

/** Number of code points, not UTF-16 units: an emoji counts once. */
export function codePointLength(text: string): number {
  return Array.from(text).length;
}

/**
 * The comparison key of V15 (design note 4.1): NFC; every run of white space or space separators becomes
 * one space; no space at either end; upper case then lower case; NFC again.
 */
export function nameKey(text: string): string {
  const spaced = text.normalize('NFC').replace(BLANK_RUN, ' ').replace(/^ | $/g, '');
  return spaced.toUpperCase().toLowerCase().normalize('NFC');
}

/**
 * Checks 1 and 2 of one label, in this order, first failure only: a control, invisible or text-direction
 * character; then too long (more than the limit in code points after NFC and stripping, or a raw label
 * over 400 UTF-16 units). Null when the label is fine.
 */
export function labelShapeProblem(raw: string, max: number): string | null {
  if (hasControlOrBidi(raw)) {
    return LABEL_CONTROL_TEXT;
  }
  if (raw.length > RAW_MAX || codePointLength(normalLabel(raw)) > max) {
    return LABEL_LONG_TEXT;
  }
  return null;
}

/**
 * Every label problem of the editor's setup, one message per field (the first failure: control character,
 * then length, then V15). Standard names of all 25 catalogue fields are reserved, on or off; a label may
 * not equal the label of a field placed before it, so the later field gets the error; a label equal to
 * the field's own name (in any case or spacing) is never a V15 error. Patient name and the safety fields
 * keep their standard name and are not checked. The result is keyed by field key, in body order.
 */
export function labelProblems(
  setup: CardSetup,
  catalogue: CardCatalogue,
): Map<CardFieldKey, string> {
  const own = new Map(catalogue.fields.map((f) => [f.key, nameKey(f.name)] as const));
  const reserved = new Set(own.values());
  const earlier = new Set<string>();
  const out = new Map<CardFieldKey, string>();
  for (const section of setup.sections) {
    for (const placement of section.fields) {
      const cat = catalogue.fields.find((f) => f.key === placement.key);
      if (!cat || cat.required || cat.safety) {
        continue;
      }
      const shape = labelShapeProblem(placement.label, catalogue.labelMaxLength);
      if (shape !== null) {
        out.set(placement.key, shape);
        continue;
      }
      const label = normalLabel(placement.label);
      if (label === '') {
        continue;
      }
      const key = nameKey(label);
      if (key !== '' && key !== own.get(placement.key) && (reserved.has(key) || earlier.has(key))) {
        out.set(placement.key, LABEL_USED_TEXT);
      }
      earlier.add(key);
    }
  }
  return out;
}
