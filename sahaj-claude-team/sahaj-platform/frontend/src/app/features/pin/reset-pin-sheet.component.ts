import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemMessage } from '../config/config-format';
import { TeamApi } from '../config/team/team.api';
import { TeamMember } from '../config/team/team.model';
import { IconComponent } from '../../shared/icon/icon.component';

export interface ResetPinSheetData {
  /** The membership id (`TeamMember.id`). */
  id: string;
  name: string;
}

export const RESET_PIN_ERROR = 'We could not reset that PIN. Try again.';

/**
 * Confirm sheet "Reset PIN · <name>" (SAH-31, ADR-010 D8), OWNER only. The owner clears a PIN; they
 * never see, choose or receive one. Nothing is sent until "Reset PIN" is pressed; the button, Cancel
 * and the close button are off while the request runs, and Esc does nothing then. A failure keeps
 * the sheet open with the server's text. Closes with the updated member on success.
 */
@Component({
  selector: 'app-reset-pin-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent],
  styles: `
    .sub {
      font-size: 0.86rem;
    }
    .sheet-f button,
    .notice button {
      min-height: 44px;
    }
  `,
  template: `
    <div class="sheet" data-testid="reset-pin-sheet">
      <div class="sheet-h">
        <div>
          <h2 id="rp-title">Reset PIN · {{ data.name }}</h2>
        </div>
        <button
          mat-icon-button
          type="button"
          aria-label="Close"
          data-act="rp-x"
          [disabled]="running()"
          (click)="cancel()"
        >
          <app-icon name="x" [size]="18" />
        </button>
      </div>
      <div class="sheet-b">
        <p>
          {{ data.name }} will have no PIN until they set a new one with a code sent to their own
          mobile. You will not see or choose their PIN.
        </p>
        @if (failure(); as message) {
          <div class="notice err" role="alert" data-testid="rp-failure">
            <app-icon name="alert" [size]="16" />
            <span>{{ message }}</span>
          </div>
        }
      </div>
      <div class="sheet-f">
        <button mat-stroked-button type="button" data-act="rp-cancel" [disabled]="running()" (click)="cancel()">
          Cancel
        </button>
        <button
          mat-flat-button
          type="button"
          class="danger"
          data-act="rp-confirm"
          [disabled]="running()"
          [attr.aria-busy]="running()"
          (click)="confirm()"
        >
          <app-icon name="key" [size]="15" /> Reset PIN
        </button>
      </div>
    </div>
  `,
})
export class ResetPinSheetComponent {
  private readonly api = inject(TeamApi);
  private readonly dialogRef = inject<MatDialogRef<ResetPinSheetComponent, TeamMember>>(MatDialogRef);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly data = inject<ResetPinSheetData>(MAT_DIALOG_DATA);

  protected readonly running = signal(false);
  protected readonly failure = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.dialogRef.disableClose = this.running();
    });
  }

  protected confirm(): void {
    if (this.running()) {
      return;
    }
    this.running.set(true);
    this.failure.set(null);
    this.api
      .resetPin(this.data.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (member) => {
          this.running.set(false);
          this.dialogRef.close(member);
        },
        error: (error: unknown) => {
          this.running.set(false);
          this.failure.set(problemMessage(error, RESET_PIN_ERROR));
        },
      });
  }

  protected cancel(): void {
    if (!this.running()) {
      this.dialogRef.close();
    }
  }
}
