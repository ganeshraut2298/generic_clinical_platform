import { THREE_TEMPLATES, printTemplate } from '../pharmacy-c.fixtures';
import {
  docTypeLabel,
  financialYearPart,
  missingNotes,
  previewModel,
  printHtml,
  safePaperSize,
  renderedLength,
  seriesExample,
  seriesInput,
  validateSeries,
} from './documents.util';

const BILL = { prefix: 'SB/', financialYearPart: true, sequenceLength: 6, restartsEachFinancialYear: true };

describe('documents util', () => {
  it('finds the financial year from April to March, in IST', () => {
    expect(financialYearPart('2026-10-24')).toBe('26-27');
    expect(financialYearPart('2027-03-31')).toBe('26-27');
    expect(financialYearPart('2027-04-01')).toBe('27-28');
  });

  it('renders the live example of a series', () => {
    expect(seriesExample(BILL, '2026-10-24')).toBe('SB/26-27/000001');
    expect(
      seriesExample({ prefix: 'PO-', financialYearPart: false, sequenceLength: 4, restartsEachFinancialYear: false }),
    ).toBe('PO-0001');
    expect(renderedLength(BILL)).toBe(15);
  });

  it('keeps the contract rules for a series, with its sentences', () => {
    expect(validateSeries(BILL)).toEqual({ prefix: null, sequenceLength: null, restart: null, tooLong: null });
    expect(validateSeries({ ...BILL, prefix: '' }).prefix).toBe('Use 1 to 6 letters, digits, - or /');
    expect(validateSeries({ ...BILL, prefix: 'ABCDEFG' }).prefix).toBe('Use 1 to 6 letters, digits, - or /');
    expect(validateSeries({ ...BILL, prefix: 'A B' }).prefix).toBe('Use 1 to 6 letters, digits, - or /');
    expect(validateSeries({ ...BILL, sequenceLength: 3 }).sequenceLength).toBe('Choose 4 to 8 digits');
    expect(validateSeries({ ...BILL, prefix: 'ABCDEF', sequenceLength: 8 }).tooLong).toBe(
      'The number must be 16 characters or fewer',
    );
    expect(validateSeries({ ...BILL, financialYearPart: false }).restart).toBe(
      'Show the financial year when numbers restart every 1 April',
    );
  });

  it('builds the PUT body from the version read and the draft', () => {
    expect(seriesInput(3, BILL)).toEqual({ version: 3, ...BILL });
  });

  it('names every type, and shows an unknown future type in plain words', () => {
    expect(docTypeLabel('PURCHASE_ORDER')).toBe('Purchase order');
    expect(docTypeLabel('DAMAGE_REPORT')).toBe('Damage report');
    expect(docTypeLabel('CREDIT_NOTE')).toBe('Credit note');
  });

  it('tells the owner to set the missing legal fields and that invoices cannot be printed', () => {
    expect(missingNotes(THREE_TEMPLATES[0])).toEqual([
      'Set GSTIN in Unit settings',
      'Invoices cannot be printed until it is set.',
    ]);
    expect(missingNotes(THREE_TEMPLATES[1])).toEqual([]);
    expect(
      missingNotes(printTemplate({ docType: 'INVOICE', printable: false, missing: ['GSTIN', 'DRUG_LICENCE_NO'] })),
    ).toHaveLength(3);
  });

  it('builds a preview from sample values, using the clinic header when none is typed', () => {
    const model = previewModel(
      THREE_TEMPLATES[0],
      { headerLines: ['', ' ', ''], footerNote: ' Thank you ', paperSize: 'A5' },
      'Test Clinic Baner',
      '24 Oct 2026',
    );
    expect(model.title).toBe('Invoice · Sample');
    expect(model.header).toEqual(['Test Clinic Baner', 'Test Road, Pune', '9000000000']);
    expect(model.footer).toBe('Thank you');
    expect(model.fields.find((f) => f.label === 'GSTIN')?.value).toBe('Sample');
    expect(model.fields.find((f) => f.label === 'Date')?.value).toBe('24 Oct 2026');
    expect(model.fields.find((f) => f.label === 'Unit name')?.value).toBe('Test Clinic Baner');
  });

  it('escapes every text in the print view', () => {
    const html = printHtml({
      title: 'Invoice · Sample',
      header: ['<script>alert(1)</script>'],
      fields: [{ label: 'GSTIN', value: 'a & b' }],
      footer: '"x"',
      paperSize: 'A5',
    });
    expect(html).not.toContain('<script>');
    expect(html).toContain('&lt;script&gt;');
    expect(html).toContain('a &amp; b');
    expect(html).toContain('size:A5');
  });

  it('adds a content security policy that allows no script, image or network', () => {
    const html = printHtml({ title: 'T', header: [], fields: [], footer: '', paperSize: 'A4' });
    expect(html).toContain('http-equiv="Content-Security-Policy"');
    expect(html).toContain("default-src 'none'; style-src 'unsafe-inline'");
  });

  it('maps the paper size through a whitelist', () => {
    expect(safePaperSize('A4')).toBe('A4');
    expect(safePaperSize('A5')).toBe('A5');
    expect(safePaperSize('A4}</style><script>')).toBe('A4');
    expect(safePaperSize(undefined)).toBe('A4');
    const html = printHtml({
      title: 'T',
      header: [],
      fields: [],
      footer: '',
      paperSize: 'A3};x{' as unknown as 'A4',
    });
    expect(html).toContain('size:A4');
    expect(html).not.toContain('A3');
  });
});
