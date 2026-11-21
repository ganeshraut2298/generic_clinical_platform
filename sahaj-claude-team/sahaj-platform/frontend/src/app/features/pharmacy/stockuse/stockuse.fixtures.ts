import { CENTRAL_REF, PRODUCT_REF, WARD_REF, batchRow, productStock } from '../inventory/inventory.fixtures';
import { BatchRef, ProductRef } from '../pharmacy-inventory.model';
import {
  CountSheetLine,
  CountSheetPage,
  ReportPage,
  StockAudit,
  VarianceReview,
} from '../pharmacy-stockcount.model';
import { Consumption, DamageReport, StoreReturn, Transfer, TransferListRow } from '../pharmacy-stockuse.model';
import { MyStore, PharmacyContext, StoreAccess } from '../pharmacy.model';
import { INVENTORY_OWNER_CONTEXT } from '../inventory/inventory.fixtures';

/**
 * Contract-shaped sample data for the SAH-79 work packages G and H (pharmacy.yaml 0.3.5). The backend of these
 * packages is built after the screens, so these fixtures stand in for it. Every name and number here is invented.
 */

export const BATCH: BatchRef = { batchId: 'batch-1', batchNo: 'TESTB001', expiryDate: '2028-03-31' };
export const PERSON = { membershipId: '00000000-0000-4000-8000-000000000011', name: 'Test Pharmacist One' };
export const NURSE = { membershipId: '00000000-0000-4000-8000-000000000021', name: 'Test Ward Nurse' };

/** A ward user with the Work package G and H capabilities of the working store. */
export function storeContext(access: Partial<StoreAccess> = {}, capabilities: string[] = ['INVENTORY']): PharmacyContext {
  const base = INVENTORY_OWNER_CONTEXT.stores[1];
  const store: MyStore = {
    ...base,
    storeId: 'store-ward',
    name: 'Test Ward 3B store',
    type: 'WARD',
    access: {
      ...base.access,
      readStock: true,
      raiseIndent: true,
      receive: true,
      returnDamage: true,
      consume: true,
      count: true,
      startCount: true,
      reviewCount: false,
      dispatchTransfer: false,
      ...access,
    },
  };
  return {
    ...INVENTORY_OWNER_CONTEXT,
    caller: { ...INVENTORY_OWNER_CONTEXT.caller, name: 'Test Ward Nurse', role: 'NURSE', roleLabel: 'Nurse', setupAccess: 'NONE' },
    capabilities,
    stores: [store],
    workingStoreId: 'store-ward',
  };
}

export function countLine(over: Partial<CountSheetLine> = {}): CountSheetLine {
  return {
    auditLineId: 'al-1',
    product: PRODUCT_REF,
    batch: BATCH,
    rack: 'W3-A1',
    barcode: '8900000000011',
    counted: null,
    notCounted: false,
    notCountedReason: null,
    recount: false,
    ...over,
  };
}

export function countSheet(over: Partial<CountSheetPage> = {}): CountSheetPage {
  return {
    auditNo: 'AUD-0093',
    storeName: 'Test Ward 3B store',
    status: 'COUNTING',
    counted: 0,
    itemsInCount: 2,
    version: 3,
    items: [countLine(), countLine({ auditLineId: 'al-2', batch: { ...BATCH, batchNo: 'TESTB002' }, barcode: '8900000000028' })],
    total: 2,
    page: 0,
    size: 200,
    ...over,
  };
}

export function stockAudit(over: Partial<StockAudit> = {}): StockAudit {
  return {
    id: 'aud-1',
    number: 'AUD-0093',
    store: WARD_REF,
    status: 'COUNTING',
    startedBy: PERSON,
    startedAt: '2026-11-19T04:30:00Z',
    submittedBy: null,
    itemsInCount: 40,
    counted: 32,
    withVariance: null,
    varianceValuePaise: null,
    approval: null,
    history: [],
    version: 3,
    ...over,
  };
}

export function varianceReview(over: Partial<VarianceReview> = {}): VarianceReview {
  return {
    auditId: 'aud-1',
    auditNo: 'AUD-0093',
    store: WARD_REF,
    status: 'WAITING_REVIEW',
    varianceValuePaise: -41200,
    lines: [
      {
        auditLineId: 'al-1',
        product: PRODUCT_REF,
        batch: BATCH,
        system: 8,
        counted: 7,
        diff: -1,
        valuePaise: -7050,
        reason: null,
        counter: NURSE,
        stockMoved: false,
        suggestConsumption: false,
      },
    ],
    version: 5,
    ...over,
  };
}

export function transferRow(over: Partial<TransferListRow> = {}): TransferListRow {
  return {
    id: 'trf-1',
    number: 'TRF-0440',
    fromStore: CENTRAL_REF,
    toStore: WARD_REF,
    itemCount: 2,
    status: 'IN_TRANSIT',
    ageMinutes: 40,
    notReceivedHours: null,
    ...over,
  };
}

export function transfer(over: Partial<Transfer> = {}): Transfer {
  return {
    id: 'trf-1',
    number: 'TRF-0440',
    fromStore: CENTRAL_REF,
    toStore: WARD_REF,
    reason: { reasonKey: 'transfer.urgent-shortage', label: 'Urgent shortage' },
    status: 'APPROVED',
    step: 'APPROVED',
    notReceivedHours: null,
    lines: [
      {
        id: 'tl-1',
        product: PRODUCT_REF,
        quantity: 15,
        previewBatches: [{ batch: BATCH, quantity: 15 }],
        availableAtSender: 25,
        valuePaise: 63000,
      },
    ],
    totalValuePaise: 63000,
    approval: null,
    dispatch: null,
    raisedBy: PERSON,
    raisedAt: '2026-11-19T04:30:00Z',
    history: [],
    version: 2,
    ...over,
  };
}

export function storeReturn(over: Partial<StoreReturn> = {}): StoreReturn {
  return {
    id: 'ret-1',
    number: 'RET-0311',
    store: WARD_REF,
    product: PRODUCT_REF,
    batch: BATCH,
    quantity: 3,
    reason: { reasonKey: 'store-return.not-needed', label: 'Not needed' },
    item: null as never,
    history: [],
    ...over,
  };
}

export function damageReport(over: Partial<DamageReport> = {}): DamageReport {
  return {
    id: 'dmg-1',
    number: 'DMG-0087',
    store: WARD_REF,
    product: PRODUCT_REF,
    batch: BATCH,
    quantity: 2,
    reason: { reasonKey: 'damage.leaking', label: 'Damaged / leaking' },
    afterApproval: 'WRITE_OFF',
    status: 'WAITING_APPROVAL',
    outcome: null,
    approval: null,
    history: [],
    version: 0,
    ...over,
  };
}

export function consumption(over: Partial<Consumption> = {}): Consumption {
  return {
    id: 'con-1',
    store: WARD_REF,
    type: 'GENERAL',
    purpose: { reasonKey: 'consumption-purpose.ward-general-use', label: 'Ward general use' },
    ipNumber: null,
    orderedBy: null,
    chargeStatus: 'NOT_APPLICABLE',
    recordedBy: NURSE,
    recordedAt: '2026-11-19T04:40:00Z',
    costBookedTo: 'Test Ward 3B store',
    lines: [{ product: PRODUCT_REF, batch: BATCH, quantity: 4 }],
    secondSigner: null,
    ...over,
  };
}

export function reportPage(over: Partial<ReportPage> = {}): ReportPage {
  return {
    reportKey: 'store-stock',
    title: 'Store-wise stock',
    columns: [
      { key: 'store', label: 'Store', kind: 'TEXT' },
      { key: 'product', label: 'Product', kind: 'TEXT' },
      { key: 'available', label: 'Available', kind: 'INTEGER' },
      { key: 'value', label: 'Value', kind: 'PAISE' },
    ],
    rows: [
      ['Test Ward 3B store', 'Sample Ceftriaxone 1 g injection', 8, 56400],
      ['Test Central Store', 'Sample Ceftriaxone 1 g injection', null, null],
    ],
    totals: null,
    total: 2,
    page: 0,
    size: 50,
    ...over,
  };
}

export const SECOND_PRODUCT_REF: ProductRef = { ...PRODUCT_REF, productId: 'prod-2', name: 'Sample IV NS 500 ml', schedule: 'NONE' };
export { batchRow, productStock };
