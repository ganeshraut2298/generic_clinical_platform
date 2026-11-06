import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import {
  Attendance,
  AttendanceStatus,
  LeaveInput,
  LeaveView,
  Roster,
  RosterCellInput,
  StaffSummary,
} from './staff.model';

/** The only place that calls the SAH-20 `/hra` endpoints. */
@Injectable({ providedIn: 'root' })
export class StaffApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/hra`;

  summary(): Observable<StaffSummary> {
    return this.http.get<StaffSummary>(`${this.baseUrl}/summary`);
  }

  roster(): Observable<Roster> {
    return this.http.get<Roster>(`${this.baseUrl}/roster`);
  }

  setCell(cell: RosterCellInput): Observable<Roster> {
    return this.http.put<Roster>(`${this.baseUrl}/roster/cells`, cell);
  }

  publish(): Observable<Roster> {
    return this.http.post<Roster>(`${this.baseUrl}/roster/publish`, null);
  }

  attendance(): Observable<Attendance> {
    return this.http.get<Attendance>(`${this.baseUrl}/attendance`);
  }

  mark(membershipId: string, status: AttendanceStatus): Observable<Attendance> {
    return this.http.put<Attendance>(`${this.baseUrl}/attendance/${membershipId}`, { status });
  }

  leave(): Observable<LeaveView> {
    return this.http.get<LeaveView>(`${this.baseUrl}/leave`);
  }

  applyLeave(input: LeaveInput): Observable<LeaveView> {
    return this.http.post<LeaveView>(`${this.baseUrl}/leave`, input);
  }

  approveLeave(id: string): Observable<LeaveView> {
    return this.http.post<LeaveView>(`${this.baseUrl}/leave/${id}/approve`, null);
  }

  declineLeave(id: string): Observable<LeaveView> {
    return this.http.post<LeaveView>(`${this.baseUrl}/leave/${id}/decline`, null);
  }
}
