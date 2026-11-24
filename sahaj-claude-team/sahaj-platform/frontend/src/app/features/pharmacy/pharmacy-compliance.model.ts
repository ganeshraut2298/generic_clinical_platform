import { SecondSignerInput } from './pharmacy-indent.model';
import { ApprovalSummary, BatchRef, HistoryEvent, PageMeta, ProductRef, ReasonRef, StoreRef } from './pharmacy-inventory.model';
import { PaymentMode } from './pharmacy-sale.model';
import { PersonRef, Schedule } from './pharmacy.model';

/**
 * Hand-written from docs/api/pharmacy-sales.yaml 0.3.6, work packages J and L (SAH-80 stories 80-9, 80-10, 80-18, 80-19):
 * the sales return, the Schedule H1 register, the Schedule X balance register and the licences. No field is invented.
 */

export interface WindowState {
  state: 'WITHIN' | 'OVER' | 'CANCELLED';
  days: number;
  lastDay?: string;
}

export interface ReturnableBillRow {
  billId: string;
  billNo: string;
  savedAt: string;
  buyerLabel: string;
  netPayablePaise: number;
  window: WindowState;
}

export interface ReturnableLine {
  billLineId: string;
  product: ProductRef;
  batch: BatchRef;
  soldQty: number;
  returnedQty: number;
  returnableQty: number;
  returnable: boolean;
  refundPerUnitPaise: number;
  schedule: Schedule;
}

export interface RefundModeOption {
  mode: PaymentMode;
  maxPaise: number | null;
  original?: boolean;
}

export interface ReturnableBill extends ReturnableBillRow {
  store: StoreRef;
  namedCustomer: boolean;
  limitPaise: number | null;
  refundModes: RefundModeOption[];
  lines: ReturnableLine[];
}

export interface ReturnRefundInput {
  mode: PaymentMode;
  amountPaise: number;
}

export interface SalesReturnCreate {
  clientKey: string;
  billId: string;
  lines: { billLineId: string; qty: number; conditionKey: string }[];
  reasonKey: string;
  note?: string | null;
  refunds: ReturnRefundInput[];
  secondSigner?: SecondSignerInput | null;
}

export type SalesReturnStatus = 'WAITING_PHARMACIST' | 'WAITING_APPROVAL' | 'APPROVED' | 'COMPLETED' | 'REJECTED';

export const SALES_RETURN_STATUS_LABELS: Record<SalesReturnStatus, string> = {
  WAITING_PHARMACIST: 'Waiting for the pharmacist',
  WAITING_APPROVAL: 'Waiting for approval',
  APPROVED: 'Approved',
  COMPLETED: 'Completed',
  REJECTED: 'Rejected',
};

export interface SalesReturn {
  id: string;
  status: SalesReturnStatus;
  creditNoteNo: string | null;
  bill: { billId: string; billNo: string; savedAt: string; buyerLabel: string };
  store: StoreRef;
  lines: {
    returnLineId: string;
    billLineId: string;
    product: ProductRef;
    batch: BatchRef;
    qty: number;
    condition: ReasonRef;
    destination: 'AVAILABLE' | 'QUARANTINE' | 'REFUSED' | 'PENDING';
    refundPaise: number;
    taxablePaise: number;
    taxPaise: number;
    gstRate: string;
  }[];
  reason: ReasonRef;
  refunds: ReturnRefundInput[];
  refundTotalPaise: number;
  startedBy: PersonRef;
  startedAt: string;
  acceptedBy?: PersonRef | null;
  completedBy: PersonRef | null;
  completedAt: string | null;
  approval: ApprovalSummary | null;
  canAccept: boolean;
  canFinish: boolean;
  history?: HistoryEvent[];
  version: number;
}

// ---- 80-9 Schedule H1 register

export interface H1RegisterRow {
  rowId: string;
  kind: 'ENTRY' | 'REVERSAL';
  reversalOf?: string | null;
  reversalReason?: 'CANCELLED' | 'RETURNED' | null;
  at: string;
  billNo: string;
  patientName: string;
  patientAge: number | null;
  patientAddress: string | null;
  prescriberName: string | null;
  prescriberRegNo: string | null;
  prescriptionDate: string | null;
  drug: ProductRef;
  prescribedDrug: ProductRef | null;
  batch: BatchRef;
  qty: number;
  rxCopy: { source: 'E_RX' | 'TYPED'; rxId?: string | null };
  pharmacist: PersonRef;
  override: ReasonRef | null;
}

export interface H1RegisterPage extends PageMeta {
  entriesToday: number;
  missingPrescriberToday: number;
  items: H1RegisterRow[];
}

export interface H1RegisterQuery {
  from?: string;
  to?: string;
  prescriber?: string;
  size?: number;
  page?: number;
}

// ---- 80-10 Schedule X balance register

export interface ScheduleXRow {
  product: ProductRef;
  ndps: boolean;
  opening: number;
  received: number;
  issued: number;
  book: number;
  physical: number | null;
  physicalCountedOn: string | null;
  status: 'MATCH' | 'REVIEW' | 'NOT_COUNTED';
  difference: number | null;
}

export interface ScheduleXRegister {
  from: string;
  to: string;
  toReview: number;
  items: ScheduleXRow[];
}

// ---- 80-19 licences

export interface LicenceStatus {
  state: 'VALID' | 'DUE' | 'EXPIRED' | 'NO_DATE' | 'NONE';
  daysLeft: number | null;
}

export interface Licences {
  warningDays: number;
  expired: string[];
  rows: {
    kind: 'RETAIL_DRUG_LICENCE' | 'SCHEDULE_X_LICENCE' | 'GST_REGISTRATION' | 'PHARMACIST';
    document: string;
    number: string | null;
    validTill: string | null;
    status: LicenceStatus;
    pharmacist?: { id: string; member: PersonRef; registrationNo: string; validTill: string | null; version: number } | null;
  }[];
}

// ---- work package L: the pharmacist's list of returns, the role read-out and the audit log

export interface SalesReturnRow {
  id: string;
  status: SalesReturnStatus;
  creditNoteNo: string | null;
  billNo: string;
  at: string;
  buyerLabel: string;
  refundTotalPaise: number;
  startedBy: PersonRef;
}

export interface SalesReturnPage extends PageMeta {
  items: SalesReturnRow[];
}

export interface AcceptSalesReturnInput {
  clientKey: string;
  version: number;
  decisions: { returnLineId: string; decision: 'ACCEPT' | 'ACCEPT_QUARANTINE' | 'REFUSE' }[];
  refunds: ReturnRefundInput[];
  secondSigner?: SecondSignerInput | null;
}

export type RoleColumn = 'OWNER' | 'PHARMACIST' | 'CASHIER' | 'STORE_KEEPER' | 'WARD_NURSE';
export type RoleAction =
  | 'DISCOUNT_ABOVE_LIMIT'
  | 'CANCEL_SAVED_BILL'
  | 'SALES_RETURN'
  | 'STOCK_ADJUSTMENT'
  | 'OVERRIDE_ALLERGY_H1'
  | 'RAISE_WARD_INDENT';

export interface RoleReadout {
  sentence: string;
  columns: RoleColumn[];
  rows: {
    action: RoleAction;
    cells: { column: string; value: 'ALLOW' | 'NEEDS_APPROVAL' | 'DENY' | 'SAME_DAY_ONLY'; ruleText?: string | null }[];
  }[];
}

export type ActivityType =
  | 'ALLERGY_OVERRIDE'
  | 'H1_OVERRIDE'
  | 'DISCOUNT_REQUESTED'
  | 'DISCOUNT_DECIDED'
  | 'STOCK_ADJUSTED'
  | 'BILL_CANCELLED'
  | 'SALES_RETURN'
  | 'SECOND_SIGNATURE';

export interface ActivityPage extends PageMeta {
  items: { at: string; by: PersonRef; roleLabel: string; type: ActivityType; text: string; store: StoreRef }[];
}

export interface ActivityQuery {
  storeId?: string;
  from?: string;
  to?: string;
  membershipId?: string;
  type?: ActivityType;
  page?: number;
  size?: number;
}

// ---- work package N: the Command Center

export type CardState = 'OK' | 'ERROR' | 'EMPTY';

export interface CommandCentre {
  asOf: string;
  store: StoreRef | null;
  todaysSales: { state: CardState; netPaise?: number; billCount?: number; averagePaise?: number };
  grossMargin?: { state: CardState; percent?: string } | null;
  nearExpiry90?: { state: CardState; valuePaise?: number | null; batchCount?: number; returnablePaise?: number | null } | null;
  reorder?: { state: CardState; productCount?: number } | null;
  rxWaiting?: {
    state: CardState;
    opdConnected?: boolean;
    opdWaiting?: number | null;
    ipdConnected?: boolean;
    ipdWaiting?: number | null;
    indentsWaiting?: number;
  } | null;
  udhar?: { state: CardState; duePaise?: number; over60DaysPaise?: number } | null;
  expiryRisk?: {
    state: CardState;
    bars?: { band: 'EXPIRED' | 'D0_30' | 'D31_60' | 'D61_90'; batchCount: number; valuePaise: number | null }[];
  } | null;
  livePrescriptions?: {
    state: CardState;
    items?: {
      source: 'OPD' | 'IPD';
      id: string;
      at: string;
      patientName: string;
      itemCount: number;
      availability: string;
      shortCount?: number;
      action: 'BILL' | 'ISSUE';
    }[];
  } | null;
  compliance?: {
    state: CardState;
    h1?: { billsToday: number; overridesToReview: number };
    scheduleX?: { toReview: number; balanced: boolean; neverCounted?: number };
    drugLicence?: LicenceStatus;
  } | null;
}

// ---- work package K: the OPD queue (connected false until the OPD port is approved)

export interface OpdQueuePage extends PageMeta {
  connected: boolean;
  refreshAfterSeconds: number;
  counts: { new: number; picking: number; ready: number; needsAction: number };
  items: {
    rxId: string;
    token: string;
    patientName: string;
    doctorName: string;
    itemCount: number;
    waitingMinutes: number;
    status: 'NEW' | 'PICKING' | 'PICKED' | 'SUBSTITUTE_OK' | 'NEEDS_ACTION' | 'PART_SUPPLIED';
    pickingBy?: PersonRef | null;
    readyMinutes: number | null;
  }[];
}
