import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { BillingTodaySummary, TodaySummary } from './today.model';

/** The only place that calls the SAH-15 `today-summary` endpoints. */
@Injectable({ providedIn: 'root' })
export class TodayApi {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  opd(): Observable<TodaySummary> {
    return this.http.get<TodaySummary>(`${this.base}/opd/today-summary`);
  }

  billing(): Observable<BillingTodaySummary> {
    return this.http.get<BillingTodaySummary>(`${this.base}/billing/today-summary`);
  }
}
