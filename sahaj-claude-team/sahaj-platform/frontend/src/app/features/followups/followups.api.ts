import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import { FollowUpList, FollowUpStatus } from './followups.model';

/** The only place that calls the SAH-14 `GET /opd/follow-ups` endpoint. */
@Injectable({ providedIn: 'root' })
export class FollowUpsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/opd`;

  list(status: FollowUpStatus): Observable<FollowUpList> {
    const params = new HttpParams().set('status', status);
    return this.http.get<FollowUpList>(`${this.baseUrl}/follow-ups`, { params });
  }
}
