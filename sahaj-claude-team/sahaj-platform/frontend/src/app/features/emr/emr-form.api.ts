import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import {
  EmrFormDraftInput,
  EmrFormList,
  EmrFormStarterDetail,
  EmrFormStarterKey,
  EmrFormStarterSummary,
  EmrFormView,
} from './emr-phase3.model';

/**
 * The only place that calls the form builder endpoints (`/api/v1/opd/emr/forms/**` and
 * `/emr/form-starters/**`, opd.yaml 0.4.0, SAH-59). Creating a form needs an `Idempotency-Key`
 * made by the screen for one tap.
 */
@Injectable({ providedIn: 'root' })
export class EmrFormApi {
  private readonly http = inject(HttpClient);
  private readonly emr = `${inject(API_BASE_URL)}/opd/emr`;

  listStarters(): Observable<EmrFormStarterSummary[]> {
    return this.http.get<EmrFormStarterSummary[]>(`${this.emr}/form-starters`);
  }

  getStarter(key: EmrFormStarterKey): Observable<EmrFormStarterDetail> {
    return this.http.get<EmrFormStarterDetail>(`${this.emr}/form-starters/${key}`);
  }

  list(): Observable<EmrFormList> {
    return this.http.get<EmrFormList>(`${this.emr}/forms`);
  }

  create(starterKey: EmrFormStarterKey, name: string | null, idempotencyKey: string): Observable<EmrFormView> {
    return this.http.post<EmrFormView>(
      `${this.emr}/forms`,
      { starterKey, name },
      { headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }) },
    );
  }

  get(formKey: string): Observable<EmrFormView> {
    return this.http.get<EmrFormView>(`${this.emr}/forms/${formKey}`);
  }

  saveDraft(formKey: string, body: EmrFormDraftInput): Observable<EmrFormView> {
    return this.http.put<EmrFormView>(`${this.emr}/forms/${formKey}/draft`, body);
  }

  submit(formKey: string, revision: number): Observable<EmrFormView> {
    return this.http.post<EmrFormView>(`${this.emr}/forms/${formKey}/submit`, { revision });
  }

  approve(formKey: string, revision: number): Observable<EmrFormView> {
    return this.http.post<EmrFormView>(`${this.emr}/forms/${formKey}/approve`, { revision });
  }

  sendBack(formKey: string, revision: number, reason: string): Observable<EmrFormView> {
    return this.http.post<EmrFormView>(`${this.emr}/forms/${formKey}/send-back`, { revision, reason });
  }

  publish(formKey: string, revision: number): Observable<EmrFormView> {
    return this.http.post<EmrFormView>(`${this.emr}/forms/${formKey}/publish`, { revision });
  }

  retire(formKey: string, revision: number): Observable<EmrFormView> {
    return this.http.post<EmrFormView>(`${this.emr}/forms/${formKey}/retire`, { revision });
  }
}
