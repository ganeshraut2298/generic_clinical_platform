import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, shareReplay, tap } from 'rxjs';
import { AuthApi } from './auth.api';
import {
  AuthResult,
  ConsentInput,
  Mobile,
  OtpRequestResult,
  SelectClinicBody,
} from './auth.model';

/**
 * Holds the access token in memory only (a signal) — never in localStorage or sessionStorage.
 * The refresh token lives in an HttpOnly cookie the browser manages; this service never reads it.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(AuthApi);

  private readonly accessTokenSig = signal<string | null>(null);
  private readonly selectionTokenSig = signal<string | null>(null);
  private readonly clinicsSig = signal<AuthResult['clinics']>(undefined);

  /** Set once the first silent-refresh attempt on app load finishes, success or not. */
  private readonly readySig = signal(false);

  /** In-flight refresh, shared so a burst of 401s only calls `/auth/refresh` once. */
  private refreshInFlight: Observable<boolean> | null = null;

  readonly accessToken = this.accessTokenSig.asReadonly();
  readonly isAuthenticated = computed(() => this.accessTokenSig() !== null);
  readonly ready = this.readySig.asReadonly();
  readonly selectionToken = this.selectionTokenSig.asReadonly();
  readonly clinics = this.clinicsSig.asReadonly();

  requestOtp(mobile: Mobile, mode: 'LOGIN' | 'SIGNUP'): Observable<OtpRequestResult> {
    return this.api.requestOtp({ mobile, mode });
  }

  verifyOtp(
    mobile: Mobile,
    code: string,
    mode: 'LOGIN' | 'SIGNUP',
    consent?: ConsentInput,
  ): Observable<AuthResult> {
    return this.api.verifyOtp({ mobile, code, mode, consent }).pipe(tap((r) => this.applyResult(r)));
  }

  selectClinic(tenantId: string): Observable<AuthResult> {
    const selectionToken = this.selectionTokenSig();
    if (!selectionToken) {
      throw new Error('No selection token. Verify the code again.');
    }
    const body: SelectClinicBody = { selectionToken, tenantId };
    return this.api.selectClinic(body).pipe(tap((r) => this.applyResult(r)));
  }

  /** Called once on app start. Never throws: a failed refresh just leaves the visitor signed out. */
  initialLoad(): Observable<boolean> {
    return this.refresh().pipe(tap(() => this.readySig.set(true)));
  }

  /**
   * Tries to get a new access token from the refresh cookie. Shared across callers so the
   * interceptor's retries and the app-start attempt never race each other.
   */
  refresh(): Observable<boolean> {
    if (this.refreshInFlight) {
      return this.refreshInFlight;
    }
    const call = this.api.refresh().pipe(
      map((result) => {
        this.applyResult(result);
        return typeof result.accessToken === 'string';
      }),
      catchError(() => {
        this.clearSession();
        return of(false);
      }),
      tap(() => {
        this.refreshInFlight = null;
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    this.refreshInFlight = call;
    return call;
  }

  logout(): Observable<void> {
    return this.api.logout().pipe(
      tap(() => this.clearSession()),
      catchError(() => {
        this.clearSession();
        return of(undefined);
      }),
    );
  }

  logoutAll(): Observable<void> {
    return this.api.logoutAll().pipe(
      tap(() => this.clearSession()),
      catchError(() => {
        this.clearSession();
        return of(undefined);
      }),
    );
  }

  private applyResult(result: AuthResult): void {
    this.accessTokenSig.set(result.accessToken ?? null);
    this.selectionTokenSig.set(result.selectionToken ?? null);
    this.clinicsSig.set(result.clinics);
  }

  private clearSession(): void {
    this.accessTokenSig.set(null);
    this.selectionTokenSig.set(null);
    this.clinicsSig.set(undefined);
  }
}
