import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import {
  CasePaperSettings,
  FeeSettings,
  MedicinePage,
  Service,
  ServiceUpdate,
} from './opd.model';

/** The only place that calls the SAH-7 `/opd` endpoints (services, fees, medicines, case paper). */
@Injectable({ providedIn: 'root' })
export class OpdApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/opd`;

  listServices(): Observable<Service[]> {
    return this.http.get<Service[]>(`${this.baseUrl}/services`);
  }

  addService(service: Service): Observable<Service> {
    return this.http.post<Service>(`${this.baseUrl}/services`, service);
  }

  updateService(id: string, patch: ServiceUpdate): Observable<Service> {
    return this.http.patch<Service>(`${this.baseUrl}/services/${id}`, patch);
  }

  getFeeSettings(): Observable<FeeSettings> {
    return this.http.get<FeeSettings>(`${this.baseUrl}/fee-settings`);
  }

  saveFeeSettings(settings: FeeSettings): Observable<FeeSettings> {
    return this.http.put<FeeSettings>(`${this.baseUrl}/fee-settings`, settings);
  }

  searchMedicines(query: string, page: number, favouritesOnly: boolean): Observable<MedicinePage> {
    let params = new HttpParams().set('page', page);
    if (query.trim()) {
      params = params.set('q', query.trim());
    }
    if (favouritesOnly) {
      params = params.set('favouritesOnly', true);
    }
    return this.http.get<MedicinePage>(`${this.baseUrl}/medicines`, { params });
  }

  markFavourite(medicineId: string): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/medicine-favourites/${medicineId}`, null);
  }

  unmarkFavourite(medicineId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/medicine-favourites/${medicineId}`);
  }

  getCasePaperSettings(): Observable<CasePaperSettings> {
    return this.http.get<CasePaperSettings>(`${this.baseUrl}/case-paper-settings`);
  }

  saveCasePaperSettings(settings: CasePaperSettings): Observable<CasePaperSettings> {
    return this.http.put<CasePaperSettings>(`${this.baseUrl}/case-paper-settings`, settings);
  }
}
