import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../config/api-config';
import {
  AuthResult,
  OtpRequestBody,
  OtpRequestResult,
  OtpVerifyBody,
  SelectClinicBody,
} from './auth.model';

/**
 * The only place that calls `/auth/*`. The refresh cookie is `HttpOnly; Secure; SameSite=Strict`,
 * set by the server; `withCredentials` lets the browser send and receive it. No code, token or
 * mobile number is ever put in a URL, localStorage or sessionStorage.
 */
@Injectable({ providedIn: 'root' })
export class AuthApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/platform/auth`;

  requestOtp(body: OtpRequestBody): Observable<OtpRequestResult> {
    return this.http.post<OtpRequestResult>(`${this.baseUrl}/otp/request`, body, {
      withCredentials: true,
    });
  }

  verifyOtp(body: OtpVerifyBody): Observable<AuthResult> {
    return this.http.post<AuthResult>(`${this.baseUrl}/otp/verify`, body, {
      withCredentials: true,
    });
  }

  selectClinic(body: SelectClinicBody): Observable<AuthResult> {
    return this.http.post<AuthResult>(`${this.baseUrl}/select-clinic`, body, {
      withCredentials: true,
    });
  }

  /** Uses the HttpOnly refresh cookie and rotates it. No body, nothing sensitive in the request. */
  refresh(): Observable<AuthResult> {
    return this.http.post<AuthResult>(`${this.baseUrl}/refresh`, null, {
      withCredentials: true,
    });
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/logout`, null, { withCredentials: true });
  }

  logoutAll(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/logout-all`, null, { withCredentials: true });
  }
}
