import { BedStatus, CategoryKey } from '../config/ipd/ipd.model';
import type { DischargeRecord, FinalBill } from './discharge.model';
import type { EmergencyCounts, EmergencyHandoff } from './emergency/emergency.model';
import type { AllergyStatus } from './emr/emr.model';
import type { EwsSummary } from './nursing/nursing.model';
import type { AdmissionSafety } from './safety/safety.model';
import type { CardDataGroup, CardLatestVitals, ResolvedCardSetup } from './card/card-setup.model';

/** Types for the SAH-23 endpoints in `docs/api/ipd.yaml`. Money is integer paise. */
export type AdmissionType = 'planned' | 'emergency' | 'daycare' | 'maternity';
export type Payer = 'Self' | 'Insurance' | 'Corporate';
export type PayMode = 'Cash' | 'UPI' | 'Card';
export type DepositState = 'OK' | 'DUE' | 'NOT_NEEDED';

export interface DepositByType {
  planned: number;
  emergency: number;
  daycare: number;
  maternity: number;
}

export interface CategoryAdd {
  gen: number;
  semi: number;
  pvt: number;
  icu: number;
}

export interface AdmissionRulesInput {
  depositPaise: DepositByType;
  categoryAddPaise: CategoryAdd;
  emergencyWithoutDeposit: boolean;
  alertPercent: number;
}

export interface AdmissionRules extends AdmissionRulesInput {
  confirmed: boolean;
}

export interface AdmissionDoctor {
  membershipId: string;
  name: string;
}

export interface AdmissionBedOption {
  id: string;
  code: string;
  wardName: string;
  categoryKey: CategoryKey;
  categoryName: string;
  ratePaise: number;
}

export interface AdmissionOptions {
  doctors: AdmissionDoctor[];
  beds: AdmissionBedOption[];
  rules: AdmissionRules;
  /** SAH-28. Set when the request had `emergencyCaseId`; null or absent otherwise. */
  emergencyCase?: EmergencyHandoff | null;
}

export interface AdmissionInput {
  patientId: string;
  doctorMembershipId: string;
  admissionType: AdmissionType;
  bedId: string;
  reason?: string | null;
  payer: Payer;
  depositPaise?: number | null;
  depositMode?: PayMode | null;
  consentSigned: boolean;
  /** SAH-28. The emergency case this admission comes from (source EMERGENCY). */
  emergencyCaseId?: string | null;
}

export interface AdmissionRow {
  id: string;
  ipNumber: string;
  patientId: string;
  patientName: string;
  age: number | null;
  sex: string | null;
  doctorName: string;
  bedCode: string;
  categoryName: string;
  wardName: string;
  admissionType: AdmissionType;
  payer: Payer;
  dayNumber: number;
  depositPaise: number;
  minimumDepositPaise?: number;
  runningBillPaise: number;
  depositLow: boolean;
  depositState: DepositState;
  admittedAt: string;
  /** SAH-27 (OQ-27-5). True when the summary was signed today and the patient is not yet discharged. */
  goingHomeToday?: boolean;
}

export interface AdmissionList {
  bedsTotal: number;
  bedsOccupied: number;
  bedsAvailable: number;
  canAdmit: boolean;
  depositAlerts: number;
  alertPercent: number;
  /** SAH-27. Discharged on today's IST date (N in "N discharged · M planned"). */
  dischargedToday?: number;
  /** SAH-27. Admitted patients flagged to go home today (M). */
  goingHomeToday?: number;
  /** SAH-28. Second tile "Emergency" and the count on the Emergency tab. */
  emergency: EmergencyCounts;
  /** SAH-33. The Safety tab count and the banner; null for a role without a Safety tab. */
  safety: AdmissionSafety | null;
  /** SAH-36. Database clock of this answer (the time in "Could not refresh. Showing data from ..."). */
  asOf: string;
  /** SAH-36. Every ward of the clinic in the Beds setup order, for the select "Ward". */
  wards: WardOption[];
  /** SAH-36. What the card shows for this caller, once for the whole list. */
  card: AdmittedCardAbilities;
  /** SAH-37. The published card setup the Cards view draws (read in the same snapshot as the rows). */
  cardSetup: ResolvedCardSetup;
  items: AdmittedPatientRow[];
}

/** SAH-36 `WardOption`: one option of the select "Ward". */
export interface WardOption {
  id: string;
  name: string;
}

/** SAH-36 `AdmittedCardAbilities`: the same for every row; a false action is not drawn. */
export interface AdmittedCardAbilities {
  nursing: boolean;
  emr: boolean;
  transfer: boolean;
  discharge: boolean;
  /** SAH-37. OWNER, FRONT_DESK; not in Read-only. The quick action Deposit. */
  deposit: boolean;
  depositLowFlag: boolean;
  /** SAH-37. The data groups this caller may see on the card. */
  visibleGroups: CardDataGroup[];
}

/** SAH-36 `CardEscalationFlag`: the open escalation of one patient (status OPEN or ACK). */
export interface CardEscalationFlag {
  status: 'OPEN' | 'ACK';
  waitingMinutes: number;
  targetMinutes: 15 | 60;
  pastTarget: boolean;
}

/** SAH-36 `CardAllergy`: `text` is present only when the status is RECORDED. */
export interface CardAllergy {
  status: AllergyStatus;
  text?: string;
}

/** SAH-36 `AdmittedClinicalFlags`: absent from the row for FRONT_DESK; null inside means "none exists". */
export interface AdmittedClinicalFlags {
  escalation: CardEscalationFlag | null;
  latestEws: EwsSummary | null;
  vitalsDue: boolean;
  allergy: CardAllergy;
  /** SAH-37. Present only when the published setup shows Diagnosis (or `cardFacts=all`). */
  diagnosis?: string;
  /** SAH-37. Present only when a vital reading or Last recorded is shown; null when none was recorded. */
  latestVitals?: CardLatestVitals | null;
}

/** SAH-36 `AdmittedPatientRow`: `AdmissionRow` plus the card facts. */
export interface AdmittedPatientRow extends AdmissionRow {
  uhid: string | null;
  wardId: string;
  roomName: string | null;
  clinical?: AdmittedClinicalFlags;
  /** SAH-37. OWNER and FRONT_DESK only, and only when the setup shows Mobile (absent otherwise). */
  mobile?: string | null;
}

export interface DepositEntry {
  id: string;
  amountPaise: number;
  mode: PayMode;
  collectedAt: string;
  collectedByName: string;
}

export interface AdmissionDetail extends AdmissionRow {
  mobile?: string | null;
  reason: string;
  deposits: DepositEntry[];
  canCollectDeposit: boolean;
  bill: RunningBill;
  canAddCharge: boolean;
  /** SAH-26. True for OWNER, FRONT_DESK, NURSE while admitted and IPD is not read-only. */
  canTransfer?: boolean;
  transferRules?: TransferRules;
  /** SAH-27. ADMITTED or DISCHARGED; a discharged admission is read-only. */
  status?: 'ADMITTED' | 'DISCHARGED';
  dischargedAt?: string | null;
  /** SAH-27. Shows the footer button "Start discharge". */
  canStartDischarge?: boolean;
  discharge?: DischargeRecord | null;
  finalBill?: FinalBill | null;
  /** SAH-30. Shows the footer button "Nursing": OWNER, NURSE and DOCTOR (also read-only or discharged). */
  canOpenNursing?: boolean;
  /** SAH-31. Shows the footer button "EMR": OWNER, DOCTOR, NURSE while ADMITTED (also in Read-only IPD). */
  canOpenEmr?: boolean;
  /** SAH-30, for the later patient card. Null for a role without vitals access. */
  vitalsDue?: boolean | null;
  /** SAH-30, for the later patient card. Null when no vitals, or for a role without vitals access. */
  latestEws?: { score: number; risk: 'LOW' | 'MEDIUM' | 'HIGH'; recordedAt: string } | null;
}

/** SAH-26 `TransferRules`: `upgradeNeedsOwner` true = "Owner approves"; `newRateFrom` same | next. */
export type NewRateFrom = 'same' | 'next';

export interface TransferRules {
  upgradeNeedsOwner: boolean;
  newRateFrom: NewRateFrom;
}

export const UPGRADE_CHOICES: readonly { value: boolean; label: string }[] = [
  { value: true, label: 'Owner approves' },
  { value: false, label: 'Front desk can do it' },
];

export const NEW_RATE_CHOICES: readonly { value: NewRateFrom; label: string }[] = [
  { value: 'same', label: 'Same day' },
  { value: 'next', label: 'Next day' },
];

/** SAH-25 running bill (`RunningBill`, `BillLine` in docs/api/ipd.yaml). Money is integer paise. */
export interface BillLine {
  name: string;
  unitPaise: number;
  qty: number;
  amountPaise: number;
  /** SAH-26. True for a line of a bed the patient was moved away from. Absent or false otherwise. */
  segment?: boolean;
}

export interface RunningBill {
  billDays: number;
  bedDayCut: 'midnight' | '24h';
  categoryName: string;
  multiplier: number;
  lines: BillLine[];
  totalPaise: number;
  paidPaise: number;
  depositPercent: number | null;
  alertPercent?: number;
}

export type ChargeServiceKey =
  | 'visit'
  | 'ivfluid'
  | 'injection'
  | 'cbc'
  | 'xray'
  | 'oxygen'
  | 'dressing'
  | 'ecg'
  | 'nebulisation'
  | 'physio';

export interface ChargeService {
  key: ChargeServiceKey;
  name: string;
  basePaise: number;
}

/** Alert share used when the bill does not carry one (the Admission and deposits default). */
export const DEFAULT_ALERT_PERCENT = 80;

/** Price of an extra charge for the patient's category: base x multiplier, half-up to whole paise. */
export function chargePricePaise(basePaise: number, multiplier: number): number {
  return Math.round(Number((basePaise * multiplier).toFixed(6)));
}

/** Heading note of the Running bill panel. */
export function billNote(bill: RunningBill): string {
  const cut = bill.bedDayCut === 'midnight' ? 'Bed day counted at midnight' : 'Bed day counted every 24 hours';
  return bill.multiplier !== 1 ? `${cut} · ${bill.categoryName} rate ×${bill.multiplier} on services` : cut;
}

export const ADMISSION_TYPES: readonly { value: AdmissionType; label: string }[] = [
  { value: 'planned', label: 'Planned' },
  { value: 'emergency', label: 'Emergency' },
  { value: 'daycare', label: 'Day-care' },
  { value: 'maternity', label: 'Maternity' },
];

export const ADMISSION_TYPE_LABEL: Record<AdmissionType, string> = {
  planned: 'Planned',
  emergency: 'Emergency',
  daycare: 'Day-care',
  maternity: 'Maternity',
};

export const PAYERS: readonly Payer[] = ['Self', 'Insurance', 'Corporate'];
export const PAY_MODES: readonly PayMode[] = ['Cash', 'UPI', 'Card'];

/** Categories in the order the Admit sheet and the setup page list them. */
export const CATEGORY_ORDER: readonly CategoryKey[] = ['gen', 'semi', 'pvt', 'icu'];

export const MAX_DEPOSIT_RUPEES = 1000000;
export const ALERT_MIN = 50;
export const ALERT_MAX = 100;
export const ALERT_STEP = 5;
export const MAX_REASON = 200;

/** Minimum deposit = base deposit for the type + add-on of the bed's category (in paise). */
export function minimumDepositPaise(
  rules: AdmissionRulesInput,
  type: AdmissionType,
  category: CategoryKey | null | undefined,
): number {
  return rules.depositPaise[type] + (category ? rules.categoryAddPaise[category] : 0);
}

/** "101-A · 56 M" style line under the patient name in the Admitted list. */
export function admissionSubline(row: AdmissionRow): string {
  const who = [row.age, row.sex].filter((v) => v !== null && v !== undefined && v !== '').join(' ');
  return [row.ipNumber, who, ADMISSION_TYPE_LABEL[row.admissionType], `day ${row.dayNumber}`]
    .filter((v) => v)
    .join(' · ');
}

export interface StatusPill {
  text: string;
  css: string;
}

/**
 * Status pill of the Admitted list (mockup `a.out ? teal : payer !== 'Self' ? payer : low ? ... : OK`).
 * Exactly one pill: the teal "Going home today" when the patient goes home today (it replaces the
 * payer and deposit pills), else "Insurance" / "Corporate" when the payer is not Self, else
 * "Deposit low" when the running bill has reached the alert share, else "Deposit OK".
 */
export function statusPills(row: AdmissionRow): StatusPill[] {
  if (row.goingHomeToday) {
    return [{ text: 'Going home today', css: 'p-teal' }];
  }
  if (row.payer !== 'Self') {
    return [{ text: row.payer, css: 'p-blue' }];
  }
  return [row.depositLow ? { text: 'Deposit low', css: 'p-crit' } : { text: 'Deposit OK', css: 'p-good' }];
}

/** Hint under "Deposit now" (acceptance criterion 3). `typeLabel` and `categoryName` as shown. */
export function depositHint(args: {
  minimumPaise: number;
  type: AdmissionType;
  categoryName: string;
  emergencyWithoutDeposit: boolean;
  payer: Payer;
  format: (paise: number) => string;
}): string {
  const parts = [
    `Minimum ${args.format(args.minimumPaise)} · ${ADMISSION_TYPE_LABEL[args.type].toLowerCase()}${
      args.categoryName ? ', ' + args.categoryName : ''
    }`,
  ];
  if (args.type === 'emergency' && args.emergencyWithoutDeposit) {
    parts.push('emergency can be admitted first');
  }
  if (args.payer !== 'Self') {
    parts.push('not needed for ' + args.payer.toLowerCase());
  }
  return parts.join(' · ');
}

/** Whole rupees from text (digits only, Indian commas allowed), or null when not valid. */
export function parseWholeRupees(text: string, max = MAX_DEPOSIT_RUPEES): number | null {
  const t = text.trim().replace(/,/g, '');
  if (!/^\d+$/.test(t)) {
    return null;
  }
  const value = Number(t);
  return value <= max ? value : null;
}

/** SAH-24 bed board (`BedBoard` in docs/api/ipd.yaml). */
export interface BoardBed {
  id: string;
  code: string;
  categoryKey: CategoryKey;
  categoryName: string;
  ratePaise: number;
  status: BedStatus;
  admissionId?: string | null;
  patientName?: string | null;
  dayNumber?: number | null;
  doctorShortName?: string | null;
  depositLow?: boolean | null;
  /** SAH-27. Set when occupied: the tile shows the flag "Going home today" or "Deposit low", never both (the go flag wins). */
  goingHomeToday?: boolean | null;
}

export interface BoardRoom {
  id: string | null;
  name: string;
  beds: BoardBed[];
}

export interface BoardWard {
  id: string;
  name: string;
  station: string;
  occupied: number;
  total: number;
  hasFreeBed: boolean;
  firstFreeBedId?: string | null;
  rooms: BoardRoom[];
}

export interface BedBoard {
  state: 'trial' | 'on' | 'readonly';
  roomLevel: boolean;
  canAdmit: boolean;
  canChangeBeds: boolean;
  counts: Record<BedStatus, number>;
  wards: BoardWard[];
}

/** Mockup `BSTAT`: label, pill class and icon of each bed status. */
export const BED_STATUS: Record<
  BedStatus,
  { label: string; pill: string; icon: 'check' | 'user' | 'clock' | 'calendar' | 'lock' }
> = {
  free: { label: 'Available', pill: 'p-good', icon: 'check' },
  occ: { label: 'Occupied', pill: 'p-teal', icon: 'user' },
  clean: { label: 'Cleaning', pill: 'p-warn', icon: 'clock' },
  res: { label: 'Reserved', pill: 'p-sec', icon: 'calendar' },
  blk: { label: 'Blocked', pill: 'p-wait', icon: 'lock' },
};

/** Legend order of the mockup. */
export const BED_STATUS_ORDER: readonly BedStatus[] = ['free', 'occ', 'clean', 'res', 'blk'];

/** "Bed 101-A, General, Occupied, Sunil Jadhav" (the name only when occupied). */
export function bedAriaLabel(bed: BoardBed): string {
  const parts = [`Bed ${bed.code}`, bed.categoryName, BED_STATUS[bed.status].label];
  if (bed.status === 'occ' && bed.patientName) {
    parts.push(bed.patientName);
    if (bed.goingHomeToday) {
      parts.push('going home today');
    } else if (bed.depositLow) {
      parts.push('deposit low');
    }
  }
  return parts.join(', ');
}

/**
 * The line under the "Beds and admissions" title. A part the server did not send is left out, so
 * the page never prints "undefined%".
 */
export function ipdLead(bedDayCut: string | null | undefined, alertPercent: number | null | undefined): string {
  const parts = ['Beds and admissions'];
  if (bedDayCut === 'midnight') {
    parts.push('Bed and nursing charges post at midnight');
  } else if (bedDayCut === '24h') {
    parts.push('Bed and nursing charges post every 24 hours');
  }
  if (typeof alertPercent === 'number' && Number.isFinite(alertPercent)) {
    parts.push(`deposit alert at ${alertPercent}% of the deposit`);
  }
  return parts.join(' · ');
}

/** "101-B · General · ₹800" */
export function transferOptionLabel(bed: AdmissionBedOption, format: (paise: number) => string): string {
  return `${bed.code} · ${bed.categoryName} · ${format(bed.ratePaise)}`;
}

/** Toast after a move; "from tomorrow" when the rule is Next day. */
export function transferToast(args: {
  patientName: string;
  fromCode: string;
  toCode: string;
  categoryName: string;
  newRateFrom: NewRateFrom;
}): string {
  const when = args.newRateFrom === 'next' ? 'from tomorrow' : 'from today';
  return `${args.patientName} moved from ${args.fromCode} to ${args.toCode} · ${args.categoryName} rate ${when} · ${args.fromCode} sent for cleaning`;
}
