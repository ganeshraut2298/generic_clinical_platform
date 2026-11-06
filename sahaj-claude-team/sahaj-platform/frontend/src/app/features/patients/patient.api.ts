import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import { ConsentInput, PatientConsentState, RecordAccess } from '../privacy/privacy.model';
import { Patient, PatientCreate, PatientFilter, PatientPage, PatientSummary, PatientUpdate } from './patient.model';

/** The only place that calls `/patient/patients` (SAH-8). */
@Injectable({ providedIn: 'root' })
export class PatientApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/patient`;

  list(options: { q?: string; filter?: PatientFilter; page?: number } = {}): Observable<PatientPage> {
    let params = new HttpParams();
    if (options.q) {
      params = params.set('q', options.q);
    }
    if (options.filter) {
      params = params.set('filter', options.filter);
    }
    if (options.page != null) {
      params = params.set('page', options.page);
    }
    return this.http.get<PatientPage>(`${this.baseUrl}/patients`, { params });
  }

  create(body: PatientCreate): Observable<Patient> {
    return this.http.post<Patient>(`${this.baseUrl}/patients`, body);
  }

  get(id: string): Observable<Patient> {
    return this.http.get<Patient>(`${this.baseUrl}/patients/${id}`);
  }

  update(id: string, body: PatientUpdate): Observable<Patient> {
    return this.http.patch<Patient>(`${this.baseUrl}/patients/${id}`, body);
  }

  family(id: string): Observable<PatientSummary[]> {
    return this.http.get<PatientSummary[]>(`${this.baseUrl}/patients/${id}/family`);
  }

  merge(keepId: string, mergeId: string): Observable<Patient> {
    return this.http.post<Patient>(`${this.baseUrl}/patients/merge`, { keepId, mergeId });
  }

  getConsents(id: string): Observable<PatientConsentState> {
    return this.http.get<PatientConsentState>(`${this.baseUrl}/patients/${id}/consents`);
  }

  recordConsent(id: string, body: ConsentInput): Observable<PatientConsentState> {
    return this.http.post<PatientConsentState>(`${this.baseUrl}/patients/${id}/consents`, body);
  }

  /** Owner only. */
  recordAccess(limit = 5): Observable<RecordAccess[]> {
    return this.http.get<RecordAccess[]>(`${this.baseUrl}/record-access`, {
      params: new HttpParams().set('limit', limit),
    });
  }
}
