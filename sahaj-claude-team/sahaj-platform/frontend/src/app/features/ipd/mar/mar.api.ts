import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { CorrectionInput, DoseInput, DoseRecorded, Mar } from './mar.model';

/**
 * The only place that calls the SAH-32 and SAH-76 endpoints (`/ipd/admissions/{id}/mar`,
 * `/ipd/medicine-orders/{id}/doses`, `/ipd/doses/{id}/undo` and `/ipd/doses/{id}/recorded-in-error`). Answers hold the patient's name and allergy text, and the
 * request holds the scanned wristband text and the second nurse's PIN: nothing here is logged or stored.
 */
@Injectable({ providedIn: 'root' })
export class MarApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/ipd`;

  /** OWNER, NURSE, DOCTOR. Allowed in Read-only state and for a discharged admission. */
  mar(admissionId: string): Observable<Mar> {
    return this.http.get<Mar>(`${this.baseUrl}/admissions/${admissionId}/mar`);
  }

  /**
   * Given and Record not given. OWNER, NURSE. 201 when recorded, 200 when the clientKey was already
   * used with the same values (`replayed`); both carry the MAR now.
   */
  give(orderId: string, body: DoseInput): Observable<DoseRecorded> {
    return this.http.post<DoseRecorded>(`${this.baseUrl}/medicine-orders/${orderId}/doses`, body);
  }

  /**
   * Undo a Given within the undo window (SAH-76-5). The nurse who recorded it, or the owner. 200 with
   * the corrected dose and the MAR now; also 200 for a repeat of the same clientKey.
   */
  undo(doseId: string, body: CorrectionInput): Observable<DoseRecorded> {
    return this.http.post<DoseRecorded>(`${this.baseUrl}/doses/${doseId}/undo`, body);
  }

  /** Mark a given dose as recorded in error (SAH-76-5). NURSE, OWNER; body carries the reason key. */
  recordedInError(doseId: string, body: CorrectionInput): Observable<DoseRecorded> {
    return this.http.post<DoseRecorded>(`${this.baseUrl}/doses/${doseId}/recorded-in-error`, body);
  }
}
