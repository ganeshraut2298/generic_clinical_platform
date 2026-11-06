import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { EmrSheet, MedicineOrderAnswer, MedicineOrderInput } from './emr.model';

/**
 * The only place that calls the SAH-31 EMR endpoints (`/ipd/admissions/{id}/emr`,
 * `/ipd/admissions/{id}/medicine-orders`, `/ipd/medicine-orders/{id}/stop`). Answers hold the
 * patient's name and allergy text: nothing here is logged or stored.
 */
@Injectable({ providedIn: 'root' })
export class EmrApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/ipd`;

  /** OWNER, DOCTOR, NURSE. Every 200 writes the entry "Opened the clinical record". */
  sheet(admissionId: string): Observable<EmrSheet> {
    return this.http.get<EmrSheet>(`${this.baseUrl}/admissions/${admissionId}/emr`);
  }

  /**
   * Check and order, and Order with this reason. 201 PLACED, or 200 with `outcome` PLACED (a repeat),
   * BLOCKED or NEEDS_REASON: the caller switches on `outcome`, not on the status code.
   */
  order(admissionId: string, body: MedicineOrderInput): Observable<MedicineOrderAnswer> {
    return this.http.post<MedicineOrderAnswer>(
      `${this.baseUrl}/admissions/${admissionId}/medicine-orders`,
      body,
    );
  }

  /** OWNER, DOCTOR. No body. 200 with the sheet now. */
  stop(orderId: string): Observable<EmrSheet> {
    return this.http.post<EmrSheet>(`${this.baseUrl}/medicine-orders/${orderId}/stop`, null);
  }
}
