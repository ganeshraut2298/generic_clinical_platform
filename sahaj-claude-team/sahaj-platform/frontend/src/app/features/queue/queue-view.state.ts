import { Injectable, signal } from '@angular/core';

/**
 * The Queue's own view choice (Everyone or My tasks), kept in memory for the session so the doctor comes
 * back to the same filter after the EMR (SAH-51-2: "returns to the Queue with the same filter and view").
 * No patient data; nothing is written to browser storage.
 */
@Injectable({ providedIn: 'root' })
export class QueueViewState {
  readonly myOnly = signal(false);
}
