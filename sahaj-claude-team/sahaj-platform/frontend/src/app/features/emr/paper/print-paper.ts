import { PaperSize } from '../emr-phase3.model';

/**
 * Browser print of the paper only (SAH-55-2, SAH-59-5): the sheet is copied into a print-only
 * container, everything else on the page is hidden by the global print rules (`styles/_print.scss`),
 * the page size is set to A4 or A5, and the copy is removed when printing is done. The copy lives
 * for the print only: nothing is stored, sent or logged. Returns false when the browser could not
 * print, so the screen can say so and the doctor can try again.
 */
export function printPaper(doc: Document, win: Window, source: HTMLElement, size: PaperSize): boolean {
  const root = doc.createElement('div');
  root.className = 'print-root';
  const copy = source.cloneNode(true) as HTMLElement;
  copy.classList.add('print-sheet');
  root.appendChild(copy);

  const style = doc.createElement('style');
  style.setAttribute('data-print-page', size);
  style.textContent = `@page { size: ${size}; margin: 10mm; }`;

  doc.body.appendChild(root);
  doc.head.appendChild(style);
  doc.body.classList.add('printing-paper');
  try {
    win.print();
    return true;
  } catch {
    return false;
  } finally {
    doc.body.classList.remove('printing-paper');
    root.remove();
    style.remove();
  }
}
