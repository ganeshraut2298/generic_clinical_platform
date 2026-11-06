import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { CategoryKey, IpdCharges, IpdLayout } from './ipd.model';

/** The only place that calls the SAH-22 `/ipd` endpoints. Every call returns the whole layout. */
@Injectable({ providedIn: 'root' })
export class IpdApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/ipd`;

  layout(): Observable<IpdLayout> {
    return this.http.get<IpdLayout>(`${this.baseUrl}/layout`);
  }

  addWard(name: string): Observable<IpdLayout> {
    return this.http.post<IpdLayout>(`${this.baseUrl}/wards`, { name });
  }

  addRoom(wardId: string): Observable<IpdLayout> {
    return this.http.post<IpdLayout>(`${this.baseUrl}/wards/${wardId}/rooms`, null);
  }

  addBed(wardId: string, roomId?: string): Observable<IpdLayout> {
    return this.http.post<IpdLayout>(`${this.baseUrl}/beds`, roomId ? { wardId, roomId } : { wardId });
  }

  setBedCategory(bedId: string, categoryKey: CategoryKey): Observable<IpdLayout> {
    return this.http.put<IpdLayout>(`${this.baseUrl}/beds/${bedId}`, { categoryKey });
  }

  removeBed(bedId: string): Observable<IpdLayout> {
    return this.http.delete<IpdLayout>(`${this.baseUrl}/beds/${bedId}`);
  }

  setRoomLevel(on: boolean): Observable<IpdLayout> {
    return this.http.put<IpdLayout>(`${this.baseUrl}/room-level`, { on });
  }

  setCategory(key: CategoryKey, ratePaise: number, multiplier: number): Observable<IpdLayout> {
    return this.http.put<IpdLayout>(`${this.baseUrl}/categories/${key}`, { ratePaise, multiplier });
  }

  setCharges(charges: IpdCharges): Observable<IpdLayout> {
    return this.http.put<IpdLayout>(`${this.baseUrl}/charges`, charges);
  }
}
