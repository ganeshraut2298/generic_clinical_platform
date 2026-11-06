/** Types from `docs/api/hra.yaml` (SAH-20, SAH-21). */
export type Shift = 'M' | 'E' | 'N' | 'X';
export type AttendanceStatus = 'in' | 'late' | 'abs';

export interface StaffSummary {
  staff: number;
  doctors: number;
  others: number;
  /** Present or late. */
  presentToday: number;
  lateToday: number;
  /** Pending leave requests. Owner only; 0 for other roles. */
  leaveRequests: number;
}

export interface RosterCellInput {
  membershipId: string;
  /** `YYYY-MM-DD` */
  date: string;
  shift: Shift;
}

export interface RosterCell {
  date: string;
  kind: 'SHIFT' | 'OPD' | 'OFF' | 'LEAVE';
  shift?: Shift | null;
}

export interface RosterRow {
  membershipId: string;
  name: string;
  roleLabel: string;
  doctor: boolean;
  cells: RosterCell[];
}

export interface RosterShiftInfo {
  key: Shift;
  name: string;
  /** Empty for Off. */
  time: string;
}

export interface Roster {
  days: string[];
  shifts: RosterShiftInfo[];
  published: boolean;
  publishedAt?: string | null;
  rows: RosterRow[];
}

export interface AttendanceRow {
  membershipId: string;
  name: string;
  roleLabel: string;
  /** Approved leave covers today: no status buttons. */
  onLeave: boolean;
  status?: AttendanceStatus | null;
  /** `HH:mm`, IST. */
  markedTime?: string | null;
}

export interface Attendance {
  date: string;
  lateAfterMinutes: number;
  canMark: boolean;
  rows: AttendanceRow[];
}

export type LeaveType = 'CL' | 'SL' | 'EL';
export type LeaveStatus = 'pending' | 'approved' | 'declined';

export interface LeaveInput {
  /** Omit for yourself. Only the owner may name another person. */
  membershipId?: string | null;
  type: LeaveType;
  /** `YYYY-MM-DD` */
  from: string;
  to: string;
  /** Free text, up to 200 characters. */
  reason?: string | null;
}

export interface LeaveRequest {
  id: string;
  membershipId: string;
  name: string;
  roleLabel: string;
  type: LeaveType;
  typeName: string;
  from: string;
  to: string;
  days: number;
  reason?: string | null;
  status: LeaveStatus;
  askedAt: string;
  decidedAt?: string | null;
  decidedByName?: string | null;
  affectedAppointments?: number;
}

export interface LeaveBalance {
  membershipId: string;
  name: string;
  remaining: Record<LeaveType, number>;
}

export interface LeaveTypeInfo {
  key: LeaveType;
  name: string;
  /** Yearly allowance. */
  days: number;
}

export interface LeaveApplicant {
  membershipId: string;
  name: string;
}

export interface LeaveView {
  types: LeaveTypeInfo[];
  /** Newest first. */
  requests: LeaveRequest[];
  balances: LeaveBalance[];
  applicants: LeaveApplicant[];
  pendingCount: number;
  canApprove: boolean;
}
