import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, tap, throwError } from 'rxjs';
import { ShellApi } from './shell.api';
import { Me, MenuMode, Preferences, Theme } from './shell.model';
import { Language } from '../platform/platform.model';
import { applyBrand, applyTheme } from './apply-brand';

/**
 * Signal store for the signed-in user (`GET /me`), seeded once per sign-in and kept current
 * across the shell: menu, role, clinic brand and the four preferences (SAH-5).
 */
@Injectable({ providedIn: 'root' })
export class ShellStore {
  private readonly api = inject(ShellApi);

  private readonly meSig = signal<Me | null>(null);
  private readonly loadingSig = signal(false);

  readonly me = this.meSig.asReadonly();
  readonly loading = this.loadingSig.asReadonly();

  readonly menu = computed(() => this.meSig()?.menu ?? []);
  readonly language = computed<Language>(() => this.meSig()?.preferences.language ?? 'en');
  readonly theme = computed<Theme>(() => this.meSig()?.preferences.theme ?? 'light');
  readonly menuMode = computed<MenuMode>(() => this.meSig()?.preferences.menuMode ?? 'advanced');
  readonly sidebarCollapsed = computed(() => this.meSig()?.preferences.sidebarCollapsed ?? false);

  /** Loads `/me` once; later calls reuse the cached value until `clear()` runs. */
  load(): Observable<Me> {
    const cached = this.meSig();
    if (cached) {
      return new Observable((subscriber) => {
        subscriber.next(cached);
        subscriber.complete();
      });
    }
    this.loadingSig.set(true);
    return this.api.getMe().pipe(
      tap((me) => this.applyMe(me)),
      tap({
        next: () => this.loadingSig.set(false),
        error: () => this.loadingSig.set(false),
      }),
    );
  }

  /** Reloads from the server, for example after setup completes with a new brand. */
  refresh(): Observable<Me> {
    this.meSig.set(null);
    return this.load();
  }

  /** Re-reads `/me` in place (menu, plan) without clearing it, so the shell does not flash. */
  reloadMenu(): Observable<Me> {
    return this.api.getMe().pipe(tap((me) => this.applyMe(me)));
  }

  updatePreferences(patch: Preferences): Observable<Me> {
    const before = this.meSig();
    return this.api.updatePreferences(patch).pipe(
      tap((me) => this.applyMe(me)),
      catchError((error: unknown) => {
        if (before) {
          this.applyMe(before);
        }
        return throwError(() => error);
      }),
    );
  }

  clear(): void {
    this.meSig.set(null);
  }

  private applyMe(me: Me): void {
    this.meSig.set(me);
    applyBrand(me.clinic.brand, me.clinic.brandHex);
    applyTheme(me.preferences.theme ?? 'light');
  }
}
