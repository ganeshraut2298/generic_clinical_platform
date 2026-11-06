import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import {
  ClinicalEventPage,
  EscalationActionResult,
  EscalationResponseInput,
  SafetyBoard,
  SafetyCounts,
  SeniorCallInput,
} from './safety.model';

/**
 * The only place that calls the SAH-33 endpoints (`/ipd/safety`, `/ipd/safety/summary`,
 * `/ipd/escalations/{id}/...` and `/ipd/clinical-events`). Answers hold patient names: nothing here
 * is logged or stored.
 */
@Injectable({ providedIn: 'root' })
export class SafetyApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/ipd`;

  board(): Observable<SafetyBoard> {
    return this.http.get<SafetyBoard>(`${this.baseUrl}/safety`);
  }

  /** Counts only, for the tab number and the banner check; no names travel in a poll. */
  summary(): Observable<SafetyCounts> {
    return this.http.get<SafetyCounts>(`${this.baseUrl}/safety/summary`);
  }

  /** OWNER, DOCTOR. 200 with `replayed` true when the same user already did it (nothing written). */
  acknowledge(id: string, version: number): Observable<EscalationActionResult> {
    return this.http.post<EscalationActionResult>(`${this.baseUrl}/escalations/${id}/acknowledge`, { version });
  }

  /** OWNER, DOCTOR. */
  respond(id: string, body: EscalationResponseInput): Observable<EscalationActionResult> {
    return this.http.post<EscalationActionResult>(`${this.baseUrl}/escalations/${id}/respond`, body);
  }

  /** NURSE. Sends the `seniorCalls` the nurse saw: a stored count that differs is a replay. */
  callSenior(id: string, body: SeniorCallInput): Observable<EscalationActionResult> {
    return this.http.post<EscalationActionResult>(`${this.baseUrl}/escalations/${id}/call-senior`, body);
  }

  /** Newest first. `limit` 14 for the first page, 50 with the `nextCursor` of the last page after that. */
  events(limit: number, cursor?: string | null): Observable<ClinicalEventPage> {
    let params = new HttpParams().set('limit', String(limit));
    if (cursor) {
      params = params.set('cursor', cursor);
    }
    return this.http.get<ClinicalEventPage>(`${this.baseUrl}/clinical-events`, { params });
  }
}
