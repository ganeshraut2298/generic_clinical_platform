import { formatWhen, istToday } from '../../config/config-format';
import type { IconName } from '../../../shared/icon/icon.component';
import type { AdmittedCardAbilities, AdmittedPatientRow } from '../ipd.model';

/**
 * SAH-36 Admitted patient cards (mockup `pcCard`, `sfFlags`, `pcFiltered`, `pcmore`; ADR-013).
 * Pure helpers: every flag, number and minute is the server's (`asOf`, `clinical`); the browser never
 * reads its own clock for them and never decides a clinical rule. Names, UHIDs and allergy text are
 * returned for the screen only and are never logged or stored.
 */

export type AdmittedView = 'cards' | 'table';
export type CardsPerRow = 2 | 3 | 4;
/** Where the admission sheet opens when it is started from a card (ADR-013 D6). In memory only. */
export type AdmissionStartView = 'details' | 'move' | 'discharge';

export const PAGE_SIZE = 24;
export const DEFAULT_COLS: CardsPerRow = 3;
export const COLS_CHOICES: readonly CardsPerRow[] = [2, 3, 4];
export const ALL_WARDS = 'all';

export const EMPTY_FILTER_TEXT = 'No patients match these filters.';
export const SEARCH_LABEL = 'Search admitted patients';
export const SEARCH_PLACEHOLDER = 'Name, UHID or bed';
export const NAME_TOOLTIP = 'Open bill and bed details';
export const DASH = '—';

/** Action row of the Standard card: order, words and icons (mockup `PC_A`). */
export type CardAction = 'nursing' | 'emr' | 'transfer' | 'discharge';
export const CARD_ACTIONS: readonly { key: CardAction; label: string; icon: IconName }[] = [
  { key: 'nursing', label: 'Nursing', icon: 'nurse' },
  { key: 'emr', label: 'EMR', icon: 'emr' },
  { key: 'transfer', label: 'Transfer', icon: 'swap' },
  { key: 'discharge', label: 'Discharge', icon: 'door' },
];

/** Actions the server allows for this caller, in card order (a false one is not drawn). */
export function visibleActions(card: AdmittedCardAbilities): typeof CARD_ACTIONS {
  return CARD_ACTIONS.filter((a) => card[a.key]);
}

export function actionLabel(action: { label: string }, row: Pick<AdmittedPatientRow, 'patientName'>): string {
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
 * Rows whose patient name, UHID, bed code or IP number contains the text (case and edge spaces
 * ignored) and, when a ward is chosen, whose bed is in that ward. The order of the list is kept.
 */
export function filterRows(
  rows: readonly AdmittedPatientRow[],
  query: string,
  wardId: string,
): AdmittedPatientRow[] {
  const q = query.trim().toLowerCase();
  return rows.filter((r) => {
    if (wardId !== ALL_WARDS && r.wardId !== wardId) {
      return false;
    }
    if (q === '') {
      return true;
    }
    return [r.patientName, r.uhid ?? '', r.bedCode, r.ipNumber].some((v) => v.toLowerCase().includes(q));
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
  if (!Number.isNaN(at.getTime()) && !Number.isNaN(now.getTime()) && istToday(at) === istToday(now)) {
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

export type FlagKind = 'escalation' | 'allergy' | 'ews' | 'vitals' | 'home' | 'deposit';
export type FlagTone = 'crit' | 'warn' | 'blue';

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
 * give no flag (OQ-36-6). No Dose overdue flag and no Emergency access flag in this story.
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
      flags.push({ kind: 'allergy', text: shown, tone: 'crit', icon: 'alert', title: shown, safety: true });
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
      flags.push({ kind: 'vitals', text: 'Vitals due', tone: 'warn', icon: 'clock', title: 'Vitals due', safety: false });
    }
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
    flags.push({ kind: 'deposit', text: 'Deposit low', tone: 'crit', icon: 'deposit', title: 'Deposit low', safety: false });
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
