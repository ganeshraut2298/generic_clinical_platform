import { Role } from '../../core/platform/platform.model';

/**
 * Types for the Pharmacy & Inventory API, work packages A and B of SAH-78 (stories 78-1 to 78-7).
 * Hand-written from `docs/api/pharmacy.yaml` 0.1.1; no field is invented. Dates are IST calendar
 * dates (`YYYY-MM-DD`), instants are ISO date-times.
 */

export type IntegrationMode = 'INTEGRATED' | 'STANDALONE';
export type StoreType = 'CENTRAL' | 'PHARMACY' | 'SUB_STORE' | 'WARD';
export type StoreRole = 'STORE_USER' | 'CENTRAL_STORE_USER' | 'APPROVER' | 'DELEGATE';
export type SetupAccess = 'EDIT' | 'VIEW' | 'NONE';
/** Later phases add values; the client ignores any it does not know. */
export type Capability = 'SETUP_VIEW' | 'SETUP_EDIT' | 'OPENING_STOCK_UPLOAD' | 'OPENING_STOCK_APPROVE';
export type DelegateState = 'UPCOMING' | 'CURRENT' | 'ENDED';
export type StoreLockedField = 'TYPE' | 'CODE' | 'ISSUES_FROM';

export interface StoreAccess {
  raiseIndent: boolean;
  approve: boolean;
  receive: boolean;
  receiveScheduleX: boolean;
  consumeReturn: boolean;
  uploadOpeningStock: boolean;
  /** 0.2.0 (SAH-79): optional so that an older answer still reads; absent means not allowed. */
  readStock?: boolean;
  postOpeningStock?: boolean;
  adjustStock?: boolean;
  startCount: boolean;
  handleScheduleX: boolean;
  /** 0.3.3 (work package F): what the server lets this person do at the store. */
  purchase?: boolean;
  dispatchTransfer: boolean;
  acceptReturn?: boolean;
  returnDamage: boolean;
  /** 0.2.0: the store's Consumption entry is Yes and the person may record it. */
  consume: boolean;
  /** 0.2.0: any ACTIVE mapping; OWNER. May count (blind). */
  count: boolean;
  /** 0.2.0: HOD, current delegate, OWNER. Sees the book quantity after submit. */
  reviewCount: boolean;
  /** 0.3.0 (SAH-80): saleAllowed store and a mapped PHARMACIST member, a CASHIER, or OWNER. */
  sell?: boolean;
  /** 0.3.0: sell and team role PHARMACIST mapped here (lines of schedule H, H1, X). */
  sellScheduled?: boolean;
  /** 0.3.0: sell (a cashier starts a sales return, a pharmacist or the owner finishes it). */
  salesReturn?: boolean;
  /** 0.3.0: owner, a mapped pharmacist, or the store approver or delegate (H1 and Schedule X registers). */
  readRegisters?: boolean;
  /** 0.3.0: equals sellScheduled (pharmacist only). */
  substitute?: boolean;
  /** 0.3.0: equals sellScheduled (allergy and H1 overrides; never the owner). */
  overrideBlock?: boolean;
  /** 0.3.0: the owner; a pharmacist the same IST day (checked per bill); never a cashier. */
  cancelBill?: boolean;
  /** 0.3.0: a pharmacist, the store approver or delegate, or the owner. */
  acceptSalesReturn?: boolean;
}

export interface MyStore {
  storeId: string;
  name: string;
  code: string;
  type: StoreType;
  storeRole: StoreRole;
  effectiveStoreRole: StoreRole;
  access: StoreAccess;
  /** The store this store's indents go to (null for the Central Store). */
  issuesFrom?: { id: string; name: string } | null;
}

export interface PharmacyContext {
  unit: { name: string; code: string; integrationMode: IntegrationMode };
  caller: {
    membershipId: string;
    name: string;
    role: Role;
    roleLabel: string;
    setupAccess: SetupAccess;
  };
  capabilities: string[];
  stores: MyStore[];
  workingStoreId: string | null;
  mustPickWorkingStore: boolean;
  fixedRules: { fefo: true; negativeStockBlocked: true };
}

export interface WorkingStoreInput {
  storeId: string;
}

export interface SetupSummary {
  storeCount: number;
  mappedStaffCount: number;
  activeVendorCount: number;
}

export interface UnitSettings {
  unitName: string;
  unitCode: string;
  gstin: string | null;
  drugLicenceNo: string | null;
  drugLicenceValidTill: string | null;
  drugLicenceExpired: boolean;
  integrationMode: IntegrationMode;
  fefo: true;
  negativeStockBlocked: true;
  ipdAvailable: boolean;
  saved: boolean;
  version: number;
  updatedAt: string | null;
}

/** FEFO and negative stock are never sent (OQ-78-11); extra fields answer 400. */
export interface UnitSettingsInput {
  version: number;
  gstin: string | null;
  drugLicenceNo: string | null;
  drugLicenceValidTill: string | null;
  integrationMode: IntegrationMode;
}

export interface StorePerson {
  membershipId: string;
  name: string;
  roleLabel: string;
  canSignIn: boolean;
}

export interface StoreDelegate extends StorePerson {
  from: string;
  to: string;
  state: DelegateState;
}

export interface DelegateInput {
  membershipId: string;
  from: string;
  to: string;
}

export interface Store {
  id: string;
  name: string;
  code: string;
  type: StoreType;
  issuesFromStoreId: string | null;
  issuesFromName: string | null;
  saleAllowed: boolean;
  consumptionAllowed: boolean;
  hod: StorePerson | null;
  delegate: StoreDelegate | null;
  approverState: 'SET' | 'NONE';
  staffCount: number;
  status: 'ACTIVE' | 'INACTIVE';
  hasStock: boolean;
  lockedFields: StoreLockedField[];
  version: number;
}

export interface StoreList {
  items: Store[];
  centralStoreId: string | null;
}

export interface StoreCreate {
  name: string;
  code: string;
  type: StoreType;
  saleAllowed?: boolean;
  consumptionAllowed?: boolean;
  hodMembershipId?: string | null;
  delegate?: DelegateInput | null;
}

export interface StoreUpdate {
  version: number;
  name: string;
  code: string;
  type: StoreType;
  saleAllowed: boolean;
  consumptionAllowed: boolean;
  hodMembershipId: string | null;
  delegate: DelegateInput | null;
}

export interface StaffCandidate {
  membershipId: string;
  name: string;
  role: Role;
  roleLabel: string;
  allowedStoreRoles: StoreRole[];
  alreadyMappedHere: boolean;
}

export interface StaffMapping {
  id: string;
  storeId: string;
  membershipId: string;
  name: string;
  role: Role;
  roleLabel: string;
  isHod: boolean;
  storeRole: StoreRole;
  effectiveStoreRole: StoreRole;
  delegateFrom: string | null;
  delegateTo: string | null;
  delegateState: DelegateState | null;
  access: StoreAccess;
  otherStores: { storeId: string; name: string }[];
  memberState: 'ACTIVE' | 'CANNOT_SIGN_IN';
  needsReview: boolean;
  status: 'ACTIVE' | 'REMOVED';
  version: number;
}

export interface StaffMappingInput {
  membershipId: string;
  storeRole: StoreRole;
  delegateFrom?: string | null;
  delegateTo?: string | null;
}

export interface StaffMappingUpdate {
  version: number;
  storeRole: StoreRole;
  delegateFrom?: string | null;
  delegateTo?: string | null;
}

// ---------------------------------------------------------------- products (78-5, 78-6)

export type Schedule = 'NONE' | 'H' | 'H1' | 'X';
export type ProductSource = 'GLOBAL' | 'OWN';
export type ProductInUnit = 'ADOPTED' | 'NOT_ADOPTED' | 'WITHDRAWN' | 'ACTIVE' | 'INACTIVE';

export interface ProductRow {
  source: ProductSource;
  catalogueProductId: string | null;
  /** The unit's product; null for a global product that is not adopted. */
  productId: string | null;
  code: string;
  name: string;
  manufacturer: string | null;
  madeInHouse: boolean;
  salt: string;
  strength: string;
  form: string;
  purchaseUnit: string;
  issueUnit: string;
  conversion: number;
  hsn: string;
  /** Percent as a decimal string, for example "12". */
  gstRate: string;
  schedule: Schedule;
  ndps: boolean;
  inUnit: ProductInUnit;
  /** A seeded sample row: "Sample · not clinically validated" (DEC-062 Q2). */
  sample: boolean;
  alias: string | null;
}

export interface ProductPage {
  items: ProductRow[];
  total: number;
  page: number;
  size: number;
}

export interface StoreSetting {
  storeId: string;
  storeName: string;
  reorderLevel: number | null;
  rack: string | null;
}

export interface PreferredVendor {
  id: string;
  name: string;
  needsReview: boolean;
}

export interface ProductLocalSettings {
  storeSettings: StoreSetting[];
  preferredVendor: PreferredVendor | null;
  alias: string | null;
  barcode: string | null;
  returnable: boolean;
  active: boolean;
}

export interface ProductDetail extends ProductRow {
  /** Catalogue barcodes of an adopted product (read-only). */
  barcodes: string[];
  localSettings: ProductLocalSettings;
  unitsLocked: boolean;
  hasStock: boolean;
  version: number;
}

export interface DuplicateCandidate {
  source: ProductSource;
  catalogueProductId: string | null;
  productId: string | null;
  code: string;
  name: string;
  manufacturer: string | null;
  madeInHouse: boolean;
  inUnit: 'ADOPTED' | 'NOT_ADOPTED' | 'ACTIVE';
}

export interface OwnProductInput {
  clientKey: string;
  name: string;
  salt: string;
  strength: string;
  form: string;
  purchaseUnit: string;
  issueUnit: string;
  conversion: number;
  manufacturer: string | null;
  madeInHouse: boolean;
  hsn: string;
  gstRate: string;
  /** null is refused with "Choose the schedule" (BR-16). */
  schedule: Schedule | null;
  barcode?: string | null;
  ignoreDuplicates?: boolean;
}

export interface OwnProductUpdate {
  version: number;
  name: string;
  salt: string;
  strength: string;
  form: string;
  purchaseUnit: string;
  issueUnit: string;
  conversion: number;
  manufacturer: string | null;
  madeInHouse: boolean;
  hsn: string;
  gstRate: string;
  schedule: Schedule | null;
  barcode?: string | null;
  ignoreDuplicates?: boolean;
}

export interface StoreSettingInput {
  storeId: string;
  reorderLevel: number | null;
  rack: string | null;
}

export interface LocalSettingsInput {
  version: number;
  storeSettings: StoreSettingInput[];
  preferredVendorId: string | null;
  alias: string | null;
  barcode: string | null;
  returnable: boolean;
  active: boolean;
}

export interface ProductOptions {
  units: string[];
  forms: string[];
}

// ---------------------------------------------------------------- opening stock (78-7)

export type ImportStatus =
  | 'DRAFT'
  | 'NOT_ACCEPTED'
  | 'WAITING_APPROVAL'
  | 'APPROVED'
  | 'REJECTED'
  | 'REPLACED';
export type RejectReasonKey = 'WRONG_FILE' | 'WRONG_QUANTITIES' | 'WRONG_STORE' | 'OTHER';
export type ApproveLockReason = 'SELF_UPLOADED' | 'NOT_OWNER';
export type RowIssueField =
  | 'storeCode'
  | 'productCode'
  | 'batch'
  | 'expiryDate'
  | 'mrp'
  | 'purchaseRate'
  | 'quantity';

export interface PersonRef {
  membershipId: string;
  name: string;
}

export interface OpeningStockImport {
  id: string;
  storeId: string;
  storeName: string;
  status: ImportStatus;
  rowCount: number;
  receivedRows: number;
  lineCount: number;
  totalQuantity: number;
  totalValuePaise: number;
  errorCount: number;
  warningCount: number;
  uploadedBy: PersonRef;
  createdAt: string;
  submittedAt: string | null;
  decidedBy: PersonRef | null;
  decidedAt: string | null;
  rejectReasonKey: RejectReasonKey | null;
  rejectNote: string | null;
  replacedByImportId: string | null;
  canApprove: boolean;
  approveLockReason: ApproveLockReason | null;
  /** 0.2.0: set by `POST .../post` (SAH-79-1); false until then. */
  posted: boolean;
  postedAt?: string | null;
  postedBy?: PersonRef | null;
  version: number;
}

export interface ImportPage {
  items: OpeningStockImport[];
  total: number;
  page: number;
  size: number;
}

export interface ImportCreate {
  clientKey: string;
  columns: string[];
  rowCount: number;
}

/** One data row of the file as text, exactly as read; the server parses and checks it. */
export interface LineRowInput {
  rowNo: number;
  storeCode: string;
  productCode: string;
  batch: string;
  expiryDate: string;
  mrp: string;
  purchaseRate: string;
  quantity: string;
}

export interface LinesInput {
  rows: LineRowInput[];
}

export interface RowIssue {
  rowNo: number;
  field: RowIssueField;
  code: string;
  severity: 'ERROR' | 'WARNING';
  message: string;
}

export interface LinesResult {
  receivedRows: number;
  rowCount: number;
  issues: RowIssue[];
}

export interface OpeningStockLine {
  rowNo: number;
  storeCode: string;
  productCode: string;
  productId: string | null;
  productName: string | null;
  batch: string;
  expiryDate: string | null;
  mrp: string | null;
  purchaseRate: string | null;
  quantity: number | null;
  valuePaise: number | null;
  issues: RowIssue[];
}

export interface OpeningStockLinePage {
  items: OpeningStockLine[];
  total: number;
  page: number;
  size: number;
}

export interface RejectInput {
  reasonKey: RejectReasonKey;
  note?: string | null;
}

// ---------------------------------------------------------------- labels (story texts)

export const STORE_TYPE_LABELS: Record<StoreType, string> = {
  CENTRAL: 'Central Store',
  PHARMACY: 'Pharmacy',
  SUB_STORE: 'Sub-store',
  WARD: 'Ward store',
};

export const STORE_ROLE_LABELS: Record<StoreRole, string> = {
  STORE_USER: 'Store user',
  CENTRAL_STORE_USER: 'Central Store user',
  APPROVER: 'Approver',
  DELEGATE: 'Delegate approver',
};

export const INTEGRATION_MODE_LABELS: Record<IntegrationMode, string> = {
  INTEGRATED: 'Integrated',
  STANDALONE: 'Standalone',
};

/** Message shown for every failed save (stories, common rules). */
export const SAVE_FAILED = 'Could not save. Check your connection and try again.';
export const LOAD_FAILED = 'Could not load. Try again.';
export const NO_ACCESS = 'You do not have access to Pharmacy & Inventory. Ask your owner.';

export const IN_UNIT_LABELS: Record<ProductInUnit, string> = {
  ADOPTED: 'Adopted',
  NOT_ADOPTED: 'Not adopted',
  WITHDRAWN: 'Withdrawn',
  ACTIVE: 'Active',
  INACTIVE: 'Inactive',
};

/** The Schedule select of the own-product form. The list shows "—" for NONE and a chip for the rest. */
export const SCHEDULE_OPTIONS: readonly { value: Schedule; label: string }[] = [
  { value: 'NONE', label: 'None' },
  { value: 'H', label: 'Schedule H' },
  { value: 'H1', label: 'Schedule H1' },
  { value: 'X', label: 'Schedule X' },
];

export const IMPORT_STATUS_LABELS: Record<ImportStatus, string> = {
  DRAFT: 'Draft',
  NOT_ACCEPTED: 'Not accepted',
  WAITING_APPROVAL: 'Waiting for approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  REPLACED: 'Replaced',
};

export const REJECT_REASON_LABELS: Record<RejectReasonKey, string> = {
  WRONG_FILE: 'Wrong file',
  WRONG_QUANTITIES: 'Wrong quantities',
  WRONG_STORE: 'Wrong store',
  OTHER: 'Other',
};

export const UPLOAD_FAILED = 'Could not upload. Try again.';
export const TEMPLATE_HELP = 'Use the Sahaj template. Save the file as CSV.';

// ---------------------------------------------------------------- vendors (78-8), docs/api/pharmacy.yaml 0.2.1

export type LicenceState = 'VALID' | 'EXPIRED' | 'NOT_REQUIRED';
export type VendorStatus = 'ACTIVE' | 'BLOCKED' | 'INACTIVE';
export type VendorStatusFilter = VendorStatus | 'ALL';

export interface VendorStoreRef {
  storeId: string;
  name: string;
}

export interface Vendor {
  id: string;
  name: string;
  gstin: string | null;
  /** 0.3.4: licenceRequired, licenceState, creditDays, suppliesTo and productsCount are null in the reduced view. */
  licenceRequired: boolean | null;
  licenceNo: string | null;
  licenceValidTill: string | null;
  licenceState: LicenceState | null;
  contactName: string | null;
  contactPhone: string | null;
  creditDays: number | null;
  returnDaysBeforeExpiry: number | null;
  /** Percent as a decimal string, for example "100". */
  returnCreditPercent: string | null;
  /** 0.2.1: credit for goods damaged on receipt; absent or null means the standard credit applies. */
  damagedCreditPercent?: string | null;
  /** 0.2.1: credit after expiry; absent or null means the standard credit applies. */
  expiredCreditPercent?: string | null;
  suppliesTo: VendorStoreRef[] | null;
  productsCount: number | null;
  /** BLOCKED is computed by the server from the licence date and never stored. */
  status: VendorStatus;
  blocked: boolean;
  version: number;
}

export interface VendorPage {
  items: Vendor[];
  total: number;
  page: number;
  size: number;
}

export interface VendorInput {
  clientKey: string;
  name: string;
  gstin: string | null;
  licenceRequired: boolean;
  licenceNo: string | null;
  licenceValidTill: string | null;
  contactName: string | null;
  contactPhone: string | null;
  creditDays: number;
  returnDaysBeforeExpiry: number | null;
  returnCreditPercent: string | null;
  /** 0.2.1, optional: absent leaves the stored value; null clears it. */
  damagedCreditPercent?: string | null;
  /** 0.2.1, optional: absent leaves the stored value; null clears it. */
  expiredCreditPercent?: string | null;
  suppliesToStoreIds: string[];
}

export type VendorUpdate = Omit<VendorInput, 'clientKey'> & { version: number };

// ---------------------------------------------------------------- approval rules (78-9)

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
export type ConditionKind =
  | 'ALWAYS'
  | 'ABOVE_AMOUNT'
  | 'ABOVE_PERCENT'
  | 'SCHEDULE_X_ITEM'
  | 'HIGH_VALUE_ITEM'
  | 'URGENT_PRIORITY'
  | 'STORE_TYPE';
export type ApproverKind = 'STORE_APPROVER' | 'OWNER';
export type RulePreset = 'LEAN' | 'STANDARD' | 'STRICT' | 'CUSTOM';
export type FixedRuleKey = 'NO_SELF_APPROVAL' | 'SCHEDULE_X_TWO_SIGNATURES' | 'LEDGER_APPEND_ONLY';

export interface ApprovalCondition {
  kind: ConditionKind;
  amountPaise: number | null;
  percent: string | null;
  storeTypes: StoreType[] | null;
}

export interface ApprovalLevel {
  level: 1 | 2;
  approver: ApproverKind;
  condition: ApprovalCondition;
}

export interface ApprovalRule {
  transactionType: TransactionType;
  levels: ApprovalLevel[];
  escalateAfterHours: number | null;
  urgentEscalateAfterMinutes: number | null;
}

/** 0.2.1: the transaction types whose owner level needs a limit in a preset; the server is the source of truth. */
export interface PresetInfo {
  preset: 'LEAN' | 'STANDARD' | 'STRICT';
  limitTypes: TransactionType[];
}

export interface ApprovalRules {
  versionNo: number;
  preset: RulePreset;
  reviewNeeded: boolean;
  savedAt: string | null;
  savedBy: PersonRef | null;
  highValueThresholdPaise: number | null;
  rules: ApprovalRule[];
  presets?: PresetInfo[];
  fixedRules: { key: FixedRuleKey; text: string }[];
  storesWithoutApprover: { storeId: string; name: string }[];
  currentDelegates: {
    storeId: string;
    storeName: string;
    membershipId: string;
    name: string;
    to: string;
  }[];
}

export interface ApprovalRulesInput {
  baseVersionNo: number;
  highValueThresholdPaise: number | null;
  rules: ApprovalRule[];
}

export interface PresetLimit {
  transactionType: TransactionType;
  amountPaise: number | null;
  percent: string | null;
  noAmountCondition: boolean;
}

export interface PresetInput {
  baseVersionNo: number;
  preset: 'LEAN' | 'STANDARD' | 'STRICT';
  limits: PresetLimit[];
}

// ---------------------------------------------------------------- numbering, reasons, print templates (78-10)

/** 0.2.0 lists ten series; the client renders the rows the server returns and ignores no type it knows. */
export type DocType =
  | 'PURCHASE_ORDER'
  | 'GRN'
  | 'INDENT'
  | 'TRANSFER'
  | 'BILL'
  | 'DEBIT_NOTE'
  | 'STOCK_AUDIT'
  | 'STORE_RETURN'
  | 'DAMAGE_REPORT'
  | 'STOCK_ADJUSTMENT';

export interface DocumentSeries {
  docType: DocType;
  prefix: string;
  financialYearPart: boolean;
  sequenceLength: number;
  restartsEachFinancialYear: boolean;
  example: string;
  renderedLength: number;
  numbersIssued: boolean;
  saved: boolean;
  version: number;
}

export interface DocumentSeriesInput {
  version: number;
  prefix: string;
  financialYearPart: boolean;
  sequenceLength: number;
  restartsEachFinancialYear: boolean;
}

export type ListKey =
  | 'store-return'
  | 'sales-return'
  | 'damage'
  | 'stock-adjustment'
  | 'receipt-reject'
  | 'cs-change'
  | 'approval-reject'
  | 'transfer'
  | 'consumption-purpose'
  | 'dispute'
  | 'vendor-return';

export interface ReasonEntry {
  reasonKey: string;
  label: string;
  seeded: boolean;
  active: boolean;
  /** 0.2.1: true for a reason that cannot be switched off. */
  fixed?: boolean;
}

export interface ReasonList {
  listKey: ListKey;
  label: string;
  reasons: ReasonEntry[];
}

export interface ReasonLists {
  lists: ReasonList[];
}

export interface ReasonInput {
  label: string;
}

export type PrintDocType = 'INVOICE' | 'ISSUE_SLIP' | 'DEBIT_NOTE';
export type PaperSize = 'A4' | 'A5';
export type PrintFixedField =
  | 'UNIT_NAME'
  | 'DOCUMENT_NO'
  | 'DATE'
  | 'GSTIN'
  | 'DRUG_LICENCE_NO'
  | 'HSN'
  | 'BATCH'
  | 'EXPIRY'
  | 'MRP'
  | 'GST_AMOUNTS';

export interface PrintTemplate {
  docType: PrintDocType;
  headerLines: string[];
  defaultHeaderLines: string[];
  footerNote: string | null;
  paperSize: PaperSize;
  fixedFields: PrintFixedField[];
  printable: boolean;
  missing: ('GSTIN' | 'DRUG_LICENCE_NO')[];
  saved: boolean;
  version: number;
}

export interface PrintTemplateInput {
  version: number;
  headerLines: string[];
  footerNote: string | null;
  paperSize: PaperSize;
}
