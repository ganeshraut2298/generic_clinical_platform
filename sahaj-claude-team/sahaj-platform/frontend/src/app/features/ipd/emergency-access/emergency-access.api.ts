import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import {
  EmergencyAccessGrant,
  EmergencyAccessInput,
  EmergencyAccessPicker,
  EmergencyAccessReviewPage,
  EmergencyAccessReviewResult,
} from './emergency-access.model';

/**
 * The only place that calls the SAH-34 emergency access endpoints (`/ipd/emergency-access...`). The first
 * page of the owner's panel comes with `GET /ipd/safety`; `reviewPage` reads the following pages. Answers
 * hold names and UHIDs: nothing here is logged or stored.
 */
@Injectable({ providedIn: 'root' })
export class EmergencyAccessApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/ipd/emergency-access`;

  /** The select "Patient" (admitted patients outside the caller's scope) and the three reasons. Writes nothing. */
  picker(): Observable<EmergencyAccessPicker> {
    return this.http.get<EmergencyAccessPicker>(`${this.baseUrl}/patients`);
  }

  /** "Open for 30 minutes": no duration, time or user is sent; the server sets them. */
  open(body: EmergencyAccessInput): Observable<EmergencyAccessGrant> {
    return this.http.post<EmergencyAccessGrant>(this.baseUrl, body);
  }

  /** OWNER: the next 25 unreviewed grants after the `nextCursor` of the last page ("Show 25 more"). */
  reviewPage(cursor: string): Observable<EmergencyAccessReviewPage> {
    return this.http.get<EmergencyAccessReviewPage>(this.baseUrl, {
      params: new HttpParams().set('limit', '25').set('cursor', cursor),
    });
  }

  /** OWNER: no body. 409 `already-reviewed` when someone else was first. */
  review(grantId: string): Observable<EmergencyAccessReviewResult> {
    return this.http.post<EmergencyAccessReviewResult>(`${this.baseUrl}/${grantId}/review`, null);
  }
}
