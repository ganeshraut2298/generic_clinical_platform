import { formatDisplayDate } from '../../../shared/format/ist-date';
import { safePaperSize } from '../documents/documents.util';
import { DebitNote, PrintHeader, PurchaseOrder } from '../pharmacy-buying.model';
import { rateText, rupees } from '../opening-stock/opening-stock.util';
import { monthYearText } from './purchase.util';

export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

const PAGE_HEAD =
  '<!doctype html><html lang="en"><head><meta charset="utf-8">' +
  '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'">';
const PAGE_STYLE =
  'body{font-family:sans-serif;margin:24px}h1{font-size:18px}table{border-collapse:collapse;width:100%}' +
  'th,td{border:1px solid #999;padding:6px;text-align:left}td.n,th.n{text-align:right}.foot{margin-top:16px;font-size:12px}';

function headerBlock(print: PrintHeader): string {
  const lines = print.headerLines.map((l) => `<div>${escapeHtml(l)}</div>`).join('');
  const gstin = print.unitGstin ? `<div>GSTIN ${escapeHtml(print.unitGstin)}</div>` : '';
  const licence = print.drugLicenceNo ? `<div>Drug licence ${escapeHtml(print.drugLicenceNo)}</div>` : '';
  return `<div>${lines}${gstin}${licence}</div>`;
}

export function page(title: string, print: PrintHeader, body: string): string {
  const footer = print.footerNote ? `<p class="foot">${escapeHtml(print.footerNote)}</p>` : '';
  return (
    `${PAGE_HEAD}<title>${escapeHtml(title)}</title><style>@page{size:${safePaperSize(print.paperSize)}}${PAGE_STYLE}</style>` +
    `</head><body>${headerBlock(print)}<h1>${escapeHtml(title)}</h1>${body}${footer}</body></html>`
  );
}

/** The browser print page of an approved purchase order: header from the print template, vendor, lines and totals. */
export function purchaseOrderPrintHtml(po: PurchaseOrder): string {
  const rows = po.lines
    .map(
      (l) =>
        `<tr><td>${escapeHtml(l.product.name)} ${escapeHtml(l.product.strength)}</td><td class="n">${l.qty} ${escapeHtml(l.product.purchaseUnit)}</td>` +
        `<td class="n">${escapeHtml(rateText(l.rate))}</td><td class="n">${escapeHtml(rupees(l.amountPaise))}</td></tr>`,
    )
    .join('');
  const delivery = po.deliveryExpected ? `Delivery expected ${formatDisplayDate(po.deliveryExpected)} · ` : '';
  const vendorGstin = po.vendor.gstin ? ` · GSTIN ${escapeHtml(po.vendor.gstin)}` : '';
  const body =
    `<p>Vendor: ${escapeHtml(po.vendor.name)}${vendorGstin}</p>` +
    `<table><thead><tr><th>Item</th><th class="n">Qty</th><th class="n">Rate</th><th class="n">Amount</th></tr></thead><tbody>${rows}</tbody></table>` +
    `<p>Subtotal ${escapeHtml(rupees(po.subtotalPaise))} · GST ${escapeHtml(rupees(po.gstPaise))} · <b>Total incl. GST ${escapeHtml(rupees(po.totalPaise))}</b></p>` +
    `<p>${escapeHtml(delivery)}credit ${po.creditDays} days</p>`;
  return page(`Purchase order ${po.number ?? ''}`.trim(), po.print, body);
}

/** The pick list of a debit note: item, batch, quantity and rack (mockup 09, "Create debit note & pick list"). */
export function pickListPrintHtml(dn: DebitNote): string {
  const print: PrintHeader = dn.print ?? {
    templateDocType: 'DEBIT_NOTE',
    headerLines: [],
    footerNote: null,
    paperSize: 'A4',
    unitGstin: null,
    drugLicenceNo: null,
  };
  const rows = dn.pickList
    .map(
      (l) =>
        `<tr><td>${escapeHtml(l.product)}</td><td>${escapeHtml(l.batch.batchNo)} · ${escapeHtml(monthYearText(l.batch.expiryDate))}</td>` +
        `<td class="n">${l.qty}</td><td>${escapeHtml(l.rack ?? '—')}</td></tr>`,
    )
    .join('');
  const body =
    `<p>Vendor: ${escapeHtml(dn.vendor.name)}</p>` +
    `<table><thead><tr><th>Item</th><th>Batch</th><th class="n">Qty</th><th>Rack</th></tr></thead><tbody>${rows}</tbody></table>`;
  return page(`Pick list ${dn.number ?? ''}`.trim(), print, body);
}

/** Opens the browser print view for a page of HTML; nothing is sent to a server (DEC-061). */
export function openPrintView(html: string): void {
  const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }));
  const view = window.open(url, '_blank');
  if (view) {
    view.addEventListener('load', () => {
      view.print();
      URL.revokeObjectURL(url);
    });
  }
}
