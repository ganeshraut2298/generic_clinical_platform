import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { BOOTSTRAP_CALL } from '../../../core/http/bootstrap-call';
import {
  BootstrapRegisterBody,
  BootstrapUnlockBody,
  BootstrapUnlocked,
} from './admin-bootstrap.model';

/**
 * The only place that calls `/api/v1/admin/bootstrap/*` (docs/api/admin.yaml, SAH-42).
 *
 * The server accepts these calls only when they are same-origin (ADR-018 D2, checks K4 and K5).
 * In development the app's API base points at port 8080, which would be another origin, so this
 * client always uses the path part of the base (`/api/v1`) and lets the dev server proxy forward
 * it. The PIN, the ticket and the password go in request bodies only, never in a URL or header.
 * The binding cookie `sahaj_admin_bootstrap` is HttpOnly: the page never reads it, the browser
 * sends it.
 */
@Injectable({ providedIn: 'root' })
export class AdminBootstrapApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${new URL(inject(API_BASE_URL), globalThis.location?.origin ?? 'http://localhost').pathname}/admin/bootstrap`;

  unlock(body: BootstrapUnlockBody): Observable<BootstrapUnlocked> {
    return this.http.post<BootstrapUnlocked>(`${this.baseUrl}/unlock`, body, this.options());
  }

  /** The 201 has no body (ADR-018 Revision 5): success is the status alone. */
  register(body: BootstrapRegisterBody): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/register`, body, this.options());
  }

  private options() {
    return { withCredentials: true, context: new HttpContext().set(BOOTSTRAP_CALL, true) };
  }
}
