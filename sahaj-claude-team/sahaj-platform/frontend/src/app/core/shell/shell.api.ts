import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../config/api-config';
import { Me, Preferences } from './shell.model';

/** The only place that calls `/me` and `/me/preferences`. */
@Injectable({ providedIn: 'root' })
export class ShellApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/platform/me`;

  getMe(): Observable<Me> {
    return this.http.get<Me>(this.baseUrl);
  }

  updatePreferences(body: Preferences): Observable<Me> {
    return this.http.patch<Me>(`${this.baseUrl}/preferences`, body);
  }
}
