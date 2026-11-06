import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { NursingSheet, VitalsInput, VitalsSaved } from './nursing.model';

/** The only place that calls the SAH-30 endpoints (`/ipd/admissions/{id}/nursing` and `/vitals`). */
@Injectable({ providedIn: 'root' })
export class NursingApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/ipd`;

  /** OWNER, NURSE, DOCTOR. Allowed in Read-only state and for a discharged admission. */
  sheet(admissionId: string): Observable<NursingSheet> {
    return this.http.get<NursingSheet>(`${this.baseUrl}/admissions/${admissionId}/nursing`);
  }

  /** OWNER, NURSE. 201 when saved, 200 when the clientKey was already used (nothing written). */
  record(admissionId: string, body: VitalsInput): Observable<VitalsSaved> {
    return this.http.post<VitalsSaved>(`${this.baseUrl}/admissions/${admissionId}/vitals`, body);
  }
}
