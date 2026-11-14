import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';

export interface AdmitAdviceData {
  /** True only for the owner: "Switch on IPD" opens Clinic setup → Plan and modules. */
  canSwitchOn: boolean;
}

/** What the sheet gives back: the referral line (empty text is null). */
export interface AdmitAdviceResult {
  referralLine: string | null;
}

export const REFERRAL_MAX = 300;

/**
 * Admit to IPD when the clinic has IPD switched off (SAH-52-4): a sheet explains the clinic is
 * OPD-only and offers "Advise admission + referral", which marks the visit "Admission advised" and
 * keeps a referral line for the case paper. Only the owner is also offered "Switch on IPD". The
 * referral line is clinical text: it is never logged.
 */
@Component({
  selector: 'app-admit-advice-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, RouterLink],
  styles: `
    button,
    a {
      min-height: 44px;
    }
    .field {
      margin-top: 12px;
    }
  `,
  template: `
    <h2 mat-dialog-title id="advice-title">Admit to IPD</h2>
    <mat-dialog-content>
      <p>
        This clinic uses OPD only, so the patient cannot be admitted here. You can advise admission and add a
        referral line for the case paper.
      </p>
      <div class="field">
        <label class="field-label" for="referral-line">Referral line (optional)</label>
        <textarea
          id="referral-line"
          class="input"
          rows="3"
          [maxlength]="max"
          [formControl]="referral"
          [attr.aria-invalid]="referral.invalid"
        ></textarea>
        @if (referral.invalid) {
          <span class="err-text" role="alert">Use at most {{ max }} characters.</span>
        }
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button data-act="advice-cancel" (click)="ref.close(null)">Cancel</button>
      @if (data.canSwitchOn) {
        <a mat-stroked-button data-act="switch-on-ipd" routerLink="/app/config/modules" (click)="ref.close(null)">
          Switch on IPD
        </a>
      }
      <button
        type="button"
        mat-flat-button
        data-act="advice-go"
        [disabled]="referral.invalid"
        (click)="advise()"
      >
        Advise admission + referral
      </button>
    </mat-dialog-actions>
  `,
})
export class AdmitAdviceDialogComponent {
  protected readonly ref = inject<MatDialogRef<AdmitAdviceDialogComponent, AdmitAdviceResult | null>>(MatDialogRef);
  protected readonly data = inject<AdmitAdviceData>(MAT_DIALOG_DATA);
  protected readonly max = REFERRAL_MAX;
  protected readonly referral = new FormControl('', {
    nonNullable: true,
    validators: [Validators.maxLength(REFERRAL_MAX)],
  });

  protected advise(): void {
    const text = this.referral.value.trim();
    this.ref.close({ referralLine: text === '' ? null : text });
  }
}
