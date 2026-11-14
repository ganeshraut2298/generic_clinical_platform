import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { EmrAccess } from '../../emr/emr-access.service';
import { EmrLauncher } from '../../emr/emr-launcher.service';
import { EmergencyComponent } from './emergency.component';
import { EmergencyCase, EmergencyQueue } from './emergency.model';

const QUEUE = '/api/v1/ipd/emergency';

const CASE: EmergencyCase = {
  id: 'c1',
  displayName: 'Unknown male, about 40',
  age: 40,
  sex: 'M',
  complaint: 'Chest pain',
  identityPending: true,
  level: 1,
  levelName: 'Red',
  bay: 'ER-1',
  status: 'WAITING',
  arrivedAt: '2026-11-05T05:00:00Z',
  waitingMinutes: 2,
  targetMinutes: 5,
  overTarget: false,
  seenAt: null,
};

const QUEUE_ANSWER: EmergencyQueue = {
  state: 'on',
  scale: 3,
  levels: [{ level: 1, name: 'Red', description: 'Immediate', tone: 'crit', targetMinutes: 5 }],
  defaultLevel: 1,
  unknownPatientRegistration: true,
  baysTotal: 2,
  baysFree: 1,
  counts: { writable: true, queue: 1, overTarget: 0 },
  cases: [CASE],
  canRegister: false,
  canRetriage: false,
  canAct: false,
  canAdmit: false,
  asOf: '2026-11-05T05:02:00Z',
};

/** "Open EMR" on an Emergency card (SAH-46-1, SAH-46-4). */
describe('EmergencyComponent Open EMR', () => {
  let fixture: ComponentFixture<EmergencyComponent>;
  let http: HttpTestingController;
  let launcher: { open: ReturnType<typeof vi.fn> };

  async function open(canOpen: boolean): Promise<HTMLElement> {
    launcher = { open: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
        { provide: MatDialog, useValue: { open: vi.fn() } },
        { provide: EmrAccess, useValue: { ensureLoaded: vi.fn(), canOpen: () => canOpen } },
        { provide: EmrLauncher, useValue: launcher },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EmergencyComponent);
    fixture.detectChanges();
    http.expectOne(QUEUE).flush(QUEUE_ANSWER);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('is drawn for a doctor, with the patient in its hidden name, and opens the case', async () => {
    const el = await open(true);
    const button = el.querySelector<HTMLButtonElement>('[data-act=open-emr]')!;
    expect(button.textContent).toContain('Open EMR');
    expect(button.querySelector('.sr-only')!.textContent).toContain('Unknown male, about 40');
    button.click();
    expect(launcher.open).toHaveBeenCalledWith('ER_ASSESSMENT', 'c1');
  });

  it('is not drawn for a user who may not open the EMR', async () => {
    const el = await open(false);
    expect(el.querySelector('[data-act=open-emr]')).toBeNull();
  });
});
