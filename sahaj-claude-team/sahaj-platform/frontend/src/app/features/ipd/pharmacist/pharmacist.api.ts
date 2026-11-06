import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import {
  PharmacistActionResult,
  PharmacistChecks,
  PharmacistChecksSummary,
  RejectOrderInput,
  VerifyOrderInput,
} from './pharmacist.model';

/**
 * The only place that calls the SAH-31 pharmacist endpoints (`/ipd/pharmacist-checks`,
 * `/ipd/pharmacist-checks/summary`, `/ipd/medicine-orders/{id}/verify` and `/reject`). Answers hold
 * patient names and allergy text; the verify body holds a PIN. Nothing here is logged or stored.
 */
@Injectable({ providedIn: 'root' })
export class PharmacistApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/ipd`;

  /** OWNER, PHARMACIST. Oldest first; `limit` 50 and the `nextCursor` of the last page for more. */
  checks(limit: number, cursor?: string | null): Observable<PharmacistChecks> {
    let params = new HttpParams().set('limit', String(limit));
    if (cursor) {
      params = params.set('cursor', cursor);
    }
    return this.http.get<PharmacistChecks>(`${this.baseUrl}/pharmacist-checks`, { params });
  }

  /** Counts only, for the tab number and the poll; no names travel in a poll. */
  summary(): Observable<PharmacistChecksSummary> {
    return this.http.get<PharmacistChecksSummary>(`${this.baseUrl}/pharmacist-checks/summary`);
  }

  /** The caller's own PIN is checked by the server. 200 also when the same user repeats it. */
  verify(orderId: string, body: VerifyOrderInput): Observable<PharmacistActionResult> {
    return this.http.post<PharmacistActionResult>(
      `${this.baseUrl}/medicine-orders/${orderId}/verify`,
      body,
    );
  }

  reject(orderId: string, body: RejectOrderInput): Observable<PharmacistActionResult> {
    return this.http.post<PharmacistActionResult>(
      `${this.baseUrl}/medicine-orders/${orderId}/reject`,
      body,
    );
  }
}
