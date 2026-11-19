import { LineRowInput, TEMPLATE_HELP } from '../pharmacy.model';

/** The header row of the Sahaj template (`GET /opening-stock/template`, story 78-7). */
export const TEMPLATE_COLUMNS = [
  'store code',
  'product code',
  'batch',
  'expiry date',
  'MRP',
  'purchase rate',
  'quantity',
] as const;

/** Rows per file (OQ-78-35) and rows per request (ADR-028 D12). */
export const MAX_ROWS = 5000;
export const BATCH_SIZE = 200;
/** Most JSON bytes of one lines request: the server refuses a body over 64 KB, so stay a little under. */
export const MAX_BATCH_BYTES = 60 * 1024;
/** The header row and the example row do not count as data rows, but they are read. */
const MAX_TABLE_ROWS = MAX_ROWS + 2;
/** A data row has exactly these cells; anything beyond them is a problem (story 78-7). */
const COLUMN_COUNT = TEMPLATE_COLUMNS.length;

/** Transport limits of `LinesInput`: a longer value would make the server refuse the whole request. */
const LIMITS: readonly { key: keyof Omit<LineRowInput, 'rowNo'>; max: number; label: string }[] = [
  { key: 'storeCode', max: 40, label: 'store code' },
  { key: 'productCode', max: 40, label: 'product code' },
  { key: 'batch', max: 40, label: 'batch' },
  { key: 'expiryDate', max: 20, label: 'expiry date' },
  { key: 'mrp', max: 20, label: 'MRP' },
  { key: 'purchaseRate', max: 20, label: 'purchase rate' },
  { key: 'quantity', max: 20, label: 'quantity' },
];

export type CsvRead =
  | { ok: true; columns: string[]; rows: LineRowInput[] }
  | { ok: false; message: string };

/**
 * Reads CSV text (comma separated, double quotes, CR LF or LF, optional byte-order mark) into rows of
 * cells. No library (DEC-061). The text stays in memory only; nothing here logs or stores it.
 * Blank rows (every cell empty) are not kept. Reading stops as soon as more than `maxRows` rows are kept,
 * so a file full of separators cannot build millions of rows (the caller then sees `length > maxRows`).
 */
export function parseCsv(text: string, maxRows = Number.POSITIVE_INFINITY): string[][] {
  const source = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  const keep = (): boolean => {
    row.push(cell);
    if (!isBlank(row)) {
      rows.push(row);
    }
    row = [];
    cell = '';
    return rows.length > maxRows;
  };
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    if (quoted) {
      if (c === '"') {
        if (source[i + 1] === '"') {
          cell += '"';
          i++;
        } else {
          quoted = false;
        }
      } else {
        cell += c;
      }
    } else if (c === '"') {
      quoted = true;
    } else if (c === ',') {
      row.push(cell);
      cell = '';
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && source[i + 1] === '\n') {
        i++;
      }
      if (keep()) {
        return rows;
      }
    } else {
      cell += c;
    }
  }
  if (cell !== '' || row.length > 0) {
    keep();
  }
  return rows;
}

function isBlank(row: readonly string[]): boolean {
  return row.every((cell) => cell.trim() === '');
}

/** The header cells as found in the file: trimmed, without empty cells at the end. */
function headerCells(header: readonly string[]): string[] {
  const cells = header.map((cell) => cell.trim());
  while (cells.length > 0 && cells[cells.length - 1] === '') {
    cells.pop();
  }
  return cells;
}

/** True when the header row is the template's, ignoring capital letters and spaces around each name. */
export function isTemplateHeader(header: readonly string[]): boolean {
  const cells = [...header];
  while (cells.length > 0 && cells[cells.length - 1].trim() === '') {
    cells.pop();
  }
  return (
    cells.length === TEMPLATE_COLUMNS.length &&
    cells.every((cell, i) => cell.trim().toLowerCase() === TEMPLATE_COLUMNS[i].toLowerCase())
  );
}

/**
 * The browser's checks before anything is sent (story 78-7, contract POST /opening-stock-imports): a file
 * that is not CSV, is empty, has more than 5,000 rows or has another header is refused with the one plain
 * sentence of the template. Example rows (first cell starting with #) are skipped. Messages never carry
 * file contents, only a row number and a column name.
 */
export function readOpeningStockCsv(fileName: string, text: string): CsvRead {
  if (!/\.csv$/i.test(fileName.trim())) {
    return { ok: false, message: TEMPLATE_HELP };
  }
  const table = parseCsv(text, MAX_TABLE_ROWS);
  if (table.length === 0 || table.length > MAX_TABLE_ROWS || !isTemplateHeader(table[0])) {
    return { ok: false, message: TEMPLATE_HELP };
  }
  const dataRows = table.slice(1).filter((row) => !row[0].trim().startsWith('#'));
  if (dataRows.length === 0 || dataRows.length > MAX_ROWS) {
    return { ok: false, message: TEMPLATE_HELP };
  }
  const rows: LineRowInput[] = [];
  for (let i = 0; i < dataRows.length; i++) {
    if (dataRows[i].slice(COLUMN_COUNT).some((extra) => extra.trim() !== '')) {
      return {
        ok: false,
        message: `Row ${i + 1}: there are more than ${COLUMN_COUNT} columns. Fix the file and upload it again.`,
      };
    }
    const cell = (n: number) => (dataRows[i][n] ?? '').trim();
    const row: LineRowInput = {
      rowNo: i + 1,
      storeCode: cell(0),
      productCode: cell(1),
      batch: cell(2),
      expiryDate: cell(3),
      mrp: cell(4),
      purchaseRate: cell(5),
      quantity: cell(6),
    };
    const tooLong = LIMITS.find((limit) => row[limit.key].length > limit.max);
    if (tooLong) {
      return {
        ok: false,
        message: `Row ${i + 1}: the ${tooLong.label} is too long. Fix the file and upload it again.`,
      };
    }
    rows.push(row);
  }
  return { ok: true, columns: headerCells(table[0]), rows };
}

/**
 * The rows in requests of at most 200 (ADR-028 D12) and at most about 60 KB of encoded JSON, so that a
 * request with long values is not refused by the server's 64 KB body cap. A row alone is always sent.
 */
export function inBatches(rows: readonly LineRowInput[]): LineRowInput[][] {
  const encoder = new TextEncoder();
  const wrapper = '{"rows":[]}'.length;
  const batches: LineRowInput[][] = [];
  let batch: LineRowInput[] = [];
  let bytes = wrapper;
  for (const row of rows) {
    const size = encoder.encode(JSON.stringify(row)).length + 1;
    if (batch.length > 0 && (batch.length >= BATCH_SIZE || bytes + size > MAX_BATCH_BYTES)) {
      batches.push(batch);
      batch = [];
      bytes = wrapper;
    }
    batch.push(row);
    bytes += size;
  }
  if (batch.length > 0) {
    batches.push(batch);
  }
  return batches;
}
