import { PrintHeader } from './pharmacy-buying.model';
import {
  ApprovalSummary,
  BatchRef,
  HistoryEvent,
  PageMeta,
  ProductRef,
  ReasonRef,
  StoreRef,
} from './pharmacy-inventory.model';
import { PersonRef } from './pharmacy.model';

/**
 * SAH-79 work package F (stories 79-9 to 79-13): indents, dispatches, receipts, returns and shortages.
 * Hand-written from docs/api/pharmacy.yaml 0.3.4 (the schemas Indent, Dispatch, ReturnItem and Shortage); no field is
 * invented. The backend of this package was built in parallel, so the screens also run on the fixtures.
 */

export type IndentPriority = 'ROUTINE' | 'URGENT';

export type IndentStatus =
  | 'DRAFT'
  | 'WAITING_APPROVAL'
  | 'APPROVED'
  | 'PARTLY_DISPATCHED'
  | 'DISPATCHED'
  | 'RECEIVED'
  | 'CLOSED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'DISCARDED';

export type BalanceAction = 'BACK_ORDER' | 'CLOSE';
export type DispatchStatus = 'IN_TRANSIT' | 'PARTLY_RECEIVED' | 'RECEIVED';

export interface IndentChips {
  rejectedWaitingCs: number;
  shortInvestigating: number;
  backOrderOpen: boolean;
  /** 0.3.4: the Schedule X dispatch lines not yet received; the chip "· Schedule X lines waiting for a pharmacist". */
  scheduleXWaiting: number;
}

export interface IndentStockImpact {
  /** null for a caller blind at the Central Store while the product is on its open audit (rule 24). */
  csSaleable: number | null;
  inTransit: number;
  requestingAvailable: number | null;
  pendingReturn: number | null;
  backOrder: number;
  shortInvestigating: number;
}

export interface IndentLine {
  id: string;
  product: ProductRef;
  requestedQty: number;
  approvedQty: number | null;
  /** "+4 by HOD" / "-5 by HOD": the signed difference; null shows a dash. */
  hodChange: number | null;
  hodRemark?: ReasonRef | null;
  remainingQty: number;
  dispatchedQty: number;
  receivedQty: number;
  rejectedQty: number;
  shortQty: number;
  balanceAction: BalanceAction | null;
  closeReason?: ReasonRef | null;
  replacement: { product: ProductRef; reason: ReasonRef } | null;
  /** "<store> stock"; null for a caller who is blind at the requesting store (rule 24). */
  requestingStoreAvailable: number | null;
  /** Central Store available, unexpired; null while a blind caller's audit is open. */
  csStock: number | null;
  stockImpact: IndentStockImpact;
}

export interface IndentDispatchRef {
  dispatchId: string;
  reference: string;
  status: DispatchStatus;
  dispatchedAt: string;
}

export interface Indent {
  id: string;
  number: string | null;
  fromStore: StoreRef;
  toStore: StoreRef;
  priority: IndentPriority;
  status: IndentStatus;
  chips: IndentChips;
  raisedBy: PersonRef;
  raisedAt: string | null;
  actingAs: string;
  lines: IndentLine[];
  approval: ApprovalSummary | null;
  dispatches: IndentDispatchRef[];
  cancelReason?: ReasonRef | null;
  history: HistoryEvent[];
  version: number;
}

export interface IndentListRow {
  id: string;
  number: string | null;
  fromStore: StoreRef;
  toStore: StoreRef;
  itemCount: number;
  priority: IndentPriority;
  status: IndentStatus;
  raisedBy: PersonRef;
  raisedAt: string | null;
  ageMinutes: number | null;
}

export interface IndentPage extends PageMeta {
  items: IndentListRow[];
}

export type IndentView = 'RAISED' | 'TO_DISPATCH';

export interface IndentLineInput {
  productId: string;
  requestedQty: number;
}

export interface IndentCreate {
  clientKey: string;
  priority: IndentPriority;
  lines: IndentLineInput[];
}

export interface IndentUpdate {
  version: number;
  priority: IndentPriority;
  lines: IndentLineInput[];
}

export interface ReplacementOption {
  product: ProductRef;
  /** null for a caller blind at the Central Store while the product is on its open audit. */
  csAvailable: number | null;
}

export interface SecondSignerInput {
  membershipId: string;
  /** Four digits, as typed; write-only (the field is cleared after every send). */
  pin: string;
}

export interface IndentDispatchLineInput {
  indentLineId: string;
  dispatchProductId: string;
  quantity: number;
  balanceAction?: BalanceAction | null;
  changeReasonKey?: string | null;
  changeNote?: string | null;
}

export interface IndentDispatchInput {
  clientKey: string;
  indentVersion: number;
  lines: IndentDispatchLineInput[];
  secondSigner?: SecondSignerInput | null;
}

export interface DispatchLine {
  id: string;
  product: ProductRef;
  batch: BatchRef;
  dispatched: number;
  replacedByCs: boolean;
  changeReason: ReasonRef | null;
  backOrdered: number;
  scheduleX: boolean;
  accepted: number | null;
  rejected: number | null;
  rejectReason: ReasonRef | null;
  received: boolean;
}

export interface Dispatch {
  id: string;
  reference: string;
  source: 'INDENT' | 'TRANSFER';
  sourceId: string;
  sourceNo: string;
  fromStore: StoreRef;
  toStore: StoreRef;
  status: DispatchStatus;
  dispatchedBy: PersonRef;
  dispatchedAt: string;
  actingAs: string;
  lines: DispatchLine[];
  receipt: { receivedBy: PersonRef; receivedAt: string; secondSigner?: PersonRef | null } | null;
  notes: { rejectedNote?: string | null; shortNote?: string | null };
  print: PrintHeader;
  version: number;
}

export interface DispatchListRow {
  id: string;
  reference: string;
  source: 'INDENT' | 'TRANSFER';
  fromStore: StoreRef;
  itemCount: number;
  status: DispatchStatus;
  dispatchedAt: string;
}

export interface DispatchPage extends PageMeta {
  items: DispatchListRow[];
}

export interface DispatchReceiptLineInput {
  dispatchLineId: string;
  accepted: number;
  rejected: number;
  rejectReasonKey?: string | null;
  note?: string | null;
  /** A Schedule X line left for a pharmacist. */
  skipped?: boolean;
}

export interface DispatchReceiptInput {
  clientKey: string;
  dispatchVersion: number;
  lines: DispatchReceiptLineInput[];
  secondSigner?: SecondSignerInput | null;
}

export type ReturnItemStatus = 'WAITING' | 'DISPUTED' | 'ACCEPTED_SALEABLE' | 'ACCEPTED_QUARANTINE' | 'WAITING_HOD';
export type ReturnItemType = 'INDENT_REJECTION' | 'TRANSFER_REJECTION' | 'RETURN_TO_CS' | 'DAMAGED';
export type PutBackInto = 'SALEABLE' | 'QUARANTINE';

export interface ReturnItem {
  id: string;
  docNo: string;
  type: ReturnItemType;
  holdingStore: StoreRef;
  acceptingStore: StoreRef;
  product: ProductRef;
  batch: BatchRef;
  qty: number;
  reason: ReasonRef;
  raisedBy: PersonRef;
  raisedAt: string;
  status: ReturnItemStatus;
  defaultPutBackInto: PutBackInto;
  expired: boolean;
  scheduleX?: boolean;
  dispute: { reason: ReasonRef; by: PersonRef; at: string } | null;
  notes: { note: string; by: PersonRef; at: string }[];
  resultNote: string | null;
  history: HistoryEvent[];
  version: number;
}

export interface WaitingForActionPage extends PageMeta {
  items: ReturnItem[];
}

export type ReturnSide = 'ACCEPTING' | 'RETURNING';

export interface AcceptReturnInput {
  clientKey: string;
  version: number;
  putBackInto: PutBackInto;
  secondSigner?: SecondSignerInput | null;
}

export type ShortageStatus = 'OPEN' | 'CLAIM_OPEN' | 'WAITING_WRITE_OFF' | 'FOUND' | 'WRITTEN_OFF';
export type ShortageOutcome = 'FOUND' | 'TRANSIT_LOSS' | 'CARRIER_CLAIM';

export interface Shortage {
  id: string;
  dispatchReference: string;
  receivingStore: StoreRef;
  settlingStore: StoreRef;
  product: ProductRef;
  batch: BatchRef;
  qty: number;
  status: ShortageStatus;
  approval: ApprovalSummary | null;
  banner?: string;
  resultNote: string | null;
  history: HistoryEvent[];
  version: number;
}

export interface ShortagePage extends PageMeta {
  items: Shortage[];
}

export interface SettleShortageInput {
  clientKey: string;
  version: number;
  outcome: ShortageOutcome;
  note?: string | null;
  secondSigner?: SecondSignerInput | null;
}

export interface NoteInput {
  clientKey: string;
  note: string;
}

/** GET /stores/{storeId}/stock/fefo-pick (work package D): the batches FEFO would pick now, a preview only. */
export interface FefoPick {
  productId: string;
  requested: number;
  /** null for a blind caller while the product is on the store's open audit (rule 24). */
  availableQty: number | null;
  shortBy: number | null;
  batches: { batch: BatchRef; quantity: number | null; purchaseRate: string | null }[];
}
