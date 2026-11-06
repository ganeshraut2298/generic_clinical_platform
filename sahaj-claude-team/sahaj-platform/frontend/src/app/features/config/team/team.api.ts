import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../../core/config/api-config';
import { RolePermission, TeamMember, TeamMemberCreate, TeamMemberUpdate } from './team.model';

/** The only place that calls `/team` and `/role-permissions` (SAH-6). */
@Injectable({ providedIn: 'root' })
export class TeamApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/platform`;

  list(): Observable<TeamMember[]> {
    return this.http.get<TeamMember[]>(`${this.baseUrl}/team`);
  }

  add(body: TeamMemberCreate): Observable<TeamMember> {
    return this.http.post<TeamMember>(`${this.baseUrl}/team`, body);
  }

  update(id: string, body: TeamMemberUpdate): Observable<TeamMember> {
    return this.http.patch<TeamMember>(`${this.baseUrl}/team/${id}`, body);
  }

  deactivate(id: string): Observable<TeamMember> {
    return this.http.post<TeamMember>(`${this.baseUrl}/team/${id}/deactivate`, null);
  }

  reactivate(id: string): Observable<TeamMember> {
    return this.http.post<TeamMember>(`${this.baseUrl}/team/${id}/reactivate`, null);
  }

  /** SAH-31, OWNER only. Clears the PIN (the owner never sees or sets one). 200 with the member. */
  resetPin(id: string): Observable<TeamMember> {
    return this.http.post<TeamMember>(`${this.baseUrl}/team/${id}/pin/reset`, null);
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/team/${id}`);
  }

  getRolePermissions(): Observable<RolePermission[]> {
    return this.http.get<RolePermission[]>(`${this.baseUrl}/role-permissions`);
  }

  saveRolePermissions(matrix: RolePermission[]): Observable<RolePermission[]> {
    return this.http.put<RolePermission[]>(`${this.baseUrl}/role-permissions`, matrix);
  }
}
