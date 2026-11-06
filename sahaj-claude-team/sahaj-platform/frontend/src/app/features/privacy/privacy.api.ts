import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import { PrivacySettings } from './privacy.model';

/** The only place that calls `/platform/privacy` (SAH-17). */
@Injectable({ providedIn: 'root' })
export class PrivacyApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  get(): Observable<PrivacySettings> {
    return this.http.get<PrivacySettings>(`${this.baseUrl}/platform/privacy`);
  }

  save(settings: PrivacySettings): Observable<PrivacySettings> {
    return this.http.put<PrivacySettings>(`${this.baseUrl}/platform/privacy`, settings);
  }
}
