/** Typed from `docs/api/opd.yaml` (SAH-14): `FollowUpStatus`, `FollowUp`, `FollowUpList`. */
export type FollowUpStatus = 'DUE' | 'MISSED' | 'UPCOMING' | 'RETURNED';

export interface FollowUp {
  id: string;
  patientId: string;
  patientName: string;
  mobile?: string | null;
  doctorMembershipId: string;
  doctorName: string;
  dueDate: string;
  reason: string;
  status: FollowUpStatus;
  daysLate?: number;
  /** The visit that created it; null for a follow-up added by an IPD discharge (SAH-27). */
  sourceVisitId: string | null;
  /** SAH-27. The IPD admission whose discharge added it; null for a visit follow-up. */
  sourceAdmissionId: string | null;
  returnedVisitId?: string | null;
  /** 0.6.0 (SAH-51-6). The appointment the doctor's sign booked for this follow-up; null when none. */
  appointmentDate?: string | null;
  /** 0.6.0. HH:mm of that appointment. */
  appointmentTime?: string | null;
}

export interface FollowUpCounts {
  due: number;
  missed: number;
  upcoming: number;
  returned: number;
}

export interface FollowUpList {
  counts: FollowUpCounts;
  items: FollowUp[];
}
