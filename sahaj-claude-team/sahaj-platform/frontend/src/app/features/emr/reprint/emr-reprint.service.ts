import { Injectable, inject, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Role } from '../../../core/platform/platform.model';
import { EmrApi } from '../emr.api';
import { detailOr, problemSlug } from '../emr-errors';
import { ReprintDialogComponent } from './reprint-dialog.component';

/** Owner, front desk and pharmacist reprint any visit; a doctor only their own (ADR-025 H12). Billing, nurse and counsellor cannot. */
const ANY_VISIT: readonly Role[] = ['OWNER', 'FRONT_DESK', 'PHARMACIST'];
const OWN_VISIT: readonly Role[] = ['DOCTOR', 'VISITING_DOCTOR'];

/** Whether the role is offered "Reprint" on a signed visit. The server checks again and audits the read. */
export function roleCanReprint(role: Role | undefined, isOwnVisit: boolean): boolean {
  if (!role) {
    return false;
  }
  return ANY_VISIT.includes(role) || (OWN_VISIT.includes(role) && isOwnVisit);
}

/** Opens the signed prescription of a visit read-only, with "Reprint" on the paper (SAH-51-6). */
@Injectable({ providedIn: 'root' })
export class EmrReprintService {
  private readonly api = inject(EmrApi);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly opening = signal<string | null>(null);

  open(visitId: string): void {
    if (this.opening()) {
      return;
    }
    this.opening.set(visitId);
    this.api.reprintPrescription(visitId).subscribe({
      next: (reprint) => {
        this.opening.set(null);
        this.dialog.open(ReprintDialogComponent, {
          panelClass: 'sheet-panel',
          width: '820px',
          maxWidth: '100vw',
          ariaLabelledBy: 'reprint-title',
          data: { reprint },
        });
      },
      error: (error: unknown) => {
        this.opening.set(null);
        const text =
          problemSlug(error) === 'emr-record-not-signed'
            ? 'This visit is not signed yet.'
            : detailOr(error, 'We could not open the prescription. Try again.');
        this.snackBar.open(text, 'Close', { duration: 5000 });
      },
    });
  }
}
