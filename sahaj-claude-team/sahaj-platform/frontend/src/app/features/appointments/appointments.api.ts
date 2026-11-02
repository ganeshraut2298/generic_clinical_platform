import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import {
  Appointment,
  AppointmentCreate,
  AppointmentStatus,
  BookingRules,
  DoctorSlotSettings,
} from './appointments.model';

/** The only place that calls the SAH-11 `/opd/doctor-slots`, `/opd/booking-rules` and
 * `/opd/appointments*` endpoints. */
@Injectable({ providedIn: 'root' })
export class AppointmentsApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/opd`;

  getDoctorSlots(): Observable<DoctorSlotSettings[]> {
    return this.http.get<DoctorSlotSettings[]>(`${this.baseUrl}/doctor-slots`);
  }

  saveDoctorSlots(doctorMembershipId: string, settings: DoctorSlotSettings): Observable<DoctorSlotSettings> {
    return this.http.put<DoctorSlotSettings>(`${this.baseUrl}/doctor-slots/${doctorMembershipId}`, settings);
  }

  getBookingRules(): Observable<BookingRules> {
    return this.http.get<BookingRules>(`${this.baseUrl}/booking-rules`);
  }

  saveBookingRules(rules: BookingRules): Observable<BookingRules> {
    return this.http.put<BookingRules>(`${this.baseUrl}/booking-rules`, rules);
  }

  list(date: string, doctorMembershipId?: string): Observable<Appointment[]> {
    let params = new HttpParams().set('date', date);
    if (doctorMembershipId) {
      params = params.set('doctorMembershipId', doctorMembershipId);
    }
    return this.http.get<Appointment[]>(`${this.baseUrl}/appointments`, { params });
  }

  book(body: AppointmentCreate): Observable<Appointment> {
    return this.http.post<Appointment>(`${this.baseUrl}/appointments`, body);
  }

  setStatus(id: string, status: AppointmentStatus): Observable<Appointment> {
    return this.http.patch<Appointment>(`${this.baseUrl}/appointments/${id}`, { status });
  }

  createToken(id: string): Observable<Appointment> {
    return this.http.post<Appointment>(`${this.baseUrl}/appointments/${id}/token`, null);
  }
}
