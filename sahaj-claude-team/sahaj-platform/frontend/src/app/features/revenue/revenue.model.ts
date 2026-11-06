/** Typed from `RevenueReport` in docs/api/billing.yaml (SAH-16). Money is integer paise. */
export type RevenueRange = 'TODAY' | 'WEEK' | 'MONTH';
export type RevenuePaymentMode = 'CASH' | 'UPI' | 'CARD';
export type AdjustmentKind = 'DISCOUNT' | 'REFUND';

export interface RevenueDay {
  date: string;
  collectedPaise: number;
}

export interface RevenueMode {
  mode: RevenuePaymentMode;
  amountPaise: number;
}

export interface RevenueDoctor {
  doctorMembershipId: string;
  doctorName: string;
  patients: number;
  collectedPaise: number;
}

export interface RevenueService {
  name: string;
  billedPaise: number;
}

export interface RevenueDue {
  visitId: string;
  patientName: string;
  mobile?: string | null;
  doctorName: string;
  date: string;
  balancePaise: number;
}

export interface RevenueAdjustment {
  kind: AdjustmentKind;
  patientName: string;
  amountPaise: number;
  detail: string;
  date: string;
}

export interface RevenueReport {
  range: RevenueRange;
  collectedPaise: number;
  collectedChangePercent?: number | null;
  billedPaise: number;
  patients: number;
  averageBillPaise: number;
  duePaise: number;
  atCounterPaise: number;
  discountPaise: number;
  refundPaise: number;
  cashInDrawerPaise: number;
  days: RevenueDay[];
  modes: RevenueMode[];
  doctors: RevenueDoctor[];
  topServices: RevenueService[];
  dues: RevenueDue[];
  adjustments: RevenueAdjustment[];
}
