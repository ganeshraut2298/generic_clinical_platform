import { BookingRules, bookingRuleChips } from '../appointments.model';
import { ApiError } from '../../../core/http/problem-detail';
import { addDays, formatClockLabel, formatDisplayDate, isoWeekday } from '../../../shared/format/ist-date';
import { formatInrPaise } from '../../../shared/format/inr';

/**
 * Types for `GET /opd/desk/snapshot` and `GET /opd/desk/doctors/{id}/day-slots`
 * (docs/api/opd.yaml 0.9.0, SAH-90). Field names are the contract's; none is invented.
 * Only the fields the read side uses are typed from the token-panel and follow-up result schemas (SAH-92, SAH-93).
 */

export type DeskTodayStatus = 'IN_OPD' | 'STARTS_LATER' | 'ON_LEAVE' | 'FULL_TODAY' | 'OFF_TODAY';
export type DeskDepartmentSource = 'EMR_DEPARTMENT' | 'SPECIALTY' | 'NONE';
export type DeskColumn = 'BOOKED' | 'WAITING' | 'IN_CONSULTATION' | 'DONE';
export type DeskCardKind = 'APPOINTMENT' | 'WALK_IN';
export type DeskVisitType = 'NEW' | 'FOLLOW_UP' | 'REVISIT' | 'EMERGENCY';
export type DeskAppointmentStatus = 'BOOKED' | 'ARRIVED' | 'NOSHOW';
export type DeskVisitStage = 'WAITING' | 'VITALS' | 'WITH_DOCTOR' | 'COUNSEL' | 'BILLING' | 'DONE';
export type DeskCardTag = 'NO_SHOW' | 'DUE_NOW' | 'DOCTOR_ON_LEAVE' | 'VITALS';
export type DeskPrimaryAction =
  | 'CHECK_IN'
  | 'REBOOK'
  | 'MOVE_TO_NEW_SLOT'
  | 'START_CONSULTATION'
  | 'COMPLETE_VISIT'
  | 'SET_FOLLOW_UP'
  | 'NONE';
export type DeskFollowUpState = 'NONE' | 'OPEN' | 'BOOKED' | 'NOT_NEEDED' | 'RETURNED';
export type DeskSlotGroupLabel = 'MORNING' | 'EVENING';
export type FollowUpFeeRule = 'free' | 'half' | 'full';

export interface DeskDayCount {
  date: string;
  freeCount: number;
  working: boolean;
  onLeave: boolean;
}

export interface DeskDoctor {
  doctorMembershipId: string;
  name: string;
  department: string | null;
  departmentSource: DeskDepartmentSource;
  room: string | null;
  slotMinutes: number;
  consultFeePaise: number;
  followUpFeePaise: number;
  closeBeforeHours: number;
  todayStatus: DeskTodayStatus;
  startsAt: string | null;
  leaveUntil: string | null;
  freeToday: number;
  nextFree: { date: string; freeCount: number } | null;
  days: DeskDayCount[];
}

export interface DeskSlot {
  time: string;
  insideCutOff: boolean;
}

export interface DeskSlotGroup {
  label: DeskSlotGroupLabel;
  slots: DeskSlot[];
}

export interface DeskDaySlots {
  doctorMembershipId: string;
  date: string;
  working: boolean;
  onLeave: boolean;
  slotMinutes: number;
  closeBeforeHours: number;
  freeCount: number;
  groups: DeskSlotGroup[];
  nextFreeDate: string | null;
}

export interface DeskCounters {
  booked: number;
  waiting: number;
  inConsult: number;
  done: number;
  freeToday: number;
}

export interface DeskVisitFollowUp {
  followUpId: string | null;
  state: DeskFollowUpState;
  dueDate: string | null;
  appointmentId: string | null;
  apptDate: string | null;
  apptTime: string | null;
}

export interface DeskBoardCard {
  key: string;
  kind: DeskCardKind;
  appointmentId: string | null;
  appointmentVersion: number | null;
  visitId: string | null;
  column: DeskColumn;
  time: string;
  patientId: string;
  patientName: string;
  doctorMembershipId: string;
  doctorName: string;
  visitType: DeskVisitType;
  appointmentStatus: DeskAppointmentStatus | null;
  stage: DeskVisitStage | null;
  nextStage: DeskVisitStage | null;
  tokenNo: number | null;
  checkedInAt: string | null;
  consultStartedAt?: string | null;
  consultEndedAt?: string | null;
  tags: DeskCardTag[];
  primaryAction: DeskPrimaryAction;
  followUp: DeskVisitFollowUp | null;
}

export interface DeskBoard {
  date: string;
  truncated: boolean;
  cards: DeskBoardCard[];
}

export interface DeskFollowUpToBook {
  followUpId: string;
  patientId: string;
  patientName: string;
  doctorMembershipId: string;
  doctorName: string;
  dueDate: string;
  status: 'DUE' | 'MISSED';
  daysLate: number;
  reason: string;
}

export interface DeskSnapshot {
  generatedAt: string;
  today: string;
  bookingRules: BookingRules;
  followUpFee: FollowUpFeeRule;
  counters: DeskCounters;
  doctors: { total: number; items: DeskDoctor[] };
  board: DeskBoard;
  followUpsToBook: { total: number; items: DeskFollowUpToBook[] };
  selected: DeskDaySlots | null;
}

export type DeskIntervalKey = 'D7' | 'D15' | 'M1';

/** `GET /opd/desk/visits/{visitId}/token-panel` and `tokenPanel` of the check-in answer (SAH-92-2). */
export interface DeskTokenPanel {
  visitId: string;
  tokenNo: number;
  tokenDate: string;
  patientName: string;
  doctorMembershipId: string;
  doctorName: string;
  room: string | null;
  stage: DeskVisitStage;
  checkedInAt: string;
  patientsAhead: number;
  estimatedWaitMinutes: number;
  slotMinutes: number;
}

/** The appointment fields the desk reads back from a write (docs/api/opd.yaml Appointment). */
export interface DeskAppointment {
  id: string;
  patientId: string;
  patientName: string;
  doctorMembershipId: string;
  doctorName: string;
  apptDate: string;
  apptTime: string;
  status: 'BOOKED' | 'ARRIVED' | 'NOSHOW' | 'CANCELLED';
  visitType: 'NEW' | 'FOLLOW_UP';
  visitId?: string | null;
  tokenNo?: number | null;
  version?: number;
}

export interface DeskCheckIn extends DeskAppointment {
  tokenPanel: DeskTokenPanel;
}

export interface DeskFollowUpResult {
  followUpId: string;
  state: 'BOOKED' | 'NOT_NEEDED';
  dueDate: string;
  appointment: DeskAppointment | null;
}

export interface DeskBookInput {
  patientId: string;
  doctorMembershipId: string;
  apptDate: string;
  apptTime: string;
  visitType: 'NEW' | 'FOLLOW_UP';
  source: 'FRONT_DESK';
}

export interface DeskRescheduleInput {
  version: number;
  doctorMembershipId: string;
  apptDate: string;
  apptTime: string;
}

export interface DeskVisitFollowUpInput {
  intervalKey: DeskIntervalKey;
  apptDate: string;
  apptTime: string;
  visitType?: 'NEW' | 'FOLLOW_UP';
}

export interface DeskFollowUpBookInput {
  doctorMembershipId: string;
  apptDate: string;
  apptTime: string;
  visitType?: 'NEW' | 'FOLLOW_UP';
}

/** Why the panel is in Pick a slot or Confirm booking: a moved appointment or a follow-up from the idle list. */
export interface DeskPickContext {
  /** The booked or no-show card being moved ("Move to new slot", "Rebook"). */
  reschedule: DeskBoardCard | null;
  /** The follow-up from the idle list ("Find slot"). */
  followUp: DeskFollowUpToBook | null;
}

export const NO_PICK_CONTEXT: DeskPickContext = { reschedule: null, followUp: null };

export interface DeskBookedSummary {
  patientName: string;
  doctorName: string;
  date: string;
  time: string;
}

/** The action panel modes (SAH-90 to SAH-93). One mode at a time. */
export type DeskPanelMode =
  | { kind: 'idle' }
  | ({ kind: 'book'; doctorMembershipId: string; date: string; time: string; insideCutOff: boolean } & DeskPickContext)
  | ({ kind: 'pick'; doctorMembershipId: string } & DeskPickContext)
  | ({ kind: 'booked' } & DeskBookedSummary)
  | { kind: 'token'; visitId: string; panel: DeskTokenPanel | null; issued: boolean }
  | { kind: 'follow'; visitId: string; patientName: string; doctorMembershipId: string; doctorName: string };

export const DESK_COLUMNS: readonly { key: DeskColumn; title: string }[] = [
  { key: 'BOOKED', title: 'Booked' },
  { key: 'WAITING', title: 'Waiting' },
  { key: 'IN_CONSULTATION', title: 'In consultation' },
  { key: 'DONE', title: 'Done' },
];

export const DESK_ACTION_LABELS: Record<Exclude<DeskPrimaryAction, 'NONE'>, string> = {
  CHECK_IN: 'Check in · token',
  REBOOK: 'Rebook',
  MOVE_TO_NEW_SLOT: 'Move to new slot',
  START_CONSULTATION: 'Start consultation',
  COMPLETE_VISIT: 'Complete visit',
  SET_FOLLOW_UP: 'Set follow-up',
};

export const DESK_TAG_LABELS: Record<DeskCardTag, string> = {
  NO_SHOW: 'No-show',
  DUE_NOW: 'Due now',
  DOCTOR_ON_LEAVE: 'Doctor on leave',
  VITALS: 'Vitals',
};

export const ALL_DEPARTMENTS = 'All departments';

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "Dr. Meera Kulkarni" -> "Dr. Meera K.". Two-part or shorter names stay as saved. */
export function shortDoctorName(name: string): string {
  const parts = name.trim().split(/\s+/);
  if (parts.length < 3) {
    return name.trim();
  }
  const last = parts[parts.length - 1];
  return `${parts.slice(0, -1).join(' ')} ${last.charAt(0).toUpperCase()}.`;
}

/** Two letters for the rail avatar, ignoring "Dr.". */
export function doctorInitials(name: string): string {
  const parts = name
    .trim()
    .split(/\s+/)
    .filter((p) => !/^dr\.?$/i.test(p));
  const letters = parts.length > 1 ? parts[0].charAt(0) + parts[parts.length - 1].charAt(0) : (parts[0] ?? '?').slice(0, 2);
  return letters.toUpperCase();
}

/** `2026-11-25` -> `Wed 25`. */
export function dayShortLabel(iso: string): string {
  return `${WEEKDAYS[isoWeekday(iso)]} ${Number(iso.slice(8, 10))}`;
}

/** `2026-11-24` -> `Tue, 24 Nov`. */
export function dayLongLabel(iso: string): string {
  return `${WEEKDAYS[isoWeekday(iso)]}, ${Number(iso.slice(8, 10))} ${MONTHS[Number(iso.slice(5, 7)) - 1]}`;
}

/** The strip button label: "Today" or "Wed 25". */
export function stripDayLabel(iso: string, today: string): string {
  return iso === today ? 'Today' : dayShortLabel(iso);
}

export function freeCountText(count: number): string {
  return count > 0 ? `${count} free` : 'No slots';
}

/** "Today · 7 free", "Wed 25 · 8 free" or "No slots". */
export function nextFreeText(doctor: DeskDoctor, today: string): string {
  const next = doctor.nextFree;
  if (!next) {
    return 'No slots';
  }
  return `${next.date === today ? 'Today' : dayShortLabel(next.date)} · ${next.freeCount} free`;
}

/** Status words are always text; an indicator shape is added in the template, never colour alone. */
export function doctorStatusText(doctor: DeskDoctor): string {
  switch (doctor.todayStatus) {
    case 'IN_OPD':
      return 'In OPD';
    case 'STARTS_LATER':
      return doctor.startsAt ? `From ${formatClockLabel(doctor.startsAt)}` : 'Starts later';
    case 'ON_LEAVE':
      return doctor.leaveUntil ? `On leave till ${WEEKDAYS[isoWeekday(doctor.leaveUntil)]}` : 'On leave';
    case 'FULL_TODAY':
      return 'Full today';
    case 'OFF_TODAY':
      return 'Off today';
  }
}

/** Indicator class: "ok" green, "wait" amber, "off" grey. Paired with the status text. */
export function doctorStatusTone(status: DeskTodayStatus): 'ok' | 'wait' | 'off' {
  if (status === 'IN_OPD') {
    return 'ok';
  }
  if (status === 'STARTS_LATER' || status === 'FULL_TODAY') {
    return 'wait';
  }
  return 'off';
}

export function doctorFeeLine(doctor: DeskDoctor): string {
  const followUp = doctor.followUpFeePaise === 0 ? 'Free' : formatInrPaise(doctor.followUpFeePaise);
  return `Consultation ${formatInrPaise(doctor.consultFeePaise)} · Follow-up ${followUp}`;
}

/** Department filter options: "All departments" then each department once, by name. */
export function departmentOptions(doctors: readonly DeskDoctor[]): string[] {
  const names = new Set<string>();
  for (const d of doctors) {
    if (d.department) {
      names.add(d.department);
    }
  }
  return [ALL_DEPARTMENTS, ...[...names].sort((a, b) => a.localeCompare(b))];
}

/**
 * The rail: department narrows; then the search narrows by name or department, except that a
 * search no doctor matches does not narrow (mockup, SAH-90-2).
 */
export function filterRailDoctors(doctors: readonly DeskDoctor[], department: string, query: string): DeskDoctor[] {
  const byDept = department === ALL_DEPARTMENTS ? [...doctors] : doctors.filter((d) => d.department === department);
  const q = query.trim().toLowerCase();
  if (!q) {
    return byDept;
  }
  const matches = byDept.filter((d) => d.name.toLowerCase().includes(q) || (d.department ?? '').toLowerCase().includes(q));
  return matches.length > 0 ? matches : byDept;
}

/** Board cards: scope first, then the patient search; a search with no match does not narrow. */
export function filterBoardCards(
  cards: readonly DeskBoardCard[],
  scope: 'all' | 'selected',
  selectedDoctorId: string | null,
  query: string,
): DeskBoardCard[] {
  const scoped = scope === 'selected' && selectedDoctorId ? cards.filter((c) => c.doctorMembershipId === selectedDoctorId) : [...cards];
  const q = query.trim().toLowerCase();
  if (!q) {
    return scoped;
  }
  const matches = scoped.filter((c) => c.patientName.toLowerCase().includes(q));
  return matches.length > 0 ? matches : scoped;
}

/** Cards of one column, earliest first (stable for equal times). */
export function columnCards(cards: readonly DeskBoardCard[], column: DeskColumn): DeskBoardCard[] {
  return cards.filter((c) => c.column === column).sort((a, b) => a.time.localeCompare(b.time));
}

export function visitTypeText(type: DeskVisitType): string {
  switch (type) {
    case 'NEW':
      return 'New';
    case 'FOLLOW_UP':
      return 'Follow-up';
    case 'REVISIT':
      return 'Revisit';
    case 'EMERGENCY':
      return 'Emergency';
  }
}

export function tokenText(tokenNo: number | null): string {
  return tokenNo === null ? '' : `T-${tokenNo}`;
}

/** The tag that replaces an action on a Done card, or null when "Set follow-up" is offered. */
export function followUpTagText(card: DeskBoardCard): string | null {
  const f = card.followUp;
  if (!f || card.column !== 'DONE') {
    return null;
  }
  if (f.state === 'BOOKED' && f.apptDate && f.apptTime) {
    return `Follow-up ${formatDisplayDate(f.apptDate)} · ${formatClockLabel(f.apptTime)}`;
  }
  if (f.state === 'NOT_NEEDED') {
    return 'No follow-up needed';
  }
  if (f.state === 'RETURNED') {
    return 'Patient returned';
  }
  return null;
}

export interface DeskRuleChip {
  label: string;
  disabled: boolean;
}

/** Rule chips drawn from the saved values. Reminder and self-booking chips are disabled (not in this phase). */
export function deskRuleChips(rules: BookingRules): DeskRuleChip[] {
  return bookingRuleChips(rules).map((label) => ({
    label,
    disabled: /WhatsApp|Booking by staff only/.test(label),
  }));
}

/** `Tue, 24 Nov 2026 · 11:05 AM` from the IST date and the clock text. */
export function headerDateTime(isoDate: string, clock: string): string {
  return `${WEEKDAYS[isoWeekday(isoDate)]}, ${formatDisplayDate(isoDate)} · ${clock}`;
}

export function noSlotsMessage(date: string, nextFreeDate: string | null): string {
  return nextFreeDate
    ? `No slots on ${dayLongLabel(date)}. Next free day: ${dayLongLabel(nextFreeDate)}.`
    : `No slots on ${dayLongLabel(date)} or later this week.`;
}

export function inrOrFree(paise: number): string {
  return paise === 0 ? 'Free' : formatInrPaise(paise);
}

/** The fee of a visit type from the doctor's snapshot fees (C1: the follow-up fee is already computed). */
export function visitFeePaise(doctor: DeskDoctor, type: 'NEW' | 'FOLLOW_UP'): number {
  return type === 'FOLLOW_UP' ? doctor.followUpFeePaise : doctor.consultFeePaise;
}

/** "Confirm booking · ₹400" or "Confirm booking · Free". */
export function confirmBookingLabel(feePaise: number): string {
  return `Confirm booking · ${inrOrFree(feePaise)}`;
}

/** "Wed, 25 Nov · 10:30 AM · 15 min". */
export function slotSummary(date: string, time: string, slotMinutes: number): string {
  return `${dayLongLabel(date)} · ${formatClockLabel(time)} · ${slotMinutes} min`;
}

/** The wait line of the token panel: "Next in line" or "3 ahead · about 45 min". */
export function tokenWaitText(panel: Pick<DeskTokenPanel, 'patientsAhead' | 'estimatedWaitMinutes'>): string {
  return panel.patientsAhead === 0
    ? 'Next in line'
    : `${panel.patientsAhead} ahead · about ${panel.estimatedWaitMinutes} min`;
}

export const FOLLOW_INTERVALS: readonly { key: DeskIntervalKey; label: string }[] = [
  { key: 'D7', label: '+7 days' },
  { key: 'D15', label: '+15 days' },
  { key: 'M1', label: '+1 month' },
];

/** The follow-up date from today (IST): +7 days, +15 days or one calendar month (end of month clamped). */
export function followUpDate(today: string, key: DeskIntervalKey): string {
  if (key === 'D7') {
    return addDays(today, 7);
  }
  if (key === 'D15') {
    return addDays(today, 15);
  }
  const year = Number(today.slice(0, 4));
  const month = Number(today.slice(5, 7));
  const day = Number(today.slice(8, 10));
  const target = month === 12 ? { y: year + 1, m: 1 } : { y: year, m: month + 1 };
  const last = new Date(Date.UTC(target.y, target.m, 0)).getUTCDate();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${target.y}-${pad(target.m)}-${pad(Math.min(day, last))}`;
}

/** "Rebooking the 11:15 AM appointment with Dr. Meera K." or "Moving the ..." */
export function rescheduleHint(card: DeskBoardCard): string {
  const verb = card.tags.includes('NO_SHOW') || card.appointmentStatus === 'NOSHOW' ? 'Rebooking' : 'Moving';
  return `${verb} the ${formatClockLabel(card.time)} appointment with ${shortDoctorName(card.doctorName)}`;
}

/** "Follow-up with Dr. Meera K. · Thyroid report review". */
export function followUpHint(item: DeskFollowUpToBook): string {
  return `Follow-up with ${shortDoctorName(item.doctorName)} · ${item.reason}`;
}

export interface DeskProblem {
  message: string;
  slug: string;
  /** The board changed under the user: refresh it. */
  refreshBoard: boolean;
  /** The slot is gone: refresh the finder. */
  slotTaken: boolean;
}

export const BOOK_NETWORK_MESSAGE = 'Could not book. Check the connection and try again.';
export const ACTION_NETWORK_MESSAGE = 'Could not save. Check the connection and try again.';
const BUSY_MESSAGE = 'Sahaj is busy. Nothing was saved. Wait a moment and try again.';

function slugOf(type: string): string {
  return type.slice(type.lastIndexOf('/') + 1);
}

function hoursText(hours: number): string {
  return hours === 1 ? '1 hour' : `${hours} hours`;
}

/**
 * Maps a failed write to plain English by the problem type of docs/api/opd.yaml 0.9.0. An unknown type falls
 * back to the server's own detail (already plain English) or a generic line. Never logs the problem.
 */
export function deskProblem(error: unknown, networkMessage = ACTION_NETWORK_MESSAGE): DeskProblem {
  const problem = error instanceof ApiError ? error.problem : null;
  const status = problem?.status ?? 0;
  const slug = problem ? slugOf(problem.type) : '';
  const base = { slug, refreshBoard: false, slotTaken: false };
  if (!problem || status === 0) {
    return { ...base, message: networkMessage };
  }
  const board = (message: string): DeskProblem => ({ ...base, message, refreshBoard: true });
  switch (slug) {
    case 'doctor-not-working':
      return { ...base, message: 'This doctor does not see patients on that day.' };
    case 'slot-not-on-grid':
      return { ...base, message: "That time is not one of this doctor's slots. Pick a time from the list." };
    case 'slot-walk-in-only':
      return { ...base, message: 'That slot is kept for walk-ins. Pick another time.' };
    case 'slot-in-past':
      return { ...base, message: 'That time has passed.', refreshBoard: true, slotTaken: true };
    case 'booking-closed':
      return {
        ...base,
        message:
          typeof problem.closeBeforeHours === 'number'
            ? `Booking closes ${hoursText(problem.closeBeforeHours)} before the slot.`
            : 'Booking is closed for that slot.',
      };
    case 'doctor-unavailable':
      return { ...base, message: 'This doctor is not taking bookings on that date.' };
    case 'appointment-duplicate':
      return { ...base, message: 'This patient is already booked in this slot.' };
    case 'appointment-slot-taken':
      return { ...base, message: 'That slot was just booked. Pick another time.', refreshBoard: true, slotTaken: true };
    case 'appointment-changed':
      return board('This appointment was changed by someone else. The board now shows the latest.');
    case 'appointment-not-reschedulable':
      return board('Only a booked or no-show appointment can be moved.');
    case 'appointment-not-booked':
      return board('This appointment is no longer booked. The board now shows the latest.');
    case 'appointment-already-checked-in':
      return board('This patient is already checked in.');
    case 'appointment-not-bookable':
      return board('This appointment cannot be checked in now. The board now shows the latest.');
    case 'check-in-not-today':
      return { ...base, message: 'Check-in is only possible on the day of the visit.' };
    case 'visit-stage-changed':
      return board('This card was moved by someone else. The board now shows the latest.');
    case 'visit-not-done':
      return board('Set the follow-up after the visit is completed.');
    case 'follow-up-already-booked':
      return board('This follow-up is already booked.');
    case 'follow-up-closed':
      return board('This follow-up is closed.');
    case 'database-busy':
      return { ...base, message: BUSY_MESSAGE };
    default:
      break;
  }
  if (status === 403) {
    return { ...base, message: problem.detail ?? 'You do not have permission to do this.' };
  }
  if (status === 404) {
    return board('We could not find that record. The board now shows the latest.');
  }
  if (status === 503) {
    return { ...base, message: BUSY_MESSAGE };
  }
  if (status === 400) {
    return { ...base, message: problem.fieldErrors?.[0]?.message ?? problem.detail ?? 'Check the details and try again.' };
  }
  return { ...base, message: problem.detail ?? 'Something went wrong. Try again.' };
}
