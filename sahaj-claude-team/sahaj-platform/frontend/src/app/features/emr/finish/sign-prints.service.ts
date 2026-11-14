import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';
import { EmrFinishPrint, EmrFinishProfileEffects } from '../emr-finish.model';
import { EmrRecordStore } from '../record/emr-record.store';
import { SpecialtyApi } from '../specialty/specialty.api';
import { SpecialtyContext } from '../specialty/specialty.context';
import { PrintSheet, printSheet } from '../specialty/print-sheet';
import { ChemoOrderView, EmrSpecialtyValue, SpChemoPlan, SpPreauth, SpPreopOrders, SpSurgeryPlan } from '../specialty/specialty.model';
import { admissionLetterSheet, chemoCalendarSheet, cycleRecordSheet, preopSheet } from './sign-print-sheets.logic';

/** What the post-sign prints need beyond the record on screen. */
export interface SignPrintData {
  /** The profile block of the last check (cycle, next chemo date, OT booking). */
  profile: EmrFinishProfileEffects | null;
  /** The chemo order, for the cycle record; null when it could not be read. */
  order: ChemoOrderView | null;
}

/**
 * Builds and prints the documents that follow a sign (ADR-025 D19): cycle record, chemo calendar,
 * admission letter and pre-op sheet, one browser print each, from answers the client already has. The
 * prescription is printed by the Case paper panel. Nothing is stored, sent or logged.
 */
@Injectable()
export class SignPrints {
  private readonly store = inject(EmrRecordStore);
  private readonly ctx = inject(SpecialtyContext);
  private readonly api = inject(SpecialtyApi);
  private readonly doc = inject(DOCUMENT);

  /** Reads what the prints need; the chemo order is read only for the cycle record, and a failed read leaves that part out. */
  prepare(prints: readonly EmrFinishPrint[], profile: EmrFinishProfileEffects | null): Observable<SignPrintData> {
    const id = this.store.recordId();
    if (!prints.includes('CYCLE_RECORD') || !id) {
      return of({ profile, order: null });
    }
    return this.api.getChemoOrder(id).pipe(
      catchError(() => of(null)),
      map((order) => ({ profile, order })),
    );
  }

  sheet(print: EmrFinishPrint, data: SignPrintData): PrintSheet | null {
    const sheetOf = (title: string, sections: PrintSheet['sections'], footer?: string): PrintSheet => this.ctx.sheet(title, sections, footer);
    const onco = data.profile?.profile === 'onco' ? data.profile : null;
    const surgery = data.profile?.profile === 'surgery' ? data.profile : null;
    const plan = this.valueOf<SpChemoPlan>('CHEMO_PLAN');
    const surgeryPlan = this.valueOf<SpSurgeryPlan>('SURGERY_PLAN');
    switch (print) {
      case 'CYCLE_RECORD':
        return cycleRecordSheet({ sheetOf, block: onco, onco: this.ctx.onco(), plan, order: data.order, reference: this.ctx.reference() });
      case 'CHEMO_CALENDAR':
        return chemoCalendarSheet({ sheetOf, block: onco, onco: this.ctx.onco(), plan });
      case 'ADMISSION_LETTER':
        return admissionLetterSheet({ sheetOf, block: surgery, plan: surgeryPlan, preauth: this.valueOf<SpPreauth>('PREAUTH'), view: this.ctx.view() });
      case 'PREOP_SHEET':
        return preopSheet({ sheetOf, block: surgery, plan: surgeryPlan, preop: this.valueOf<SpPreopOrders>('PREOP_ORDERS'), reference: this.ctx.reference() });
      default:
        return null;
    }
  }

  /** Prints one document; false when the browser could not print it. */
  print(print: EmrFinishPrint, data: SignPrintData): boolean {
    const sheet = this.sheet(print, data);
    return sheet !== null && printSheet(this.doc, sheet);
  }

  private valueOf<T extends EmrSpecialtyValue>(component: T['component']): T | null {
    const key = this.ctx.sectionKeyOf(component);
    return key ? this.ctx.value<T>(key, component) : null;
  }
}
