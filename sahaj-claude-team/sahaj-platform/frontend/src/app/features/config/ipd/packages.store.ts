import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AdmissionsApi } from '../../ipd/admissions.api';
import { IpdPackage } from '../../ipd/ipd.model';
import { isModuleOff, isRefusal } from '../../ipd/ipd-refusal';
import { problemMessage } from '../config-format';
import { IpdAccess } from './ipd-access';

const LOAD_ERROR = 'We could not load the packages. Try again.';
/** Fixed text for 403 `module-off`: no Try again, so the words must not offer it. */
export const MODULE_OFF_ERROR = 'Packages are not available because the module is off.';

/**
 * State of the read-only Packages panel in Clinic setup → Admission and deposits (SAH-29, DEC-030).
 * Read once when IPD is available and again on Try again; never polled. `items` is null until the first
 * answer. A 403 (`forbidden`, `module-off`) clears the list (SAH-40); a network failure or a 5xx keeps
 * the last list and shows the error beside it. `moduleOff` is true while the last answer was `module-off`: the
 * page shows the fixed text without Try again, which cannot help while IPD is off. No call here writes anything.
 */
@Injectable()
export class PackagesStore {
  private readonly api = inject(AdmissionsApi);
  private readonly access = inject(IpdAccess);
  private readonly destroyRef = inject(DestroyRef);

  readonly items = signal<IpdPackage[] | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  /** The last answer was 403 `module-off`: the list is cleared and Try again is not offered (IPD is off for the clinic). */
  readonly moduleOff = signal(false);

  /** The first read has not been tried yet. */
  private started = false;

  readonly pending = computed(() => this.items() === null && this.error() === null);

  constructor() {
    effect(() => {
      if (this.access.available() && !this.started) {
        this.started = true;
        // The read must not become a dependency of this effect (its own signals change while it runs).
        untracked(() => this.load());
      }
    });
  }

  load(): void {
    this.started = true;
    // Two click events in the same instant must send one read (SAH-29 D-1); the Try again button is
    // only disabled after the next change detection.
    if (this.loading()) {
      return;
    }
    this.loading.set(true);
    this.api
      .packages()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.items.set(list.items);
          this.error.set(null);
          this.moduleOff.set(false);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          if (isRefusal(error)) {
            this.items.set(null);
            this.moduleOff.set(isModuleOff(error));
            this.error.set(
              isModuleOff(error) ? MODULE_OFF_ERROR : problemMessage(error, LOAD_ERROR),
            );
            return;
          }
          this.moduleOff.set(false);
          this.error.set(LOAD_ERROR);
        },
      });
  }
}
