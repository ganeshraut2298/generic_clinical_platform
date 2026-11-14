import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { ageText } from '../../../shared/format/patient-age';
import { AdviceTextService } from '../components/advice-text.service';
import { EmrReprint } from '../emr-finish.model';
import { NEW_PATIENT, REG_NOT_SET, footerLines, visibleRows } from '../paper/case-paper.logic';
import { printPaper } from '../paper/print-paper';
import { drName } from '../record/emr-save-status';
import { printSheet } from '../specialty/print-sheet';
import { canPrintVaccinationCard, reprintPaper, reprintVaccinationCard } from './reprint.logic';

const SEX_TEXT = { F: 'Female', M: 'Male', O: 'Other' } as const;

/**
 * Reprint of a signed prescription (SAH-51-6): the same paper as the live case paper, read-only, with the
 * word "Reprint" and the addenda under the signature. Opened from the Queue card of a signed visit by the
 * roles that may reprint (owner, front desk, pharmacist, the visit's doctor). The server audits the read.
 * The paper is for the screen and the print only: nothing is stored or logged.
 */
@Component({
  selector: 'app-reprint-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule],
  styleUrl: '../paper/case-paper-panel.component.scss',
  styles: `
    mat-dialog-content {
      max-height: 70vh;
    }
    .reprint {
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: var(--paper-soft);
    }
    button {
      min-height: 44px;
    }
  `,
  template: `
    <h2 mat-dialog-title id="reprint-title">Reprint prescription</h2>
    <mat-dialog-content>
      <article #sheet class="sheet" [class.a5]="size() === 'A5'" [class.a4]="size() === 'A4'" aria-label="Prescription reprint">
        <header class="letter">
          <div class="clinic">
            <span class="logo" aria-hidden="true">+</span>
            <div class="cl">
              <strong>{{ c.letterhead.clinicName }}</strong>
              <span class="sm">{{ [c.letterhead.address, c.letterhead.phone, c.departmentName].filter(present).join(' · ') }}</span>
            </div>
          </div>
          <div class="doc">
            <strong>{{ c.doctor.name }}</strong>
            @if (c.doctor.qualification) {
              <span class="sm">{{ c.doctor.qualification }}</span>
            }
            <span class="sm">{{ c.doctor.registrationNo ? 'Reg. No. ' + c.doctor.registrationNo : regNotSet }}</span>
          </div>
        </header>
        <div class="pat">
          <strong>{{ c.patient.name }}</strong>
          <span>{{ meta() }}</span>
          <span>{{ signedOn() }}</span>
          <span>{{ c.patient.uhid ? 'UHID ' + c.patient.uhid : newPatient }}</span>
          <span>{{ c.visitLine }}</span>
          @if (model().vitalsLine; as vitals) {
            <span>{{ vitals }}</span>
          }
          <span class="reprint" data-testid="reprint-word">Reprint</span>
        </div>
        @for (r of rows(); track r.heading) {
          <section class="prow" [class.rx]="r.heading === 'MEDICINES'">
            <span class="h" [class.has]="r.filled">{{ r.label }}</span>
            <div class="b">
              @for (line of r.lines; track $index) {
                <span class="line">{{ line }}</span>
              }
              @for (m of r.rx; track m.n) {
                <div class="med">
                  <span>{{ m.n }}.</span>
                  <span class="mn">{{ m.name }}</span>
                  <span>{{ m.how }}</span>
                  <span>{{ m.days }}</span>
                  <span>{{ m.note }}</span>
                </div>
              }
              @if (!r.filled) {
                <span class="none">Not recorded yet</span>
              }
            </div>
          </section>
        }
        @if (data.reprint.addenda.length > 0) {
          <section class="adds" aria-label="Addenda">
            @for (a of data.reprint.addenda; track a.id) {
              <span class="line">Addendum · {{ when(a.createdAt) }} · {{ dr(a.createdByName) }}: {{ a.reason }}. {{ a.text }}</span>
            }
          </section>
        }
        <footer class="foot">
          <span class="legal">{{ footer().join(' ') }}</span>
          <div class="sign">
            <span class="signed">{{ c.signature.signerName ?? c.doctor.name }}</span>
            <span>{{ c.doctor.name }}</span>
            <span class="sm">{{ c.doctor.registrationNo ? 'Reg. No. ' + c.doctor.registrationNo : regNotSet }} · {{ signedOn() }}</span>
          </div>
        </footer>
      </article>
      @if (error(); as message) {
        <p class="err-text" role="alert">{{ message }}</p>
      }
      @if (cardMessage(); as message) {
        <p role="status" data-testid="reprint-card-message">{{ message }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button data-act="reprint-close" (click)="ref.close()">Close</button>
      @if (hasCard) {
        <button type="button" mat-stroked-button data-act="reprint-card" (click)="printCard()">Print vaccination card</button>
      }
      <button type="button" mat-flat-button data-act="reprint-print" (click)="print()">Print</button>
    </mat-dialog-actions>
  `,
})
export class ReprintDialogComponent {
  protected readonly ref = inject<MatDialogRef<ReprintDialogComponent>>(MatDialogRef);
  protected readonly data = inject<{ reprint: EmrReprint }>(MAT_DIALOG_DATA);
  private readonly doc = inject(DOCUMENT);
  private readonly advice = inject(AdviceTextService);
  private readonly sheet = viewChild<ElementRef<HTMLElement>>('sheet');

  protected readonly c = this.data.reprint.context;
  protected readonly regNotSet = REG_NOT_SET;
  protected readonly newPatient = NEW_PATIENT;
  protected readonly present = (v: string | null): boolean => !!v;
  protected readonly error = signal<string | null>(null);
  protected readonly cardMessage = signal<string | null>(null);
  protected readonly hasCard = canPrintVaccinationCard(this.data.reprint);
  protected readonly size = computed(() => this.c.chosen.paperSize ?? this.c.clinicDefaults.paperSize);
  protected readonly model = computed(() => reprintPaper(this.data.reprint, () => null));
  protected readonly rows = computed(() => visibleRows(this.model(), true));
  protected readonly footer = computed(() => footerLines(this.c.clinicDefaults.capitals, false, true));
  protected readonly signedOn = computed(() => formatDisplayDateTimeOfInstant(this.data.reprint.signedAt));
  protected readonly meta = computed(() => {
    const p = this.c.patient;
    const age = p.ageDetail && this.c.departmentName.toLowerCase().includes('paed') ? ageText(p.ageDetail, 'header') : p.age !== null ? `${p.age} y` : '';
    return [age, SEX_TEXT[p.sex]].filter((x) => x).join(' · ');
  });

  protected when(instant: string): string {
    return formatDisplayDateTimeOfInstant(instant);
  }

  protected dr(name: string): string {
    return drName(name);
  }

  /** The vaccination card of a Paediatrics visit, read as on the signing day (SAH-62-4). */
  protected printCard(): void {
    const sheet = reprintVaccinationCard(this.data.reprint);
    const ok = !!sheet && printSheet(this.doc, sheet);
    this.cardMessage.set(ok ? 'Vaccination card sent to printer' : 'We could not print. Try again.');
  }

  protected print(): void {
    const el = this.sheet()?.nativeElement;
    if (!el || !printPaper(this.doc, this.doc.defaultView ?? window, el, this.size())) {
      this.error.set('We could not print. Try again.');
    }
  }
}
