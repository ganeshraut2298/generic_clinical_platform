import { formatWhen, istToday } from '../../config/config-format';
import type { IconName } from '../../../shared/icon/icon.component';
import type { CardActionKey, CardsPerRow } from '../card/card-setup.model';
import { timeOfInstant } from '../emergency/emergency.model';
import type { AdmissionList, AdmissionScope, AdmittedCardAbilities, AdmittedPatientRow } from '../ipd.model';

/**
 * SAH-36 Admitted patient cards (mockup `pcCard`, `sfFlags`, `pcFiltered`, `pcmore`; ADR-013).
 * Pure helpers: every flag, number and minute is the server's (`asOf`, `clinical`); the browser never
 * reads its own clock for them and never decides a clinical rule. Names, UHIDs and allergy text are
 * returned for the screen only and are never logged or stored.
 */

export type AdmittedView = 'cards' | 'table';
export type { CardsPerRow };
/** Where the admission sheet opens when it is started from a card (ADR-013 D6). In memory only. */
export type AdmissionStartView = 'details' | 'move' | 'discharge' | 'deposit';

export const PAGE_SIZE = 24;
export const DEFAULT_COLS: CardsPerRow = 3;
export const COLS_CHOICES: readonly CardsPerRow[] = [2, 3, 4];
export const ALL_WARDS = 'all';

export const EMPTY_FILTER_TEXT = 'No patients match these filters.';

// ---- SAH-34 ward scope (mockup `pcscope`, `pctog`; texts: docs/stories/SAH-34.md AC 26 to 42) ----

export const MY_PATIENTS_LABEL = 'My patients';
export const EMERGENCY_ACCESS_LABEL = 'Emergency access';
/** AC 29: a scoped nurse with no live ward (new text, OQ-34-27). */
export const NO_WARDS_TEXT =
  'No wards are assigned to you, so no patients are shown. Ask the owner to assign your ward. If a patient needs you now, use Emergency access.';
/** AC 30: a nurse with wards but nobody in them while others are admitted (new text, OQ-34-27). */
export const NO_WARD_PATIENTS_TEXT = 'No patients in your wards right now.';
/** The empty list of the Admitted tab when nobody at all is admitted (SAH-36). */
export const NOBODY_ADMITTED_TEXT = 'No one is admitted right now.';

/** True when the scope is narrower than all (the server's `kind`; the browser decides nothing else). */
export function isScoped(scope: AdmissionScope | undefined): scope is AdmissionScope {
  return !!scope && scope.kind !== 'ALL';
}

/** AC 26: "Patients in their wards · 3 of 12" (N visible, M admitted, both from the server). */
export function scopeChipText(scope: Pick<AdmissionScope, 'visible' | 'admitted'>): string {
  return `Patients in their wards · ${scope.visible} of ${scope.admitted}`;
}

/** AC 26 and AC 28: the tooltip and the accessible name of the chip. The mockup's pointer to the Roles panel is not shown. */
export function scopeTooltip(scope: Pick<AdmissionScope, 'visible' | 'admitted'>): string {
  return `As Nurse you see patients in their wards (${scope.visible} of ${scope.admitted}). The owner sets your wards in Clinic setup.`;
}

/**
 * AC 26, 27: the chip shows for a scoped user when somebody is admitted in the clinic (M above 0);
 * before the first answer there is no list, so no chip is drawn from a guess (AC 41).
 */
export function showScopeChip(scope: AdmissionScope | undefined): boolean {
  return isScoped(scope) && scope.admitted > 0;
}

/**
 * AC 32: "My patients" is shown to OWNER and DOCTOR (scope all) and to a scoped nurse only when she has
 * a live ward. FRONT_DESK never sees it (mockup). `role` is the signed-in role; the server's `hasWard`
 * decides for the nurse.
 */
export function showMyPatients(role: string | undefined, scope: AdmissionScope | undefined): boolean {
  if (role === 'FRONT_DESK') {
    return false;
  }
  if (role === 'NURSE') {
    // AC 32: with the switch on or off, the nurse needs at least one live ward ("mine" means her wards).
    return scope?.hasWard === true;
  }
  return true;
}

/**
 * AC 29, 30: what the list area says in place of cards when a scoped user has no row. A nurse with no
 * live ward gets the zero-ward text; one with wards but nobody in them gets "No patients in your wards
 * right now.". Null when the list has rows, or when the scope is all (then "No one is admitted right
 * now." or the filter text of SAH-36 apply).
 */
export function scopedEmptyText(list: Pick<AdmissionList, 'scope' | 'items'>): string | null {
  const scope = list.scope;
  if (list.items.length > 0 || !isScoped(scope) || scope.admitted === 0) {
    return null;
  }
  return scope.hasWard === false ? NO_WARDS_TEXT : NO_WARD_PATIENTS_TEXT;
}

/** The search box takes at most this many characters (DEC-025 G-10). */
export const SEARCH_MAX_LENGTH = 100;
/** The polite result line waits this long after the last keystroke (the filter itself is instant). */
export const SEARCH_ANNOUNCE_MS = 400;
export const SEARCH_LABEL = 'Search admitted patients';
export const SEARCH_PLACEHOLDER = 'Name, UHID or bed';
export const NAME_TOOLTIP = 'Open bill and bed details';
export const DASH = '—';

/** Quick actions of the card: order, words and icons (mockup PC_A; the clinic's order comes from the setup). */
export type CardAction = CardActionKey;
export const CARD_ACTIONS: readonly { key: CardAction; label: string; icon: IconName }[] = [
  { key: 'nursing', label: 'Nursing', icon: 'nurse' },
  { key: 'emr', label: 'EMR', icon: 'emr' },
  { key: 'transfer', label: 'Transfer', icon: 'swap' },
  { key: 'discharge', label: 'Discharge', icon: 'door' },
  { key: 'deposit', label: 'Deposit', icon: 'deposit' },
];

/**
 * Actions the server allows for this caller (a false one is not drawn), in the order of the setup
 * (order: the published quick actions that are on) or, without one, in the default card order.
 */
export function visibleActions(
  card: AdmittedCardAbilities,
  order?: readonly CardAction[],
): typeof CARD_ACTIONS {
  const keys = order ?? CARD_ACTIONS.map((a) => a.key);
  return keys
    .map((k) => CARD_ACTIONS.find((a) => a.key === k))
    .filter((a): a is (typeof CARD_ACTIONS)[number] => !!a && card[a.key]);
}

export function actionLabel(
  action: { label: string },
  row: Pick<AdmittedPatientRow, 'patientName'>,
): string {
  return `${action.label} for ${row.patientName}`;
}

/** "Showing 24 of 61 patients" */
export function showingText(shown: number, total: number): string {
  return `Showing ${shown} of ${total} patients`;
}

/** Polite announcement after "Show 24 more" or "Show all". */
export function announceText(shown: number, total: number): string {
  return shown >= total ? `Showing all ${total} patients` : showingText(shown, total);
}

/**
 * Polite announcement after a search or ward change (R36-4): how many of the admitted patients match,
 * or the empty-result line when none do.
 */
export function filterAnnounceText(matches: number, total: number): string {
  if (matches === 0) {
    return EMPTY_FILTER_TEXT;
  }
  return matches === total ? `Showing all ${total} patients` : showingText(matches, total);
}

// ---- Search and ward filter (on the device; ADR-013 D8) ----

/**
 * Rows whose patient name, UHID, bed code or IP number contains the text (case, edge spaces and the way an accent is typed
 * ignored: composed or as a base letter plus a combining mark, DEC-032 G-10) and, when a ward is chosen, whose bed is in that ward
 * and, with "My patients" pressed, that the server marked `mine`. The order of the list is kept.
 */
export function filterRows(
  rows: readonly AdmittedPatientRow[],
  query: string,
  wardId: string,
  mine = false,
): AdmittedPatientRow[] {
  const fold = (text: string) => text.normalize('NFC').toLowerCase();
  const q = fold(query.trim());
  return rows.filter((r) => {
    if (wardId !== ALL_WARDS && r.wardId !== wardId) {
      return false;
    }
    // AC 33: "My patients" keeps the rows the server marked mine; a filter, never a security rule.
    if (mine && r.mine !== true) {
      return false;
    }
    if (q === '') {
      return true;
    }
    return [r.patientName, r.uhid ?? '', r.bedCode, r.ipNumber].some((v) => fold(v).includes(q));
  });
}

// ---- Texts of the card ----

/** Round initials: first letters of the first two words. */
export function initials(name: string): string {
  return name
    .trim()
    .split(/\s+/)
    .filter((w) => w !== '')
    .slice(0, 2)
    .map((w) => Array.from(w)[0].toUpperCase())
    .join('');
}

const SEX_LABEL: Record<string, string> = {
  m: 'Male',
  male: 'Male',
  f: 'Female',
  female: 'Female',
  o: 'Other',
  other: 'Other',
};

/** "Male", "Female" or "Other"; "—" when missing. Anything else is shown as sent. */
export function sexLabel(sex: string | null | undefined): string {
  const s = (sex ?? '').trim();
  return s === '' ? DASH : (SEX_LABEL[s.toLowerCase()] ?? s);
}

/** "UHID PT-0042 · 56 · Female"; a missing part is "—", never "null" or "undefined". */
export function identityLine(row: Pick<AdmittedPatientRow, 'uhid' | 'age' | 'sex'>): string {
  const uhid = (row.uhid ?? '').trim() === '' ? DASH : (row.uhid as string);
  const age = row.age === null || row.age === undefined ? DASH : String(row.age);
  return `UHID ${uhid} · ${age} · ${sexLabel(row.sex)}`;
}

/** "24 Oct 2026, 9:20 AM IST" */
export function admittedOnText(admittedAt: string): string {
  const when = formatWhen(admittedAt);
  return when === '' ? DASH : `${when} IST`;
}

/**
 * "Day 1 (today)" when the IST date of the admission is the IST date of the answer; otherwise the
 * billed day number of the list: "3 days" ("1 day" when the 24-hour cut gives day 1 on a later date).
 */
export function lengthOfStay(admittedAt: string, dayNumber: number, asOf: string): string {
  const at = new Date(admittedAt);
  const now = new Date(asOf);
  if (
    !Number.isNaN(at.getTime()) &&
    !Number.isNaN(now.getTime()) &&
    istToday(at) === istToday(now)
  ) {
    return 'Day 1 (today)';
  }
  return dayNumber === 1 ? '1 day' : `${dayNumber} days`;
}

export function roomText(row: Pick<AdmittedPatientRow, 'roomName'>): string {
  return (row.roomName ?? '').trim() === '' ? DASH : (row.roomName as string);
}

export function cardLabel(row: Pick<AdmittedPatientRow, 'patientName' | 'bedCode'>): string {
  return `${row.patientName}, bed ${row.bedCode}`;
}

// ---- Flags (ADR-013 D4) ----

export type FlagKind = 'escalation' | 'emergency-access' | 'allergy' | 'ews' | 'vitals' | 'home' | 'deposit' | 'round';
export type FlagTone = 'crit' | 'warn' | 'blue' | 'grey' | 'good';

export interface CardFlag {
  kind: FlagKind;
  text: string;
  tone: FlagTone;
  icon: IconName;
  /** Tooltip. */
  title: string;
  /** Safety flags are never folded into "+N" (OQ-36-1). */
  safety: boolean;
}

export const HIGH_RISK = 'HIGH';

/**
 * The flags of one card, in the order of ADR-013 D4: Not seen or Doctor reviewing, Allergy, EWS,
 * Vitals due, Going home today, Deposit low. Only server fields are used: `clinical` is absent for
 * FRONT_DESK, so the clinical flags are simply not built. "Allergy not recorded" and "None known"
 * give no flag (OQ-36-6). No Dose overdue flag. SAH-34 adds the grey Emergency access flag (key icon) for the grantee.
 */
export function cardFlags(row: AdmittedPatientRow, card: AdmittedCardAbilities): CardFlag[] {
  const flags: CardFlag[] = [];
  const c = row.clinical;
  if (c) {
    const e = c.escalation;
    if (e && e.status === 'OPEN') {
      const m = e.waitingMinutes;
      flags.push({
        kind: 'escalation',
        text: `Not seen · ${m} min`,
        tone: 'crit',
        icon: 'siren',
        title: `Early warning escalation raised ${m} min ago, ${e.pastTarget ? 'past' : 'within'} the ${e.targetMinutes} min target, not seen by a doctor`,
        safety: true,
      });
    } else if (e && e.status === 'ACK') {
      flags.push({
        kind: 'escalation',
        text: 'Doctor reviewing',
        tone: 'warn',
        icon: 'siren',
        title: 'Doctor reviewing',
        safety: true,
      });
    }
    if (c.allergy.status === 'RECORDED') {
      const text = (c.allergy.text ?? '').trim();
      const shown = text === '' ? 'Allergy recorded' : `Allergy: ${text}`;
      flags.push({
        kind: 'allergy',
        text: shown,
        tone: 'crit',
        icon: 'alert',
        title: shown,
        safety: true,
      });
    }
    const ews = c.latestEws;
    if (ews && (ews.risk === 'MEDIUM' || ews.risk === 'HIGH')) {
      const text = `EWS ${ews.score} · ${ews.risk === 'HIGH' ? 'High' : 'Medium'}`;
      flags.push({
        kind: 'ews',
        text,
        tone: ews.risk === 'HIGH' ? 'crit' : 'warn',
        icon: 'heart',
        title: text,
        safety: true,
      });
    }
    if (c.vitalsDue) {
      flags.push({
        kind: 'vitals',
        text: 'Vitals due',
        tone: 'warn',
        icon: 'clock',
        title: 'Vitals due',
        safety: false,
      });
    }
  }
  if (row.emergencyAccess) {
    // SAH-34 (mockup `sfFlags`): grey, key icon, right after the escalation flag. Only the person who opened
    // the grant gets `emergencyAccess` on the row. The end time is the server's (OQ-34-33); no countdown here.
    const until = timeOfInstant(row.emergencyAccess.expiresAt);
    const after = flags.filter((f) => f.kind === 'escalation').length;
    flags.splice(after, 0, {
      kind: 'emergency-access',
      text: EMERGENCY_ACCESS_LABEL,
      tone: 'grey',
      icon: 'key',
      title: until === '' ? EMERGENCY_ACCESS_LABEL : `Emergency access until ${until} IST`,
      safety: true,
    });
  }
  if (row.goingHomeToday) {
    flags.push({
      kind: 'home',
      text: 'Going home today',
      tone: 'blue',
      icon: 'door',
      title: 'Going home today',
      safety: false,
    });
  }
  if (row.depositLow && card.depositLowFlag) {
    flags.push({
      kind: 'deposit',
      text: 'Deposit low',
      tone: 'crit',
      icon: 'deposit',
      title: 'Deposit low',
      safety: false,
    });
  }
  if (c?.roundDoneToday) {
    // SAH-67-1: the doctor signed today's progress note. Good, with a tick and the text; folds into "+N".
    flags.push({
      kind: 'round',
      text: 'Round done',
      tone: 'good',
      icon: 'check',
      title: 'Round done today',
      safety: false,
    });
  }
  return flags;
}

export interface FoldedFlags {
  shown: CardFlag[];
  /** Flags behind the "+N" chip, in order; empty when nothing is folded. */
  folded: CardFlag[];
}

/** Flags the line draws before the rest fold into "+N" (the mockup draws one: `flags(1)`). */
export const FLAGS_BEFORE_FOLD = 1;

/**
 * Safety flags always show in full. The others (Vitals due, Going home today, Deposit low) show while
 * the line has fewer than {@link FLAGS_BEFORE_FOLD} flags and fold into "+N" after that.
 */
export function foldFlags(flags: readonly CardFlag[]): FoldedFlags {
  const shown: CardFlag[] = [];
  const folded: CardFlag[] = [];
  const safetyCount = flags.filter((f) => f.safety).length;
  let room = Math.max(0, FLAGS_BEFORE_FOLD - safetyCount);
  for (const f of flags) {
    if (f.safety) {
      shown.push(f);
    } else if (room > 0) {
      shown.push(f);
      room -= 1;
    } else {
      folded.push(f);
    }
  }
  return { shown, folded };
}

/** "2 more: Vitals due, Deposit low" (tooltip and accessible name of the "+N" chip). */
export function foldedText(folded: readonly CardFlag[]): string {
  return `${folded.length} more: ${folded.map((f) => f.text).join(', ')}`;
}

/** The whole card gets the red outline for a High score (mockup `pc-crit`). */
export function isCritical(row: AdmittedPatientRow): boolean {
  return row.clinical?.latestEws?.risk === HIGH_RISK;
}
