import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { WorkflowApi } from './workflow.api';
import { DEFAULT_WORKFLOW_STEPS, WorkflowVersion } from './workflow.model';

const BASE = '/api/v1/opd/workflow';

const VERSION: WorkflowVersion = {
  versionNo: 3,
  steps: [...DEFAULT_WORKFLOW_STEPS],
  publishedAt: '2026-10-01T05:00:00.000Z',
  publishedByName: 'Dr. Meera Kulkarni',
};

describe('WorkflowApi', () => {
  let api: WorkflowApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(WorkflowApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the draft and the current version', () => {
    api.getDraft().subscribe();
    http.expectOne(`${BASE}/draft`).flush({ steps: DEFAULT_WORKFLOW_STEPS });

    api.getCurrent().subscribe();
    http.expectOne(`${BASE}/current`).flush(VERSION);
  });

  it('saves the draft with a PUT of the steps', () => {
    const steps = [...DEFAULT_WORKFLOW_STEPS];
    api.saveDraft(steps).subscribe();
    const req = http.expectOne(`${BASE}/draft`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ steps });
    req.flush({ steps });
  });

  it('lists publish history, newest first', () => {
    api.listVersions().subscribe();
    http.expectOne(`${BASE}/versions`).flush([VERSION]);
  });

  it('publishes the draft with a POST and no body', () => {
    api.publish().subscribe();
    const req = http.expectOne(`${BASE}/publish`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({ ...VERSION, versionNo: 4 });
  });
});
