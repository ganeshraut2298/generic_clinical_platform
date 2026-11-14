import { Injectable, inject, signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { EmrApi } from './emr.api';
import { detailOr } from './emr-errors';
import { EmrEncounterType, EmrStageOutcome } from './emr.model';

/** Router state handed to the EMR page after an open: only an outcome word, no patient data. */
export interface EmrNavigationState {
  stageOutcome: EmrStageOutcome;
}

const OPEN_ERROR = 'We could not open the EMR. Try again.';

/**
 * Opens the EMR for an encounter from a card (OPD Queue, IPD Admitted, Emergency): one call that
 * finds or creates the record, then the EMR page (SAH-46). Safe to repeat. One open at a time.
 */
@Injectable({ providedIn: 'root' })
export class EmrLauncher {
  private readonly api = inject(EmrApi);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  /** The encounter being opened, so its button can show a busy state. */
  readonly opening = signal<string | null>(null);

  open(encounterType: EmrEncounterType, encounterId: string): void {
    if (this.opening()) {
      return;
    }
    this.opening.set(encounterId);
    this.api.openRecord({ encounterType, encounterId }).subscribe({
      next: (result) => {
        this.opening.set(null);
        const state: EmrNavigationState = { stageOutcome: result.stageOutcome };
        void this.router.navigate(['/app/emr', result.record.id], { state });
      },
      error: (error: unknown) => {
        this.opening.set(null);
        this.snackBar.open(detailOr(error, OPEN_ERROR), 'Close', { duration: 5000 });
      },
    });
  }
}
