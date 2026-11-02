import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { AuditEvent, ClinicProfile, Holiday, SetupOverview, Specialty } from './clinic.model';

/** The only place that calls the SAH-7 `/platform` endpoints (profile, holidays, specialty, overview). */
@Injectable({ providedIn: 'root' })
export class ClinicApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/platform`;

  getProfile(): Observable<ClinicProfile> {
    return this.http.get<ClinicProfile>(`${this.baseUrl}/clinic/profile`);
  }

  /** `slug` is read-only, so it is taken out of the body. */
  saveProfile(profile: ClinicProfile): Observable<ClinicProfile> {
    const body: ClinicProfile = { ...profile };
    delete body.slug;
    return this.http.put<ClinicProfile>(`${this.baseUrl}/clinic/profile`, body);
  }

  listHolidays(): Observable<Holiday[]> {
    return this.http.get<Holiday[]>(`${this.baseUrl}/clinic/holidays`);
  }

  addHoliday(holiday: Omit<Holiday, 'id'>): Observable<Holiday> {
    return this.http.post<Holiday>(`${this.baseUrl}/clinic/holidays`, holiday);
  }

  removeHoliday(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/clinic/holidays/${id}`);
  }

  listSpecialties(): Observable<Specialty[]> {
    return this.http.get<Specialty[]>(`${this.baseUrl}/specialties`);
  }

  applySpecialty(code: string): Observable<ClinicProfile> {
    return this.http.put<ClinicProfile>(`${this.baseUrl}/clinic/specialty`, { code });
  }

  getOverview(): Observable<SetupOverview> {
    return this.http.get<SetupOverview>(`${this.baseUrl}/setup/overview`);
  }

  listAuditEvents(limit = 20): Observable<AuditEvent[]> {
    return this.http.get<AuditEvent[]>(`${this.baseUrl}/audit-events`, {
      params: new HttpParams().set('limit', limit),
    });
  }
}
