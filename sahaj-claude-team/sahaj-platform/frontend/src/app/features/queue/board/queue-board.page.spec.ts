import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { DEFAULT_WORKFLOW_STEPS, WorkflowStep } from '../../workflow/workflow.model';
import { Visit } from '../queue.model';
import { QueueBoardPage } from './queue-board.page';

const BASE = '/api/v1/opd';

const WAITING: Visit = {
  id: 'v1',
  tokenNo: 1,
  patientId: 'p1',
  patientName: 'Rohit Patil',
  age: 30,
  sex: 'M',
  doctorMembershipId: 'd1',
  doctorName: 'Dr. Rao',
  visitType: 'NEW',
  stage: 'WAITING',
  feePaise: 50000,
  createdAt: new Date().toISOString(),
};

const WITH_DOCTOR: Visit = {
  ...WAITING,
  id: 'v2',
  tokenNo: 2,
  patientName: 'Seema Kadam',
  stage: 'WITH_DOCTOR',
};

describe('QueueBoardPage', () => {
  let fixture: ComponentFixture<QueueBoardPage>;
  let http: HttpTestingController;

  async function open(
    role: Role,
    visits: Visit[] = [WAITING, WITH_DOCTOR],
    steps: readonly WorkflowStep[] = DEFAULT_WORKFLOW_STEPS,
  ) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(QueueBoardPage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/visits/today`).flush(visits);
    http.expectOne(`${BASE}/workflow/current`).flush({ versionNo: 2, steps, publishedAt: '2026-11-01T05:00:00Z' });
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  const labelsOf = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('.col-h .pill')).map((p) => p.textContent!.trim());

  it('builds columns from the published steps, Vitals included by default', async () => {
    const el = await open('OWNER');
    expect(labelsOf(el)).toEqual(['Waiting', 'Vitals', 'With doctor', 'Billing', 'Done']);
  });

  it('leaves out Vitals when it is off and adds Counselling when it is on', async () => {
    const steps = DEFAULT_WORKFLOW_STEPS.map((s) => ({
      ...s,
      on: s.key === 'vitals' ? false : s.key === 'counsel' ? true : s.on,
    }));
    const el = await open('OWNER', [WAITING, WITH_DOCTOR], steps);
    expect(labelsOf(el)).toEqual(['Waiting', 'With doctor', 'Counselling', 'Billing', 'Done']);
  });

  it('adds a legacy column for a visit stranded on a step that is now off', async () => {
    const steps = DEFAULT_WORKFLOW_STEPS.map((s) => ({ ...s, on: s.key === 'vitals' ? false : s.on }));
    const el = await open('OWNER', [WAITING, { ...WAITING, id: 'v3', tokenNo: 3, stage: 'VITALS' }], steps);
    expect(labelsOf(el)).toEqual(['Waiting', 'With doctor', 'Billing', 'Vitals', 'Done']);
    expect(el.querySelector('.col.legacy')?.textContent).toContain('Removed in v2');
  });

  it('shows "New token" for front desk and hides it for a doctor', async () => {
    const owner = await open('FRONT_DESK');
    expect(Array.from(owner.querySelectorAll('button')).some((b) => b.textContent!.includes('New token'))).toBe(
      true,
    );
    TestBed.resetTestingModule();
    const doc = await open('DOCTOR');
    expect(Array.from(doc.querySelectorAll('button')).some((b) => b.textContent!.includes('New token'))).toBe(
      false,
    );
  });

  it('shows a "Move to Vitals" button a front desk can use on a waiting card', async () => {
    const el = await open('FRONT_DESK');
    const button = Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent!.includes('Move to Vitals'),
    );
    expect(button).toBeTruthy();
  });

  it('hides the move button on a card the signed-in role does not own', async () => {
    const el = await open('NURSE');
    const waitingCard = Array.from(el.querySelectorAll('.qcard')).find((c) => c.textContent!.includes('T-1'))!;
    expect(waitingCard.querySelector('button')).toBeNull();
  });

  it('moves a card forward and reloads the board', async () => {
    const el = await open('FRONT_DESK');
    const button = Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent!.includes('Move to Vitals'),
    )!;
    button.click();
    const req = http.expectOne(`${BASE}/visits/v1/stage`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ stage: 'VITALS' });
    req.flush({ ...WAITING, stage: 'WITH_DOCTOR' });
    http.expectOne(`${BASE}/visits/today`).flush([{ ...WAITING, stage: 'WITH_DOCTOR' }]);
    http.expectOne(`${BASE}/workflow/current`).flush({ versionNo: 2, steps: DEFAULT_WORKFLOW_STEPS, publishedAt: '2026-11-01T05:00:00Z' });
  });

  it('opens the new-token dialog when the button is clicked', async () => {
    const el = await open('OWNER');
    const dialog = TestBed.inject(MatDialog);
    const openSpy = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => ({ subscribe: () => undefined }) } as never);
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent!.includes('New token'))!.click();
    expect(openSpy).toHaveBeenCalled();
  });
});
