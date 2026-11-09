import { formatInrPaise } from '../../../shared/format/inr';
import type { IconName } from '../../../shared/icon/icon.component';
import {
  CardFlag,
  DASH,
  FoldedFlags,
  admittedOnText,
  cardFlags,
  cardLabel,
  foldFlags,
  initials,
  isCritical,
  lengthOfStay,
  roomText,
  sexLabel,
  visibleActions,
} from '../admitted/admitted.model';
import { ADMISSION_TYPE_LABEL } from '../ipd.model';
import type { AdmittedCardAbilities, AdmittedPatientRow } from '../ipd.model';
import {
  CardActionKey,
  CardFieldKey,
  CardSectionKey,
  MARK_TITLE,
  MARK_WORD,
  ResolvedCardField,
  ResolvedCardSection,
  ResolvedCardSetup,
  SAFETY_FIELDS,
  STANDARD_RESOLVED,
  VITAL_KEYS,
  VITAL_UNIT,
  VitalKey,
  VitalMark,
} from './card-setup.model';

/**
 * What one patient card draws (SAH-37; mockup `pcCard`). A pure mapping from the server's row, the
 * caller's abilities and the published setup to texts and tones, shared by the Admitted page and the
 * live preview. Every number, mark, score and minute is the server's (`asOf`, `clinical`); the browser
 * adds no clinical rule. Owner labels are returned as text only (the template interpolates them).
 */

export type Tone = 'crit' | 'warn' | 'good' | 'blue' | 'grey';

export interface ValueView {
  /** Plain text; also the tooltip. */
  text: string;
  tone: Tone | null;
  /** Drawn as a pill (Early warning). */
  chip: boolean;
  /** The Alerts field: every flag, drawn as pills ("None" when empty). */
  flags: CardFlag[] | null;
}

export interface KvItem {
  key: CardFieldKey;
  label: string;
  testId: string;
  value: ValueView;
  /** Compact one-line text: the value, with "Allergies: " in front of the allergy so it is never bare. */
  lineText: string;
  wide: boolean;
}

export interface TileView {
  key: VitalKey;
  label: string;
  value: string;
  unit: string;
  mark: VitalMark | null;
  markWord: string;
  markTitle: string;
}

export interface IdentityPart {
  key: CardFieldKey;
  label: string;
  text: string;
}

export interface StatusSectionView {
  kind: 'status';
  key: CardSectionKey;
  title: string;
  /** Present when the Alerts field is in this section: shown folded as on the ward. */
  alerts: FoldedFlags | null;
  chips: KvItem[];
}

export interface VitalsSectionView {
  kind: 'vitals';
  key: CardSectionKey;
  title: string;
  /** "No vitals recorded yet" instead of tiles. */
  noVitals: boolean;
  tiles: TileView[];
  /** Compact: BP, SpO₂ and Pulse only. */
  minis: TileView[];
  /** Early warning, Last recorded and any other field placed here. */
  foot: KvItem[];
}

export interface KvSectionView {
  kind: 'kv';
  key: CardSectionKey;
  title: string;
  items: KvItem[];
}

export type SectionView = StatusSectionView | VitalsSectionView | KvSectionView;

export interface ActionView {
  key: CardActionKey;
  label: string;
  icon: IconName;
  primary: boolean;
  /** "<Action> for <patient name>" */
  name: string;
}

export interface CardView {
  ariaLabel: string;
  name: string;
  initials: string;
  female: boolean;
  critical: boolean;
  identity: IdentityPart[];
  bed: { label: string; value: string } | null;
  sections: SectionView[];
  actions: ActionView[];
}

const FIELD_TEST_ID: Partial<Record<CardFieldKey, string>> = {
  admittedOn: 'card-admitted-on',
  lengthOfStay: 'card-stay',
  ward: 'card-ward',
  room: 'card-room',
  doctor: 'card-doctor',
};

const COMPACT_MINIS: readonly VitalKey[] = ['bp', 'spo2', 'pulse'];

export const NO_VITALS_TEXT = 'No vitals recorded yet';
export const NOT_RECORDED = 'Not recorded';

const isVital = (key: CardFieldKey): key is VitalKey =>
  (VITAL_KEYS as readonly string[]).includes(key);

/** "35 min ago", "2 h 5 min ago" (mockup `pcAgo`), from the server's `asOf`. */
export function agoText(recordedAt: string, asOf: string): string {
  const minutes = Math.max(
    0,
    Math.floor((new Date(asOf).getTime() - new Date(recordedAt).getTime()) / 60000),
  );
  if (Number.isNaN(minutes)) {
    return DASH;
  }
  if (minutes < 60) {
    return `${minutes} min ago`;
  }
  const rest = minutes % 60;
  return `${Math.floor(minutes / 60)} h ${rest > 0 ? `${rest} min ` : ''}ago`;
}

/**
 * Puts back, for drawing, anything the safety rules say can never be missing from a card: the patient
 * name, UHID, Alerts, Early warning and Allergies, with their standard names. The server already
 * repairs a stored setup; this is only a guard in the browser (AC57, AC61).
 */
export function ensureSafety(setup: ResolvedCardSetup): ResolvedCardSetup {
  const sections: ResolvedCardSection[] = setup.sections.map((s) => ({
    ...s,
    fields: s.fields.map((f) => {
      const fixed = SAFETY_FIELDS.find((x) => x.key === f.key);
      return fixed ? { ...f, label: fixed.name, safety: true } : f;
    }),
  }));
  const find = (key: CardSectionKey): ResolvedCardSection | undefined =>
    sections.find((s) => s.key === key);
  const has = (key: CardFieldKey) => sections.some((s) => s.fields.some((f) => f.key === key));
  if (!has('name')) {
    if (!find('header')) {
      sections.unshift({ key: 'header', name: 'Card header', fields: [] });
    }
    find('header')!.fields.unshift({
      key: 'name',
      label: 'Patient name',
      group: 'patient',
      safety: false,
    });
  }
  for (const item of SAFETY_FIELDS) {
    if (has(item.key)) {
      continue;
    }
    let target = find(item.section);
    if (!target) {
      target = { key: item.section, name: item.sectionName, fields: [] };
      sections.push(target);
    }
    const added: ResolvedCardField = {
      key: item.key,
      label: item.name,
      group: item.group,
      safety: true,
    };
    target.fields.splice(
      item.key === 'uhid' ? Math.min(1, target.fields.length) : target.fields.length,
      0,
      added,
    );
  }
  return { ...setup, sections };
}

function plain(text: string, tone: Tone | null = null): ValueView {
  return { text, tone, chip: false, flags: null };
}

const blank = (v: string | null | undefined): string =>
  (v ?? '').trim() === '' ? DASH : (v as string);

interface Context {
  row: AdmittedPatientRow;
  asOf: string;
  flags: CardFlag[];
}

function tileOf(key: VitalKey, label: string, ctx: Context): TileView {
  const v = ctx.row.clinical?.latestVitals ?? null;
  let value = DASH;
  let mark: VitalMark | null = null;
  if (v) {
    if (key === 'bp' && v.bp) {
      value = `${v.bp.systolic}/${v.bp.diastolic}`;
      mark = v.bp.mark ?? null;
    } else if (key !== 'bp' && v[key]) {
      const reading = v[key]!;
      value = key === 'temp' ? reading.value.toFixed(1) : String(reading.value);
      mark = reading.mark ?? null;
    }
  }
  return {
    key,
    label,
    value,
    unit: VITAL_UNIT[key],
    mark,
    markWord: mark ? MARK_WORD[mark] : '',
    markTitle: mark ? MARK_TITLE[mark] : '',
  };
}

function valueOf(f: ResolvedCardField, ctx: Context): ValueView {
  const { row, asOf } = ctx;
  const c = row.clinical;
  switch (f.key) {
    case 'name':
      return plain(row.patientName);
    case 'uhid':
      return plain(blank(row.uhid));
    case 'ageSex':
      return plain(
        `${row.age === null || row.age === undefined ? DASH : row.age} · ${sexLabel(row.sex)}`,
      );
    case 'bed':
      return plain(row.bedCode);
    case 'ipNumber':
      return plain(blank(row.ipNumber));
    case 'mobile':
      return plain(blank(row.mobile));
    case 'alerts':
      return {
        text: ctx.flags.length > 0 ? ctx.flags.map((x) => x.text).join(', ') : 'None',
        tone: null,
        chip: false,
        flags: ctx.flags,
      };
    case 'admittedOn':
      return plain(admittedOnText(row.admittedAt));
    case 'lengthOfStay':
      return plain(lengthOfStay(row.admittedAt, row.dayNumber, asOf));
    case 'ward':
      return plain(blank(row.wardName));
    case 'room':
      return plain(roomText(row));
    case 'admissionType':
      return plain(ADMISSION_TYPE_LABEL[row.admissionType] ?? DASH);
    case 'doctor':
      return plain(blank(row.doctorName));
    case 'bp':
    case 'pulse':
    case 'spo2':
    case 'temp':
    case 'rr': {
      const tile = tileOf(f.key, f.label, ctx);
      const text = tile.value === DASH ? DASH : `${tile.value} ${tile.unit}`;
      const tone: Tone | null =
        tile.mark === 'CRITICAL' ? 'crit' : tile.mark === 'CHECK' ? 'warn' : null;
      return plain(tile.mark ? `${text} · ${tile.markWord}` : text, tone);
    }
    case 'ews': {
      const e = c?.latestEws;
      if (!e) {
        return plain(DASH);
      }
      const text = `${e.score} · ${e.risk === 'HIGH' ? 'High' : e.risk === 'MEDIUM' ? 'Medium' : 'Low'}`;
      return {
        text,
        tone: e.risk === 'HIGH' ? 'crit' : e.risk === 'MEDIUM' ? 'warn' : 'good',
        chip: true,
        flags: null,
      };
    }
    case 'lastRecorded': {
      const v = c?.latestVitals ?? null;
      const at = v?.recordedAt ?? c?.latestEws?.recordedAt;
      if (!at) {
        return plain(NOT_RECORDED, 'warn');
      }
      const ago = agoText(at, asOf);
      return plain(v?.recordedByName ? `${ago} · ${v.recordedByName}` : ago);
    }
    case 'diagnosis':
      return plain(blank(c?.diagnosis));
    case 'allergies': {
      const a = c?.allergy;
      if (a?.status === 'RECORDED') {
        const text = (a.text ?? '').trim();
        return plain(text === '' ? 'Allergy recorded' : text, 'crit');
      }
      return a?.status === 'NONE_KNOWN' ? plain('None known') : plain(NOT_RECORDED, 'warn');
    }
    case 'runningBill':
      return plain(formatInrPaise(row.runningBillPaise));
    case 'deposit':
      return plain(formatInrPaise(row.depositPaise));
    case 'payer':
      return plain(blank(row.payer));
  }
}

function itemOf(f: ResolvedCardField, ctx: Context): KvItem {
  const value = valueOf(f, ctx);
  return {
    key: f.key,
    label: f.label,
    testId: FIELD_TEST_ID[f.key] ?? `card-field-${f.key}`,
    value,
    lineText: f.key === 'allergies' ? `${f.label}: ${value.text}` : value.text,
    wide: f.key === 'diagnosis',
  };
}

/** Identity line under the name: UHID and IP number carry their label, the rest is the value (mockup). */
function identityPart(f: ResolvedCardField, ctx: Context): IdentityPart {
  const value = valueOf(f, ctx).text;
  const text =
    f.key === 'uhid' || f.key === 'ipNumber'
      ? `${f.label} ${value}`
      : f.key === 'ageSex' || f.key === 'mobile'
        ? value
        : `${f.label}: ${value}`;
  return { key: f.key, label: f.label, text };
}

/**
 * Builds the card. A field is drawn when it is the patient name or its data group is one the caller
 * may see (`card.visibleGroups`); a section with nothing to draw is not drawn. The vitals section also
 * carries the Compact shape (BP, SpO₂, Pulse); the template chooses by layout.
 */
export function buildCard(
  row: AdmittedPatientRow,
  card: AdmittedCardAbilities,
  setupIn: ResolvedCardSetup,
  asOf: string,
): CardView {
  const setup = ensureSafety(setupIn);
  const groups = new Set<string>(card.visibleGroups ?? []);
  const ctx: Context = { row, asOf, flags: cardFlags(row, card) };
  const visible = (f: ResolvedCardField): boolean => f.key === 'name' || groups.has(f.group);

  const header = setup.sections.find((s) => s.key === 'header');
  const headerFields = (header?.fields ?? []).filter(visible);
  const bedField = headerFields.find((f) => f.key === 'bed');
  const identity = headerFields
    .filter((f) => f.key !== 'name' && f.key !== 'bed')
    .map((f) => identityPart(f, ctx));

  const noVitals = !row.clinical || row.clinical.latestEws == null;
  const sections: SectionView[] = [];
  for (const s of setup.sections) {
    if (s.key === 'header') {
      continue;
    }
    const fields = s.fields.filter(visible);
    if (fields.length === 0) {
      continue;
    }
    if (s.key === 'status') {
      const alerts = fields.some((f) => f.key === 'alerts') ? foldFlags(ctx.flags) : null;
      sections.push({
        kind: 'status',
        key: s.key,
        title: s.name,
        alerts,
        chips: fields.filter((f) => f.key !== 'alerts').map((f) => itemOf(f, ctx)),
      });
    } else if (s.key === 'vitals') {
      const tiles = fields
        .filter((f) => isVital(f.key))
        .map((f) => tileOf(f.key as VitalKey, f.label, ctx));
      const others = fields.filter((f) => !isVital(f.key)).map((f) => itemOf(f, ctx));
      sections.push({
        kind: 'vitals',
        key: s.key,
        title: s.name,
        noVitals,
        tiles,
        minis: tiles.filter((t) => COMPACT_MINIS.includes(t.key)),
        foot: noVitals ? others.filter((i) => i.key !== 'ews') : others,
      });
    } else {
      sections.push({
        kind: 'kv',
        key: s.key,
        title: s.name,
        items: fields.map((f) => itemOf(f, ctx)),
      });
    }
  }

  const actions: ActionView[] = visibleActions(card, setup.actions).map((a, i) => ({
    key: a.key,
    label: a.label,
    icon: a.icon,
    primary: i === 0 && a.key === 'nursing',
    name: `${a.label} for ${row.patientName}`,
  }));

  return {
    ariaLabel: cardLabel(row),
    name: row.patientName,
    initials: initials(row.patientName),
    female: (row.sex ?? '').trim().toLowerCase().startsWith('f'),
    critical: isCritical(row),
    identity,
    bed: bedField ? { label: bedField.label, value: row.bedCode } : null,
    sections,
    actions,
  };
}

const KNOWN_LAYOUTS: readonly string[] = ['compact', 'standard', 'expanded'];

/**
 * The setup the Admitted page draws. A missing or unreadable `cardSetup` (or one the server marked
 * `fallback`) draws the Sahaj standard card and asks for the notice (AC61); the patient list is
 * unaffected.
 */
export function drawnSetup(raw: ResolvedCardSetup | null | undefined): {
  setup: ResolvedCardSetup;
  fallback: boolean;
} {
  const usable =
    !!raw &&
    KNOWN_LAYOUTS.includes(raw.layout) &&
    Array.isArray(raw.sections) &&
    Array.isArray(raw.actions) &&
    raw.sections.every((s) => Array.isArray(s.fields));
  if (!usable) {
    return { setup: STANDARD_RESOLVED, fallback: true };
  }
  return { setup: raw, fallback: raw.fallback === true };
}
