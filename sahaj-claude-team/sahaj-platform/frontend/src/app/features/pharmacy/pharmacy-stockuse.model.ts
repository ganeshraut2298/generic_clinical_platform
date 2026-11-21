import { SecondSignerInput, ReturnItem } from './pharmacy-indent.model';
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
 * Hand-written from docs/api/pharmacy.yaml 0.3.5, work package G (SAH-79 stories 79-14 to 79-16): the return to the
 * Central Store, the damage report, the stock transfer and the ward consumption. No field is invented.
 */

// ---- 79-14 store return and damage report

export interface StoreReturnCreate {
  clientKey: string;
  batchId: string;
  quantity: number;
  /** List store-return. */
  reasonKey: string;
  note?: string | null;
  secondSigner?: SecondSignerInput | null;
}

export interface StoreReturn {
  id: string;
  number: string;
  store: StoreRef;
  product: ProductRef;
  batch: BatchRef;
  quantity: number;
  reason: ReasonRef;
  item: ReturnItem;
  history: HistoryEvent[];
}

export type DamageFromState = 'AVAILABLE' | 'QUARANTINE';
export type DamageAfterApproval = 'WRITE_OFF' | 'SEND_TO_CS';
export type DamageStatus = 'WAITING_APPROVAL' | 'WRITTEN_OFF' | 'SENT_TO_CS' | 'RELEASED' | 'KEPT_IN_QUARANTINE';

export interface DamageReportCreate {
  clientKey: string;
  batchId: string;
  fromState: DamageFromState;
  quantity: number;
  /** List damage. */
  reasonKey: string;
  note?: string | null;
  afterApproval: DamageAfterApproval;
  secondSigner?: SecondSignerInput | null;
}

export interface DamageReport {
  id: string;
  number: string;
  store: StoreRef;
  product: ProductRef;
  batch: BatchRef;
  quantity: number;
  reason: ReasonRef;
  afterApproval: DamageAfterApproval;
  status: DamageStatus;
  outcome: string | null;
  approval: ApprovalSummary | null;
  history: HistoryEvent[];
  version: number;
}

export const DAMAGE_STATUS_LABELS: Record<DamageStatus, string> = {
  WAITING_APPROVAL: 'Waiting for HOD',
  WRITTEN_OFF: 'Written off',
  SENT_TO_CS: 'Sent to Central Store',
  RELEASED: 'Released to available stock',
  KEPT_IN_QUARANTINE: 'Kept in quarantine',
};

// ---- 79-15 stock transfer

export type TransferStatus = 'WAITING_APPROVAL' | 'APPROVED' | 'IN_TRANSIT' | 'RECEIVED' | 'REJECTED' | 'CANCELLED';
export type TransferStep = 'REQUESTED' | 'APPROVED' | 'DISPATCHED' | 'RECEIVED';

export interface TransferCreate {
  clientKey: string;
  fromStoreId: string;
  toStoreId: string;
  /** List transfer. */
  reasonKey: string;
  note?: string | null;
  lines: { productId: string; quantity: number }[];
}

export interface TransferLine {
  id: string;
  product: ProductRef;
  quantity: number;
  previewBatches: { batch: BatchRef; quantity: number }[];
  /** null for a caller blind at the sending store while the product is on its open audit (rule 24). */
  availableAtSender: number | null;
  valuePaise: number;
}

export interface Transfer {
  id: string;
  number: string;
  fromStore: StoreRef;
  toStore: StoreRef;
  reason: ReasonRef;
  status: TransferStatus;
  step: TransferStep;
  notReceivedHours: number | null;
  lines: TransferLine[];
  totalValuePaise: number;
  approval: ApprovalSummary | null;
  dispatch: { dispatchId: string; status: 'IN_TRANSIT' | 'PARTLY_RECEIVED' | 'RECEIVED' } | null;
  raisedBy: PersonRef;
  raisedAt: string;
  history: HistoryEvent[];
  version: number;
}

export interface TransferListRow {
  id: string;
  number: string;
  fromStore: StoreRef;
  toStore: StoreRef;
  itemCount: number;
  status: TransferStatus;
  ageMinutes: number;
  notReceivedHours: number | null;
}

export interface TransferPage extends PageMeta {
  items: TransferListRow[];
}

export const TRANSFER_STATUS_LABELS: Record<TransferStatus, string> = {
  WAITING_APPROVAL: 'Waiting for approval',
  APPROVED: 'Approved',
  IN_TRANSIT: 'In transit',
  RECEIVED: 'Received',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
};

/** Dispatch of a transfer: the body of PostWithSignerInput. */
export interface TransferDispatchInput {
  clientKey: string;
  version: number;
  secondSigner?: SecondSignerInput | null;
}

// ---- 79-16 ward consumption

export interface AdmittedPatient {
  admissionId: string;
  ipNumber: string;
  /** For on-screen verification only: never stored, never logged, never shown in a list. */
  patientName: string;
  bedLabel: string | null;
}

export type ConsumptionType = 'GENERAL' | 'PATIENT';
export type ConsumptionChargeStatus = 'NOT_APPLICABLE' | 'CHARGE_PENDING';

export interface ConsumptionCreate {
  clientKey: string;
  type: ConsumptionType;
  purposeKey?: string | null;
  note?: string | null;
  admissionId?: string | null;
  orderedBy?: { membershipId?: string | null; otherName?: string | null } | null;
  stockAuditLineId?: string | null;
  lines: { productId: string; quantity: number }[];
  secondSigner?: SecondSignerInput | null;
}

export interface Consumption {
  id: string;
  store: StoreRef;
  type: ConsumptionType;
  purpose: ReasonRef | null;
  ipNumber: string | null;
  orderedBy: string | null;
  chargeStatus: ConsumptionChargeStatus;
  recordedBy: PersonRef;
  recordedAt: string;
  costBookedTo: string;
  lines: { product: ProductRef; batch: BatchRef; quantity: number }[];
  secondSigner?: PersonRef | null;
}

export interface ConsumptionPage extends PageMeta {
  items: Consumption[];
}
