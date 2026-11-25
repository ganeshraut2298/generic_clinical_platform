import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { Visit, VisitStage } from '../../queue/queue.model';
import {
  DeskAppointment,
  DeskBookInput,
  DeskCheckIn,
  DeskDaySlots,
  DeskFollowUpBookInput,
  DeskFollowUpResult,
  DeskRescheduleInput,
  DeskSnapshot,
  DeskTokenPanel,
  DeskVisitFollowUpInput,
} from './desk.model';

/** The only place that calls the SAH-90 to SAH-93 `/opd` desk reads and writes (docs/api/opd.yaml 0.9.0). */
@Injectable({ providedIn: 'root' })
export class DeskApi {
  private readonly http = inject(HttpClient);
  private readonly opd = `${inject(API_BASE_URL)}/opd`;
  private readonly baseUrl = `${this.opd}/desk`;

  /** One polled read. `doctorMembershipId` and `date` add that doctor's open slots to `selected`. */
  getSnapshot(options: { doctorMembershipId?: string | null; date?: string | null } = {}): Observable<DeskSnapshot> {
    let params = new HttpParams();
    if (options.doctorMembershipId) {
      params = params.set('doctorMembershipId', options.doctorMembershipId);
      if (options.date) {
        params = params.set('date', options.date);
      }
    }
    return this.http.get<DeskSnapshot>(`${this.baseUrl}/snapshot`, { params });
  }

  getDaySlots(doctorMembershipId: string, date: string): Observable<DeskDaySlots> {
    const params = new HttpParams().set('date', date);
    return this.http.get<DeskDaySlots>(`${this.baseUrl}/doctors/${doctorMembershipId}/day-slots`, { params });
  }

  getTokenPanel(visitId: string): Observable<DeskTokenPanel> {
    return this.http.get<DeskTokenPanel>(`${this.baseUrl}/visits/${visitId}/token-panel`);
  }

  /** SAH-91: book one appointment (`POST /opd/appointments`). */
  book(body: DeskBookInput): Observable<DeskAppointment> {
    return this.http.post<DeskAppointment>(`${this.opd}/appointments`, body);
  }

  /** SAH-91-3: move the same appointment to a new doctor, date and time, with the version the board showed. */
  reschedule(id: string, body: DeskRescheduleInput): Observable<DeskAppointment> {
    return this.http.post<DeskAppointment>(`${this.opd}/appointments/${id}/reschedule`, body);
  }

  /** SAH-92-1: "Check in · token". The answer carries the token panel. */
  checkIn(appointmentId: string): Observable<DeskCheckIn> {
    return this.http.post<DeskCheckIn>(`${this.opd}/appointments/${appointmentId}/token`, null);
  }

  /** SAH-92-4: store a no-show by hand. */
  markNoShow(appointmentId: string): Observable<DeskAppointment> {
    return this.http.patch<DeskAppointment>(`${this.opd}/appointments/${appointmentId}`, { status: 'NOSHOW' });
  }

  /** SAH-92-3: one step forward, compare-and-set on the stage the card showed. */
  moveStage(visitId: string, stage: VisitStage, expectedStage: VisitStage): Observable<Visit> {
    return this.http.patch<Visit>(`${this.opd}/visits/${visitId}/stage`, { stage, expectedStage });
  }

  /** SAH-93-1: book the follow-up of a completed visit. */
  bookVisitFollowUp(visitId: string, body: DeskVisitFollowUpInput): Observable<DeskFollowUpResult> {
    return this.http.post<DeskFollowUpResult>(`${this.opd}/visits/${visitId}/follow-up/appointment`, body);
  }

  /** SAH-93-1: "No follow-up needed". */
  markFollowUpNotNeeded(visitId: string): Observable<DeskFollowUpResult> {
    return this.http.post<DeskFollowUpResult>(`${this.opd}/visits/${visitId}/follow-up/not-needed`, null);
  }

  /** SAH-93-2: book a follow-up from the idle list, linked to its record. */
  bookFollowUp(followUpId: string, body: DeskFollowUpBookInput): Observable<DeskFollowUpResult> {
    return this.http.post<DeskFollowUpResult>(`${this.opd}/follow-ups/${followUpId}/appointment`, body);
  }
}
