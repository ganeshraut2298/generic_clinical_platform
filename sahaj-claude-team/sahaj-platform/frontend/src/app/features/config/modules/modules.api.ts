import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { ModulesView } from './modules.model';

/** The only place that calls the SAH-19 `/platform/modules` endpoints. */
@Injectable({ providedIn: 'root' })
export class ModulesApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/platform/modules`;

  get(): Observable<ModulesView> {
    return this.http.get<ModulesView>(this.baseUrl);
  }

  requestIpd(): Observable<ModulesView> {
    return this.http.post<ModulesView>(`${this.baseUrl}/ipd-request`, null);
  }

  startIpdTrial(): Observable<ModulesView> {
    return this.http.post<ModulesView>(`${this.baseUrl}/ipd-trial`, null);
  }

  setHra(on: boolean): Observable<ModulesView> {
    return this.http.put<ModulesView>(`${this.baseUrl}/hra`, { on });
  }
}
