import {
  ApprovalRule,
  ApprovalRules,
  DocumentSeries,
  PrintTemplate,
  ReasonLists,
  Vendor,
} from './pharmacy.model';

/** Contract-shaped sample data for the unit tests of work package C. Every name and number here is invented. */

export function vendor(over: Partial<Vendor> = {}): Vendor {
  return {
    id: 'vendor-1',
    name: 'Test Distributors Pvt. Ltd. (fake)',
    gstin: '27AAAAA0000A1Z5',
    licenceRequired: true,
    licenceNo: 'TEST-DL-0002',
    licenceValidTill: '2028-03-31',
    licenceState: 'VALID',
    contactName: 'Test Contact',
    contactPhone: '9000000001',
    creditDays: 30,
    returnDaysBeforeExpiry: 90,
    returnCreditPercent: '100',
    suppliesTo: [{ storeId: 'store-central', name: 'Test Central Store' }],
    productsCount: 12,
    status: 'ACTIVE',
    blocked: false,
    version: 1,
    ...over,
  };
}

export const ACTIVE_VENDOR = vendor();
export const BLOCKED_VENDOR = vendor({
  id: 'vendor-2',
  name: 'Test City Medicals (fake)',
  gstin: '27BBBBB1111B1Z6',
  licenceNo: 'TEST-DL-0003',
  licenceValidTill: '2026-10-31',
  licenceState: 'EXPIRED',
  contactName: null,
  contactPhone: null,
  returnDaysBeforeExpiry: null,
  returnCreditPercent: null,
  productsCount: 0,
  status: 'BLOCKED',
  blocked: true,
  version: 2,
});
export const CONSUMABLES_VENDOR = vendor({
  id: 'vendor-3',
  name: 'Test Surgical (fake)',
  gstin: null,
  licenceRequired: false,
  licenceNo: null,
  licenceValidTill: null,
  licenceState: 'NOT_REQUIRED',
  creditDays: 15,
  version: 1,
});

const ALWAYS = { kind: 'ALWAYS', amountPaise: null, percent: null, storeTypes: null } as const;

export function approvalRule(
  over: Partial<ApprovalRule> & Pick<ApprovalRule, 'transactionType'>,
): ApprovalRule {
  return { levels: [], escalateAfterHours: null, urgentEscalateAfterMinutes: null, ...over };
}

/** A saved set of rules: the nine types, one owner level on the purchase order and the discount. */
export function approvalRules(over: Partial<ApprovalRules> = {}): ApprovalRules {
  return {
    versionNo: 2,
    preset: 'CUSTOM',
    reviewNeeded: false,
    savedAt: '2026-11-17T07:00:00Z',
    savedBy: { membershipId: 'owner-1', name: 'Test Owner One' },
    highValueThresholdPaise: null,
    rules: [
      approvalRule({
        transactionType: 'PURCHASE_ORDER',
        levels: [
          { level: 1, approver: 'OWNER', condition: { ...ALWAYS, kind: 'ABOVE_AMOUNT', amountPaise: 5000000 } },
        ],
      }),
      approvalRule({ transactionType: 'GRN_RATE_RISE' }),
      approvalRule({ transactionType: 'VENDOR_RETURN' }),
      approvalRule({
        transactionType: 'INDENT',
        levels: [{ level: 1, approver: 'STORE_APPROVER', condition: { ...ALWAYS } }],
        escalateAfterHours: 2,
        urgentEscalateAfterMinutes: 30,
      }),
      approvalRule({ transactionType: 'STOCK_TRANSFER', escalateAfterHours: 24 }),
      approvalRule({ transactionType: 'WRITE_OFF' }),
      approvalRule({ transactionType: 'STOCK_ADJUSTMENT' }),
      approvalRule({
        transactionType: 'SALE_DISCOUNT',
        levels: [
          { level: 1, approver: 'OWNER', condition: { ...ALWAYS, kind: 'ABOVE_PERCENT', percent: '10' } },
        ],
      }),
      approvalRule({ transactionType: 'SALES_RETURN' }),
    ],
    presets: [
      { preset: 'LEAN', limitTypes: ['PURCHASE_ORDER'] },
      {
        preset: 'STANDARD',
        limitTypes: ['PURCHASE_ORDER', 'GRN_RATE_RISE', 'STOCK_ADJUSTMENT', 'SALE_DISCOUNT', 'SALES_RETURN'],
      },
      {
        preset: 'STRICT',
        limitTypes: [
          'PURCHASE_ORDER',
          'GRN_RATE_RISE',
          'VENDOR_RETURN',
          'INDENT',
          'STOCK_TRANSFER',
          'WRITE_OFF',
          'STOCK_ADJUSTMENT',
          'SALE_DISCOUNT',
          'SALES_RETURN',
        ],
      },
    ],
    fixedRules: [
      { key: 'NO_SELF_APPROVAL', text: 'Nobody approves their own request.' },
      { key: 'SCHEDULE_X_TWO_SIGNATURES', text: 'Schedule X needs a pharmacist and a second signature.' },
      { key: 'LEDGER_APPEND_ONLY', text: 'The stock ledger cannot be edited; corrections are reversing lines.' },
    ],
    storesWithoutApprover: [{ storeId: 'store-ot', name: 'Test OT Store' }],
    currentDelegates: [
      {
        storeId: 'store-opd',
        storeName: 'Test OPD Store',
        membershipId: 'doc-2',
        name: 'Test Doctor Two',
        to: '2026-11-30',
      },
    ],
    ...over,
  };
}

export function series(over: Partial<DocumentSeries> & Pick<DocumentSeries, 'docType'>): DocumentSeries {
  return {
    prefix: 'PO-',
    financialYearPart: false,
    sequenceLength: 4,
    restartsEachFinancialYear: false,
    example: 'PO-0001',
    renderedLength: 7,
    numbersIssued: false,
    saved: false,
    version: 0,
    ...over,
  };
}

/** The ten series of contract 0.2.0 (the five of SAH-78 and the five of SAH-79). */
export const TEN_SERIES: DocumentSeries[] = [
  series({ docType: 'PURCHASE_ORDER' }),
  series({ docType: 'GRN', prefix: 'GRN-', example: 'GRN-0001', renderedLength: 8 }),
  series({ docType: 'INDENT', prefix: 'IND-', example: 'IND-0001', renderedLength: 8 }),
  series({ docType: 'TRANSFER', prefix: 'TRF-', example: 'TRF-0001', renderedLength: 8 }),
  series({
    docType: 'BILL',
    prefix: 'SB/',
    financialYearPart: true,
    sequenceLength: 6,
    restartsEachFinancialYear: true,
    example: 'SB/26-27/000001',
    renderedLength: 15,
  }),
  series({ docType: 'DEBIT_NOTE', prefix: 'DN-', example: 'DN-0001', renderedLength: 7 }),
  series({ docType: 'STOCK_AUDIT', prefix: 'AUD-', example: 'AUD-0001', renderedLength: 8 }),
  series({ docType: 'STORE_RETURN', prefix: 'RET-', example: 'RET-0001', renderedLength: 8 }),
  series({ docType: 'DAMAGE_REPORT', prefix: 'DMG-', example: 'DMG-0001', renderedLength: 8 }),
  series({ docType: 'STOCK_ADJUSTMENT', prefix: 'ADJ-', example: 'ADJ-0001', renderedLength: 8 }),
];

function reasons(prefix: string, labels: string[]) {
  return labels.map((label, i) => ({
    reasonKey: `${prefix}.${i + 1}`,
    label,
    seeded: true,
    active: true,
  }));
}

/** Eleven lists of contract 0.2.0 (the four of SAH-78 and the seven of SAH-79). */
export const ELEVEN_LISTS: ReasonLists = {
  lists: [
    {
      listKey: 'store-return',
      label: 'Store return',
      reasons: reasons('store-return', ['Excess stock', 'Near expiry', 'Not needed (order stopped)']),
    },
    {
      listKey: 'sales-return',
      label: 'Sales return',
      reasons: reasons('sales-return', [
        'Doctor changed medicine',
        'Excess bought',
        'Wrong item given',
        'Adverse reaction',
      ]),
    },
    {
      listKey: 'damage',
      label: 'Damage',
      reasons: reasons('damage', ['Damaged / leaking', 'Expired', 'Cold-chain break']),
    },
    {
      listKey: 'stock-adjustment',
      label: 'Stock adjustment',
      reasons: reasons('stock-adjustment', [
        'Consumption not entered',
        'Breakage',
        'Unknown',
        'Count mismatch',
        'Theft or loss',
      ]),
    },
    {
      listKey: 'receipt-reject',
      label: 'Receipt rejection',
      reasons: [
        ...reasons('receipt-reject', ['Damaged in transit', 'Wrong item']),
        { reasonKey: 'receipt-reject.short', label: 'Short / not received', seeded: true, active: true, fixed: true },
      ],
    },
    { listKey: 'cs-change', label: 'Central Store change', reasons: reasons('cs-change', ['Out of stock']) },
    { listKey: 'approval-reject', label: 'Approval rejection', reasons: reasons('approval-reject', ['Not needed']) },
    { listKey: 'transfer', label: 'Transfer', reasons: reasons('transfer', ['Urgent shortage']) },
    {
      listKey: 'consumption-purpose',
      label: 'Consumption purpose',
      reasons: reasons('consumption-purpose', ['Ward general use']),
    },
    { listKey: 'dispute', label: 'Dispute', reasons: reasons('dispute', ['Other']) },
    { listKey: 'vendor-return', label: 'Vendor return', reasons: reasons('vendor-return', ['Other']) },
  ],
};

export function printTemplate(
  over: Partial<PrintTemplate> & Pick<PrintTemplate, 'docType'>,
): PrintTemplate {
  return {
    headerLines: [],
    defaultHeaderLines: ['Test Clinic Baner', 'Test Road, Pune', '9000000000'],
    footerNote: null,
    paperSize: 'A4',
    fixedFields: ['UNIT_NAME', 'DOCUMENT_NO', 'DATE'],
    printable: true,
    missing: [],
    saved: false,
    version: 0,
    ...over,
  };
}

export const THREE_TEMPLATES: PrintTemplate[] = [
  printTemplate({
    docType: 'INVOICE',
    fixedFields: [
      'UNIT_NAME',
      'DOCUMENT_NO',
      'DATE',
      'GSTIN',
      'DRUG_LICENCE_NO',
      'HSN',
      'BATCH',
      'EXPIRY',
      'MRP',
      'GST_AMOUNTS',
    ],
    printable: false,
    missing: ['GSTIN'],
  }),
  printTemplate({ docType: 'ISSUE_SLIP' }),
  printTemplate({ docType: 'DEBIT_NOTE' }),
];
