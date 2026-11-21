import {
  DebitNote,
  GoodsReceipt,
  GoodsReceiptLine,
  PrintHeader,
  PurchaseOrder,
  PurchaseOrderPage,
  ReorderSuggestionRow,
  VendorRef,
  VendorReturnEligible,
} from '../pharmacy-buying.model';
import { CENTRAL_REF, INVENTORY_OWNER_CONTEXT, PRODUCT_REF } from '../inventory/inventory.fixtures';
import { PharmacyContext } from '../pharmacy.model';

/** Contract-shaped sample data for the SAH-79 work package E unit tests. Every name and number here is invented. */

export const BUYER_CONTEXT: PharmacyContext = {
  ...INVENTORY_OWNER_CONTEXT,
  capabilities: [...INVENTORY_OWNER_CONTEXT.capabilities, 'PURCHASE'],
  stores: INVENTORY_OWNER_CONTEXT.stores.map((s) => ({
    ...s,
    access: { ...s.access, handleScheduleX: s.type === 'CENTRAL', receiveScheduleX: s.type === 'CENTRAL' },
  })),
};

/** The same person with a pharmacist who may not handle Schedule X. */
export const BUYER_NO_X_CONTEXT: PharmacyContext = {
  ...BUYER_CONTEXT,
  stores: BUYER_CONTEXT.stores.map((s) => ({ ...s, access: { ...s.access, handleScheduleX: false, receiveScheduleX: false } })),
};

export const VENDOR_REF: VendorRef = {
  vendorId: 'vendor-1',
  name: 'Test Distributors Pvt. Ltd. (fake)',
  gstin: '27AAAAA0000A1Z5',
  blocked: false,
};

export const PRINT_HEADER: PrintHeader = {
  templateDocType: 'DEBIT_NOTE',
  headerLines: ['Test Clinic Baner', 'Test Road, Pune'],
  footerNote: 'Thank you',
  paperSize: 'A4',
  unitGstin: '27AAAAA0000A1Z5',
  drugLicenceNo: 'TEST-DL-1',
};

export const PO_PRODUCT = {
  ...PRODUCT_REF,
  purchaseUnit: 'box',
  issueUnit: 'vial',
  conversion: 10,
};

export function purchaseOrder(over: Partial<PurchaseOrder> = {}): PurchaseOrder {
  return {
    id: 'po-1',
    number: 'PO-26-27/0001',
    store: CENTRAL_REF,
    vendor: VENDOR_REF,
    status: 'APPROVED',
    deliveryExpected: '2026-11-25',
    creditDays: 30,
    sentOn: null,
    overdueDays: null,
    lines: [
      {
        id: 'po-line-1',
        product: PO_PRODUCT,
        qty: 10,
        rate: '70.0000',
        gstRate: '12',
        amountPaise: 70000,
        gstPaise: 8400,
        receivedQty: 0,
        closedShort: false,
      },
    ],
    subtotalPaise: 70000,
    gstPaise: 8400,
    totalPaise: 78400,
    ruleNote: 'POs above ₹ 50,000 need owner approval',
    approval: null,
    receipts: [],
    raisedBy: { membershipId: 'm-me', name: 'Test Owner' },
    createdAt: '2026-11-19T04:30:00Z',
    history: [
      {
        event: 'CREATED',
        at: '2026-11-19T04:30:00Z',
        by: { membershipId: 'm-me', name: 'Test Owner' },
        actingAs: null,
        store: CENTRAL_REF,
        lineLabel: null,
        qtyBefore: null,
        qtyAfter: null,
        reason: null,
      },
    ],
    print: PRINT_HEADER,
    version: 3,
    ...over,
  };
}

export function purchaseOrderPage(over: Partial<PurchaseOrderPage> = {}): PurchaseOrderPage {
  return {
    items: [
      { id: 'po-1', number: 'PO-26-27/0001', vendorName: VENDOR_REF.name, totalPaise: 78400, status: 'SENT', overdueDays: 2 },
      { id: 'po-2', number: null, vendorName: VENDOR_REF.name, totalPaise: 10000, status: 'DRAFT', overdueDays: null },
    ],
    tiles: { draft: 1, waitingApproval: 0, sent: 1, partlyReceived: 0, overdue: 1 },
    total: 2,
    page: 0,
    size: 20,
    ...over,
  };
}

export function reorderRow(over: Partial<ReorderSuggestionRow> = {}): ReorderSuggestionRow {
  return {
    product: PO_PRODUCT,
    csStock: 4,
    allStoresStock: 12,
    openPoQty: 0,
    openPoNo: null,
    reorderLevel: 20,
    suggestedQty: 3,
    vendor: VENDOR_REF,
    lastRate: '70.0000',
    ...over,
  };
}

export function grnLine(over: Partial<GoodsReceiptLine> = {}): GoodsReceiptLine {
  return {
    id: 'grn-line-1',
    product: PO_PRODUCT,
    batchNo: 'TESTB001',
    expiryDate: '2028-03-31',
    qty: 10,
    billedQty: 10,
    freeQty: 0,
    ptr: '74.3000',
    mrp: '120.0000',
    discountPercent: '0.00',
    gstRate: '12',
    taxablePaise: 74300,
    gstPaise: 8916,
    landed: '7.4300',
    marginPercent: '38.1',
    checks: ['OK'],
    ratePercent: null,
    rateBasis: null,
    shortQty: null,
    shortAction: null,
    claimRateDifference: false,
    scheduleX: false,
    secondSigner: null,
    ...over,
  };
}

export function goodsReceipt(over: Partial<GoodsReceipt> = {}): GoodsReceipt {
  return {
    id: 'grn-1',
    number: null,
    status: 'DRAFT',
    outcome: null,
    step: 'VERIFY',
    purchaseOrder: { poId: 'po-1', number: 'PO-26-27/0001' },
    vendor: VENDOR_REF,
    invoiceNo: 'TEST-INV-1',
    invoiceDate: '2026-11-18',
    creditDays: 30,
    invoiceTotalPaise: 83216,
    computedTotalPaise: 83216,
    totalDifferencePaise: 0,
    warnings: [],
    lines: [grnLine()],
    threeWayMatch: [],
    approval: null,
    payable: null,
    debitNoteDraft: null,
    history: [],
    version: 1,
    ...over,
  };
}

export function eligible(over: Partial<VendorReturnEligible> = {}): VendorReturnEligible {
  return {
    vendor: VENDOR_REF,
    policy: { returnDaysBeforeExpiry: 90, creditPercent: '100.00' },
    items: [
      {
        source: 'QUARANTINE_EXPIRED',
        product: PO_PRODUCT,
        batch: { batchId: 'batch-1', batchNo: 'TESTB001', expiryDate: '2026-10-31' },
        sourceState: 'QUARANTINE',
        goodsReceiptLineId: null,
        receiptNo: null,
        availableQty: 20,
        rate: '70.5000',
        defaultCreditPercent: '50.00',
        daysToExpiry: -19,
      },
      {
        source: 'GRN_SHORT',
        product: PO_PRODUCT,
        batch: null,
        sourceState: null,
        goodsReceiptLineId: 'grn-line-9',
        receiptNo: 'GRN-26-27/0009',
        availableQty: 4,
        rate: '70.0000',
        defaultCreditPercent: '100.00',
        daysToExpiry: null,
      },
    ],
    ...over,
  };
}

export function debitNote(over: Partial<DebitNote> = {}): DebitNote {
  return {
    id: 'dn-1',
    number: 'DN-26-27/0001',
    store: CENTRAL_REF,
    vendor: VENDOR_REF,
    status: 'CREATED',
    lines: [
      {
        id: 'dn-line-1',
        type: 'STOCK_RETURN',
        product: PO_PRODUCT,
        batch: { batchId: 'batch-1', batchNo: 'TESTB001', expiryDate: '2026-10-31' },
        sourceState: 'QUARANTINE',
        qty: 20,
        rate: '70.5000',
        creditPercent: '50.00',
        creditPercentChanged: true,
        creditPaise: 70500,
        gstRate: '12',
        gstReversalPaise: 8460,
        scheduleX: false,
      },
    ],
    creditTotalPaise: 70500,
    gstReversalPaise: 8460,
    approval: null,
    steps: [
      { step: 'CREATED', at: '2026-11-19T04:30:00Z', by: { membershipId: 'm-me', name: 'Test Owner' } },
      { step: 'PICKED', at: null, by: null },
      { step: 'HANDED_OVER', at: null, by: null },
      { step: 'CREDIT_NOTE_RECEIVED', at: null, by: null },
      { step: 'ADJUSTED_IN_PAYABLE', at: null, by: null },
    ],
    representativeName: null,
    creditNote: null,
    payableEffect: null,
    pickList: [
      { product: 'Sample Ceftriaxone 1 g injection', batch: { batchId: 'batch-1', batchNo: 'TESTB001', expiryDate: '2026-10-31' }, qty: 20, rack: 'R-4' },
    ],
    print: PRINT_HEADER,
    history: [],
    version: 2,
    ...over,
  };
}
