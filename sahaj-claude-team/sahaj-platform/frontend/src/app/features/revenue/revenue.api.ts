import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import { RevenueRange, RevenueReport } from './revenue.model';

/** The only place that calls the SAH-16 `GET /billing/revenue` endpoint. */
@Injectable({ providedIn: 'root' })
export class RevenueApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/billing`;

  report(range: RevenueRange): Observable<RevenueReport> {
    const params = new HttpParams().set('range', range);
    return this.http.get<RevenueReport>(`${this.baseUrl}/revenue`, { params });
  }
}
