import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { ApiError } from '../../../core/http/problem-detail';
import { IdResult, SalesEnquiryRequest } from './sales-enquiry.model';

/** The only place that calls the sales enquiry endpoint. */
@Injectable({ providedIn: 'root' })
export class SalesEnquiryApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  /**
   * Sends the enquiry. Completes with the saved id only on a real 201.
   * Any other outcome is an ApiError, so a thank-you is never shown by mistake.
   */
  create(request: SalesEnquiryRequest): Observable<IdResult> {
    return this.http
      .post<IdResult>(`${this.baseUrl}/platform/public/sales-enquiries`, request, {
        observe: 'response',
      })
      .pipe(
        map((response) => {
          const id = response.body?.id;
          if (response.status !== 201 || typeof id !== 'string' || id.length === 0) {
            throw new ApiError({
              type: 'about:blank',
              title: 'Unexpected response from the server',
              status: response.status,
            });
          }
          return { id };
        }),
      );
  }
}
