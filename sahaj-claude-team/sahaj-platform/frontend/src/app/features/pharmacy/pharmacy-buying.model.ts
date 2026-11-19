import {
  ApprovalSummary,
  BatchRef,
  HistoryEvent,
  PageMeta,
  ProductRef,
  StoreRef,
} from './pharmacy-inventory.model';
import { PersonRef } from './pharmacy.model';

/**
 * Types of the SAH-79 work package E (buy): purchase orders (79-6), goods receipts (79-7) and debit notes (79-8),
 * written by hand from docs/api/pharmacy.yaml 0.3.2. Rates are decimal strings in rupees; amounts are whole paise.
 */

export type { ApprovalSummary, HistoryEvent };

export interface VendorRef {
  vendorId: string;
  name: string;
  gstin: string | null;
  blocked: boolean;
  needsReview?: boolean;
}

// ---------------------------------------------------------------- reorder list and purchase orders (79-6)

export interface ReorderSuggestionRow {
  product: ProductRef;
  /** Issue units; null for a blind caller on an audited product (rule 24). */
  csStock: number | null;
  /** Owner only; null for others. */
  allStoresStock: number | null;
  /** Purchase units. */
  openPoQty: number;
  openPoNo: string | null;
  reorderLevel: number;
  /** Whole purchase units. */
  suggestedQty: number;
  vendor: VendorRef | null;
  /** Per purchase unit. */
  lastRate: string | null;
}

export interface ReorderSuggestionPage extends PageMeta {
  items: ReorderSuggestionRow[];
}

export interface PurchaseOrderLineInput {
  productId: string;
  qty: number;
  rate: string;
}

export interface PurchaseOrdersFromSuggestionsRow {
  productId: string;
  vendorId: string | null;
  qty: number;
  rate: string;
}

export interface PurchaseOrdersFromSuggestionsInput {
  clientKey: string;
  rows: PurchaseOrdersFromSuggestionsRow[];
}

export type PurchaseOrderStatus =
  | 'DRAFT'
  | 'WAITING_APPROVAL'
  | 'APPROVED'
  | 'REJECTED'
  | 'SENT'
  | 'PARTLY_RECEIVED'
  | 'RECEIVED'
  | 'CLOSED_SHORT'
  | 'CANCELLED'
  | 'DISCARDED';

export interface PurchaseOrderCreate {
  clientKey: string;
  vendorId: string;
  deliveryExpected: string | null;
  /** Omitted when the caller cannot see the vendor's credit terms: the server fills it from the vendor (DEC-082). */
  creditDays?: number;
  lines: PurchaseOrderLineInput[];
}

export interface PurchaseOrderUpdate {
  version: number;
  vendorId: string;
  deliveryExpected: string | null;
  creditDays?: number;
  lines: PurchaseOrderLineInput[];
}

export interface MarkSentInput {
  version: number;
  sentOn: string;
}

export interface PrintHeader {
  templateDocType: 'INVOICE' | 'ISSUE_SLIP' | 'DEBIT_NOTE';
  headerLines: string[];
  footerNote: string | null;
  paperSize: 'A4' | 'A5';
  unitGstin: string | null;
  drugLicenceNo: string | null;
}

export interface PurchaseOrderLine {
  id: string;
  product: ProductRef;
  qty: number;
  rate: string;
  gstRate: string;
  amountPaise: number;
  gstPaise: number;
  receivedQty: number;
  closedShort: boolean;
}

export interface PurchaseOrder {
  id: string;
  /** null while DRAFT. */
  number: string | null;
  store: StoreRef;
  vendor: VendorRef;
  status: PurchaseOrderStatus;
  deliveryExpected: string | null;
  creditDays: number;
  sentOn: string | null;
  overdueDays: number | null;
  shortLineCount?: number;
  lines: PurchaseOrderLine[];
  subtotalPaise: number;
  gstPaise: number;
  totalPaise: number;
  ruleNote: string | null;
  approval: ApprovalSummary | null;
  receipts: { grnId: string; number: string; postedAt: string }[];
  raisedBy: PersonRef;
  createdAt: string;
  history: HistoryEvent[];
  print: PrintHeader;
  version: number;
}

export interface PurchaseOrderListRow {
  id: string;
  number: string | null;
  vendorName: string;
  totalPaise: number;
  status: PurchaseOrderStatus;
  overdueDays: number | null;
  shortLineCount?: number;
}

export interface PurchaseOrderTiles {
  draft: number;
  waitingApproval: number;
  sent: number;
  partlyReceived: number;
  overdue: number;
}

export interface PurchaseOrderPage extends PageMeta {
  items: PurchaseOrderListRow[];
  tiles: PurchaseOrderTiles;
}

export interface PurchaseOrderQuery {
  status?: PurchaseOrderStatus[];
  overdue?: boolean;
  page?: number;
  size?: number;
}

export interface VersionInput {
  version: number;
}

export interface VersionClientKeyInput {
  clientKey: string;
  version: number;
}

export interface VersionReasonInput {
  version: number;
  reasonKey: string;
  note?: string | null;
}

export interface ClientKeyInput {
  clientKey: string;
}

// ---------------------------------------------------------------- goods receipts (79-7)

export type GoodsReceiptStatus = 'DRAFT' | 'WAITING_APPROVAL' | 'APPROVED' | 'POSTED' | 'DISCARDED';
export type ShortAction = 'BALANCE_TO_COME' | 'CLOSE_SHORT';
export type LineCheck = 'OK' | 'RATE_UP' | 'SHORT' | 'MRP_CHANGED' | 'PTR_ABOVE_MRP';
export type GrnStep = 'PO' | 'ENTER_INVOICE' | 'VERIFY' | 'POST' | 'PAYABLE';

export interface GoodsReceiptLineInput {
  purchaseOrderLineId?: string | null;
  productId: string;
  batchNo: string;
  /** YYYY-MM */
  expiryMonth: string;
  qty: number;
  billedQty: number;
  freeQty: number;
  ptr: string;
  mrp: string;
  discountPercent: string;
  claimRateDifference?: boolean;
  shortAction?: ShortAction | null;
}

export interface GoodsReceiptCreate {
  clientKey: string;
  purchaseOrderId: string | null;
  vendorId: string | null;
  invoiceNo: string;
  invoiceDate: string;
  creditDays: number;
  invoiceTotalPaise: number;
  lines: GoodsReceiptLineInput[];
}

export interface GoodsReceiptUpdate {
  version: number;
  vendorId?: string | null;
  invoiceNo: string;
  invoiceDate: string;
  creditDays: number;
  invoiceTotalPaise: number;
  lines: GoodsReceiptLineInput[];
}

export interface PostWithSignerInput {
  clientKey: string;
  version: number;
  secondSigner?: { membershipId: string; pin: string } | null;
}

export interface GoodsReceiptLine {
  id: string;
  product: ProductRef;
  batchNo: string;
  expiryDate: string;
  qty: number;
  billedQty: number;
  freeQty: number;
  ptr: string;
  mrp: string;
  discountPercent: string;
  gstRate: string;
  taxablePaise: number;
  gstPaise: number;
  landed: string;
  marginPercent: string | null;
  checks: LineCheck[];
  ratePercent: string | null;
  rateBasis: 'PO' | 'LAST' | null;
  shortQty: number | null;
  shortAction: ShortAction | null;
  claimRateDifference: boolean;
  scheduleX: boolean;
  secondSigner: PersonRef | null;
}

export interface VendorPayable {
  payableId: string;
  vendor: VendorRef;
  invoiceNo: string;
  invoiceDate: string;
  amountPaise: number;
  creditedPaise: number;
  openPaise: number;
  dueDate: string;
  status: 'OPEN' | 'SETTLED_BY_CREDIT';
}

export interface ThreeWayMatchRow {
  lineId: string;
  product: string;
  poQty: number | null;
  billedQty: number;
  receivedQty: number;
  poRate: string | null;
  billedRate: string;
}

export interface GoodsReceipt {
  id: string;
  /** Taken at posting. */
  number: string | null;
  status: GoodsReceiptStatus;
  outcome: 'POSTED' | 'SENT_FOR_APPROVAL' | null;
  step: GrnStep;
  purchaseOrder: { poId: string; number: string } | null;
  vendor: VendorRef;
  invoiceNo: string;
  invoiceDate: string;
  creditDays: number;
  invoiceTotalPaise: number;
  computedTotalPaise: number;
  totalDifferencePaise: number;
  warnings: 'VENDOR_LICENCE_EXPIRED'[];
  lines: GoodsReceiptLine[];
  threeWayMatch: ThreeWayMatchRow[];
  approval: ApprovalSummary | null;
  payable: VendorPayable | null;
  debitNoteDraft: { dnId: string; lineCount: number; amountPaise: number } | null;
  history: HistoryEvent[];
  version: number;
}

export interface GoodsReceiptListRow {
  id: string;
  number: string | null;
  vendorName: string;
  invoiceNo: string;
  invoiceTotalPaise: number;
  status: GoodsReceiptStatus;
  createdAt: string;
}

export interface GoodsReceiptPage extends PageMeta {
  items: GoodsReceiptListRow[];
}

// ---------------------------------------------------------------- vendor return and debit note (79-8)

export type ReturnSource =
  | 'QUARANTINE_INDENT_REJECTION'
  | 'QUARANTINE_DAMAGED'
  | 'QUARANTINE_EXPIRED'
  | 'QUARANTINE_OTHER'
  | 'NEAR_EXPIRY'
  | 'GRN_SHORT';

export type ReturnSourceState = 'QUARANTINE' | 'AVAILABLE' | null;

export interface EligibleReturnItem {
  source: ReturnSource;
  product: ProductRef;
  batch: BatchRef | null;
  sourceState: ReturnSourceState;
  goodsReceiptLineId: string | null;
  receiptNo: string | null;
  /** null for a blind caller on an audited product (rule 24). */
  availableQty: number | null;
  rate: string;
  defaultCreditPercent: string | null;
  daysToExpiry: number | null;
}

export interface VendorReturnEligible {
  vendor: VendorRef;
  policy: { returnDaysBeforeExpiry: number | null; creditPercent: string | null };
  items: EligibleReturnItem[];
}

export type DebitNoteStatus =
  | 'DRAFT'
  | 'WAITING_APPROVAL'
  | 'REJECTED'
  | 'CREATED'
  | 'PICKED'
  | 'HANDED_OVER'
  | 'CREDIT_NOTE_RECEIVED'
  | 'CANCELLED'
  | 'DISCARDED';

export type DebitNoteLineType = 'STOCK_RETURN' | 'SHORT_SUPPLY';

export interface DebitNoteLineInput {
  type: DebitNoteLineType;
  batchId?: string | null;
  sourceState?: ReturnSourceState;
  goodsReceiptLineId?: string | null;
  qty: number;
  creditPercent: string;
}

export interface DebitNoteCreate {
  clientKey: string;
  vendorId: string;
  reasonKey?: string | null;
  note?: string | null;
  lines: DebitNoteLineInput[];
}

export interface DebitNoteUpdate {
  version: number;
  reasonKey?: string | null;
  note?: string | null;
  lines: DebitNoteLineInput[];
}

export interface HandOverInput {
  clientKey: string;
  version: number;
  representativeName: string;
  secondSigner?: { membershipId: string; pin: string } | null;
}

export interface CreditNoteInput {
  clientKey: string;
  version: number;
  creditNoteNo: string;
  creditNoteDate: string;
  amountPaise: number;
}

export interface DebitNoteLine {
  id: string;
  type: DebitNoteLineType;
  product: ProductRef;
  batch: BatchRef | null;
  sourceState: ReturnSourceState;
  qty: number;
  rate: string;
  creditPercent: string;
  creditPercentChanged: boolean;
  creditPaise: number;
  gstRate: string;
  gstReversalPaise: number;
  scheduleX: boolean;
}

export type DebitNoteStepKey =
  | 'CREATED'
  | 'PICKED'
  | 'HANDED_OVER'
  | 'CREDIT_NOTE_RECEIVED'
  | 'ADJUSTED_IN_PAYABLE';

export interface DebitNote {
  id: string;
  /** From submit. */
  number: string | null;
  store: StoreRef;
  vendor: VendorRef;
  status: DebitNoteStatus;
  preparedFromGrnId?: string | null;
  reason?: { reasonKey: string; label: string; note?: string | null } | null;
  lines: DebitNoteLine[];
  creditTotalPaise: number;
  gstReversalPaise: number;
  approval: ApprovalSummary | null;
  steps: { step: DebitNoteStepKey; at: string | null; by: PersonRef | null }[];
  representativeName: string | null;
  creditNote: {
    creditNoteNo: string;
    creditNoteDate: string;
    amountPaise: number;
    differencePaise: number;
  } | null;
  payableEffect: { appliedPaise: number; unappliedCreditPaise: number } | null;
  pickList: { product: string; batch: BatchRef; qty: number; rack: string | null }[];
  print?: PrintHeader;
  history: HistoryEvent[];
  version: number;
}

export interface DebitNoteListRow {
  id: string;
  number: string | null;
  vendorName: string;
  creditTotalPaise: number;
  status: DebitNoteStatus;
  createdAt: string;
}

export interface DebitNotePage extends PageMeta {
  items: DebitNoteListRow[];
}
