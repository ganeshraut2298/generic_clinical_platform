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
  CourseSittingChangeInput,
  CourseSittingLinkVisitInput,
  TreatmentCourseCreateInput,
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

  listForPatient(patientId: string): Observable<TreatmentCourseSummary[]> {
    return this.http.get<TreatmentCourseSummary[]>(this.opd, { params: new HttpParams().set('patientId', patientId) });
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
