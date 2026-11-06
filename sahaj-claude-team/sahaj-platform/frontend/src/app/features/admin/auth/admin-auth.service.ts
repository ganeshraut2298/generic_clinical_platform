import { Injectable, inject } from '@angular/core';
import { Observable, catchError, finalize, map, of, shareReplay, tap } from 'rxjs';
import { AdminAuthApi } from './admin-auth.api';
import {
  AdminAuthResult,
  AdminLoginResult,
  TotpEnrolment,
  TotpVerifyBody,
} from './admin-auth.model';
import { AdminSessionStore } from './admin-session.store';

/**
 * Admin sign-in, silent refresh and sign-out. Nothing here touches the clinic AuthService.
 * The pre-auth token, the enrolment key and the recovery codes are NOT kept here: they pass
 * through to the sign-in component and live in its memory only.
 */
@Injectable({ providedIn: 'root' })
export class AdminAuthService {
  private readonly api = inject(AdminAuthApi);
  private readonly session = inject(AdminSessionStore);

  /** In-flight refresh, shared so a burst of 401s calls `/auth/refresh` only once. */
  private refreshInFlight: Observable<boolean> | null = null;

  readonly accessToken = this.session.accessToken;
  readonly admin = this.session.admin;
  readonly isAuthenticated = this.session.isAuthenticated;

  login(email: string, password: string): Observable<AdminLoginResult> {
    return this.api.login({ email: email.trim(), password });
  }

  enrol(preAuthToken: string): Observable<TotpEnrolment> {
    return this.api.enrol({ preAuthToken });
  }

  /** On success the session is stored; the result (with any recovery codes) goes to the caller. */
  verify(body: TotpVerifyBody): Observable<AdminAuthResult> {
    return this.api.verify(body).pipe(tap((result) => this.session.set(result)));
  }

  /** Already signed in, or try the refresh cookie once. Used by the admin guard on page load. */
  restore(): Observable<boolean> {
    return this.session.isAuthenticated() ? of(true) : this.refresh();
  }

  refresh(): Observable<boolean> {
    if (this.refreshInFlight) {
      return this.refreshInFlight;
    }
    const call = this.api.refresh().pipe(
      map((result) => {
        this.session.set(result);
        return true;
      }),
      catchError(() => {
        this.session.clear();
        return of(false);
      }),
      finalize(() => {
        this.refreshInFlight = null;
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    this.refreshInFlight = call;
    return call;
  }

  /** Revokes the session on the server. The local session is cleared whatever the answer. */
  signOut(): Observable<void> {
    return this.api.signOut().pipe(
      catchError(() => of(undefined)),
      tap(() => this.session.clear()),
    );
  }

  /** The server ended the session (401 that refresh cannot fix). */
  endSession(): void {
    this.session.clear();
  }
}
