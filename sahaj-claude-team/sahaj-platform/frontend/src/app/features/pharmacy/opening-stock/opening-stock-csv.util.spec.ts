import { LineRowInput, TEMPLATE_HELP } from '../pharmacy.model';
import {
  BATCH_SIZE,
  MAX_BATCH_BYTES,
  MAX_ROWS,
  TEMPLATE_COLUMNS,
  inBatches,
  isTemplateHeader,
  parseCsv,
  readOpeningStockCsv,
} from './opening-stock-csv.util';

const HEADER = 'store code,product code,batch,expiry date,MRP,purchase rate,quantity';

describe('opening stock CSV', () => {
  it('reads quoted cells, doubled quotes, CR LF and a byte-order mark', () => {
    expect(parseCsv('﻿a,"b,1","c ""x"""\r\nd,e,f\n')).toEqual([
      ['a', 'b,1', 'c "x"'],
      ['d', 'e', 'f'],
    ]);
    expect(parseCsv('')).toEqual([]);
  });

  it('knows the template header, ignoring capital letters and spaces', () => {
    expect(isTemplateHeader([...TEMPLATE_COLUMNS])).toBe(true);
    expect(isTemplateHeader(HEADER.toUpperCase().split(','))).toBe(true);
    expect(
      isTemplateHeader([' store code ', 'product code', 'batch', 'expiry date', 'MRP', 'purchase rate', 'quantity', '']),
    ).toBe(true);
    expect(isTemplateHeader(['store code', 'product code'])).toBe(false);
    expect(
      isTemplateHeader(['code', 'product code', 'batch', 'expiry date', 'MRP', 'purchase rate', 'quantity']),
    ).toBe(false);
  });

  it('reads the rows as text, skips the example row and numbers the data rows from 1', () => {
    const read = readOpeningStockCsv(
      'stock.csv',
      `${HEADER}\n#EXAMPLE,G-000101,TESTB001,31-12-2027,45.50,30.25,100\nCEN, G-000101 ,B1,31-12-2027,45.50,30.25,100\nCEN,U-TCB-0001,B2,12/2027,10,8,5\n\n`,
    );
    expect(read.ok).toBe(true);
    if (read.ok) {
      expect(read.columns).toEqual([...TEMPLATE_COLUMNS]);
      expect(read.rows).toEqual([
        {
          rowNo: 1,
          storeCode: 'CEN',
          productCode: 'G-000101',
          batch: 'B1',
          expiryDate: '31-12-2027',
          mrp: '45.50',
          purchaseRate: '30.25',
          quantity: '100',
        },
        {
          rowNo: 2,
          storeCode: 'CEN',
          productCode: 'U-TCB-0001',
          batch: 'B2',
          expiryDate: '12/2027',
          mrp: '10',
          purchaseRate: '8',
          quantity: '5',
        },
      ]);
    }
  });

  it('refuses a file that is not CSV, empty, wrong-header or without data rows, with the one sentence', () => {
    const refused = (name: string, text: string) => {
      const read = readOpeningStockCsv(name, text);
      return read.ok ? null : read.message;
    };
    expect(refused('stock.xlsx', `${HEADER}\nCEN,G,B,1,1,1,1`)).toBe(TEMPLATE_HELP);
    expect(refused('stock.csv', '')).toBe(TEMPLATE_HELP);
    expect(refused('stock.csv', '\n\n')).toBe(TEMPLATE_HELP);
    expect(refused('stock.csv', 'a,b,c\n1,2,3')).toBe(TEMPLATE_HELP);
    expect(refused('stock.csv', `${HEADER}\n`)).toBe(TEMPLATE_HELP);
    expect(refused('stock.csv', `${HEADER}\n#EXAMPLE,G,B,1,1,1,1`)).toBe(TEMPLATE_HELP);
    expect(TEMPLATE_HELP).toBe('Use the Sahaj template. Save the file as CSV.');
  });

  it('accepts 5,000 rows and refuses 5,001', () => {
    const rows = (n: number) =>
      Array.from({ length: n }, (_, i) => `CEN,G-1,B${i},31-12-2027,10,8,1`).join('\n');
    expect(readOpeningStockCsv('s.CSV', `${HEADER}\n${rows(MAX_ROWS)}`).ok).toBe(true);
    expect(readOpeningStockCsv('s.csv', `${HEADER}\n${rows(MAX_ROWS + 1)}`).ok).toBe(false);
  });

  it('refuses a value longer than the transport limit with the row and the column, never the value', () => {
    const long = 'B'.repeat(41);
    const read = readOpeningStockCsv(
      's.csv',
      `${HEADER}\nCEN,G-1,B1,31-12-2027,10,8,1\nCEN,G-1,${long},31-12-2027,10,8,1`,
    );
    expect(read).toEqual({ ok: false, message: 'Row 2: the batch is too long. Fix the file and upload it again.' });
    expect(JSON.stringify(read)).not.toContain(long);
  });

  const emptyRow = (i: number): LineRowInput => ({
    rowNo: i + 1,
    storeCode: '',
    productCode: '',
    batch: '',
    expiryDate: '',
    mrp: '',
    purchaseRate: '',
    quantity: '',
  });
  const fullRow = (i: number): LineRowInput => ({
    rowNo: i + 1,
    storeCode: 'S'.repeat(40),
    productCode: 'P'.repeat(40),
    batch: 'B'.repeat(40),
    expiryDate: 'E'.repeat(20),
    mrp: '9'.repeat(20),
    purchaseRate: '8'.repeat(20),
    quantity: '7'.repeat(20),
  });
  const bytesOf = (batch: readonly LineRowInput[]) => new TextEncoder().encode(JSON.stringify({ rows: batch })).length;

  it('cuts the rows into requests of at most 200', () => {
    const rows = Array.from({ length: 450 }, (_, i) => emptyRow(i));
    expect(inBatches(rows).map((b) => b.length)).toEqual([200, 200, 50]);
    expect(inBatches([])).toEqual([]);
  });

  it('also cuts requests by encoded size: 200 full-length rows would be over the 64 KB cap', () => {
    const rows = Array.from({ length: 450 }, (_, i) => fullRow(i));
    expect(bytesOf(rows.slice(0, BATCH_SIZE))).toBeGreaterThan(MAX_BATCH_BYTES);
    const batches = inBatches(rows);
    expect(batches[0].length).toBeLessThan(BATCH_SIZE);
    expect(batches.length).toBeGreaterThanOrEqual(3);
    for (const batch of batches) {
      expect(batch.length).toBeLessThanOrEqual(BATCH_SIZE);
      expect(bytesOf(batch)).toBeLessThanOrEqual(MAX_BATCH_BYTES);
    }
    expect(batches.flat().map((r) => r.rowNo)).toEqual(rows.map((r) => r.rowNo));
  });

  it('counts the bytes of non-ASCII text, not its characters', () => {
    const wide = (i: number): LineRowInput => ({ ...fullRow(i), batch: 'ब'.repeat(40) });
    const batches = inBatches(Array.from({ length: 200 }, (_, i) => wide(i)));
    for (const batch of batches) {
      expect(bytesOf(batch)).toBeLessThanOrEqual(MAX_BATCH_BYTES);
    }
    expect(batches.flat().length).toBe(200);
  });

  it('does not keep blank rows, so a file of separators builds nothing', () => {
    expect(parseCsv('a,b\n,,,\n  , \n\n\nc,d\n')).toEqual([
      ['a', 'b'],
      ['c', 'd'],
    ]);
    expect(parseCsv(',\n'.repeat(100000))).toEqual([]);
  });

  it('stops reading as soon as more than the allowed rows are kept', () => {
    const rows = parseCsv('a\n'.repeat(1000), 10);
    expect(rows.length).toBe(11);
    const many = 'CEN,G-1,B,31-12-2027,10,8,1\n'.repeat(MAX_ROWS + 50);
    expect(readOpeningStockCsv('s.csv', HEADER + '\n' + many)).toEqual({ ok: false, message: TEMPLATE_HELP });
  });

  it('accepts a full file with the example row and refuses a file of only separators', () => {
    const body = Array.from({ length: MAX_ROWS }, (_, i) => 'CEN,G-1,B' + i + ',31-12-2027,10,8,1').join('\n');
    expect(readOpeningStockCsv('s.csv', HEADER + '\n#EXAMPLE,G,B,1,1,1,1\n' + body).ok).toBe(true);
    expect(readOpeningStockCsv('s.csv', HEADER + '\n' + ',,,,,,\n'.repeat(300000))).toEqual({
      ok: false,
      message: TEMPLATE_HELP,
    });
  });

  it('marks a row with a value beyond the 7 columns by its row number only', () => {
    const read = readOpeningStockCsv(
      's.csv',
      HEADER + '\nCEN,G-1,B1,31-12-2027,10,8,1\nCEN,G-2,B2,31-12-2027,10,8,1,secret-patient-name\n',
    );
    expect(read).toEqual({
      ok: false,
      message: 'Row 2: there are more than 7 columns. Fix the file and upload it again.',
    });
    expect(JSON.stringify(read)).not.toContain('secret');
    // Empty cells after the seventh are harmless.
    expect(readOpeningStockCsv('s.csv', HEADER + '\nCEN,G-1,B1,31-12-2027,10,8,1,,\n').ok).toBe(true);
  });

  it('returns the trimmed header cells found in the file as the columns', () => {
    const read = readOpeningStockCsv(
      's.csv',
      ' Store Code ,PRODUCT CODE,batch,expiry date,mrp,purchase rate,quantity,\nCEN,G-1,B1,31-12-2027,10,8,1\n',
    );
    expect(read.ok).toBe(true);
    if (read.ok) {
      expect(read.columns).toEqual(['Store Code', 'PRODUCT CODE', 'batch', 'expiry date', 'mrp', 'purchase rate', 'quantity']);
    }
  });
});
