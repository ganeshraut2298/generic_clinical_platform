import { Injectable, computed, inject, signal } from '@angular/core';
import { ConfigSection } from '../config-sections';
import { ModulesApi } from '../modules/modules.api';
import { IpdState, ModulesView } from '../modules/modules.model';

/**
 * The clinic's IPD state (from `GET /platform/modules`, SAH-19), shared by the Clinic setup nav,
 * the overview tiles and the two IPD setup pages. `null` until known; the sections that need IPD
 * stay hidden until it is trial, on or read-only. The backend still answers 403 `module-off`.
 */
@Injectable({ providedIn: 'root' })
export class IpdAccess {
  private readonly modules = inject(ModulesApi);

  readonly state = signal<IpdState | null>(null);
  readonly failed = signal(false);
  readonly available = computed(() => {
    const s = this.state();
    return s === 'trial' || s === 'on' || s === 'readonly';
  });

  private loading = false;

  /** Loads the state once; later calls do nothing unless the first one failed. */
  ensureLoaded(): void {
    if (this.state() !== null || this.loading) {
      return;
    }
    this.loading = true;
    this.failed.set(false);
    this.modules.get().subscribe({
      next: (view) => {
        this.loading = false;
        this.set(view);
      },
      error: () => {
        this.loading = false;
        this.failed.set(true);
      },
    });
  }

  /** Called by Plan and modules after each change, so the nav follows at once. */
  set(view: ModulesView): void {
    this.state.set(view.ipd.state);
    this.failed.set(false);
  }

  visible(section: ConfigSection): boolean {
    return section.requires !== 'ipd' || this.available();
  }
}
