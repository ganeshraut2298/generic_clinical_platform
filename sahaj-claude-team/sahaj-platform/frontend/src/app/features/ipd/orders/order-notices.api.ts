import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { OrderNoticeList, OrderNoticePage, OrderNoticeSummary } from './order.model';

/**
 * The only place that calls the SAH-76-3 order notice endpoints (`/ipd/admissions/{id}/order-notices`,
 * `/ipd/order-notices`, `/ipd/order-notices/summary`, `/ipd/order-notices/{id}/acknowledge`). Answers hold
 * patient names and medicines (the summary holds counts only). Nothing here is logged or stored.
 */
@Injectable({ providedIn: 'root' })
export class OrderNoticesApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/ipd`;

  /** OWNER, NURSE, DOCTOR: the Orders tab of the Nursing sheet. */
  forAdmission(admissionId: string): Observable<OrderNoticeList> {
    return this.http.get<OrderNoticeList>(`${this.baseUrl}/admissions/${admissionId}/order-notices`);
  }

  /** NURSE, OWNER: the panel "Orders to acknowledge" of the Safety tab. */
  page(limit: number, cursor?: string | null): Observable<OrderNoticePage> {
    let params = new HttpParams().set('limit', String(limit));
    if (cursor) {
      params = params.set('cursor', cursor);
    }
    return this.http.get<OrderNoticePage>(`${this.baseUrl}/order-notices`, { params });
  }

  /** Counts only, polled every 30 seconds while a nursing screen is shown. */
  summary(): Observable<OrderNoticeSummary> {
    return this.http.get<OrderNoticeSummary>(`${this.baseUrl}/order-notices/summary`);
  }

  /** NURSE, OWNER. No body. 200 with the patient's list now (also for a repeat by the same user). */
  acknowledge(noticeId: string): Observable<OrderNoticeList> {
    return this.http.post<OrderNoticeList>(`${this.baseUrl}/order-notices/${noticeId}/acknowledge`, null);
  }
}
