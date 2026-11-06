import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import {
  EmergencyCaseInput,
  EmergencyCounts,
  EmergencyQueue,
  EmergencySettings,
  EmergencySettingsInput,
  TriageLevel,
} from './emergency.model';

/**
 * The only place that calls the SAH-28 `/ipd/emergency*` endpoints. Every queue call answers the
 * whole `EmergencyQueue`; the register call answers 201 (new) or 200 (repeat of a client key) with
 * the same body, so both are one result here.
 */
@Injectable({ providedIn: 'root' })
export class EmergencyApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/ipd`;

  queue(): Observable<EmergencyQueue> {
    return this.http.get<EmergencyQueue>(`${this.baseUrl}/emergency`);
  }

  /** Counts only, for the menu badge. */
  summary(): Observable<EmergencyCounts> {
    return this.http.get<EmergencyCounts>(`${this.baseUrl}/emergency/summary`);
  }

  /** `actedCase` is an `EmergencyCaseRef` (no name) in a 200 repeat when the case is closed or not the caller's. */
  register(body: EmergencyCaseInput): Observable<EmergencyQueue> {
    return this.http.post<EmergencyQueue>(`${this.baseUrl}/emergency/cases`, body);
  }

  seen(id: string): Observable<EmergencyQueue> {
    return this.http.post<EmergencyQueue>(`${this.baseUrl}/emergency/cases/${id}/seen`, null);
  }

  setLevel(id: string, level: TriageLevel): Observable<EmergencyQueue> {
    return this.http.put<EmergencyQueue>(`${this.baseUrl}/emergency/cases/${id}/level`, { level });
  }

  release(id: string): Observable<EmergencyQueue> {
    return this.http.post<EmergencyQueue>(`${this.baseUrl}/emergency/cases/${id}/release`, null);
  }

  refer(id: string): Observable<EmergencyQueue> {
    return this.http.post<EmergencyQueue>(`${this.baseUrl}/emergency/cases/${id}/refer`, null);
  }

  settings(): Observable<EmergencySettings> {
    return this.http.get<EmergencySettings>(`${this.baseUrl}/emergency-settings`);
  }

  /** Sends the whole setting each time. */
  saveSettings(body: EmergencySettingsInput): Observable<EmergencySettings> {
    return this.http.put<EmergencySettings>(`${this.baseUrl}/emergency-settings`, body);
  }
}
