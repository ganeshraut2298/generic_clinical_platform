import { AdmissionType } from '../ipd.model';

/**
 * Types of `GET /ipd/admission-requests` (docs/api/ipd.yaml 0.3.0, SAH-67-3, ADR-025 D10). Names,
 * diagnoses and notes are for the screen only: never logged, never stored in the browser.
 */
export interface AdmissionRequestRow {
  /** EMR: a doctor's request from the EMR. COURSE_SITTING: a planned day-care or IPD sitting of a treatment course. */
  kind: 'EMR' | 'COURSE_SITTING';
  requestId: string | null;
  sittingId: string | null;
  /** COURSE_SITTING: "TC-0412". */
  courseNo?: string | null;
  /** Set for a request from an Emergency assessment; Admit sends it as emergencyCaseId. */
  emergencyCaseId: string | null;
  patientId: string | null;
  patientName: string;
  age: number | null;
  sex: 'F' | 'M' | 'O' | null;
  admissionType: AdmissionType;
  plannedStayDays: number | null;
  doctorName: string;
  diagnoses: string | null;
  note: string | null;
  requestedAt: string;
  openAdmission: { admissionId: string; ipNumber: string } | null;
}

export interface AdmissionRequestList {
  /** IPD Trial or On and an admit role; false in Read-only. */
  canAdmit: boolean;
  items: AdmissionRequestRow[];
}

export const NO_REQUESTS = 'No admission requests.';

/** "Planned", "Emergency", "Day care" or "IPD n days". */
export function requestTypeText(row: Pick<AdmissionRequestRow, 'admissionType' | 'plannedStayDays'>): string {
  switch (row.admissionType) {
    case 'emergency':
      return 'Emergency';
    case 'daycare':
      return 'Day care';
    case 'maternity':
      return 'Maternity';
    default:
      return row.plannedStayDays ? `IPD ${row.plannedStayDays} days` : 'Planned';
  }
}
