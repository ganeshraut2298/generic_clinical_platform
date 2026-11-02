import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import {
  Bill,
  BillingCounter,
  DayClosing,
  DayClosingCreate,
  PaymentCreate,
  RefundCreate,
} from './billing.model';

/** The only place that calls the SAH-10 `/billing` endpoints. */
@Injectable({ providedIn: 'root' })
export class BillingApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/billing`;

  counter(): Observable<BillingCounter> {
    return this.http.get<BillingCounter>(`${this.baseUrl}/counter`);
  }

  getBill(visitId: string): Observable<Bill> {
    return this.http.get<Bill>(`${this.baseUrl}/bills/${visitId}`);
  }

  addItem(visitId: string, serviceId: string, qty: number): Observable<Bill> {
    return this.http.post<Bill>(`${this.baseUrl}/bills/${visitId}/items`, { serviceId, qty });
  }

  removeItem(visitId: string, itemId: string): Observable<Bill> {
    return this.http.delete<Bill>(`${this.baseUrl}/bills/${visitId}/items/${itemId}`);
  }

  setDiscount(visitId: string, percent: number, reason: string | null): Observable<Bill> {
    return this.http.put<Bill>(`${this.baseUrl}/bills/${visitId}/discount`, { percent, reason });
  }

  collectPayment(visitId: string, payment: PaymentCreate): Observable<Bill> {
    return this.http.post<Bill>(`${this.baseUrl}/bills/${visitId}/payments`, payment);
  }

  refund(visitId: string, refund: RefundCreate): Observable<Bill> {
    return this.http.post<Bill>(`${this.baseUrl}/bills/${visitId}/refund`, refund);
  }

  closeDay(body: DayClosingCreate): Observable<DayClosing> {
    return this.http.post<DayClosing>(`${this.baseUrl}/day-closing`, body);
  }
}
