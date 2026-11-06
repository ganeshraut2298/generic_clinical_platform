import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import { PinCodeSent, PinSetInput, PinStatus } from './pin.model';

/**
 * The only place that calls the personal PIN endpoints (`/platform/me/pin`, `/platform/me/pin/code`;
 * ADR-010). The PIN and the code only travel in the body of `set`; nothing here is logged or kept.
 */
@Injectable({ providedIn: 'root' })
export class PinApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/platform`;

  /** The signed-in person's own status. Never returns the PIN or the hash. */
  status(): Observable<PinStatus> {
    return this.http.get<PinStatus>(`${this.baseUrl}/me/pin`);
  }

  /** Sends a 6-digit code to the person's own mobile (read on the server). 202. */
  sendCode(): Observable<PinCodeSent> {
    return this.http.post<PinCodeSent>(`${this.baseUrl}/me/pin/code`, null);
  }

  /** Sets, changes or replaces the person's own PIN. 200 with the new status. */
  set(body: PinSetInput): Observable<PinStatus> {
    return this.http.put<PinStatus>(`${this.baseUrl}/me/pin`, body);
  }
}
