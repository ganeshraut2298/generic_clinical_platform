import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { EmrLauncher } from './emr-launcher.service';
import { record } from './emr.fixtures';

const OPEN = '/api/v1/opd/emr/records/open';

describe('EmrLauncher (SAH-46)', () => {
  let launcher: EmrLauncher;
  let http: HttpTestingController;
  let router: Router;
  let snack: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    snack = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    });
    launcher = TestBed.inject(EmrLauncher);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
  });

  afterEach(() => http.verify());

  it('opens the record and goes to the EMR, handing over only the stage outcome', () => {
    launcher.open('OPD_VISIT', 'v1');
    const req = http.expectOne(OPEN);
    expect(req.request.body).toEqual({ encounterType: 'OPD_VISIT', encounterId: 'v1' });
    req.flush({ created: true, stageOutcome: 'MOVED', record: record({ id: 'r9' }) }, { status: 201, statusText: 'Created' });
    expect(router.navigate).toHaveBeenCalledWith(['/app/emr', 'r9'], { state: { stageOutcome: 'MOVED' } });
    expect(launcher.opening()).toBeNull();
  });

  it('opens one record at a time', () => {
    launcher.open('IPD_ROUND', 'a1');
    launcher.open('IPD_ROUND', 'a2');
    expect(launcher.opening()).toBe('a1');
    http.expectOne(OPEN).flush({ created: false, stageOutcome: 'NOT_APPLICABLE', record: record() });
  });

  it('says what the server said when it refuses, and stays where it is', () => {
    launcher.open('OPD_VISIT', 'v1');
    http.expectOne(OPEN).flush(
      { type: 'https://sahaj.in/problems/not-own-patient', title: 'Forbidden', status: 403, detail: 'This patient belongs to another doctor.' },
      { status: 403, statusText: 'Forbidden' },
    );
    expect(snack).toHaveBeenCalledWith('This patient belongs to another doctor.', 'Close', expect.anything());
    expect(router.navigate).not.toHaveBeenCalled();
    expect(launcher.opening()).toBeNull();
  });

  it('uses a plain sentence when the network fails', () => {
    launcher.open('ER_ASSESSMENT', 'c1');
    http.expectOne(OPEN).error(new ProgressEvent('error'));
    expect(snack).toHaveBeenCalledWith('We could not open the EMR. Try again.', 'Close', expect.anything());
  });
});
