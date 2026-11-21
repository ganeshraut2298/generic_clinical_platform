import { PRODUCT_REF } from '../inventory/inventory.fixtures';
import {
  ActivityPage,
  CommandCentre,
  H1RegisterPage,
  Licences,
  OpdQueuePage,
  ReturnableBill,
  RoleReadout,
  SalesReturn,
  SalesReturnPage,
  ScheduleXRegister,
} from '../pharmacy-compliance.model';
import { BATCH, PERSON } from '../stockuse/stockuse.fixtures';

/** Contract-shaped sample data for the sales return and the registers. Every name and number here is invented. */

export function returnableBill(over: Partial<ReturnableBill> = {}): ReturnableBill {
  return {
    billId: 'bill-1',
    billNo: 'SB/26-27/018422',
    savedAt: '2026-11-14T05:00:00Z',
    buyerLabel: 'Test Customer One',
    netPayablePaise: 64200,
    window: { state: 'WITHIN', days: 30, lastDay: '2026-12-14' },
    store: { storeId: 'store-ward', name: 'Test Ward 3B store', type: 'WARD' },
    namedCustomer: true,
    limitPaise: 100000,
    refundModes: [
      { mode: 'UPI', maxPaise: 64200, original: true },
      { mode: 'CASH', maxPaise: null },
    ],
    lines: [
      {
        billLineId: 'bl-1',
        product: PRODUCT_REF,
        batch: BATCH,
        soldQty: 2,
        returnedQty: 0,
        returnableQty: 2,
        returnable: true,
        refundPerUnitPaise: 1535,
        schedule: 'NONE',
      },
      {
        billLineId: 'bl-2',
        product: { ...PRODUCT_REF, productId: 'prod-cold', name: 'Sample Insulin glargine' },
        batch: BATCH,
        soldQty: 1,
        returnedQty: 0,
        returnableQty: 1,
        returnable: false,
        refundPerUnitPaise: 50000,
        schedule: 'NONE',
      },
    ],
    ...over,
  };
}

export function salesReturn(over: Partial<SalesReturn> = {}): SalesReturn {
  return {
    id: 'sr-1',
    status: 'COMPLETED',
    creditNoteNo: 'CN/26-27/000001',
    bill: { billId: 'bill-1', billNo: 'SB/26-27/018422', savedAt: '2026-11-14T05:00:00Z', buyerLabel: 'Test Customer One' },
    store: { storeId: 'store-ward', name: 'Test Ward 3B store', type: 'WARD' },
    lines: [],
    reason: { reasonKey: 'sales-return.excess', label: 'Excess bought' },
    refunds: [{ mode: 'UPI', amountPaise: 3070 }],
    refundTotalPaise: 3070,
    startedBy: PERSON,
    startedAt: '2026-11-20T05:00:00Z',
    completedBy: PERSON,
    completedAt: '2026-11-20T05:00:00Z',
    approval: null,
    canAccept: false,
    canFinish: false,
    version: 1,
    ...over,
  };
}

export function h1Page(over: Partial<H1RegisterPage> = {}): H1RegisterPage {
  return {
    total: 1,
    page: 0,
    size: 25,
    entriesToday: 23,
    missingPrescriberToday: 0,
    items: [
      {
        rowId: 'h1-1',
        kind: 'ENTRY',
        at: '2026-11-17T05:00:00Z',
        billNo: 'SB/018422',
        patientName: 'Test Patient One',
        patientAge: 41,
        patientAddress: 'Test Road, Pune',
        prescriberName: 'Dr. Test Prescriber',
        prescriberRegNo: 'MMC-TEST-1',
        prescriptionDate: '2026-11-17',
        drug: PRODUCT_REF,
        prescribedDrug: null,
        batch: BATCH,
        qty: 3,
        rxCopy: { source: 'E_RX', rxId: 'rx-1' },
        pharmacist: PERSON,
        override: null,
      },
    ],
    ...over,
  };
}

export function scheduleXRegister(): ScheduleXRegister {
  return {
    from: '2026-11-01',
    to: '2026-11-30',
    toReview: 1,
    items: [
      { product: { ...PRODUCT_REF, productId: 'x-1', name: 'Sample Morphine 10 mg' }, ndps: true, opening: 40, received: 0, issued: 6, book: 34, physical: 34, physicalCountedOn: '2026-11-10', status: 'MATCH', difference: 0 },
      { product: { ...PRODUCT_REF, productId: 'x-2', name: 'Sample Tramadol 50 mg' }, ndps: false, opening: 120, received: 0, issued: 18, book: 102, physical: 101, physicalCountedOn: '2026-11-10', status: 'REVIEW', difference: -1 },
      { product: { ...PRODUCT_REF, productId: 'x-3', name: 'Sample Fentanyl 50 mcg' }, ndps: true, opening: 5, received: 0, issued: 0, book: 5, physical: null, physicalCountedOn: null, status: 'NOT_COUNTED', difference: null },
    ],
  };
}

export function licences(): Licences {
  return {
    warningDays: 60,
    expired: ['Schedule X licence (20F / 21F)'],
    rows: [
      { kind: 'RETAIL_DRUG_LICENCE', document: 'Retail drug licence (20B / 21B)', number: 'TEST-DL-1', validTill: '2026-12-28', status: { state: 'DUE', daysLeft: 41 } },
      { kind: 'SCHEDULE_X_LICENCE', document: 'Schedule X licence (20F / 21F)', number: 'TEST-X-1', validTill: '2026-10-01', status: { state: 'EXPIRED', daysLeft: null } },
      { kind: 'GST_REGISTRATION', document: 'GST registration', number: 'TEST-GSTIN', validTill: null, status: { state: 'NO_DATE', daysLeft: null } },
      {
        kind: 'PHARMACIST',
        document: 'Registered pharmacist',
        number: null,
        validTill: null,
        status: { state: 'VALID', daysLeft: 120 },
        pharmacist: { id: 'ph-1', member: PERSON, registrationNo: 'TEST-REG-1', validTill: '2027-03-31', version: 0 },
      },
    ],
  };
}

export function roleReadout(): RoleReadout {
  return {
    sentence: 'These are the rules in force. The owner changes them in Inventory setup.',
    columns: ['OWNER', 'PHARMACIST', 'CASHIER', 'STORE_KEEPER', 'WARD_NURSE'],
    rows: [
      {
        action: 'DISCOUNT_ABOVE_LIMIT',
        cells: [
          { column: 'OWNER', value: 'ALLOW' },
          { column: 'PHARMACIST', value: 'NEEDS_APPROVAL', ruleText: 'Needs approval above 10%' },
          { column: 'CASHIER', value: 'DENY' },
        ],
      },
      {
        action: 'CANCEL_SAVED_BILL',
        cells: [
          { column: 'OWNER', value: 'ALLOW' },
          { column: 'PHARMACIST', value: 'SAME_DAY_ONLY' },
          { column: 'CASHIER', value: 'DENY' },
        ],
      },
    ],
  };
}

export function activityPage(over: Partial<ActivityPage> = {}): ActivityPage {
  return {
    total: 1,
    page: 0,
    size: 25,
    items: [
      {
        at: '2026-11-17T05:32:00Z',
        by: PERSON,
        roleLabel: 'Pharmacist',
        type: 'ALLERGY_OVERRIDE',
        text: 'overrode allergy block on bill SB/26-27/018419 · reason: Prescriber confirmed',
        store: { storeId: 'store-ward', name: 'Test Ward 3B store', type: 'WARD' },
      },
    ],
    ...over,
  };
}

export function salesReturnPage(over: Partial<SalesReturnPage> = {}): SalesReturnPage {
  return {
    total: 1,
    page: 0,
    size: 25,
    items: [
      {
        id: 'sr-9',
        status: 'WAITING_PHARMACIST',
        creditNoteNo: null,
        billNo: 'SB/26-27/018422',
        at: '2026-11-20T05:00:00Z',
        buyerLabel: 'Test Customer One',
        refundTotalPaise: 3070,
        startedBy: PERSON,
      },
    ],
    ...over,
  };
}

export function waitingReturn(over: Partial<SalesReturn> = {}): SalesReturn {
  return salesReturn({
    id: 'sr-9',
    status: 'WAITING_PHARMACIST',
    creditNoteNo: null,
    completedBy: null,
    completedAt: null,
    canAccept: true,
    version: 3,
    lines: [
      {
        returnLineId: 'rl-1',
        billLineId: 'bl-1',
        product: PRODUCT_REF,
        batch: BATCH,
        qty: 1,
        condition: { reasonKey: 'return-condition.sealed', label: 'Sealed' },
        destination: 'PENDING',
        refundPaise: 2000,
        taxablePaise: 1786,
        taxPaise: 214,
        gstRate: '12',
      },
      {
        returnLineId: 'rl-2',
        billLineId: 'bl-2',
        product: PRODUCT_REF,
        batch: BATCH,
        qty: 1,
        condition: { reasonKey: 'return-condition.damaged', label: 'Damaged' },
        destination: 'PENDING',
        refundPaise: 1070,
        taxablePaise: 955,
        taxPaise: 115,
        gstRate: '12',
      },
    ],
    refunds: [{ mode: 'UPI', amountPaise: 3070 }],
    refundTotalPaise: 3070,
    ...over,
  });
}

export function commandCentre(over: Partial<CommandCentre> = {}): CommandCentre {
  return {
    asOf: '2026-11-21T05:35:00Z',
    store: { storeId: 'store-ward', name: 'Test Ward 3B store', type: 'WARD' },
    todaysSales: { state: 'OK', netPaise: 4826000, billCount: 142, averagePaise: 33986 },
    grossMargin: { state: 'OK', percent: '22.4' },
    nearExpiry90: { state: 'OK', valuePaise: 3690000, batchCount: 84, returnablePaise: null },
    reorder: { state: 'OK', productCount: 27 },
    rxWaiting: { state: 'OK', opdConnected: false, opdWaiting: null, ipdConnected: false, ipdWaiting: null, indentsWaiting: 3 },
    udhar: { state: 'OK', duePaise: 11240000, over60DaysPaise: 1820000 },
    expiryRisk: {
      state: 'OK',
      bars: [
        { band: 'EXPIRED', batchCount: 3, valuePaise: 214000 },
        { band: 'D0_30', batchCount: 19, valuePaise: 846000 },
        { band: 'D31_60', batchCount: 27, valuePaise: 1193000 },
        { band: 'D61_90', batchCount: 35, valuePaise: 1437000 },
      ],
    },
    livePrescriptions: { state: 'OK', items: [] },
    compliance: {
      state: 'OK',
      h1: { billsToday: 23, overridesToReview: 0 },
      scheduleX: { toReview: 0, balanced: true },
      drugLicence: { state: 'DUE', daysLeft: 41 },
    },
    ...over,
  };
}

export function opdQueue(over: Partial<OpdQueuePage> = {}): OpdQueuePage {
  return {
    connected: false,
    refreshAfterSeconds: 60,
    counts: { new: 0, picking: 0, ready: 0, needsAction: 0 },
    items: [],
    total: 0,
    page: 0,
    size: 25,
    ...over,
  };
}
