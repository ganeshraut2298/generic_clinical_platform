import {
  CardActionKey,
  CardCatalogue,
  CardChangedPart,
  CardDataGroup,
  CardFieldKey,
  CardIpdState,
  CardLayout,
  CardRole,
  CardSection,
  CardSectionKey,
  CardSetup,
  CardTemplate,
  CardsPerRow,
  PART_WORD,
  ResolvedCardSetup,
  ROLE_NAME,
} from './card-setup.model';
import type { AdmittedCardAbilities } from '../ipd.model';
import { canonicalLabel, normalLabel } from './card-label.rules';

/**
 * Pure editing rules of Clinic setup → Patient card (SAH-37). Each operation returns a new setup, or a
 * refusal with the message of the story (AC23, AC29, AC30). They mirror the server's rules for instant
 * feedback; the server validates every Save draft and Publish and is the protection. The browser holds
 * no role table: who may see a group or use an action is read from the catalogue the server sent.
 */

export type EditResult = { ok: true; setup: CardSetup } | { ok: false; message: string };

const ok = (setup: CardSetup): EditResult => ({ ok: true, setup });
const refuse = (message: string): EditResult => ({ ok: false, message });

export function cloneSetup(setup: CardSetup): CardSetup {
  return JSON.parse(JSON.stringify(setup)) as CardSetup;
}

/**
 * The shape the server compares and stores (G-3): every label in NFC, stripped at both ends, and "" when it
 * is exactly the field's own standard name (case-sensitive), so the browser and the server agree on what
 * is a change. Without the catalogue only the NFC and strip steps apply.
 */
export function canonicalSetup(setup: CardSetup, catalogue?: CardCatalogue): CardSetup {
  const copy = cloneSetup(setup);
  for (const section of copy.sections) {
    for (const f of section.fields) {
      const name = catalogue?.fields.find((c) => c.key === f.key)?.name;
      f.label = name === undefined ? normalLabel(f.label) : canonicalLabel(f.label, name);
    }
  }
  return copy;
}

export function sameSetup(a: CardSetup, b: CardSetup, catalogue?: CardCatalogue): boolean {
  return (
    JSON.stringify(canonicalSetup(a, catalogue)) === JSON.stringify(canonicalSetup(b, catalogue))
  );
}

/** The parts that differ from the live setup, in the order of the pill (mockup `pcChanges`). */
export function changedParts(
  edit: CardSetup,
  live: CardSetup,
  catalogue?: CardCatalogue,
): CardChangedPart[] {
  const a = canonicalSetup(edit, catalogue);
  const b = canonicalSetup(live, catalogue);
  const out: CardChangedPart[] = [];
  if (a.layout !== b.layout || a.cardsPerRow !== b.cardsPerRow) {
    out.push('layout');
  }
  const flags = (s: CardSetup) => JSON.stringify(s.sections.map((x) => [x.key, x.on]));
  if (flags(a) !== flags(b)) {
    out.push('sections');
  }
  // Field placement: which fields each section holds, in what order, switched on or off, with which label.
  // Independent of the order of the sections, as the server compares it (moving a section alone is one
  // change, "sections"; SAH-37 design note section 5).
  const fields = (s: CardSetup) =>
    JSON.stringify(
      s.sections
        .map((x) => [x.key, x.fields] as const)
        .sort(([ka], [kb]) => (ka < kb ? -1 : ka > kb ? 1 : 0)),
    );
  if (fields(a) !== fields(b)) {
    out.push('fields');
  }
  if (JSON.stringify(a.actions) !== JSON.stringify(b.actions)) {
    out.push('quickActions');
  }
  return out;
}

/** "layout, fields" */
export function partsText(parts: readonly CardChangedPart[]): string {
  return parts.map((p) => PART_WORD[p]).join(', ');
}

/** "Draft · 2 changes not published" */
export function draftPillText(count: number, savedAt: string | null): string {
  const base = `Draft · ${count} ${count === 1 ? 'change' : 'changes'} not published`;
  return savedAt ? `${base} · saved ${savedAt}` : base;
}

/** "Layout, fields updated." (empty when nothing is named) */
export function updatedText(parts: readonly CardChangedPart[]): string {
  if (parts.length === 0) {
    return '';
  }
  const text = partsText(parts);
  return `${text.charAt(0).toUpperCase()}${text.slice(1)} updated.`;
}

export function publishToast(version: number, parts: readonly CardChangedPart[]): string {
  const base = `Patient card v${version} is live for every role.`;
  const tail = updatedText(parts);
  return tail === '' ? base : `${base} ${tail}`;
}

// ---- Lookups ----

const sectionOf = (setup: CardSetup, key: CardFieldKey): CardSection | undefined =>
  setup.sections.find((s) => s.fields.some((f) => f.key === key));

export function fieldName(catalogue: CardCatalogue, key: CardFieldKey): string {
  return catalogue.fields.find((f) => f.key === key)?.name ?? key;
}

export function sectionName(catalogue: CardCatalogue, key: CardSectionKey): string {
  return catalogue.sections.find((s) => s.key === key)?.name ?? key;
}

export function groupName(catalogue: CardCatalogue, key: CardDataGroup): string {
  return catalogue.groups.find((g) => g.key === key)?.name ?? key;
}

const isSafetyField = (catalogue: CardCatalogue, key: CardFieldKey): boolean =>
  catalogue.fields.find((f) => f.key === key)?.safety === true;

const isRequiredField = (catalogue: CardCatalogue, key: CardFieldKey): boolean =>
  catalogue.fields.find((f) => f.key === key)?.required === true;

/** Names of the safety fields a section holds, in card order. */
export function safetyFieldsIn(
  setup: CardSetup,
  catalogue: CardCatalogue,
  key: CardSectionKey,
): string[] {
  const section = setup.sections.find((s) => s.key === key);
  return (section?.fields ?? [])
    .filter((f) => isSafetyField(catalogue, f.key) || isRequiredField(catalogue, f.key))
    .map((f) => fieldName(catalogue, f.key));
}

// ---- Counts for the section rows ("3 of 4 fields on") ----

export function fieldsOnText(section: CardSection): string {
  return `${section.fields.filter((f) => f.on).length} of ${section.fields.length} fields on`;
}

// ---- Edits ----

export function setLayout(setup: CardSetup, layout: CardLayout): CardSetup {
  return { ...cloneSetup(setup), layout };
}

export function setCardsPerRow(setup: CardSetup, cardsPerRow: CardsPerRow): CardSetup {
  return { ...cloneSetup(setup), cardsPerRow };
}

/**
 * A template replaces the layout, the sections and the quick actions; "Cards per row" is kept
 * (AC18). The locked safety items stay on because the templates the server sends keep them on.
 */
export function applyTemplate(setup: CardSetup, template: CardTemplate): CardSetup {
  const copy = JSON.parse(JSON.stringify(template)) as CardTemplate;
  return {
    layout: copy.layout,
    cardsPerRow: setup.cardsPerRow,
    sections: copy.sections,
    actions: copy.actions,
  };
}

export function toggleSection(
  setup: CardSetup,
  catalogue: CardCatalogue,
  key: CardSectionKey,
): EditResult {
  const cat = catalogue.sections.find((s) => s.key === key);
  const section = setup.sections.find((s) => s.key === key);
  if (!section) {
    return ok(setup);
  }
  if (cat?.locked) {
    return refuse(`The ${cat.name} section is always first and always on.`);
  }
  if (cat?.safety) {
    return refuse(`The ${cat.name} section is a safety section and stays on.`);
  }
  if (section.on) {
    const held = safetyFieldsIn(setup, catalogue, key);
    if (held.length > 0) {
      return refuse(`This section holds a safety field (${held.join(', ')}). Move it out first.`);
    }
  }
  const next = cloneSetup(setup);
  const target = next.sections.find((s) => s.key === key)!;
  target.on = !target.on;
  return ok(next);
}

/** Moves a section to `toIndex` of the section list. The Card header stays first and cannot move. */
export function moveSection(setup: CardSetup, key: CardSectionKey, toIndex: number): CardSetup {
  const from = setup.sections.findIndex((s) => s.key === key);
  if (from < 0 || key === 'header') {
    return setup;
  }
  const next = cloneSetup(setup);
  const [moved] = next.sections.splice(from, 1);
  const at = Math.min(Math.max(1, toIndex), next.sections.length);
  next.sections.splice(at, 0, moved);
  return next;
}

export function moveSectionBy(setup: CardSetup, key: CardSectionKey, direction: -1 | 1): CardSetup {
  const from = setup.sections.findIndex((s) => s.key === key);
  return from < 0 ? setup : moveSection(setup, key, from + direction);
}

export function canMoveSection(setup: CardSetup, key: CardSectionKey, direction: -1 | 1): boolean {
  const from = setup.sections.findIndex((s) => s.key === key);
  if (from < 0 || key === 'header') {
    return false;
  }
  const to = from + direction;
  return to >= 1 && to < setup.sections.length;
}

export function toggleField(
  setup: CardSetup,
  catalogue: CardCatalogue,
  key: CardFieldKey,
): EditResult {
  const cat = catalogue.fields.find((f) => f.key === key);
  if (cat?.required) {
    return refuse('Patient name stays first in the Card header.');
  }
  if (cat?.safety) {
    return refuse(`${cat.name} is a safety field and cannot be hidden.`);
  }
  const next = cloneSetup(setup);
  for (const section of next.sections) {
    const f = section.fields.find((x) => x.key === key);
    if (f) {
      f.on = !f.on;
    }
  }
  return ok(next);
}

/** Safety fields and the patient name keep the standard name (AC32). */
export function labelLocked(catalogue: CardCatalogue, key: CardFieldKey): boolean {
  return isSafetyField(catalogue, key) || isRequiredField(catalogue, key);
}

/** The box stops at `max` UTF-16 units (AC31); a pair of units that make one character is never cut in half. */
function cutLabel(label: string, max: number): string {
  const cut = label.slice(0, max);
  const last = cut.charCodeAt(cut.length - 1);
  const splitPair = last >= 0xd800 && last <= 0xdbff && label.length > max;
  return splitPair ? cut.slice(0, -1) : cut;
}

export function setFieldLabel(
  setup: CardSetup,
  catalogue: CardCatalogue,
  key: CardFieldKey,
  label: string,
): CardSetup {
  if (labelLocked(catalogue, key)) {
    return setup;
  }
  const next = cloneSetup(setup);
  for (const section of next.sections) {
    const f = section.fields.find((x) => x.key === key);
    if (f) {
      f.label = cutLabel(label, catalogue.labelMaxLength);
    }
  }
  return next;
}

/**
 * Moves a field to `toIndex` of the destination section's list (an index before the move). A safety field
 * may only go to a switched-on section (AC30); nothing goes above the patient name in the Card header;
 * the patient name itself does not move.
 */
export function moveField(
  setup: CardSetup,
  catalogue: CardCatalogue,
  key: CardFieldKey,
  toSection: CardSectionKey,
  toIndex: number,
): EditResult {
  const src = sectionOf(setup, key);
  const dst = setup.sections.find((s) => s.key === toSection);
  if (!src || !dst || isRequiredField(catalogue, key)) {
    return ok(setup);
  }
  if (isSafetyField(catalogue, key) && !dst.on) {
    return refuse(
      `${fieldName(catalogue, key)} is a safety field. Move it to a section that is switched on.`,
    );
  }
  const allowed = catalogue.fields.find((f) => f.key === key)?.allowedSections;
  if (allowed && !allowed.includes(toSection)) {
    return ok(setup);
  }
  const next = cloneSetup(setup);
  const from = next.sections.find((s) => s.key === src.key)!;
  const to = next.sections.find((s) => s.key === toSection)!;
  const at = from.fields.findIndex((f) => f.key === key);
  const [moved] = from.fields.splice(at, 1);
  let index = toIndex;
  if (from === to && at < index) {
    index -= 1;
  }
  index = Math.min(Math.max(index, 0), to.fields.length);
  if (toSection === 'header') {
    index = Math.max(index, 1);
    index = Math.min(index, to.fields.length);
  }
  to.fields.splice(index, 0, moved);
  return ok(next);
}

/** Up or down by one place; past the first or last field of a section it enters the next section (AC story note). */
export function moveFieldBy(
  setup: CardSetup,
  catalogue: CardCatalogue,
  key: CardFieldKey,
  direction: -1 | 1,
): EditResult {
  const si = setup.sections.findIndex((s) => s.fields.some((f) => f.key === key));
  if (si < 0) {
    return ok(setup);
  }
  const section = setup.sections[si];
  const i = section.fields.findIndex((f) => f.key === key);
  if (direction < 0) {
    if (i > 0) {
      return moveField(setup, catalogue, key, section.key, i - 1);
    }
    const prev = setup.sections[si - 1];
    return prev ? moveField(setup, catalogue, key, prev.key, prev.fields.length) : ok(setup);
  }
  if (i < section.fields.length - 1) {
    return moveField(setup, catalogue, key, section.key, i + 2);
  }
  const nextSection = setup.sections[si + 1];
  return nextSection ? moveField(setup, catalogue, key, nextSection.key, 0) : ok(setup);
}

export function canMoveField(
  setup: CardSetup,
  catalogue: CardCatalogue,
  key: CardFieldKey,
  direction: -1 | 1,
): boolean {
  if (isRequiredField(catalogue, key)) {
    return false;
  }
  const si = setup.sections.findIndex((s) => s.fields.some((f) => f.key === key));
  if (si < 0) {
    return false;
  }
  const section = setup.sections[si];
  const i = section.fields.findIndex((f) => f.key === key);
  if (direction < 0) {
    // In the Card header nothing goes above the patient name.
    return section.key === 'header' ? i > 1 : i > 0 || si > 0;
  }
  return i < section.fields.length - 1 || si < setup.sections.length - 1;
}

export function toggleAction(setup: CardSetup, key: CardActionKey): CardSetup {
  const next = cloneSetup(setup);
  const a = next.actions.find((x) => x.key === key);
  if (a) {
    a.on = !a.on;
  }
  return next;
}

export function moveAction(setup: CardSetup, key: CardActionKey, toIndex: number): CardSetup {
  const from = setup.actions.findIndex((a) => a.key === key);
  if (from < 0) {
    return setup;
  }
  const next = cloneSetup(setup);
  const [moved] = next.actions.splice(from, 1);
  next.actions.splice(Math.min(Math.max(0, toIndex), next.actions.length), 0, moved);
  return next;
}

export function moveActionBy(setup: CardSetup, key: CardActionKey, direction: -1 | 1): CardSetup {
  const from = setup.actions.findIndex((a) => a.key === key);
  return from < 0 ? setup : moveAction(setup, key, from + direction);
}

export function canMoveAction(setup: CardSetup, key: CardActionKey, direction: -1 | 1): boolean {
  const from = setup.actions.findIndex((a) => a.key === key);
  const to = from + direction;
  return from >= 0 && to >= 0 && to < setup.actions.length;
}

/** "<Section> moved to position 3" (polite announcement, AC24). Positions count from 1. */
export function sectionMovedText(
  catalogue: CardCatalogue,
  setup: CardSetup,
  key: CardSectionKey,
): string {
  return `${sectionName(catalogue, key)} moved to position ${setup.sections.findIndex((s) => s.key === key) + 1}`;
}

export function fieldMovedText(
  catalogue: CardCatalogue,
  setup: CardSetup,
  key: CardFieldKey,
): string {
  const section = sectionOf(setup, key);
  const index = section ? section.fields.findIndex((f) => f.key === key) + 1 : 0;
  return `${fieldName(catalogue, key)} moved to position ${index} in ${section ? sectionName(catalogue, section.key) : ''}`.trim();
}

export function actionMovedText(
  catalogue: CardCatalogue,
  setup: CardSetup,
  key: CardActionKey,
): string {
  const name = catalogue.actions.find((a) => a.key === key)?.name ?? key;
  return `${name} moved to position ${setup.actions.findIndex((a) => a.key === key) + 1}`;
}

// ---- Resolve a setup for drawing (the shared card draws a ResolvedCardSetup) ----

/**
 * The editor's setup in the shape the Admitted page receives: only sections and fields that are on,
 * labels resolved, actions that are on, in order. A pure mapping with the catalogue; no rule here.
 */
export function resolveSetup(
  setup: CardSetup,
  catalogue: CardCatalogue,
  meta: { version: number; isStandard: boolean } = { version: 0, isStandard: false },
): ResolvedCardSetup {
  return {
    version: meta.version,
    isStandard: meta.isStandard,
    fallback: false,
    layout: setup.layout,
    cardsPerRow: setup.cardsPerRow,
    sections: setup.sections
      .filter((s) => s.on)
      .map((s) => ({
        key: s.key,
        name: sectionName(catalogue, s.key),
        fields: s.fields
          .filter((f) => f.on)
          .map((f) => {
            const cat = catalogue.fields.find((c) => c.key === f.key);
            return {
              key: f.key,
              label: f.label.trim() === '' ? (cat?.name ?? f.key) : f.label.trim(),
              group: cat?.group ?? 'patient',
              safety: cat?.safety === true,
            };
          }),
      })),
    actions: setup.actions.filter((a) => a.on).map((a) => a.key),
  };
}

// ---- "View as": the role rule applied to the owner's own answer (ADR-015 D7) ----

export function groupsFor(role: CardRole, catalogue: CardCatalogue): CardDataGroup[] {
  return catalogue.groups.filter((g) => g.visibleTo.includes(role)).map((g) => g.key);
}

/** What the role could use on a card, from the catalogue's "who" line and the IPD state. */
export function abilitiesFor(
  role: CardRole,
  catalogue: CardCatalogue,
  state: CardIpdState,
): AdmittedCardAbilities {
  const can = (key: CardActionKey): boolean => {
    const a = catalogue.actions.find((x) => x.key === key);
    return !!a && a.usableBy.includes(role) && (state !== 'readonly' || a.allowedInReadOnly);
  };
  const groups = groupsFor(role, catalogue);
  return {
    nursing: can('nursing'),
    emr: can('emr'),
    transfer: can('transfer'),
    discharge: can('discharge'),
    deposit: can('deposit'),
    // The Deposit low flag sits with the Billing group (OWNER, FRONT_DESK).
    depositLowFlag: groups.includes('billing'),
    visibleGroups: groups,
  };
}

/** "Hidden for Nurse: 3 fields, 1 action" counts (AC48): only what the owner switched on. */
export function hiddenCounts(
  setup: CardSetup,
  catalogue: CardCatalogue,
  role: CardRole,
  state: CardIpdState,
): { fields: number; actions: number } {
  const groups = groupsFor(role, catalogue);
  const abilities = abilitiesFor(role, catalogue, state);
  let fields = 0;
  for (const section of setup.sections) {
    if (!section.on) {
      continue;
    }
    for (const f of section.fields) {
      const group = catalogue.fields.find((c) => c.key === f.key)?.group;
      if (f.on && group && !groups.includes(group)) {
        fields += 1;
      }
    }
  }
  const actions = setup.actions.filter((a) => a.on && !abilities[a.key]).length;
  return { fields, actions };
}

/** "Hidden for Nurse: 3 fields, 1 action"; null when nothing is hidden. */
export function hiddenText(
  role: CardRole,
  counts: { fields: number; actions: number },
): string | null {
  const parts: string[] = [];
  if (counts.fields > 0) {
    parts.push(`${counts.fields} ${counts.fields === 1 ? 'field' : 'fields'}`);
  }
  if (counts.actions > 0) {
    parts.push(`${counts.actions} ${counts.actions === 1 ? 'action' : 'actions'}`);
  }
  return parts.length === 0 ? null : `Hidden for ${ROLE_NAME[role]}: ${parts.join(', ')}`;
}

/** Tile line of the Overview: "Standard · Sahaj default · v1 · draft" (AC15). */
export function tileLine(args: {
  layout: CardLayout;
  isStandard: boolean;
  version: number;
  hasDraft: boolean;
}): string {
  const layout = args.layout.charAt(0).toUpperCase() + args.layout.slice(1);
  return `${layout} · ${args.isStandard ? 'Sahaj default' : 'customized'} · v${args.version}${args.hasDraft ? ' · draft' : ''}`;
}
