import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/config/api-config';
import { WorkflowStep, WorkflowSteps, WorkflowVersion } from './workflow.model';

/** The only place that calls the SAH-12 `/opd/workflow/*` endpoints. */
@Injectable({ providedIn: 'root' })
export class WorkflowApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${inject(API_BASE_URL)}/opd/workflow`;

  /** The draft, or a fresh copy of the current published version if no draft was ever saved. */
  getDraft(): Observable<WorkflowSteps> {
    return this.http.get<WorkflowSteps>(`${this.baseUrl}/draft`);
  }

  saveDraft(steps: WorkflowStep[]): Observable<WorkflowSteps> {
    return this.http.put<WorkflowSteps>(`${this.baseUrl}/draft`, { steps });
  }

  /** The current published version, lazily created with defaults if none exists. */
  getCurrent(): Observable<WorkflowVersion> {
    return this.http.get<WorkflowVersion>(`${this.baseUrl}/current`);
  }

  listVersions(): Observable<WorkflowVersion[]> {
    return this.http.get<WorkflowVersion[]>(`${this.baseUrl}/versions`);
  }

  publish(): Observable<WorkflowVersion> {
    return this.http.post<WorkflowVersion>(`${this.baseUrl}/publish`, null);
  }
}
