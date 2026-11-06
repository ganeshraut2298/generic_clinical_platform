import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { AlertRuleInput, AlertRules } from './rules.model';

/** The only place that calls the SAH-18 `/opd/alert-rules` endpoints. */
@Injectable({ providedIn: 'root' })
export class RulesApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/opd/alert-rules`;

  get(): Observable<AlertRules> {
    return this.http.get<AlertRules>(this.baseUrl);
  }

  add(rule: AlertRuleInput): Observable<AlertRules> {
    return this.http.post<AlertRules>(this.baseUrl, rule);
  }

  addSuggestion(index: number): Observable<AlertRules> {
    return this.http.post<AlertRules>(`${this.baseUrl}/suggestions/${index}`, null);
  }

  saveStarter(): Observable<AlertRules> {
    return this.http.post<AlertRules>(`${this.baseUrl}/starter`, null);
  }

  setEnabled(id: string, enabled: boolean): Observable<AlertRules> {
    return this.http.put<AlertRules>(`${this.baseUrl}/${id}`, { enabled });
  }

  remove(id: string): Observable<AlertRules> {
    return this.http.delete<AlertRules>(`${this.baseUrl}/${id}`);
  }
}
