import { Injectable, computed, signal } from '@angular/core';
import { AdminAuthResult, AdminMe } from './admin-auth.model';

/**
 * The admin realm's own token store (ADR-003 D8). The access token lives in memory only: a
 * signal, never localStorage or sessionStorage. The refresh token is an HttpOnly cookie the
 * browser manages. The recovery codes in a sign-in result are NOT kept here.
 */
@Injectable({ providedIn: 'root' })
export class AdminSessionStore {
  private readonly tokenSig = signal<string | null>(null);
  private readonly adminSig = signal<AdminMe | null>(null);

  readonly accessToken = this.tokenSig.asReadonly();
  readonly admin = this.adminSig.asReadonly();
  readonly isAuthenticated = computed(() => this.tokenSig() !== null);

  set(result: AdminAuthResult): void {
    this.tokenSig.set(result.accessToken);
    this.adminSig.set(result.admin);
  }

  clear(): void {
    this.tokenSig.set(null);
    this.adminSig.set(null);
  }
}
