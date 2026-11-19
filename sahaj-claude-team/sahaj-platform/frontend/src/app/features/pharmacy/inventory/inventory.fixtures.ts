import {
  ApprovalInboxPage,
  ApprovalInboxRow,
  ApprovalRequest,
  OpeningStockPostingPreview,
  ProductRef,
  ProductStock,
  ProductStockByStore,
  StockAdjustment,
  StockBatchRow,
  StockOverview,
  StoreRef,
} from '../pharmacy-inventory.model';
import { MyStore, PharmacyContext } from '../pharmacy.model';
import { NO_ACCESS_FLAGS, OWNER_CONTEXT } from '../pharmacy.fixtures';

/** Contract-shaped sample data for the SAH-79 work package D unit tests. Every name and number here is invented. */

export const CENTRAL_REF: StoreRef = { storeId: 'store-central', name: 'Test Central Store', type: 'CENTRAL' };
export const WARD_REF: StoreRef = { storeId: 'store-ward', name: 'Test Ward 3B store', type: 'WARD' };

export const PRODUCT_REF: ProductRef = {
  productId: 'prod-1',
  code: 'G-000101',
  name: 'Sample Ceftriaxone 1 g injection',
  strength: '1 g',
  form: 'Injection',
  schedule: 'H',
  purchaseUnit: 'Box',
  issueUnit: 'vial',
  conversion: 10,
};

export const SCHEDULE_X_REF: ProductRef = { ...PRODUCT_REF, productId: 'prod-x', code: 'G-000999', schedule: 'X' };

export function batchRow(over: Partial<StockBatchRow> = {}): StockBatchRow {
  return {
    product: PRODUCT_REF,
    batch: { batchId: 'batch-1', batchNo: 'TESTB001', expiryDate: '2028-03-31' },
    state: 'AVAILABLE',
    qty: 40,
    fefoRank: 1,
    mrp: '120.0000',
    purchaseRate: '70.5000',
    landed: '70.5000',
    marginPercent: '41.3',
    supplier: null,
    statusChip: ['SALEABLE'],
    daysToExpiry: 500,
    canVendorReturn: false,
    ...over,
  };
}

export function overview(over: Partial<StockOverview> = {}): StockOverview {
  return {
    store: CENTRAL_REF,
    skuCount: 3,
    batchCount: 5,
    stockValuePaise: 12500050,
    pills: {
      nearExpiry30: 0,
      nearExpiry60: 1,
      nearExpiry90: 2,
      expiredQuarantined: 1,
      lowStock: 1,
      scheduleProducts: 2,
    },
    empty: false,
    ...over,
  };
}

export function productStock(over: Partial<ProductStock> = {}): ProductStock {
  return {
    product: PRODUCT_REF,
    salt: 'Ceftriaxone',
    hsn: '3004',
    gstRate: '12',
    pack: 'Box of 10 → vial',
    rack: 'R-12',
    reorderLevel: 60,
    batches: [batchRow()],
    ...over,
  };
}

export function postingPreview(over: Partial<OpeningStockPostingPreview> = {}): OpeningStockPostingPreview {
  return {
    importId: 'imp-1',
    store: CENTRAL_REF,
    lineCount: 412,
    totalQuantity: 18450,
    totalValuePaise: 125000050,
    uploadedBy: { membershipId: '00000000-0000-4000-8000-000000000011', name: 'Test Pharmacist One' },
    approvedBy: { membershipId: '00000000-0000-4000-8000-000000000002', name: 'Test Owner Two' },
    expiredLines: [],
    mrpMismatchLines: [],
    canPost: true,
    postLockReason: null,
    ...over,
  };
}

export function inboxRow(over: Partial<ApprovalInboxRow> = {}): ApprovalInboxRow {
  return {
    requestId: 'req-1',
    transactionType: 'STOCK_ADJUSTMENT',
    chip: 'STOCK_ADJUSTMENT',
    subjectType: 'STOCK_ADJUSTMENT',
    subjectId: 'adj-1',
    subjectStoreId: 'store-central',
    what: 'ADJ-0001 · Sample Ceftriaxone 1 g · − ₹ 141.00',
    amountPaise: 14100,
    raisedBy: { membershipId: '00000000-0000-4000-8000-000000000011', name: 'Test Pharmacist One' },
    raisedStore: CENTRAL_REF,
    raisedAt: '2026-11-19T05:30:00Z',
    waitingMinutes: 35,
    ruleText: 'Adjustment above ₹ 100',
    level: 1,
    urgent: false,
    escalated: false,
    needsAnotherApprover: false,
    actions: ['REVIEW', 'REJECT'],
    ...over,
  };
}

export function inboxPage(items: ApprovalInboxRow[], over: Partial<ApprovalInboxPage> = {}): ApprovalInboxPage {
  return {
    items,
    counts: { all: items.length, indents: 0, purchase: 0, stock: items.length },
    signedInAs: 'Owner',
    total: items.length,
    page: 0,
    size: 20,
    ...over,
  };
}

export function approvalRequest(over: Partial<ApprovalRequest> = {}): ApprovalRequest {
  return {
    id: 'req-1',
    transactionType: 'STOCK_ADJUSTMENT',
    subjectType: 'STOCK_ADJUSTMENT',
    subjectId: 'adj-1',
    subjectNo: 'ADJ-0001',
    subjectStoreId: 'store-central',
    ruleStore: CENTRAL_REF,
    raisedBy: { membershipId: '00000000-0000-4000-8000-000000000011', name: 'Test Pharmacist One' },
    raisedAt: '2026-11-19T05:30:00Z',
    amountPaise: 14100,
    ruleVersionNo: 2,
    status: 'PENDING',
    currentLevel: 1,
    levels: [{ level: 1, approver: 'OWNER', ruleText: 'Adjustment above ₹ 100', applies: true }],
    decisions: [],
    escalated: false,
    needsAnotherApprover: false,
    canDecide: true,
    decideLockReason: null,
    version: 4,
    ...over,
  };
}

export function adjustment(over: Partial<StockAdjustment> = {}): StockAdjustment {
  return {
    id: 'adj-1',
    number: 'ADJ-0001',
    store: CENTRAL_REF,
    product: PRODUCT_REF,
    batch: { batchId: 'batch-1', batchNo: 'TESTB001', expiryDate: '2028-03-31' },
    bookQty: 40,
    physicalQty: 38,
    difference: -2,
    valuePaise: 14100,
    reason: { reasonKey: 'stock-adjustment.count-mismatch', label: 'Count mismatch' },
    raisedBy: { membershipId: '00000000-0000-4000-8000-000000000011', name: 'Test Pharmacist One' },
    raisedAt: '2026-11-19T05:30:00Z',
    secondSigner: null,
    status: 'WAITING_APPROVAL',
    approval: null,
    history: [
      {
        event: 'CREATED',
        at: '2026-11-19T05:30:00Z',
        by: { membershipId: '00000000-0000-4000-8000-000000000011', name: 'Test Pharmacist One' },
        actingAs: null,
        store: CENTRAL_REF,
        lineLabel: null,
        qtyBefore: 40,
        qtyAfter: 38,
        reason: { reasonKey: 'stock-adjustment.count-mismatch', label: 'Count mismatch' },
      },
    ],
    version: 1,
    ...over,
  };
}

export function byStore(over: Partial<ProductStockByStore> = {}): ProductStockByStore {
  return {
    product: PRODUCT_REF,
    rows: [
      { store: CENTRAL_REF, available: 110, inTransit: 0, pendingReturn: 0, quarantine: 2, reorderLevel: 60, status: 'OK' },
      { store: WARD_REF, available: 8, inTransit: 10, pendingReturn: 3, quarantine: 0, reorderLevel: 12, status: 'INDENT_IN_TRANSIT' },
    ],
    total: { store: null, available: 118, inTransit: 10, pendingReturn: 3, quarantine: 2, reorderLevel: null, status: null },
    empty: false,
    ...over,
  };
}

function inventoryStore(over: Partial<MyStore> & { storeId: string; name: string }): MyStore {
  return {
    code: 'CEN',
    type: 'CENTRAL',
    storeRole: 'CENTRAL_STORE_USER',
    effectiveStoreRole: 'CENTRAL_STORE_USER',
    access: { ...NO_ACCESS_FLAGS, readStock: true, adjustStock: true, postOpeningStock: true },
    ...over,
  };
}

/** An owner who reads stock, adjusts, posts opening stock and has the inbox. */
export const INVENTORY_OWNER_CONTEXT: PharmacyContext = {
  ...OWNER_CONTEXT,
  capabilities: [
    'SETUP_VIEW',
    'SETUP_EDIT',
    'OPENING_STOCK_UPLOAD',
    'OPENING_STOCK_APPROVE',
    'INVENTORY',
    'APPROVALS_INBOX',
    'STOCK_REPORTS',
  ],
  stores: [
    inventoryStore({ storeId: 'store-central', name: 'Test Central Store' }),
    inventoryStore({
      storeId: 'store-ward',
      name: 'Test Ward 3B store',
      type: 'WARD',
      code: 'W3B',
      storeRole: 'STORE_USER',
      effectiveStoreRole: 'STORE_USER',
    }),
  ],
  workingStoreId: 'store-central',
};

/** A store user who can only read stock: no adjustment, no inbox, no ledger. */
export const READ_ONLY_CONTEXT: PharmacyContext = {
  ...INVENTORY_OWNER_CONTEXT,
  caller: {
    membershipId: '00000000-0000-4000-8000-000000000031',
    name: 'Test Store Staff One',
    role: 'STORE_STAFF',
    roleLabel: 'Store staff',
    setupAccess: 'NONE',
  },
  capabilities: ['INVENTORY'],
  stores: [
    inventoryStore({
      storeId: 'store-ward',
      name: 'Test Ward 3B store',
      type: 'WARD',
      storeRole: 'STORE_USER',
      effectiveStoreRole: 'STORE_USER',
      access: { ...NO_ACCESS_FLAGS, readStock: true },
    }),
  ],
  workingStoreId: 'store-ward',
};
