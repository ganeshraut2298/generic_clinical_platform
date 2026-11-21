import { PersonRef, Schedule, StoreType } from './pharmacy.model';

/**
 * Types for SAH-79 work package D (docs/api/pharmacy.yaml 0.3.0, the operations tagged WP-D): posting opening
 * stock (79-1), the approvals inbox (79-2), batches and expiry (79-3), stock by state (79-4), stock adjustment
 * (79-5), the reasons of Phase 2 screens, and the stock ledger read. Hand-written from the contract; no field is
 * invented. Dates are IST calendar dates (`YYYY-MM-DD`), instants are ISO date-times, money is whole paise, a
 * rate is a decimal string in rupees per issue unit.
 */

export type StockState = 'AVAILABLE' | 'IN_TRANSIT' | 'PENDING_RETURN' | 'QUARANTINE';
export const STOCK_STATE_LABELS: Record<StockState, string> = {
  AVAILABLE: 'Available',
  IN_TRANSIT: 'In transit',
  PENDING_RETURN: 'Pending return',
  QUARANTINE: 'Quarantine',
};

export interface StoreRef {
  storeId: string;
  name: string;
  type: StoreType;
}

export interface ProductRef {
  productId: string;
  code: string;
  name: string;
  strength: string;
  form: string;
  schedule: Schedule;
  purchaseUnit: string;
  issueUnit: string;
  conversion: number;
}

export interface BatchRef {
  batchId: string;
  batchNo: string;
  /** Last valid day (IST), `YYYY-MM-DD`. Shown as month and year, "Mar 2028". */
  expiryDate: string;
}

export interface ReasonRef {
  reasonKey: string;
  label: string;
  note?: string | null;
}

export interface PageMeta {
  total: number;
  page: number;
  size: number;
}

// ---- 79-1 post opening stock

export type PostLockReason = 'NOT_APPROVED' | 'ALREADY_POSTED' | 'MRP_MISMATCH';

export interface OpeningStockPostingPreview {
  importId: string;
  store: StoreRef;
  lineCount: number;
  totalQuantity: number;
  totalValuePaise: number;
  uploadedBy: PersonRef;
  approvedBy: PersonRef | null;
  expiredLines: {
    rowNo: number;
    productName: string;
    batchNo: string;
    expiryDate: string;
    quantity: number;
  }[];
  mrpMismatchLines: { rowNo: number; batchNo: string; fileMrp: string; batchMrp: string }[];
  canPost: boolean;
  postLockReason: PostLockReason | null;
}

// ---- 79-2 approvals

export type TransactionType =
  | 'PURCHASE_ORDER'
  | 'GRN_RATE_RISE'
  | 'VENDOR_RETURN'
  | 'INDENT'
  | 'STOCK_TRANSFER'
  | 'WRITE_OFF'
  | 'STOCK_ADJUSTMENT'
  | 'SALE_DISCOUNT'
  | 'SALES_RETURN';
export type InboxFilter = 'ALL' | 'INDENTS' | 'PURCHASE' | 'STOCK';
export type InboxChip =
  | 'INDENT'
  | 'PURCHASE_ORDER'
  | 'RATE_INCREASE'
  | 'VENDOR_RETURN'
  | 'TRANSFER'
  | 'WRITE_OFF'
  | 'STOCK_ADJUSTMENT'
  | 'AUDIT_VARIANCE'
  | 'DISCOUNT'
  | 'SALES_RETURN';
export type InboxAction = 'REVIEW' | 'REJECT' | 'RECOUNT' | 'APPROVE';
export type ApprovalSubjectType =
  | 'PURCHASE_ORDER'
  | 'GOODS_RECEIPT'
  | 'DEBIT_NOTE'
  | 'INDENT'
  | 'TRANSFER'
  | 'DAMAGE_REPORT'
  | 'SHORTAGE'
  | 'STOCK_ADJUSTMENT'
  | 'STOCK_AUDIT'
  | 'SALE_DISCOUNT'
  | 'SALES_RETURN';
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
export type ActingAs = 'HOD' | 'DELEGATE' | 'OWNER' | 'ESCALATED';
export type DecideLockReason = 'SELF_APPROVAL' | 'ALREADY_APPROVED_LEVEL_1' | 'NOT_APPROVER' | 'FINISHED';

export interface ApprovalInboxRow {
  requestId: string;
  transactionType: TransactionType;
  chip: InboxChip;
  subjectType: ApprovalSubjectType;
  subjectId: string;
  /** The store path the client opens the document under (required from contract 0.3.2). */
  subjectStoreId: string;
  what: string;
  amountPaise?: number | null;
  raisedBy: PersonRef;
  raisedStore: StoreRef;
  raisedAt: string;
  waitingMinutes: number;
  ruleText: string;
  level: 1 | 2;
  urgent: boolean;
  escalated: boolean;
  needsAnotherApprover: boolean;
  actions: InboxAction[];
}

export interface ApprovalInboxPage extends PageMeta {
  items: ApprovalInboxRow[];
  counts: { all: number; indents: number; purchase: number; stock: number };
  signedInAs: string;
  /** 0.3.6: true when the scan stopped at its cap. The screen text is not in the mockup; nothing extra is shown until it is approved. */
  moreWaiting?: boolean;
}

export interface ApprovalLevel {
  level: 1 | 2;
  approver: 'STORE_APPROVER' | 'OWNER';
  ruleText: string;
  applies: boolean;
}

export interface ApprovalDecision {
  level: 1 | 2;
  decision: 'APPROVED' | 'REJECTED';
  decidedBy: PersonRef;
  actingAs: ActingAs;
  decidedAt: string;
  reason: ReasonRef | null;
}

export interface ApprovalRequest {
  id: string;
  transactionType: TransactionType;
  subjectType: string;
  subjectId: string;
  subjectNo: string | null;
  subjectStoreId: string;
  ruleStore: StoreRef;
  raisedBy: PersonRef;
  raisedAt: string;
  amountPaise: number;
  ruleVersionNo: number;
  status: ApprovalStatus;
  currentLevel: 1 | 2 | null;
  levels: ApprovalLevel[];
  decisions: ApprovalDecision[];
  escalated: boolean;
  needsAnotherApprover: boolean;
  canDecide: boolean;
  decideLockReason: DecideLockReason | null;
  version: number;
}

export interface ApprovalDecisionInput {
  clientKey: string;
  level: 1 | 2;
  requestVersion: number;
  decision: 'APPROVED' | 'REJECTED';
  reasonKey?: string | null;
  note?: string | null;
  /** Story 79-10: an indent decision lists every line once with its approved quantity. */
  indentLines?: { indentLineId: string; approvedQty: number; remarkReasonKey?: string | null; remarkNote?: string | null }[];
}

// ---- 79-3 and 79-4 stock

export type StockView = 'ALL' | 'NEAR_EXPIRY' | 'EXPIRED' | 'SCHEDULE';
export type StockStatusChip =
  | 'SALEABLE'
  | 'EXPIRES_IN'
  | 'EXPIRED_QUARANTINED'
  | 'NEW_MRP'
  | 'IN_TRANSIT'
  | 'PENDING_RETURN'
  | 'QUARANTINE';

export interface StockOverview {
  store: StoreRef;
  skuCount: number;
  batchCount: number;
  /** null for a blind caller while the store has an open audit (rule 24). */
  stockValuePaise: number | null;
  pills: {
    nearExpiry30: number;
    nearExpiry60: number;
    nearExpiry90: number;
    expiredQuarantined: number;
    lowStock: number;
    scheduleProducts: number;
  };
  empty: boolean;
}

export interface StockBatchRow {
  product: ProductRef;
  batch: BatchRef;
  state: StockState;
  /** null for a blind caller when the product is on the store's open audit (rule 24). */
  qty: number | null;
  fefoRank: number | null;
  mrp: string;
  purchaseRate: string | null;
  landed: string | null;
  marginPercent: string | null;
  supplier: { vendorId: string; name: string } | null;
  statusChip: StockStatusChip[];
  daysToExpiry: number;
  canVendorReturn: boolean;
}

export interface StockBatchPage extends PageMeta {
  items: StockBatchRow[];
}

export interface ProductStock {
  product: ProductRef;
  salt: string;
  hsn: string;
  gstRate: string;
  pack?: string;
  rack: string | null;
  reorderLevel: number | null;
  batches: StockBatchRow[];
}

export type LowStockAction = 'RAISE_INDENT' | 'ADD_TO_PURCHASE_ORDER';

export interface LowStockPage extends PageMeta {
  items: {
    product: ProductRef;
    /** null for a blind caller (rule 24). */
    available: number | null;
    reorderLevel: number;
    action: LowStockAction | null;
  }[];
}

export type StoreStockStatus = 'OK' | 'BELOW_REORDER' | 'INDENT_IN_TRANSIT';

export interface StoreStateRow {
  /** null on the total row ("Unit total"). */
  store: StoreRef | null;
  /** The four quantities are null where the caller is blind (rule 24). */
  available: number | null;
  inTransit: number | null;
  pendingReturn: number | null;
  quarantine: number | null;
  reorderLevel: number | null;
  status: StoreStockStatus | null;
}

export interface ProductStockByStore {
  product: ProductRef;
  rows: StoreStateRow[];
  total: StoreStateRow;
  empty: boolean;
}

// ---- 79-5 stock adjustment

export type AdjustmentStatus = 'WAITING_APPROVAL' | 'POSTED' | 'REJECTED';

export const ADJUSTMENT_STATUS_LABELS: Record<AdjustmentStatus, string> = {
  WAITING_APPROVAL: 'Waiting for approval',
  POSTED: 'Posted',
  REJECTED: 'Rejected',
};

export interface StockAdjustmentCreate {
  clientKey: string;
  batchId: string;
  bookQtySeen: number;
  physicalQty: number;
  reasonKey: string;
  note?: string | null;
  correctsLedgerId?: string | null;
  secondSigner?: { membershipId: string; pin: string } | null;
}

export interface ApprovalSummary {
  requestId: string;
  /** 0.3.5: sent as requestVersion of the decision, so no read of the request comes first. */
  requestVersion: number;
  /** 0.3.5: the level a decision is for now (equals currentLevel); sent as level of the decision. */
  levelNo: 1 | 2 | null;
  status: ApprovalStatus;
  currentLevel: 1 | 2 | null;
  ruleText: string;
  waitingFor: 'HOD' | 'OWNER' | null;
  escalated: boolean;
  needsAnotherApprover: boolean;
  canDecide: boolean;
  rejectReason?: ReasonRef | null;
}

export interface HistoryEvent {
  event: string;
  at: string;
  by: PersonRef | null;
  actingAs: ActingAs | 'CENTRAL_STORE' | 'STORE' | 'PHARMACIST' | null;
  store: StoreRef | null;
  lineLabel: string | null;
  qtyBefore: number | null;
  qtyAfter: number | null;
  reason: ReasonRef | null;
}

export interface StockAdjustment {
  id: string;
  number: string;
  store: StoreRef;
  product: ProductRef;
  batch: BatchRef;
  bookQty: number;
  physicalQty: number;
  difference: number;
  valuePaise: number;
  reason: ReasonRef;
  raisedBy: PersonRef;
  raisedAt: string;
  secondSigner: PersonRef | null;
  status: AdjustmentStatus;
  approval: ApprovalSummary | null;
  history: HistoryEvent[];
  version: number;
}

export interface StockAdjustmentPage extends PageMeta {
  items: StockAdjustment[];
}

// ---- reasons of Phase 2 screens (GET /reasons)

export type ReasonListKey =
  | 'approval-reject'
  | 'stock-adjustment'
  | 'cs-change'
  | 'receipt-reject'
  | 'dispute'
  | 'store-return'
  | 'damage'
  | 'transfer'
  | 'consumption-purpose'
  | 'sales-return'
  | 'return-condition'
  | 'substitution'
  | 'allergy-override'
  | 'h1-override'
  | 'cancel-bill';

export interface ActiveReason {
  reasonKey: string;
  label: string;
  needsNote: boolean;
  fixed: boolean;
}

export interface ActiveReasons {
  lists: { listKey: string; reasons: ActiveReason[] }[];
}

// ---- stock ledger read (GET /reports/stock-ledger; the operation is tagged SAH-79-18, WP-H)

export type LedgerTransaction =
  | 'OPENING_STOCK'
  | 'GRN'
  | 'EXPIRY_QUARANTINE'
  | 'STOCK_ADJUSTMENT'
  | 'VENDOR_RETURN_HOLD'
  | 'VENDOR_RETURN_RELEASE'
  | 'VENDOR_RETURN'
  | 'INDENT_DISPATCH'
  | 'INDENT_RECEIPT_ACCEPTED'
  | 'INDENT_RECEIPT_REJECTED'
  | 'RETURN_ACCEPTED'
  | 'SHORTAGE_FOUND'
  | 'SHORTAGE_WRITE_OFF'
  | 'STORE_RETURN'
  | 'DAMAGE_QUARANTINE'
  | 'DAMAGE_RELEASE'
  | 'DAMAGE_SEND_TO_CS'
  | 'WRITE_OFF'
  | 'TRANSFER_DISPATCH'
  | 'TRANSFER_RECEIPT_ACCEPTED'
  | 'TRANSFER_RECEIPT_REJECTED'
  | 'WARD_CONSUMPTION'
  | 'WARD_CONSUMPTION_PATIENT'
  | 'STOCK_AUDIT_ADJUSTMENT'
  | 'COUNTER_SALE'
  | 'COUNTER_SALE_CANCELLED'
  | 'SALES_RETURN'
  | 'IPD_ISSUE'
  | 'WARD_RETURN_ACCEPTED'
  | 'WARD_RETURN_WASTAGE';

export interface StockLedgerLine {
  ledgerId: string;
  at: string;
  transaction: LedgerTransaction;
  ref: string | null;
  store: StoreRef;
  state: StockState;
  batch: BatchRef;
  /** null on the lines of a store where the caller is blind and the product is on its open audit (rule 24). */
  qtyIn: number | null;
  qtyOut: number | null;
  balance: number | null;
  /** null = System. */
  user: PersonRef | null;
  secondSigner: PersonRef | null;
  reversesLedgerId: string | null;
}

export interface StockLedgerPage extends PageMeta {
  items: StockLedgerLine[];
  product: ProductRef;
  stores: StoreRef[];
  from: string;
  to: string;
}

export interface StockLedgerQuery {
  productId: string;
  storeId?: string;
  from: string;
  to: string;
  page?: number;
  size?: number;
}

/** A person who may be the second signer of a Schedule X handling (contract 0.3.2, never the caller). */
export interface SecondSignerCandidate {
  membershipId: string;
  name: string;
  roleLabel: 'Pharmacist' | 'Owner';
}

export interface SecondSignerCandidates {
  items: SecondSignerCandidate[];
}
