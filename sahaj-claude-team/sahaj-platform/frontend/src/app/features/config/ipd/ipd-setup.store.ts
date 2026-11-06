import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable } from 'rxjs';
import { ShellStore } from '../../../core/shell/shell.store';
import { problemMessage } from '../config-format';
import { IpdAccess } from './ipd-access';
import { IpdApi } from './ipd.api';
import { IpdLayout } from './ipd.model';

export type IpdGate = 'checking' | 'error' | 'off' | 'ready';

/**
 * State shared by the two IPD setup pages (SAH-22). One instance per page (provided in the
 * page). Every write returns the whole layout, which replaces the signal; one write runs at a
 * time. Failures (bed-limit, bed-occupied, ipd-readonly, ...) are shown with the API's message.
 */
@Injectable()
export class IpdSetupStore {
  private readonly api = inject(IpdApi);
  private readonly access = inject(IpdAccess);
  private readonly shell = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  readonly layout = signal<IpdLayout | null>(null);
  readonly loading = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly busy = signal(false);

  readonly isOwner = computed(() => this.shell.me()?.role === 'OWNER');
  readonly isReadonly = computed(
    () => this.layout()?.state === 'readonly' || this.access.state() === 'readonly',
  );
  /** Owner and IPD not read-only. Controls are disabled otherwise (the backend still checks). */
  readonly canEdit = computed(() => this.isOwner() && !this.isReadonly());
  /** Controls are also disabled while a save is running. */
  readonly locked = computed(() => !this.canEdit() || this.busy());

  readonly gate = computed<IpdGate>(() => {
    if (this.access.available()) {
      return 'ready';
    }
    if (this.access.state() === 'off') {
      return 'off';
    }
    return this.access.failed() ? 'error' : 'checking';
  });

  constructor() {
    this.access.ensureLoaded();
    effect(() => {
      if (this.access.available() && this.layout() === null && !this.loading() && !this.loadError()) {
        this.load();
      }
    });
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api
      .layout()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (layout) => {
          this.layout.set(layout);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.loadError.set(problemMessage(error, 'We could not load the IPD setup. Try again.'));
        },
      });
  }

  retry(): void {
    if (this.access.failed()) {
      this.access.ensureLoaded();
    } else {
      this.load();
    }
  }

  /** Runs one write. `done` runs after the new layout is in; `failed` after the message is shown. */
  run(
    call: Observable<IpdLayout>,
    fallback: string,
    hooks: { done?: (layout: IpdLayout) => void; failed?: () => void } = {},
  ): void {
    if (this.busy() || !this.canEdit()) {
      hooks.failed?.();
      return;
    }
    this.busy.set(true);
    call.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (layout) => {
        this.layout.set(layout);
        this.busy.set(false);
        hooks.done?.(layout);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.toast(problemMessage(error, fallback));
        hooks.failed?.();
      },
    });
  }

  toast(message: string): void {
    this.snackBar.open(message, 'Close', { duration: 5000 });
  }
}
