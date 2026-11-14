/** Types for the SAH-10 `/billing` endpoints in `docs/api/billing.yaml`. No field is invented. */

export type PaymentMode = 'CASH' | 'UPI' | 'CARD';

export const PAYMENT_MODES: readonly PaymentMode[] = ['CASH', 'UPI', 'CARD'];

export const PAYMENT_MODE_LABELS: Record<PaymentMode, string> = {
  CASH: 'Cash',
  UPI: 'UPI',
  CARD: 'Card',
};

export interface PaymentCreate {
  mode: PaymentMode;
  amountPaise: number;
  reference?: string | null;
}

export interface RefundCreate {
  amountPaise: number;
  mode: PaymentMode;
  reason: string;
}

export interface BillItem {
  id: string;
  name: string;
  amountPaise: number;
  qty: number;
  /** False for the consultation line: it cannot be removed. */
  removable: boolean;
  /**
   * 0.2.0 EMR: a "Bill today" service of the doctor's EMR. 0.3.0 EMR_PLAN: a dental plan row done today,
   * added once at the doctor's sign (named by the procedure). Staff at the counter keep their rights on every line.
   */
  source?: 'COUNTER' | 'EMR' | 'EMR_PLAN';
}

export interface Payment {
  id: string;
  mode: PaymentMode;
  /** Negative for a refund. */
  amountPaise: number;
  reference?: string | null;
  recordedByName: string;
  recordedAt: string;
}

export type VisitSex = 'F' | 'M' | 'O';
/** REVISIT (0.2.0) and EMERGENCY (0.3.0) are set by the doctor from the EMR. */
export type VisitType = 'NEW' | 'FOLLOW_UP' | 'REVISIT' | 'EMERGENCY';

export interface Bill {
  visitId: string;
  tokenNo: number;
  patientName: string;
  age: number;
  sex: VisitSex;
  doctorName: string;
  visitType: VisitType;
  items: BillItem[];
  subtotalPaise: number;
  discountPercent: number;
  discountReason?: string | null;
  discountPaise: number;
  totalPaise: number;
  paidPaise: number;
  balancePaise: number;
  payments: Payment[];
  /** Whether the signed-in user may edit items, discount or collect payment. */
  canCollect: boolean;
  /** The signed-in role's maximum discount percent. */
  discountLimitPercent: number;
}

export interface BillSummary {
  visitId: string;
  tokenNo: number;
  patientName: string;
  doctorName?: string;
  totalPaise: number;
  balancePaise: number;
}

export interface BillingCounter {
  awaiting: BillSummary[];
  dues: BillSummary[];
  paidToday: BillSummary[];
}

export interface DayClosing {
  businessDate: string;
  countedCashPaise: number;
  expectedCashPaise: number;
  diffPaise: number;
  note?: string | null;
  closedByName: string;
  closedAt: string;
}

export interface DayClosingCreate {
  countedCashPaise: number;
  note?: string | null;
}

/** Reasons offered for a discount (mockup `DISC_REASONS`). Free text is not accepted by the UI;
 * the contract itself allows any string, so this list is a UI convenience only. */
export const DISCOUNT_REASONS: readonly string[] = [
  'Senior citizen',
  'Staff or family',
  'Camp or scheme',
  'Financial hardship',
  'Goodwill',
];

/** Reasons offered for a refund (mockup `openRefund`). */
export const REFUND_REASONS: readonly string[] = [
  'Procedure not done',
  'Charged twice',
  'Billing mistake',
  'Patient complaint',
  'Other',
];

export const MESSAGE_VIEW_ONLY =
  'Only the front desk or the owner can take payments. You can view this bill.';
