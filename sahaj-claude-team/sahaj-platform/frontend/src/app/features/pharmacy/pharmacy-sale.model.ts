import { BatchRef, HistoryEvent, PageMeta, ProductRef, StoreRef } from './pharmacy-inventory.model';
import { PrintHeader } from './pharmacy-buying.model';
import { PersonRef, Schedule } from './pharmacy.model';

/**
 * Hand-written from docs/api/pharmacy-sales.yaml 0.3.1, work package I (SAH-80 stories 80-2 to 80-5): the sale core.
 * No field is invented. The checks of work package J (discount approval, substitution, allergy block and override,
 * Schedule H1 and X at sale) are in the quote and are shown as the contract gives them; their screens are not built here.
 */

export type PaymentMode = 'CASH' | 'UPI' | 'CARD' | 'UDHAR' | 'CREDIT_NOTE';

export const PAYMENT_MODE_LABELS: Record<PaymentMode, string> = {
  CASH: 'Cash',
  UPI: 'UPI',
  CARD: 'Card',
  UDHAR: 'Udhar (credit)',
  CREDIT_NOTE: 'Credit note',
};

export type BuyerKind = 'PATIENT' | 'CUSTOMER' | 'WALK_IN' | 'NONE';

export interface BuyerInput {
  kind: BuyerKind;
  patientId?: string | null;
  customerId?: string | null;
  walkInName?: string | null;
  walkInPhone?: string | null;
  saveToCustomers?: boolean;
  linkPending?: boolean;
}

export interface SalePatientRow {
  patientId: string;
  name: string;
  age: number;
  sex: string;
  uhid: string | null;
  maskedMobile: string | null;
}

export interface CreditLine {
  customerId: string;
  creditLimitPaise: number | null;
  duePaise: number;
  over60DaysPaise?: number | null;
  creditNoteBalancePaise: number;
}

export interface Customer {
  id: string;
  kind: 'WALK_IN' | 'PATIENT';
  patientId: string | null;
  name: string;
  phone: string | null;
  phoneMasked: boolean;
  allergyNote: string | null;
  status: 'ACTIVE' | 'INACTIVE';
  credit: CreditLine;
  version: number;
}

export interface CustomerPage extends PageMeta {
  items: Customer[];
}

export interface CustomerCreate {
  clientKey: string;
  patientId?: string | null;
  name?: string | null;
  phone?: string | null;
  allergyNote?: string | null;
  creditLimitPaise?: number | null;
  confirmSamePhone?: boolean;
}

export type CustomerEntryKind =
  | 'CREDIT_BILL'
  | 'PAYMENT'
  | 'CANCELLED_BILL'
  | 'RETURN_TO_UDHAR'
  | 'CREDIT_NOTE_ISSUED'
  | 'CREDIT_NOTE_USED';

export interface CustomerAccount extends PageMeta {
  credit: CreditLine;
  items: {
    entryId: string;
    kind: CustomerEntryKind;
    at: string;
    storeName: string;
    reference: string | null;
    mode?: PaymentMode | null;
    amountPaise: number;
    dueAfterPaise: number;
    creditNoteBalanceAfterPaise: number;
    by: PersonRef;
  }[];
}

export interface CustomerPaymentInput {
  clientKey: string;
  amountPaise: number;
  mode: 'CASH' | 'UPI' | 'CARD';
}

export interface CustomerPaymentReceipt {
  entryId: string;
  customerId: string;
  customerName: string;
  amountPaise: number;
  mode: 'CASH' | 'UPI' | 'CARD';
  receivedAt: string;
  receivedBy: PersonRef;
  store: StoreRef;
  dueAfterPaise: number;
  printHeader: PrintHeader;
}

export interface SaleProductRow {
  product: ProductRef;
  salt?: string;
  packText: string;
  /** null for a blind caller on an audited product (rule 32). */
  availableQty: number | null;
  outOfStock: boolean;
  matchedOnSalt: boolean;
  mrpFrom: string | null;
}

export interface ReasonInput {
  reasonKey: string;
  note?: string | null;
}

export interface SubstitutionInput {
  reasonKey: string;
  note?: string | null;
  patientAgrees: true;
}

export interface CartLineInput {
  productId: string;
  qty: number;
  /** The product asked for, when it differs (a substitution, 80-7). */
  prescribedProductId?: string | null;
  substitution?: SubstitutionInput | null;
  /** List allergy-override; pharmacist only. */
  allergyOverride?: ReasonInput | null;
  /** List h1-override; pharmacist only; never for Schedule X. */
  h1Override?: ReasonInput | null;
}

export interface PrescriptionInput {
  source: 'E_RX' | 'TYPED';
  prescriberName?: string | null;
  prescriberRegNo?: string | null;
  prescriptionDate?: string | null;
  patientName?: string | null;
  patientAge?: number | null;
  patientAddress?: string | null;
}

export interface CartInput {
  buyer: BuyerInput;
  lines: CartLineInput[];
  discountPercent: string | null;
  discountRequestId?: string | null;
  prescription?: PrescriptionInput | null;
}

export interface TaxByRate {
  gstRate: string;
  taxablePaise: number;
  cgstPaise: number;
  sgstPaise: number;
  totalPaise: number;
}

export interface BillSummary {
  itemCount: number;
  unitCount: number;
  mrpTotalPaise: number;
  discountPercent: string | null;
  discountPaise: number;
  taxByRate: TaxByRate[];
  cgstPaise: number;
  sgstPaise: number;
  roundOffPaise: number;
  netPayablePaise: number;
}

export interface QuoteLine {
  lineNo: number;
  inputIndex?: number;
  product: ProductRef;
  saltText?: string;
  batch: BatchRef | null;
  qty: number;
  shortBy?: number | null;
  availableQty?: number | null;
  mrp: string;
  gstRate: string;
  amountPaise: number;
  discountSharePaise: number;
  netPaise: number;
  taxablePaise: number;
  taxPaise: number;
  expiresInDays: number | null;
  expiredBatchBlocked: string | null;
  schedule: Schedule;
  needsPharmacist: boolean;
  needsSecondSigner: boolean;
  allergyBlock: { allergyText: string; overridden: boolean } | null;
  h1PrescriberMissing: boolean;
  h1Overridden?: boolean;
  substitution: { prescribedProduct: ProductRef } | null;
  rx: 'NONE' | 'RX' | 'NOT_ON_RX';
}

export type QuoteBlocker =
  | 'NO_LINES'
  | 'SHORT'
  | 'ALLERGY'
  | 'H1_PRESCRIBER'
  | 'PRESCRIPTION'
  | 'PHARMACIST'
  | 'DISCOUNT_APPROVAL'
  | 'PAYMENTS';

export interface AllergyInfo {
  status: 'RECORDED' | 'NONE_RECORDED' | 'NOT_CHECKED' | 'NO_PATIENT' | 'NOT_APPLICABLE';
  text: string | null;
  classesNotChecked?: true;
}

export interface SaleQuote {
  quoteVersion: string;
  lines: QuoteLine[];
  summary: BillSummary;
  discount: {
    approvalNeeded: boolean;
    limitPercent: string | null;
    limitNotSet: boolean;
    lean: boolean;
    approval: DiscountRequest | null;
  };
  allergy: AllergyInfo;
  credit?: CreditLine | null;
  needsPharmacist: boolean;
  needsSecondSigner: boolean;
  needsPrescription: boolean;
  needsH1PatientDetails: boolean;
  canSave: boolean;
  blockers: QuoteBlocker[];
}

export interface PaymentInput {
  mode: PaymentMode;
  amountPaise: number;
}

export interface BillCreate extends CartInput {
  clientKey: string;
  quoteVersion: string;
  expectedNetPaise: number;
  payments: PaymentInput[];
  heldBillId?: string | null;
  secondSigner?: { membershipId: string; pin: string } | null;
}

export interface BillLine {
  lineId: string;
  lineNo: number;
  product: ProductRef;
  hsn: string;
  batch: BatchRef;
  qty: number;
  mrp: string;
  gstRate: string;
  amountPaise: number;
  discountSharePaise: number;
  netPaise: number;
  taxablePaise: number;
  taxPaise: number;
  returnedQty: number;
  schedule: Schedule;
}

export interface Bill {
  id: string;
  billNo: string;
  store: StoreRef;
  status: 'SAVED' | 'CANCELLED';
  savedAt: string;
  savedBy: PersonRef & { roleLabel?: string };
  buyer: { kind: BuyerKind; patientId?: string | null; customerId?: string | null; label: string; uhid?: string | null; maskedMobile?: string | null };
  lines: BillLine[];
  summary: BillSummary;
  payments: PaymentInput[];
  udharPaise: number;
  linkPending: boolean;
  allergyNotChecked: boolean;
  pharmacist: { name: string; registrationNo: string | null } | null;
  refundDuePaise: number;
  hasReturns: boolean;
  canCancel: boolean;
  print: {
    unitName: string;
    drugLicenceNo: string | null;
    gstin: string | null;
    footerNote: string | null;
  };
  history: HistoryEvent[];
  version: number;
}

export type BillMark =
  | 'LINK_PENDING'
  | 'ALLERGY_NOT_CHECKED'
  | 'REFUND_DUE'
  | 'ALLERGY_OVERRIDE'
  | 'H1_OVERRIDE'
  | 'SUBSTITUTED'
  | 'HAS_RETURNS';

export interface BillRow {
  id: string;
  billNo: string;
  savedAt: string;
  buyerLabel: string;
  itemCount: number;
  netPayablePaise: number;
  status: 'SAVED' | 'CANCELLED';
  marks: BillMark[];
}

export interface BillPage extends PageMeta {
  items: BillRow[];
}

export interface HoldInput extends CartInput {
  clientKey: string;
  replacesHeldBillId?: string | null;
}

export interface HeldBillRow {
  id: string;
  heldBy: PersonRef;
  heldAt: string;
  itemCount: number;
  ageMinutes: number;
  buyerLabel: string;
  hasScheduledLine?: boolean;
  discountState: 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'VOID';
}

export interface HeldBill extends HeldBillRow {
  status: 'OPEN' | 'RESUMED' | 'REPLACED' | 'DISCARDED';
  cart: CartInput;
  /** 0.3.6: the products the held cart names, so the resumed cart shows names before it is priced again. */
  products: ProductRef[];
  heldNetPaise: number;
  version: number;
}

// ---- work package J: discount approval and the substitute card (contract 0.3.6)

export type DiscountRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'VOID';

export interface DiscountRequestInput extends CartInput {
  clientKey: string;
  heldBillId?: string | null;
}

export interface DiscountRequest {
  /** false: level 0, the discount applies at once (no request). */
  required: boolean;
  requestId: string | null;
  status: DiscountRequestStatus | null;
  percent: string;
  discountPaise: number;
  ruleText: string | null;
  waitingFor: 'HOD' | 'OWNER' | null;
  escalated: boolean;
  needsAnotherApprover: boolean;
  selfApprovalOnly?: boolean;
  decidedBy: PersonRef | null;
  rejectReason: { reasonKey: string; label: string } | null;
  raisedAt: string | null;
}

export interface SubstituteOption {
  product: ProductRef;
  mrp: string;
  /** null for a blind caller on an audited product (rule 32). */
  availableQty: number | null;
  marginPerUnit: string | null;
  checks: { salt: true; strength: string; form: string };
}

export interface SubstituteOptions {
  product: ProductRef;
  outOfStock?: boolean;
  sentence: string;
  items: SubstituteOption[];
}

// ---- cancel a saved bill and record the refund of a cancelled one

export interface CancelBillInput {
  clientKey: string;
  version: number;
  reasonKey: string;
  note?: string | null;
  backOnShelf: boolean;
  secondSigner?: { membershipId: string; pin: string } | null;
}

export interface BillRefundInput {
  clientKey: string;
  version: number;
  refunds: { mode: PaymentMode; amountPaise: number }[];
}
