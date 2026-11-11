import { DestroyRef, Injectable } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, filter } from 'rxjs';

/** One 403 `outside-scope` answer for one admission (SAH-34 AC 38, 39). Ids only: no name, no clinical data. */
export interface ScopeRefusal {
  admissionId: string;
  /** The refused call was a save (vitals, a dose, an order, a transfer, a deposit, a discharge step ...). */
  write: boolean;
}

export const OUTSIDE_SCOPE_MESSAGE = 'This patient is not in your wards.';
export const EMERGENCY_ENDED_MESSAGE = 'Your emergency access to this patient has ended.';
export const NOTHING_SAVED = 'Nothing was saved.';

/**
 * The words after a refusal (AC 38, 39): the message for the patient, or for a patient the user had through
 * an emergency access; after a save "Nothing was saved." follows.
 */
export function outsideScopeText(viaEmergencyAccess: boolean, write: boolean): string {
  const base = viaEmergencyAccess ? EMERGENCY_ENDED_MESSAGE : OUTSIDE_SCOPE_MESSAGE;
  return write ? `${base} ${NOTHING_SAVED}` : base;
}

/**
 * Tells every screen that holds one patient that the server refused it (403 `outside-scope`). A sheet that
 * meets the refusal reports it here; the sheets of that admission clear what they show and close
 * (they `watch`), the Safety board reads itself again, and the IPD page shows the message, drops the row
 * and reloads the list. It keeps nothing: no id and no state outlive the event (AC 43). It is not a lock of the
 * page, and it is not used for `forbidden` or `module-off`.
 */
@Injectable({ providedIn: 'root' })
export class ScopeRefusals {
  private readonly subject = new Subject<ScopeRefusal>();
  readonly refusals: Observable<ScopeRefusal> = this.subject.asObservable();

  report(admissionId: string, write: boolean): void {
    this.subject.next({ admissionId, write });
  }

  /** Runs `onRefused` for each refusal of this admission until the owner is destroyed. */
  watch(admissionId: string, destroyRef: DestroyRef, onRefused: (refusal: ScopeRefusal) => void): void {
    this.refusals
      .pipe(
        filter((r) => r.admissionId === admissionId),
        takeUntilDestroyed(destroyRef),
      )
      .subscribe(onRefused);
  }
}
