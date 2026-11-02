import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import {
  SetupCompleteResult,
  SetupState,
  SetupStepBody,
  SlugAvailability,
} from './setup.model';

/** The only place that calls `/setup/*` (SAH-4). */
@Injectable({ providedIn: 'root' })
export class SetupApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/platform/setup`;

  getState(): Observable<SetupState> {
    return this.http.get<SetupState>(this.baseUrl);
  }

  saveStep(step: number, body: SetupStepBody): Observable<SetupState> {
    return this.http.put<SetupState>(`${this.baseUrl}/steps/${step}`, body);
  }

  checkSlugAvailability(slug: string): Observable<SlugAvailability> {
    const params = new HttpParams().set('slug', slug);
    return this.http.get<SlugAvailability>(`${this.baseUrl}/slug-availability`, { params });
  }

  complete(): Observable<SetupCompleteResult> {
    return this.http.post<SetupCompleteResult>(`${this.baseUrl}/complete`, null);
  }
}
