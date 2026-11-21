import {
  OpeningStockImport,
  OpeningStockLine,
  PharmacyContext,
  ProductDetail,
  ProductRow,
  StaffCandidate,
  StaffMapping,
  Store,
  StoreAccess,
  UnitSettings,
} from './pharmacy.model';

/** Contract-shaped sample data for unit tests. Every name and number here is invented. */

export const NO_ACCESS_FLAGS: StoreAccess = {
  raiseIndent: false,
  approve: false,
  receive: false,
  receiveScheduleX: false,
  consumeReturn: false,
  uploadOpeningStock: false,
  startCount: false,
  handleScheduleX: false,
  dispatchTransfer: false,
  returnDamage: false,
  consume: false,
  count: false,
  reviewCount: false,
};

export const OWNER_CONTEXT: PharmacyContext = {
  unit: { name: 'Test Clinic Baner', code: 'TCB', integrationMode: 'INTEGRATED' },
  caller: {
    membershipId: '00000000-0000-4000-8000-000000000001',
    name: 'Test Owner',
    role: 'OWNER',
    roleLabel: 'Owner',
    setupAccess: 'EDIT',
  },
  capabilities: ['SETUP_VIEW', 'SETUP_EDIT', 'OPENING_STOCK_UPLOAD', 'OPENING_STOCK_APPROVE'],
  stores: [],
  workingStoreId: null,
  mustPickWorkingStore: false,
  fixedRules: { fefo: true, negativeStockBlocked: true },
};

export const PHARMACIST_CONTEXT: PharmacyContext = {
  unit: { name: 'Test Clinic Baner', code: 'TCB', integrationMode: 'INTEGRATED' },
  caller: {
    membershipId: '00000000-0000-4000-8000-000000000011',
    name: 'Test Pharmacist One',
    role: 'PHARMACIST',
    roleLabel: 'Pharmacist',
    setupAccess: 'VIEW',
  },
  capabilities: ['SETUP_VIEW', 'OPENING_STOCK_UPLOAD'],
  stores: [
    {
      storeId: 'store-pharm',
      name: 'Test Main Pharmacy',
      code: 'PHARM',
      type: 'PHARMACY',
      storeRole: 'STORE_USER',
      effectiveStoreRole: 'STORE_USER',
      access: { ...NO_ACCESS_FLAGS, raiseIndent: true, receive: true, receiveScheduleX: true },
    },
    {
      storeId: 'store-opd',
      name: 'Test OPD Store',
      code: 'OPD',
      type: 'SUB_STORE',
      storeRole: 'STORE_USER',
      effectiveStoreRole: 'STORE_USER',
      access: { ...NO_ACCESS_FLAGS, raiseIndent: true, receive: true, consumeReturn: true },
    },
  ],
  workingStoreId: null,
  mustPickWorkingStore: true,
  fixedRules: { fefo: true, negativeStockBlocked: true },
};

export const STORE_USER_CONTEXT: PharmacyContext = {
  ...PHARMACIST_CONTEXT,
  caller: {
    membershipId: '00000000-0000-4000-8000-000000000031',
    name: 'Test Store Staff One',
    role: 'STORE_STAFF',
    roleLabel: 'Store staff',
    setupAccess: 'NONE',
  },
  capabilities: [],
  stores: [PHARMACIST_CONTEXT.stores[1]],
  workingStoreId: 'store-opd',
  mustPickWorkingStore: false,
};

export const SETTINGS: UnitSettings = {
  unitName: 'Test Clinic Baner',
  unitCode: 'TCB',
  gstin: '27AAAAA0000A1Z5',
  drugLicenceNo: 'TEST-DL-0001',
  drugLicenceValidTill: '2028-03-31',
  drugLicenceExpired: false,
  integrationMode: 'INTEGRATED',
  fefo: true,
  negativeStockBlocked: true,
  ipdAvailable: true,
  saved: true,
  version: 3,
  updatedAt: '2026-11-17T06:05:00Z',
};

export function store(over: Partial<Store> = {}): Store {
  return {
    id: 'store-central',
    name: 'Test Central Store',
    code: 'CEN',
    type: 'CENTRAL',
    issuesFromStoreId: null,
    issuesFromName: null,
    saleAllowed: false,
    consumptionAllowed: false,
    hod: null,
    delegate: null,
    approverState: 'NONE',
    staffCount: 0,
    status: 'ACTIVE',
    hasStock: false,
    lockedFields: [],
    version: 1,
    ...over,
  };
}

export const CENTRAL = store();
export const OPD_STORE = store({
  id: 'store-opd',
  name: 'Test OPD Store',
  code: 'OPD',
  type: 'SUB_STORE',
  issuesFromStoreId: 'store-central',
  issuesFromName: 'Test Central Store',
  hod: {
    membershipId: 'm-doc',
    name: 'Test Doctor One',
    roleLabel: 'Doctor',
    canSignIn: true,
  },
  delegate: {
    membershipId: 'm-doc2',
    name: 'Test Doctor Two',
    roleLabel: 'Doctor',
    canSignIn: true,
    from: '2026-11-17',
    to: '2026-11-30',
    state: 'CURRENT',
  },
  approverState: 'SET',
  staffCount: 5,
  version: 4,
});
export const WARD_STORE = store({
  id: 'store-ward',
  name: 'Test Ward 3B Store',
  code: 'W3B',
  type: 'WARD',
  issuesFromStoreId: 'store-central',
  issuesFromName: 'Test Central Store',
  consumptionAllowed: true,
  staffCount: 8,
});

export function mapping(over: Partial<StaffMapping> = {}): StaffMapping {
  return {
    id: 'map-1',
    storeId: 'store-opd',
    membershipId: 'm-doc',
    name: 'Test Doctor One',
    role: 'DOCTOR',
    roleLabel: 'Doctor',
    isHod: true,
    storeRole: 'APPROVER',
    effectiveStoreRole: 'APPROVER',
    delegateFrom: null,
    delegateTo: null,
    delegateState: null,
    access: {
      raiseIndent: true,
      approve: true,
      receive: true,
      receiveScheduleX: false,
      consumeReturn: true,
      uploadOpeningStock: false,
      startCount: false,
      handleScheduleX: false,
      dispatchTransfer: false,
      returnDamage: false,
      consume: false,
      count: false,
      reviewCount: false,
    },
    otherStores: [],
    memberState: 'ACTIVE',
    needsReview: false,
    status: 'ACTIVE',
    version: 1,
    ...over,
  };
}

export const STORE_STAFF_MAPPING = mapping({
  id: 'map-3',
  membershipId: 'm-ss',
  name: 'Test Store Staff One',
  role: 'STORE_STAFF',
  roleLabel: 'Store staff',
  isHod: false,
  storeRole: 'STORE_USER',
  effectiveStoreRole: 'STORE_USER',
  access: {
    raiseIndent: true,
    approve: false,
    receive: true,
    receiveScheduleX: false,
    consumeReturn: true,
    uploadOpeningStock: false,
    startCount: false,
    handleScheduleX: false,
    dispatchTransfer: false,
    returnDamage: false,
    consume: false,
    count: false,
    reviewCount: false,
  },
});

export const PHARMACIST_MAPPING = mapping({
  id: 'map-4',
  membershipId: 'm-ph',
  name: 'Test Pharmacist One',
  role: 'PHARMACIST',
  roleLabel: 'Pharmacist',
  isHod: false,
  storeRole: 'STORE_USER',
  effectiveStoreRole: 'STORE_USER',
  access: {
    raiseIndent: true,
    approve: false,
    receive: true,
    receiveScheduleX: true,
    consumeReturn: true,
    uploadOpeningStock: true,
    startCount: false,
    handleScheduleX: false,
    dispatchTransfer: false,
    returnDamage: false,
    consume: false,
    count: false,
    reviewCount: false,
  },
  otherStores: [{ storeId: 'store-pharm', name: 'Test Main Pharmacy' }],
});

export const CANDIDATES: StaffCandidate[] = [
  {
    membershipId: 'm-doc',
    name: 'Test Doctor One',
    role: 'DOCTOR',
    roleLabel: 'Doctor',
    allowedStoreRoles: ['STORE_USER', 'APPROVER', 'DELEGATE'],
    alreadyMappedHere: true,
  },
  {
    membershipId: 'm-doc2',
    name: 'Test Doctor Two',
    role: 'DOCTOR',
    roleLabel: 'Doctor',
    allowedStoreRoles: ['STORE_USER', 'APPROVER', 'DELEGATE'],
    alreadyMappedHere: false,
  },
  {
    membershipId: 'm-ss2',
    name: 'Test Store Staff Two',
    role: 'STORE_STAFF',
    roleLabel: 'Store staff',
    allowedStoreRoles: ['STORE_USER', 'CENTRAL_STORE_USER'],
    alreadyMappedHere: false,
  },
];

// ---------------------------------------------------------------- products and opening stock (WP-B)

export function productRow(over: Partial<ProductRow> = {}): ProductRow {
  return {
    source: 'GLOBAL',
    catalogueProductId: 'cat-1',
    productId: null,
    code: 'G-000101',
    name: 'Sample Ceftriaxone 1 g injection',
    manufacturer: 'Sample Labs (fake)',
    madeInHouse: false,
    salt: 'Ceftriaxone',
    strength: '1 g',
    form: 'injection',
    purchaseUnit: 'Box of 10',
    issueUnit: 'vial',
    conversion: 10,
    hsn: '3004',
    gstRate: '12',
    schedule: 'H',
    ndps: false,
    inUnit: 'NOT_ADOPTED',
    sample: true,
    alias: null,
    ...over,
  };
}

export const GLOBAL_NOT_ADOPTED = productRow();
export const GLOBAL_ADOPTED = productRow({
  catalogueProductId: 'cat-2',
  productId: 'prod-2',
  code: 'G-000102',
  name: 'Sample Ceftriaxone 500 mg injection',
  strength: '500 mg',
  inUnit: 'ADOPTED',
});
export const OWN_PRODUCT = productRow({
  source: 'OWN',
  catalogueProductId: null,
  productId: 'prod-own',
  code: 'U-TCB-0001',
  name: 'Test dressing kit (OT)',
  manufacturer: null,
  madeInHouse: true,
  salt: 'Dressing kit',
  strength: '1 kit',
  form: 'kit',
  purchaseUnit: 'Kit',
  issueUnit: 'kit',
  conversion: 1,
  hsn: '3005',
  schedule: 'NONE',
  inUnit: 'ACTIVE',
  sample: false,
  alias: 'OT kit',
});

export function productDetail(over: Partial<ProductDetail> = {}): ProductDetail {
  return {
    ...OWN_PRODUCT,
    barcodes: [],
    localSettings: {
      storeSettings: [
        { storeId: 'store-central', storeName: 'Test Central Store', reorderLevel: 200, rack: 'A-3' },
        { storeId: 'store-opd', storeName: 'Test OPD Store', reorderLevel: null, rack: null },
      ],
      preferredVendor: null,
      alias: 'OT kit',
      barcode: null,
      returnable: true,
      active: true,
    },
    unitsLocked: false,
    hasStock: false,
    version: 2,
    ...over,
  };
}

export function openingImport(over: Partial<OpeningStockImport> = {}): OpeningStockImport {
  return {
    id: 'imp-1',
    storeId: 'store-central',
    storeName: 'Test Central Store',
    status: 'WAITING_APPROVAL',
    rowCount: 412,
    receivedRows: 412,
    lineCount: 412,
    totalQuantity: 18450,
    totalValuePaise: 125000000,
    errorCount: 0,
    warningCount: 2,
    uploadedBy: { membershipId: '00000000-0000-4000-8000-000000000011', name: 'Test Pharmacist One' },
    createdAt: '2026-11-17T05:30:00Z',
    submittedAt: '2026-11-17T05:41:00Z',
    decidedBy: null,
    decidedAt: null,
    rejectReasonKey: null,
    rejectNote: null,
    replacedByImportId: null,
    canApprove: true,
    approveLockReason: null,
    posted: false,
    version: 3,
    ...over,
  };
}

export function openingLine(over: Partial<OpeningStockLine> = {}): OpeningStockLine {
  return {
    rowNo: 1,
    storeCode: 'CEN',
    productCode: 'G-000101',
    productId: 'prod-1',
    productName: 'Sample Ceftriaxone 1 g injection',
    batch: 'TESTB001',
    expiryDate: '2027-12-31',
    mrp: '45.5000',
    purchaseRate: '30.2500',
    quantity: 100,
    valuePaise: 302500,
    issues: [],
    ...over,
  };
}
