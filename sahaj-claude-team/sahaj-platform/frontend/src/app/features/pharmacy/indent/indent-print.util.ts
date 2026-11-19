import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { PrintHeader } from '../pharmacy-buying.model';
import { Dispatch, DispatchLine } from '../pharmacy-indent.model';
import { escapeHtml, page } from '../purchase/print.util';
import { batchWithExpiry } from './indent.util';

/** The print header used before a dispatch exists: the unit's print template arrives with the dispatch itself. */
export const EMPTY_ISSUE_HEADER: PrintHeader = {
  templateDocType: 'ISSUE_SLIP',
  headerLines: [],
  footerNote: null,
  paperSize: 'A4',
  unitGstin: null,
  drugLicenceNo: null,
};

export interface PickListRow {
  product: string;
  batch: string;
  qty: number | null;
}

function lineRow(line: DispatchLine): string {
  const replaced = line.replacedByCs
    ? ` (replaced by Central Store${line.changeReason ? ': ' + escapeHtml(line.changeReason.label) : ''})`
    : '';
  const back = line.backOrdered > 0 ? ` · ${line.backOrdered} back-ordered` : '';
  return (
    `<tr><td>${escapeHtml(line.product.name)} ${escapeHtml(line.product.strength)}${replaced}${escapeHtml(back)}</td>` +
    `<td>${escapeHtml(batchWithExpiry(line.batch.batchNo, line.batch.expiryDate))}</td>` +
    `<td class="n">${line.dispatched}</td></tr>`
  );
}

/**
 * The issue slip of a dispatch (story 79-11), made from the print template of the unit (SAH-78-10) that the server sends
 * with the dispatch: indent number, dispatch reference, stores, items, batches (month and year), quantities, replacements
 * and reasons. The page carries its own Content-Security-Policy meta, as the other print views do.
 */
export function issueSlipHtml(dispatch: Dispatch): string {
  const body =
    `<p>Indent ${escapeHtml(dispatch.sourceNo)} · dispatch ${escapeHtml(dispatch.reference)}</p>` +
    `<p>${escapeHtml(dispatch.fromStore.name)} → ${escapeHtml(dispatch.toStore.name)} · ${escapeHtml(formatDisplayDateTimeOfInstant(dispatch.dispatchedAt))}</p>` +
    `<table><thead><tr><th>Item</th><th>Batch</th><th class="n">Qty</th></tr></thead><tbody>${dispatch.lines.map(lineRow).join('')}</tbody></table>`;
  return page(`Issue slip ${dispatch.reference}`, dispatch.print, body);
}

/** The pick list of an indent before it is dispatched: what FEFO picks now, per item and batch (mockup 10 step 3). */
export function indentPickListHtml(
  indentNumber: string,
  fromStore: string,
  toStore: string,
  rows: PickListRow[],
  print: PrintHeader = EMPTY_ISSUE_HEADER,
): string {
  const body =
    `<p>Indent ${escapeHtml(indentNumber)} · ${escapeHtml(fromStore)} → ${escapeHtml(toStore)}</p>` +
    `<table><thead><tr><th>Item</th><th>Batch (FEFO)</th><th class="n">Qty</th></tr></thead><tbody>` +
    rows
      .map(
        (r) =>
          `<tr><td>${escapeHtml(r.product)}</td><td>${escapeHtml(r.batch)}</td><td class="n">${r.qty === null ? '—' : r.qty}</td></tr>`,
      )
      .join('') +
    `</tbody></table>`;
  return page(`Pick list ${indentNumber}`, print, body);
}
