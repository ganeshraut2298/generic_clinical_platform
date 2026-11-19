import { CENTRAL_REF, PRODUCT_REF, WARD_REF } from '../inventory/inventory.fixtures';
import { PRINT_HEADER } from '../purchase/purchase.fixtures';
import {
  Dispatch,
  DispatchLine,
  Indent,
  IndentLine,
  IndentListRow,
  ReturnItem,
  Shortage,
} from '../pharmacy-indent.model';
import { MyStore, PharmacyContext, StoreAccess } from '../pharmacy.model';
import { NO_ACCESS_FLAGS, OWNER_CONTEXT } from '../pharmacy.fixtures';

/**
 * Contract-shaped sample data for the SAH-79 work package F unit tests (docs/api/pharmacy.yaml 0.3.4). The backend of
 * this package was built in parallel, so every shape follows the contract's examples. Every name and number is invented.
 */

const NURSE = { membershipId: '00000000-0000-4000-8000-000000000041', name: 'Test Ward Nurse' };
const HOD = { membershipId: '00000000-0000-4000-8000-000000000042', name: 'Test Ward HOD' };
const CS_USER = { membershipId: '00000000-0000-4000-8000-000000000043', name: 'Test Central Store User' };

export const SCHEDULE_X_PRODUCT = { ...PRODUCT_REF, productId: 'prod-x', code: 'G-000999', name: 'Sample Morphine 10 mg', schedule: 'X' as const };
export const SECOND_PRODUCT = { ...PRODUCT_REF, productId: 'prod-2', code: 'G-000102', name: 'Sample IV NS 500 ml', schedule: 'NONE' as const, issueUnit: 'bottle' };

export function indentLine(over: Partial<IndentLine> = {}): IndentLine {
  return {
    id: 'line-1',
    product: PRODUCT_REF,
    requestedQty: 20,
    approvedQty: null,
    hodChange: null,
    hodRemark: null,
    remainingQty: 20,
    dispatchedQty: 0,
    receivedQty: 0,
    rejectedQty: 0,
    shortQty: 0,
    balanceAction: null,
    closeReason: null,
    replacement: null,
    requestingStoreAvailable: 8,
    csStock: 110,
    stockImpact: { csSaleable: 110, inTransit: 0, requestingAvailable: 8, pendingReturn: 0, backOrder: 0, shortInvestigating: 0 },
    ...over,
  };
}

export function indent(over: Partial<Indent> = {}): Indent {
  return {
    id: 'ind-1',
    number: 'IND-0001',
    fromStore: WARD_REF,
    toStore: CENTRAL_REF,
    priority: 'ROUTINE',
    status: 'WAITING_APPROVAL',
    chips: { rejectedWaitingCs: 0, shortInvestigating: 0, backOrderOpen: false, scheduleXWaiting: 0 },
    raisedBy: NURSE,
    raisedAt: '2026-11-19T04:30:00Z',
    actingAs: 'Acting as Test Ward Nurse · mapped to Test Ward 3B store · can raise indents for Test Ward 3B store',
    lines: [indentLine(), indentLine({ id: 'line-2', product: SECOND_PRODUCT, requestedQty: 10, remainingQty: 10 })],
    approval: null,
    dispatches: [],
    cancelReason: null,
    history: [],
    version: 1,
    ...over,
  };
}

export function indentRow(over: Partial<IndentListRow> = {}): IndentListRow {
  return {
    id: 'ind-1',
    number: 'IND-0001',
    fromStore: WARD_REF,
    toStore: CENTRAL_REF,
    itemCount: 2,
    priority: 'ROUTINE',
    status: 'WAITING_APPROVAL',
    raisedBy: NURSE,
    raisedAt: '2026-11-19T04:30:00Z',
    ageMinutes: 95,
    ...over,
  };
}

export function dispatchLine(over: Partial<DispatchLine> = {}): DispatchLine {
  return {
    id: 'dline-1',
    product: PRODUCT_REF,
    batch: { batchId: 'batch-1', batchNo: 'TESTB001', expiryDate: '2028-03-31' },
    dispatched: 18,
    replacedByCs: false,
    changeReason: null,
    backOrdered: 0,
    scheduleX: false,
    accepted: null,
    rejected: null,
    rejectReason: null,
    received: false,
    ...over,
  };
}

export function dispatch(over: Partial<Dispatch> = {}): Dispatch {
  return {
    id: 'disp-1',
    reference: 'IND-0001 / 1',
    source: 'INDENT',
    sourceId: 'ind-1',
    sourceNo: 'IND-0001',
    fromStore: CENTRAL_REF,
    toStore: WARD_REF,
    status: 'IN_TRANSIT',
    dispatchedBy: CS_USER,
    dispatchedAt: '2026-11-19T06:00:00Z',
    actingAs: 'Acting as Test Ward Nurse · Store staff assigned to Test Ward 3B store',
    lines: [dispatchLine(), dispatchLine({ id: 'dline-2', product: SECOND_PRODUCT, dispatched: 10, batch: { batchId: 'batch-3', batchNo: 'TESTB003', expiryDate: '2027-12-31' } })],
    receipt: null,
    notes: {},
    print: PRINT_HEADER_ISSUE,
    version: 1,
    ...over,
  };
}

export const PRINT_HEADER_ISSUE = { ...PRINT_HEADER, templateDocType: 'ISSUE_SLIP' as const };

export function returnItem(over: Partial<ReturnItem> = {}): ReturnItem {
  return {
    id: 'ret-1',
    docNo: 'IND-0001 / 1',
    type: 'INDENT_REJECTION',
    holdingStore: WARD_REF,
    acceptingStore: CENTRAL_REF,
    product: PRODUCT_REF,
    batch: { batchId: 'batch-1', batchNo: 'TESTB001', expiryDate: '2028-03-31' },
    qty: 2,
    reason: { reasonKey: 'receipt-reject.damaged-in-transit', label: 'Damaged in transit' },
    raisedBy: NURSE,
    raisedAt: '2026-11-19T06:30:00Z',
    status: 'WAITING',
    defaultPutBackInto: 'QUARANTINE',
    expired: false,
    scheduleX: false,
    dispute: null,
    notes: [],
    resultNote: null,
    history: [],
    version: 1,
    ...over,
  };
}

export function shortage(over: Partial<Shortage> = {}): Shortage {
  return {
    id: 'short-1',
    dispatchReference: 'IND-0001 / 1',
    receivingStore: WARD_REF,
    settlingStore: CENTRAL_REF,
    product: PRODUCT_REF,
    batch: { batchId: 'batch-1', batchNo: 'TESTB001', expiryDate: '2028-03-31' },
    qty: 3,
    status: 'OPEN',
    approval: null,
    banner: 'Shortage, not a return.',
    resultNote: null,
    history: [],
    version: 1,
    ...over,
  };
}

function access(over: Partial<StoreAccess>): StoreAccess {
  return { ...NO_ACCESS_FLAGS, readStock: true, ...over };
}

function store(over: Partial<MyStore> & { storeId: string; name: string }): MyStore {
  return {
    code: 'W3B',
    type: 'WARD',
    storeRole: 'STORE_USER',
    effectiveStoreRole: 'STORE_USER',
    access: access({}),
    ...over,
  };
}

const NURSE_CALLER = {
  membershipId: NURSE.membershipId,
  name: NURSE.name,
  role: 'STORE_STAFF' as const,
  roleLabel: 'Store staff',
  setupAccess: 'NONE' as const,
};

/** A store user at a ward: can raise an indent and receive a dispatch; no purchase capability. */
export const WARD_CONTEXT: PharmacyContext = {
  ...OWNER_CONTEXT,
  caller: NURSE_CALLER,
  capabilities: ['INVENTORY'],
  stores: [store({ storeId: 'store-ward', name: 'Test Ward 3B store', access: access({ raiseIndent: true, receive: true }) })],
  workingStoreId: 'store-ward',
};

/** A pharmacist at a ward: can also receive Schedule X lines and sign. */
export const WARD_PHARMACIST_CONTEXT: PharmacyContext = {
  ...WARD_CONTEXT,
  caller: { ...NURSE_CALLER, role: 'PHARMACIST', roleLabel: 'Pharmacist' },
  stores: [
    store({
      storeId: 'store-ward',
      name: 'Test Ward 3B store',
      storeRole: 'STORE_USER',
      effectiveStoreRole: 'STORE_USER',
      access: access({ raiseIndent: true, receive: true, receiveScheduleX: true, handleScheduleX: true }),
    }),
  ],
};

/** A Central Store user: dispatches, accepts returns and settles shortages; cannot raise an indent. */
export const CENTRAL_CONTEXT: PharmacyContext = {
  ...OWNER_CONTEXT,
  caller: { ...NURSE_CALLER, name: CS_USER.name, membershipId: CS_USER.membershipId },
  capabilities: ['INVENTORY', 'PURCHASE'],
  stores: [
    store({
      storeId: 'store-central',
      name: 'Test Central Store',
      code: 'CEN',
      type: 'CENTRAL',
      storeRole: 'CENTRAL_STORE_USER',
      effectiveStoreRole: 'CENTRAL_STORE_USER',
      access: access({ purchase: true, acceptReturn: true, adjustStock: true }),
    }),
  ],
  workingStoreId: 'store-central',
};

/** The HOD of the ward: decides indents of the ward (the inbox). */
export const HOD_CONTEXT: PharmacyContext = {
  ...WARD_CONTEXT,
  caller: { ...NURSE_CALLER, name: HOD.name, membershipId: HOD.membershipId },
  capabilities: ['INVENTORY', 'APPROVALS_INBOX'],
  stores: [
    store({
      storeId: 'store-ward',
      name: 'Test Ward 3B store',
      storeRole: 'APPROVER',
      effectiveStoreRole: 'APPROVER',
      access: access({ raiseIndent: true, receive: true, approve: true }),
    }),
  ],
};
