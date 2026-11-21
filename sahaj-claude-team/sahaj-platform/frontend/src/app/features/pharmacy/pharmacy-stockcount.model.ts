import { SecondSignerInput } from './pharmacy-indent.model';
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
 * Hand-written from docs/api/pharmacy.yaml 0.3.5, work package H (SAH-79 stories 79-17 to 79-19): the stock count
 * and audit and the reports. No field is invented. A count sheet line has no book quantity, difference or value
 * for anyone (BR-30; contract rule 24): the type below cannot hold one.
 */

export type StockAuditStatus = 'COUNTING' | 'RECOUNT' | 'WAITING_REVIEW' | 'WAITING_APPROVAL' | 'CLOSED';

export const STOCK_AUDIT_STATUS_LABELS: Record<StockAuditStatus, string> = {
  COUNTING: 'Counting in progress',
  RECOUNT: 'Recount',
  WAITING_REVIEW: 'Waiting for review',
  WAITING_APPROVAL: 'Waiting for approval',
  CLOSED: 'Closed',
};

export interface StockAuditCreate {
  clientKey: string;
  scope: 'ALL_AVAILABLE' | 'PRODUCTS';
  productIds?: string[] | null;
}

export interface StockAudit {
  id: string;
  number: string;
  store: StoreRef;
  status: StockAuditStatus;
  startedBy: PersonRef;
  startedAt: string;
  submittedBy: PersonRef | null;
  itemsInCount: number;
  counted: number;
  /** null for a caller without reviewCount, and before submit (blind count). */
  withVariance: number | null;
  varianceValuePaise: number | null;
  approval: ApprovalSummary | null;
  history: HistoryEvent[];
  version: number;
}

export interface StockAuditPage extends PageMeta {
  items: StockAudit[];
}

export interface CountSheetLine {
  auditLineId: string;
  product: ProductRef;
  batch: BatchRef;
  rack: string | null;
  barcode: string | null;
  counted: number | null;
  notCounted: boolean;
  notCountedReason: ReasonRef | null;
  recount: boolean;
}

export interface CountSheetPage extends PageMeta {
  auditNo: string;
  storeName: string;
  status: StockAuditStatus;
  counted: number;
  itemsInCount: number;
  version: number;
  items: CountSheetLine[];
}

export interface CountLineInput {
  auditLineId: string;
  counted?: number | null;
  notCounted?: boolean;
  notCountedReasonKey?: string | null;
}

export interface CountsInput {
  version: number;
  lines: CountLineInput[];
}

export interface VarianceLine {
  auditLineId: string;
  product: ProductRef;
  batch: BatchRef;
  /** The book quantity, fixed at submit. */
  system: number;
  counted: number;
  diff: number;
  valuePaise: number;
  reason: ReasonRef | null;
  counter: PersonRef;
  stockMoved: boolean;
  suggestConsumption: boolean;
  scheduleX?: boolean;
}

export interface VarianceReview {
  auditId: string;
  auditNo: string;
  store: StoreRef;
  status: StockAuditStatus;
  varianceValuePaise: number;
  lines: VarianceLine[];
  version: number;
}

export interface VarianceReasonsInput {
  version: number;
  lines: { auditLineId: string; reasonKey: string; note?: string | null }[];
}

export interface RecountInput {
  version: number;
  auditLineIds: string[];
}

export interface AuditPostInput {
  clientKey: string;
  version: number;
  secondSigner?: SecondSignerInput | null;
}

// ---- 79-18, 79-19 reports

export type ReportGroup = 'STOCK' | 'MOVEMENT' | 'PURCHASE' | 'SALES_COMPLIANCE';

export interface ReportCatalogue {
  line: string;
  groups: { group: ReportGroup; /** The label the server gives the group ("Sales & compliance", mockup 15). */ label: string; reports: { key: string; label: string }[] }[];
}

export type ReportColumnKind = 'TEXT' | 'INTEGER' | 'PAISE' | 'RATE' | 'PERCENT' | 'DATE' | 'DATE_TIME';

export interface ReportColumn {
  key: string;
  label: string;
  kind: ReportColumnKind;
}

export type ReportCell = string | number | null;

export interface ReportPage extends PageMeta {
  reportKey: string;
  title: string;
  columns: ReportColumn[];
  rows: ReportCell[][];
  totals: { label: string; cells: ReportCell[] }[] | null;
}

export interface ReportQuery {
  storeId?: string;
  from?: string;
  to?: string;
  productId?: string;
  vendorId?: string;
  status?: string;
  page?: number;
  size?: number;
}
