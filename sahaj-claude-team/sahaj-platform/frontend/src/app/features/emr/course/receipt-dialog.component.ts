import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, OnInit, inject, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { detailOr } from '../emr-errors';
import { printPaper } from '../paper/print-paper';
import { CourseApi } from './course.api';
import { CourseReceipt } from './course.model';
import { TYPE_WORD, rupees } from './course.logic';

export interface ReceiptDialogData {
  courseId: string;
  receiptNo: number;
}

const WHATSAPP_NOTE = 'Needs the WhatsApp service';

/**
 * A course receipt for printing (SAH-58-5): the clinic letterhead, receipt number, date, patient
 * name and UHID, course, type, mode, what it was against, amount, who collected it and the balance
 * after. Print is the browser's. "Send on WhatsApp" is shown switched off (DEC-040). The receipt is
 * patient and money data for the screen and the print only: never logged or stored.
 */
@Component({
  selector: 'app-receipt-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule],
  template: `
    <div class="rd">
      <h2 id="receipt-title">Receipt</h2>
      @if (loading()) {
        <p class="faint" aria-busy="true">Loading the receipt</p>
      } @else if (error(); as message) {
        <p class="err-text" role="alert">{{ message }}</p>
      } @else if (receipt(); as r) {
        <article #sheet class="sheet" aria-labelledby="receipt-title">
          <header class="lh">
            <strong>{{ r.clinicName }}</strong>
            <span class="sm">{{ [r.clinicAddress, r.clinicPhone].filter(present).join(' · ') }}</span>
          </header>
          <div class="row"><span>Receipt</span><b data-testid="receipt-no">{{ r.receiptNo }}</b></div>
          <div class="row"><span>Date</span><span>{{ when(r.date) }}</span></div>
          <div class="row"><span>Patient</span><span>{{ r.patientName }}{{ r.uhid ? ' · UHID ' + r.uhid : '' }}</span></div>
          <div class="row"><span>Course</span><span>{{ r.courseNo }} · {{ r.courseName }}</span></div>
          <div class="row"><span>Type</span><span>{{ type(r.type) }}</span></div>
          <div class="row"><span>Mode</span><span>{{ r.mode }}</span></div>
          <div class="row"><span>Against</span><span>{{ r.against }}</span></div>
          <div class="row big"><span>Amount</span><b>{{ money(r.amountPaise) }}</b></div>
          <div class="row"><span>Balance after this</span><span>{{ money(r.balanceAfterPaise) }}</span></div>
          <div class="row"><span>Collected by</span><span>{{ r.collectedByName }}</span></div>
        </article>
      }
      <div class="actions">
        <button mat-stroked-button type="button" disabled aria-describedby="rd-wa" data-act="send-whatsapp">Send on WhatsApp</button>
        <span class="faint t-xs" id="rd-wa">{{ whatsappNote }}</span>
        <span class="spacer"></span>
        <button mat-stroked-button type="button" (click)="close()">Close</button>
        <button mat-flat-button type="button" [disabled]="!receipt()" data-act="print" (click)="print()">Print</button>
      </div>
      @if (printFailed()) {
        <p class="err-text" role="alert">We could not print. Try again.</p>
      }
    </div>
  `,
  styles: `
    .rd { display: flex; flex-direction: column; gap: 12px; padding: 20px; }
    h2 { margin: 0; }
    .sheet { display: flex; flex-direction: column; gap: 6px; padding: 18px; border: 1px solid var(--paper-line); background: var(--paper); color: var(--paper-ink); font-size: 13px; }
    .lh { display: flex; flex-direction: column; padding-bottom: 8px; border-bottom: 2px solid var(--paper-ink); }
    .sm { color: var(--paper-soft); font-size: 0.9em; }
    .row { display: flex; justify-content: space-between; gap: 12px; padding: 3px 0; border-bottom: 1px dotted var(--paper-line); }
    .row.big { font-size: 1.15em; }
    .actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
    .spacer { flex: 1; }
  `,
})
export class ReceiptDialogComponent implements OnInit {
  private readonly api = inject(CourseApi);
  private readonly data = inject<ReceiptDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ReceiptDialogComponent>>(MatDialogRef);
  private readonly doc = inject(DOCUMENT);
  private readonly sheet = viewChild<ElementRef<HTMLElement>>('sheet');

  protected readonly receipt = signal<CourseReceipt | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly printFailed = signal(false);
  protected readonly whatsappNote = WHATSAPP_NOTE;
  protected readonly present = (v: string | null): boolean => !!v;
  protected readonly money = (paise: number): string => rupees(paise);
  protected readonly when = (iso: string): string => formatDisplayDateTimeOfInstant(iso);
  protected readonly type = (t: string): string => TYPE_WORD[t as keyof typeof TYPE_WORD] ?? t;

  ngOnInit(): void {
    this.api.receipt(this.data.courseId, this.data.receiptNo).subscribe({
      next: (r) => {
        this.receipt.set(r);
        this.loading.set(false);
      },
      error: (e: unknown) => {
        this.loading.set(false);
        this.error.set(detailOr(e, 'We could not open the receipt. Try again.'));
      },
    });
  }

  protected print(): void {
    const el = this.sheet()?.nativeElement;
    if (el) {
      this.printFailed.set(!printPaper(this.doc, this.doc.defaultView ?? window, el, 'A5'));
    }
  }

  protected close(): void {
    this.ref.close();
  }
}
