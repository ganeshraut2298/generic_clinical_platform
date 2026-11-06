import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import {
  AdminAuthResult,
  AdminLoginBody,
  AdminLoginResult,
  TotpEnrolBody,
  TotpEnrolment,
  TotpVerifyBody,
} from './admin-auth.model';

/**
 * The only place that calls `/api/v1/admin/auth/*` (docs/api/admin.yaml). The refresh cookie
 * `sahaj_admin_rt` is HttpOnly and managed by the browser; `withCredentials` lets it travel.
 * Passwords, codes and tokens only ever go in request bodies, never in a URL.
 */
@Injectable({ providedIn: 'root' })
export class AdminAuthApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/admin`;

  login(body: AdminLoginBody): Observable<AdminLoginResult> {
    return this.http.post<AdminLoginResult>(`${this.baseUrl}/auth/login`, body, {
      withCredentials: true,
    });
  }

  enrol(body: TotpEnrolBody): Observable<TotpEnrolment> {
    return this.http.post<TotpEnrolment>(`${this.baseUrl}/auth/totp/enrol`, body, {
      withCredentials: true,
    });
  }

  verify(body: TotpVerifyBody): Observable<AdminAuthResult> {
    return this.http.post<AdminAuthResult>(`${this.baseUrl}/auth/totp/verify`, body, {
      withCredentials: true,
    });
  }

  /** Uses the cookie and rotates it. No body. */
  refresh(): Observable<AdminAuthResult> {
    return this.http.post<AdminAuthResult>(`${this.baseUrl}/auth/refresh`, null, {
      withCredentials: true,
    });
  }

  signOut(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/auth/sign-out`, null, { withCredentials: true });
  }
}
