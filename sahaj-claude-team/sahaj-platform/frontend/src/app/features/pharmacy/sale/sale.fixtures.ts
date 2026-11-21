import { PRODUCT_REF } from '../inventory/inventory.fixtures';
import {
  Bill,
  BillPage,
  Customer,
  DiscountRequest,
  HeldBill,
  QuoteLine,
  SaleProductRow,
  SaleQuote,
  SubstituteOptions,
} from '../pharmacy-sale.model';
import { PharmacyContext, StoreAccess } from '../pharmacy.model';
import { storeContext, BATCH, PERSON } from '../stockuse/stockuse.fixtures';

/**
 * Contract-shaped sample data for SAH-80 work package I (pharmacy-sales.yaml 0.3.1). The backend of this package is
 * built after the screens, so these fixtures stand in for it. Every name and number here is invented.
 */

export function saleContext(access: Partial<StoreAccess> = {}, capabilities: string[] = ['INVENTORY', 'PHARMACY', 'POS']): PharmacyContext {
  return storeContext({ sell: true, ...access }, capabilities);
}

export function saleHit(over: Partial<SaleProductRow> = {}): SaleProductRow {
  return {
    product: PRODUCT_REF,
    salt: 'Ceftriaxone',
    packText: 'Box of 10 · vial',
    availableQty: 40,
    outOfStock: false,
    matchedOnSalt: false,
    mrpFrom: '120.0000',
    ...over,
  };
}

export function quoteLine(over: Partial<QuoteLine> = {}): QuoteLine {
  return {
    lineNo: 1,
    inputIndex: 0,
    product: PRODUCT_REF,
    batch: BATCH,
    qty: 2,
    mrp: '120.0000',
    gstRate: '12',
    amountPaise: 24000,
    discountSharePaise: 0,
    netPaise: 24000,
    taxablePaise: 21429,
    taxPaise: 2571,
    expiresInDays: null,
    expiredBatchBlocked: null,
    schedule: 'H',
    needsPharmacist: false,
    needsSecondSigner: false,
    allergyBlock: null,
    h1PrescriberMissing: false,
    substitution: null,
    rx: 'NONE',
    ...over,
  };
}

export function saleQuote(over: Partial<SaleQuote> = {}): SaleQuote {
  return {
    quoteVersion: 'q-1',
    lines: [quoteLine()],
    summary: {
      itemCount: 1,
      unitCount: 2,
      mrpTotalPaise: 24000,
      discountPercent: null,
      discountPaise: 0,
      taxByRate: [{ gstRate: '12', taxablePaise: 21429, cgstPaise: 1286, sgstPaise: 1285, totalPaise: 24000 }],
      cgstPaise: 1286,
      sgstPaise: 1285,
      roundOffPaise: 0,
      netPayablePaise: 24000,
    },
    discount: { approvalNeeded: false, limitPercent: null, limitNotSet: false, lean: true, approval: null },
    allergy: { status: 'NOT_APPLICABLE', text: null },
    credit: null,
    needsPharmacist: false,
    needsSecondSigner: false,
    needsPrescription: false,
    needsH1PatientDetails: false,
    canSave: true,
    blockers: [],
    ...over,
  };
}

export function customer(over: Partial<Customer> = {}): Customer {
  return {
    id: 'cust-1',
    kind: 'WALK_IN',
    patientId: null,
    name: 'Test Customer One',
    phone: '98XXXXX214',
    phoneMasked: true,
    allergyNote: null,
    status: 'ACTIVE',
    credit: { customerId: 'cust-1', creditLimitPaise: 500000, duePaise: 0, creditNoteBalancePaise: 0 },
    version: 0,
    ...over,
  };
}

export function bill(over: Partial<Bill> = {}): Bill {
  return {
    id: 'bill-1',
    billNo: 'SB/26-27/018422',
    store: { storeId: 'store-ward', name: 'Test Ward 3B store', type: 'WARD' },
    status: 'SAVED',
    savedAt: '2026-11-19T05:30:00Z',
    savedBy: { ...PERSON, roleLabel: 'Pharmacist' },
    buyer: { kind: 'NONE', label: 'Walk-in' },
    lines: [
      {
        lineId: 'bl-1',
        lineNo: 1,
        product: PRODUCT_REF,
        hsn: '3004',
        batch: BATCH,
        qty: 2,
        mrp: '120.0000',
        gstRate: '12',
        amountPaise: 24000,
        discountSharePaise: 0,
        netPaise: 24000,
        taxablePaise: 21429,
        taxPaise: 2571,
        returnedQty: 0,
        schedule: 'H',
      },
    ],
    summary: saleQuote().summary,
    payments: [{ mode: 'CASH', amountPaise: 24000 }],
    udharPaise: 0,
    linkPending: false,
    allergyNotChecked: false,
    pharmacist: { name: 'Test Pharmacist One', registrationNo: 'TEST-REG-1' },
    refundDuePaise: 0,
    hasReturns: false,
    canCancel: true,
    print: { unitName: 'Test Clinic Baner', drugLicenceNo: 'TEST-DL-1', gstin: 'TEST-GSTIN', footerNote: null },
    history: [],
    version: 0,
    ...over,
  };
}

export function billPage(over: Partial<BillPage> = {}): BillPage {
  return {
    items: [
      {
        id: 'bill-1',
        billNo: 'SB/26-27/018422',
        savedAt: '2026-11-19T05:30:00Z',
        buyerLabel: 'Walk-in',
        itemCount: 1,
        netPayablePaise: 24000,
        status: 'SAVED',
        marks: ['ALLERGY_NOT_CHECKED'],
      },
    ],
    total: 1,
    page: 0,
    size: 25,
    ...over,
  };
}

export function heldBill(over: Partial<HeldBill> = {}): HeldBill {
  return {
    id: 'held-1',
    heldBy: PERSON,
    heldAt: '2026-11-19T05:00:00Z',
    itemCount: 1,
    ageMinutes: 12,
    buyerLabel: 'Walk-in',
    discountState: 'NONE',
    status: 'OPEN',
    cart: { buyer: { kind: 'NONE' }, lines: [{ productId: 'prod-1', qty: 2 }], discountPercent: null },
    products: [PRODUCT_REF],
    heldNetPaise: 24000,
    version: 1,
    ...over,
  };
}

export function discountRequest(over: Partial<DiscountRequest> = {}): DiscountRequest {
  return {
    required: true,
    requestId: 'dr-1',
    status: 'PENDING',
    percent: '15',
    discountPaise: 3600,
    ruleText: 'Discount above 10%',
    waitingFor: 'OWNER',
    escalated: false,
    needsAnotherApprover: false,
    decidedBy: null,
    rejectReason: null,
    raisedAt: '2026-11-21T05:30:00Z',
    ...over,
  };
}

export function substituteOptions(over: Partial<SubstituteOptions> = {}): SubstituteOptions {
  return {
    product: PRODUCT_REF,
    outOfStock: true,
    sentence: 'Sample Ceftriaxone is out of stock. Only exact equivalents are offered.',
    items: [
      {
        product: { ...PRODUCT_REF, productId: 'prod-sub', name: 'Sample Ceftriaxone generic' },
        mrp: '95.0000',
        availableQty: 12,
        marginPerUnit: '20.0000',
        checks: { salt: true, strength: '1 g', form: 'injection' },
      },
    ],
    ...over,
  };
}
