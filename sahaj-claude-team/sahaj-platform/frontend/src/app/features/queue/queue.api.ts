import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import { Visit, VisitCreate, VisitStage } from './queue.model';

/** The only place that calls `/opd/visits*` (SAH-9). */
@Injectable({ providedIn: 'root' })
export class QueueApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/opd`;

  today(mine = false): Observable<Visit[]> {
    let params = new HttpParams();
    if (mine) {
      params = params.set('mine', true);
    }
    return this.http.get<Visit[]>(`${this.baseUrl}/visits/today`, { params });
  }

  create(body: VisitCreate): Observable<Visit> {
    return this.http.post<Visit>(`${this.baseUrl}/visits`, body);
  }

  moveStage(id: string, stage: VisitStage): Observable<Visit> {
    return this.http.patch<Visit>(`${this.baseUrl}/visits/${id}/stage`, { stage });
  }
}
