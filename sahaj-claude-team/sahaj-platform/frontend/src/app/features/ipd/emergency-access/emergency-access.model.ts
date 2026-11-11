/**
 * SAH-34 break-glass emergency access (stories 34E and 34F; mockup `sfbg`, `sfbgok`, `sfbgrev`, `bgRow`).
 * Types follow `EmergencyAccess*` in docs/api/ipd.yaml; the words are those of docs/stories/SAH-34.md.
 * Names, bed codes and UHIDs are on screen only: nothing here is logged or stored. Every time (`openedAt`,
 * `expiresAt`, `asOf`) is the database clock; the browser clock is never read for access.
 */
import { eventTime } from '../safety/safety.model';

export type EmergencyAccessReasonKey = 'DETERIORATING' | 'COVERING_BREAK' | 'DUTY_DOCTOR_ASKED';

export interface EmergencyAccessReason {
  key: EmergencyAccessReasonKey;
  label: string;
}

/** One option of the select "Patient": identity only (no diagnosis, allergy, vitals or bill). */
export interface EmergencyAccessPatient {
  admissionId: string;
  patientName: string;
  bedCode: string;
  /** Null only when the patient record cannot be found (shown as "—"). */
  uhid: string | null;
}

export interface EmergencyAccessPicker {
  items: EmergencyAccessPatient[];
  reasons: EmergencyAccessReason[];
}

/** The body of POST /emergency-access. There is no duration, start, end or user: the server sets them. */
export interface EmergencyAccessInput {
  admissionId: string;
  reasonKey: EmergencyAccessReasonKey;
  confirmed: true;
}

export interface EmergencyAccessGrant {
  id: string;
  admissionId: string;
  patientName: string;
  bedCode: string;
  reasonKey: EmergencyAccessReasonKey;
  reasonLabel: string;
  openedAt: string;
  expiresAt: string;
}

export interface EmergencyAccessReviewRow {
  grantId: string;
  admissionId: string;
  staffName: string;
  patientName: string;
  /** The bed at the time of the grant, not the bed now. */
  bedCode: string;
  reasonKey: EmergencyAccessReasonKey;
  reasonLabel: string;
  openedAt: string;
  expiresAt: string;
  /** Returned for the owner; the mockup draws no text for it. */
  admissionStatus: 'ADMITTED' | 'DISCHARGED';
}

export interface EmergencyAccessReviewPage {
  asOf: string;
  /** Every unreviewed grant of the clinic (the tile number), not the page size. */
  waiting: number;
  items: EmergencyAccessReviewRow[];
  nextCursor: string | null;
}

export interface EmergencyAccessReviewResult {
  grantId: string;
  reviewedAt: string;
  waiting: number;
}

// ---- Sheet "Emergency access" (mockup `sfbg`; AC 45 to 47, 61) ----

export const SHEET_TITLE = 'Emergency access';
export const SHEET_TITLE_ID = 'eg-title';
export const SHEET_SUBTITLE =
  'For a patient outside your wards. Access lasts 30 minutes, is logged, and the owner reviews it.';
export const PATIENT_LABEL = 'Patient';
export const REASON_LABEL = 'Reason';
export const REASON_PLACEHOLDER = 'Choose a reason';
export const CONFIRM_LABEL = 'I understand this access is recorded with my name and reviewed by the owner';
export const CANCEL_LABEL = 'Cancel';
export const OPEN_LABEL = 'Open for 30 minutes';
export const NOTHING_OUTSIDE = 'You can already see every admitted patient.';
export const REASON_MISSING = 'Choose a reason for emergency access.';
export const CONFIRM_MISSING = 'Tick the box to confirm this access is recorded.';
export const PATIENT_MISSING = 'Choose a patient.';
export const OPEN_ERROR = 'We could not open emergency access. Try again.';
/** New text (not in the stories): the picker could not be read. Needs the human's wording sign-off. */
export const PICKER_ERROR = 'We could not load the patients. Try again.';
export const PICKER_LOADING = 'Loading the patients';

/** AC 45: "Asha Patil · bed 101-A · PT-000042" (UHID in place of the mockup's MRN, OQ-36-8). */
export function patientOption(p: EmergencyAccessPatient): string {
  return `${p.patientName} · bed ${p.bedCode} · ${p.uhid === null || p.uhid.trim() === '' ? '—' : p.uhid}`;
}

/** AC 51 (DEC-031): the toast after the server has answered. Never "The owner has been told.". */
export function grantToast(patientName: string): string {
  return `Emergency access to ${patientName} for 30 minutes. The owner will see this on the Safety board.`;
}

export type EmergencyAccessField = 'admissionId' | 'reasonKey' | 'confirmed';
export type EmergencyAccessErrors = Partial<Record<EmergencyAccessField, string>>;

export interface EmergencyAccessDraft {
  admissionId: string;
  reasonKey: string;
  confirmed: boolean;
}

const REASON_KEYS: readonly string[] = ['DETERIORATING', 'COVERING_BREAK', 'DUTY_DOCTOR_ASKED'];

/** AC 47: the same two messages as the server's field errors; nothing is sent while one is shown. */
export function validateDraft(draft: EmergencyAccessDraft): EmergencyAccessErrors {
  const errors: EmergencyAccessErrors = {};
  if (draft.admissionId.trim() === '') {
    errors.admissionId = PATIENT_MISSING;
  }
  if (!REASON_KEYS.includes(draft.reasonKey)) {
    errors.reasonKey = REASON_MISSING;
  }
  if (!draft.confirmed) {
    errors.confirmed = CONFIRM_MISSING;
  }
  return errors;
}

/** Field order of the sheet, for the focus on the first error. */
export const FIELD_ORDER: readonly EmergencyAccessField[] = ['admissionId', 'reasonKey', 'confirmed'];

// ---- Owner review (mockup `ipSafety`, `bgRow`; AC 64 to 74) ----

export const TILE_TITLE = 'Emergency access';
export const TILE_TEXT = 'Waiting for your review';
export const PANEL_TITLE = 'Emergency access to review';
export const PANEL_SUBTITLE = 'Staff opened patients outside their wards. Check each reason.';
export const MARK_REVIEWED = 'Mark reviewed';
export const SHOW_MORE = 'Show 25 more';
export const REVIEWED_TOAST = 'Marked as reviewed';
export const ALREADY_REVIEWED = 'Already reviewed.';
/** New text (not in the stories): a review or a page of the panel failed. Needs the human's wording sign-off. */
export const REVIEW_ERROR = 'We could not mark that as reviewed. Try again.';
export const MORE_ERROR = 'We could not load more. Try again.';

/** AC 66: "Meera Joshi opened Asha Patil · bed 101-A". */
export function reviewHeadline(row: Pick<EmergencyAccessReviewRow, 'staffName' | 'patientName' | 'bedCode'>): string {
  return `${row.staffName} opened ${row.patientName} · bed ${row.bedCode}`;
}

/** AC 66: "Patient deteriorating, rapid response · 11:05 AM · outside their wards" ("24 Oct, 11:05 AM" on an earlier day). */
export function reviewSmallLine(
  row: Pick<EmergencyAccessReviewRow, 'reasonLabel' | 'openedAt'>,
  asOf: string,
): string {
  return `${row.reasonLabel} · ${eventTime(row.openedAt, asOf)} · outside their wards`;
}

/** The accessible name of the button: "Mark reviewed: Meera Joshi opened Asha Patil". */
export function markReviewedName(row: Pick<EmergencyAccessReviewRow, 'staffName' | 'patientName'>): string {
  return `${MARK_REVIEWED}: ${row.staffName} opened ${row.patientName}`;
}
