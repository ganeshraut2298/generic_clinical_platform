import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { CardSetupView, CardSetupWrite } from './card-setup.model';

/**
 * The only place that calls the `/ipd/card-setup` endpoints of SAH-37. Every answer is the whole
 * `CardSetupView`. The owner's preview list (`GET /admissions?cardFacts=all`) is
 * `AdmissionsApi.list({ cardFacts: 'all' })`.
 */
@Injectable({ providedIn: 'root' })
export class CardSetupApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/ipd/card-setup`;

  get(): Observable<CardSetupView> {
    return this.http.get<CardSetupView>(this.baseUrl);
  }

  saveDraft(body: CardSetupWrite): Observable<CardSetupView> {
    return this.http.put<CardSetupView>(`${this.baseUrl}/draft`, body);
  }

  discardDraft(draftRevision: number): Observable<CardSetupView> {
    return this.http.delete<CardSetupView>(`${this.baseUrl}/draft`, {
      params: new HttpParams().set('draftRevision', draftRevision),
    });
  }

  publish(body: CardSetupWrite): Observable<CardSetupView> {
    return this.http.post<CardSetupView>(`${this.baseUrl}/publish`, body);
  }
}
