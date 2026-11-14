import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import {
  ChemoCheckPage,
  ChemoCheckSummary,
  ChemoCheckerInput,
  ChemoCheckerOption,
  ChemoHoldInput,
  ChemoOrderActionInput,
  ChemoOrderInput,
  ChemoOrderView,
  CancerJourney,
  CancerJourneyInput,
  ChemoSecondCheckPinInput,
  EmrSpecialtyHistory,
  EmrSpecialtyProfile,
  EmrSpecialtyReference,
  EmrVisitTemplate,
  EmrVisitTemplateInput,
  SurgeryBookingResult,
} from './specialty.model';

/**
 * The only place that calls the phase 4 endpoints of `docs/api/opd.yaml` 0.5.1 (ADR-023): the
 * specialty reference and history, the OT booking, the chemotherapy order with its second check,
 * the checker's own list, and the visit templates. The record itself still goes through `EmrApi`.
 * Nothing sent or received here is logged; the PIN is sent once and never kept.
 */
@Injectable({ providedIn: 'root' })
export class SpecialtyApi {
  private readonly http = inject(HttpClient);
  private readonly emr = `${inject(API_BASE_URL)}/opd/emr`;

  getReference(profile: EmrSpecialtyProfile): Observable<EmrSpecialtyReference> {
    return this.http.get<EmrSpecialtyReference>(`${this.emr}/specialty-reference/${profile}`);
  }

  getHistory(recordId: string): Observable<EmrSpecialtyHistory> {
    return this.http.get<EmrSpecialtyHistory>(`${this.emr}/records/${recordId}/specialty-history`);
  }

  /** SAH-64-3. One key per action, so a second tap returns the first result. */
  bookSurgery(recordId: string, revision: number, idempotencyKey: string): Observable<SurgeryBookingResult> {
    return this.http.post<SurgeryBookingResult>(
      `${this.emr}/records/${recordId}/surgery-booking`,
      { revision },
      { headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }) },
    );
  }

  /** SAH-75-7: appends a version of the patient's Cancer journey (409 journey-changed when the version read is stale). */
  saveCancerJourney(recordId: string, body: CancerJourneyInput): Observable<CancerJourney> {
    return this.http.put<CancerJourney>(`${this.emr}/records/${recordId}/cancer-journey`, body);
  }

  // ---- chemotherapy order (SAH-66) ----

  getChemoOrder(recordId: string): Observable<ChemoOrderView> {
    return this.http.get<ChemoOrderView>(`${this.emr}/records/${recordId}/chemo-order`);
  }

  saveChemoOrder(recordId: string, body: ChemoOrderInput): Observable<ChemoOrderView> {
    return this.http.put<ChemoOrderView>(`${this.emr}/records/${recordId}/chemo-order`, body);
  }

  listChemoCheckerOptions(recordId: string): Observable<ChemoCheckerOption[]> {
    return this.http.get<ChemoCheckerOption[]>(`${this.emr}/records/${recordId}/chemo-order/checker-options`);
  }

  nameChemoChecker(recordId: string, body: ChemoCheckerInput): Observable<ChemoOrderView> {
    return this.http.put<ChemoOrderView>(`${this.emr}/records/${recordId}/chemo-order/checker`, body);
  }

  /** Way (b): the named checker's own colleague PIN, at the prescriber's device. */
  confirmChemoSecondCheckByPin(recordId: string, body: ChemoSecondCheckPinInput): Observable<ChemoOrderView> {
    return this.http.post<ChemoOrderView>(`${this.emr}/records/${recordId}/chemo-order/second-check`, body);
  }

  sendChemoOrder(recordId: string, body: ChemoOrderActionInput): Observable<ChemoOrderView> {
    return this.http.post<ChemoOrderView>(`${this.emr}/records/${recordId}/chemo-order/send`, body);
  }

  recordChemoHold(recordId: string, body: ChemoHoldInput): Observable<ChemoOrderView> {
    return this.http.post<ChemoOrderView>(`${this.emr}/records/${recordId}/chemo-order/hold`, body);
  }

  // ---- the named checker's own sign-in way (SAH-66-3, way a) ----

  listMyChemoChecks(page = 0): Observable<ChemoCheckPage> {
    let params = new HttpParams();
    if (page > 0) {
      params = params.set('page', page);
    }
    return this.http.get<ChemoCheckPage>(`${this.emr}/chemo-checks`, { params });
  }

  getChemoCheck(orderId: string): Observable<ChemoCheckSummary> {
    return this.http.get<ChemoCheckSummary>(`${this.emr}/chemo-checks/${orderId}`);
  }

  confirmChemoCheck(orderId: string, fingerprint: string): Observable<ChemoCheckSummary> {
    return this.http.post<ChemoCheckSummary>(`${this.emr}/chemo-checks/${orderId}/confirm`, { fingerprint });
  }

  // ---- visit templates (SAH-60-4) ----

  listVisitTemplates(): Observable<EmrVisitTemplate[]> {
    return this.http.get<EmrVisitTemplate[]>(`${this.emr}/visit-templates`);
  }

  createVisitTemplate(body: EmrVisitTemplateInput): Observable<EmrVisitTemplate> {
    return this.http.post<EmrVisitTemplate>(`${this.emr}/visit-templates`, body);
  }
}
