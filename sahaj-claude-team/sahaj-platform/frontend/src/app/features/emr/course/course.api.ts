import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import {
  CourseAccount,
  CoursePaymentCreate,
  CoursePaymentResult,
  CourseReceipt,
  CourseSittingAddInput,
  CourseSittingAdmissionRequestInput,
  CourseSittingDateInput,
  CourseSittingMarkDoneInput,
  CourseSittingRevisionInput,
  CourseSittingChangeInput,
  CourseSittingLinkVisitInput,
  TreatmentCourseCreateInput,
  TreatmentCourseKind,
  TreatmentCourseSummary,
  TreatmentCourseView,
} from './course.model';

const keyHeader = (key: string): HttpHeaders => new HttpHeaders({ 'Idempotency-Key': key });

/**
 * The only place that calls the treatment course endpoints: the plan in opd
 * (`/api/v1/opd/emr/courses/**`, opd.yaml 0.4.0) and the money in billing
 * (`/api/v1/billing/courses/**`, billing.yaml 0.2.0). SAH-58. Every write that can be tapped twice
 * carries an `Idempotency-Key` made for that tap, so a retry never records a second payment.
 */
@Injectable({ providedIn: 'root' })
export class CourseApi {
  private readonly http = inject(HttpClient);
  private readonly opd = `${inject(API_BASE_URL)}/opd/emr/courses`;
  private readonly billing = `${inject(API_BASE_URL)}/billing/courses`;

  /** The patient's active courses, newest first; `kind` (0.7.0) keeps only the courses of one department's kind. */
  listForPatient(patientId: string, kind?: TreatmentCourseKind): Observable<TreatmentCourseSummary[]> {
    let params = new HttpParams().set('patientId', patientId);
    if (kind) {
      params = params.set('kind', kind);
    }
    return this.http.get<TreatmentCourseSummary[]>(this.opd, { params });
  }

  create(body: TreatmentCourseCreateInput, idempotencyKey: string): Observable<TreatmentCourseView> {
    return this.http.post<TreatmentCourseView>(this.opd, body, { headers: keyHeader(idempotencyKey) });
  }

  get(courseId: string): Observable<TreatmentCourseView> {
    return this.http.get<TreatmentCourseView>(`${this.opd}/${courseId}`);
  }

  addSitting(courseId: string, body: CourseSittingAddInput, idempotencyKey: string): Observable<TreatmentCourseView> {
    return this.http.post<TreatmentCourseView>(`${this.opd}/${courseId}/sittings`, body, {
      headers: keyHeader(idempotencyKey),
    });
  }

  changeSitting(courseId: string, sittingId: string, body: CourseSittingChangeInput): Observable<TreatmentCourseView> {
    return this.http.put<TreatmentCourseView>(`${this.opd}/${courseId}/sittings/${sittingId}`, body);
  }

  linkVisit(courseId: string, sittingId: string, body: CourseSittingLinkVisitInput): Observable<TreatmentCourseView> {
    return this.http.post<TreatmentCourseView>(`${this.opd}/${courseId}/sittings/${sittingId}/link-visit`, body);
  }

  // ---- 0.7.0 (SAH-75-10): one call per drawn action; each answers the whole course ----

  private sitting(courseId: string, sittingId: string, action: string): string {
    return `${this.opd}/${courseId}/sittings/${sittingId}/${action}`;
  }

  markDone(courseId: string, sittingId: string, body: CourseSittingMarkDoneInput): Observable<TreatmentCourseView> {
    return this.http.post<TreatmentCourseView>(this.sitting(courseId, sittingId, 'mark-done'), body);
  }

  markMissed(courseId: string, sittingId: string, body: CourseSittingRevisionInput): Observable<TreatmentCourseView> {
    return this.http.post<TreatmentCourseView>(this.sitting(courseId, sittingId, 'missed'), body);
  }

  rebook(courseId: string, sittingId: string, body: CourseSittingDateInput): Observable<TreatmentCourseView> {
    return this.http.post<TreatmentCourseView>(this.sitting(courseId, sittingId, 'rebook'), body);
  }

  reschedule(courseId: string, sittingId: string, body: CourseSittingDateInput): Observable<TreatmentCourseView> {
    return this.http.post<TreatmentCourseView>(this.sitting(courseId, sittingId, 'reschedule'), body);
  }

  remove(courseId: string, sittingId: string, body: CourseSittingRevisionInput): Observable<TreatmentCourseView> {
    return this.http.post<TreatmentCourseView>(this.sitting(courseId, sittingId, 'remove'), body);
  }

  requestAdmission(courseId: string, sittingId: string, body: CourseSittingAdmissionRequestInput): Observable<TreatmentCourseView> {
    return this.http.post<TreatmentCourseView>(this.sitting(courseId, sittingId, 'admission-request'), body);
  }

  account(courseId: string): Observable<CourseAccount> {
    return this.http.get<CourseAccount>(`${this.billing}/${courseId}/account`);
  }

  collect(courseId: string, body: CoursePaymentCreate, idempotencyKey: string): Observable<CoursePaymentResult> {
    return this.http.post<CoursePaymentResult>(`${this.billing}/${courseId}/payments`, body, {
      headers: keyHeader(idempotencyKey),
    });
  }

  receipt(courseId: string, receiptNo: number): Observable<CourseReceipt> {
    return this.http.get<CourseReceipt>(`${this.billing}/${courseId}/receipts/${receiptNo}`);
  }
}
