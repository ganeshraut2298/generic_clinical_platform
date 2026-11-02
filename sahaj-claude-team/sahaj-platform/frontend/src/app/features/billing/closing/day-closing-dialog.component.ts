import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ApiError } from '../../../core/http/problem-detail';
import { formatInr } from '../../../shared/format/inr';
import { IconComponent } from '../../../shared/icon/icon.component';
import { BillingApi } from '../billing.api';
import { DayClosing } from '../billing.model';

/**
 * "Close the day" dialog (SAH-10, story acceptance criterion 9), mockup `openClosing`: counted
 * cash in, expected cash and the difference come back from the server once submitted, with an
 * optional note. One closing per clinic per day (409 if it is already closed).
 */
@Component({
  selector: 'app-day-closing-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatFormFieldModule, MatInputModule, IconComponent],
  styleUrl: './day-closing-dialog.component.scss',
  templateUrl: './day-closing-dialog.component.html',
})
export class DayClosingDialogComponent {
  private readonly api = inject(BillingApi);
  private readonly dialogRef = inject<MatDialogRef<DayClosingDialogComponent, DayClosing | null>>(MatDialogRef);

  protected readonly formatInr = formatInr;
  protected readonly Math = Math;

  protected readonly countedCash = signal('');
  protected readonly note = signal('');
  protected readonly closing = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly result = signal<DayClosing | null>(null);

  protected close(): void {
    this.dialogRef.close(this.result());
  }

  protected submit(): void {
    const raw = this.countedCash().trim();
    const counted = Math.round(Number(raw));
    if (!raw || !Number.isFinite(counted) || counted < 0) {
      this.error.set('Enter the cash counted in the drawer.');
      return;
    }
    this.closing.set(true);
    this.error.set(null);
    this.api.closeDay({ countedCashPaise: counted * 100, note: this.note().trim() || null }).subscribe({
      next: (result) => {
        this.closing.set(false);
        this.result.set(result);
      },
      error: (error: unknown) => {
        this.closing.set(false);
        this.error.set(
          error instanceof ApiError
            ? error.problem.detail ?? 'We could not close the day. Try again.'
            : 'We could not close the day. Try again.',
        );
      },
    });
  }
}
