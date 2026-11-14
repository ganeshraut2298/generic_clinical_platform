import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ApiError } from '../../../core/http/problem-detail';
import { newClientKey } from '../../../shared/client-key';
import { EmrApi } from '../emr.api';
import { detailOr, isOffline } from '../emr-errors';
import { ADDENDUM_REASON_MAX, ADDENDUM_TEXT_MAX, EmrAddendum } from '../emr-finish.model';
import { EmrRecordStore } from './emr-record.store';

/**
 * "Add addendum" (SAH-51-5; BRD section 8, not drawn in the mockup): a correction or addition to a signed
 * visit. A Reason (required, short) and the Correction or addition (required). The original values are
 * never edited; the addendum is stored with the doctor and the time and can never be changed or
 * deleted. The text is clinical: never logged. The Idempotency-Key is made on the first save and kept for
 * a retry, so a lost connection never adds it twice.
 */
@Component({
  selector: 'app-emr-addendum-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogModule],
  styles: `
    label {
      display: flex;
      flex-direction: column;
      gap: 4px;
      font-weight: 600;
      color: var(--ink-2);
      margin-bottom: 12px;
    }
    .count {
      font-weight: 400;
      font-size: 0.76rem;
    }
    button {
      min-height: 44px;
    }
  `,
  template: `
    <h2 mat-dialog-title id="addendum-title">Add addendum</h2>
    <mat-dialog-content>
      <p class="faint t-xs">The signed visit stays as it is. This note is added under the signature.</p>
      <label>
        <span>Reason</span>
        <input
          class="input"
          data-field="reason"
          [value]="reason()"
          [attr.maxlength]="reasonMax"
          [attr.aria-invalid]="reasonError() ? true : null"
          (input)="reason.set(val($event))"
        />
        @if (reasonError(); as message) {
          <span class="err-text" role="alert">{{ message }}</span>
        }
      </label>
      <label>
        <span>Correction or addition</span>
        <textarea
          class="input"
          data-field="text"
          rows="5"
          [value]="text()"
          [attr.maxlength]="textMax"
          [attr.aria-invalid]="textError() ? true : null"
          (input)="text.set(val($event))"
        ></textarea>
        <span class="count faint">{{ text().length }} / {{ textMax }}</span>
        @if (textError(); as message) {
          <span class="err-text" role="alert">{{ message }}</span>
        }
      </label>
      @if (error(); as message) {
        <p class="err-text" role="alert" data-testid="addendum-error">{{ message }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button data-act="addendum-cancel" [disabled]="saving()" (click)="ref.close(null)">Cancel</button>
      <button type="button" mat-flat-button data-act="addendum-save" [disabled]="saving()" (click)="save()">
        {{ saving() ? 'Saving' : 'Save addendum' }}
      </button>
    </mat-dialog-actions>
  `,
})
export class EmrAddendumDialogComponent {
  protected readonly ref = inject<MatDialogRef<EmrAddendumDialogComponent, EmrAddendum | null>>(MatDialogRef);
  private readonly api = inject(EmrApi);
  private readonly store = inject(EmrRecordStore);

  protected readonly reasonMax = ADDENDUM_REASON_MAX;
  protected readonly textMax = ADDENDUM_TEXT_MAX;
  protected readonly reason = signal('');
  protected readonly text = signal('');
  protected readonly touched = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  private key: string | null = null;
  private keyedBody: string | null = null;

  protected readonly reasonError = computed(() => {
    if (!this.touched()) {
      return null;
    }
    const r = this.reason().trim();
    return r === '' ? 'Enter a reason' : r.length > ADDENDUM_REASON_MAX ? `Use at most ${ADDENDUM_REASON_MAX} characters` : null;
  });
  protected readonly textError = computed(() => {
    if (!this.touched()) {
      return null;
    }
    const t = this.text().trim();
    return t === '' ? 'Enter the correction or addition' : t.length > ADDENDUM_TEXT_MAX ? `Use at most ${ADDENDUM_TEXT_MAX} characters` : null;
  });

  protected val(event: Event): string {
    return (event.target as HTMLInputElement | HTMLTextAreaElement).value;
  }

  protected save(): void {
    this.touched.set(true);
    const record = this.store.record();
    if (!record || this.saving() || this.reasonError() || this.textError()) {
      return;
    }
    const body = { reason: this.reason().trim(), text: this.text().trim() };
    const hash = JSON.stringify(body);
    if (!this.key || this.keyedBody !== hash) {
      this.key = newClientKey();
      this.keyedBody = hash;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api.addAddendum(record.id, body, this.key).subscribe({
      next: (addendum) => {
        this.saving.set(false);
        this.ref.close(addendum);
      },
      error: (e: unknown) => {
        this.saving.set(false);
        this.error.set(
          isOffline(e)
            ? 'We could not reach the server. The addendum is not saved. Try again.'
            : e instanceof ApiError && e.status === 400 && e.fieldErrors.length > 0
              ? e.fieldErrors[0].message
              : detailOr(e, 'We could not save the addendum. Try again.'),
        );
      },
    });
  }
}
