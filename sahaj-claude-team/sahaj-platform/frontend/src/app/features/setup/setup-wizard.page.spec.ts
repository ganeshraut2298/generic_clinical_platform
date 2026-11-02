import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { problemDetailInterceptor } from '../../core/http/problem-detail.interceptor';
import { SetupWizardPage } from './setup-wizard.page';

const BASE = '/api/v1/platform/setup';
const EMPTY_STATE = { currentStep: 1, answers: {}, limits: { doctors: 2, users: 5 } };

describe('SetupWizardPage', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;

  async function open() {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [
            { path: 'setup', component: SetupWizardPage },
            { path: 'app', children: [] },
            { path: 'login', children: [] },
          ],
          withComponentInputBinding(),
        ),
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/setup');
    http.expectOne(BASE).flush(EMPTY_STATE);
    detectChanges();
    await whenStable();
    return root();
  }

  const root = () => harness.routeNativeElement as HTMLElement;
  const detectChanges = () => harness.detectChanges();
  const whenStable = () => harness.fixture.whenStable();

  afterEach(() => http.verify());

  it('loads step 1 from the server and shows its fields', async () => {
    const el = await open();
    expect(el.querySelector('h1')?.textContent).toContain('Tell us about your clinic');
    expect(el.textContent).toContain('Step 1 of 5');
  });

  it('does not call the server when the clinic name is missing', async () => {
    await open();
    root().querySelector<HTMLButtonElement>('.su-foot button:last-child')!.click();
    detectChanges();
    await whenStable();
    http.expectNone(`${BASE}/steps/1`);
  });

  it('saves step 1 and moves to step 2 on Continue', async () => {
    await open();
    const nameInput = root().querySelector<HTMLInputElement>('input[formcontrolname="name"]')!;
    const cityInput = root().querySelector<HTMLInputElement>('input[formcontrolname="city"]')!;
    nameInput.value = 'Shree Sai Family Clinic';
    nameInput.dispatchEvent(new Event('input'));
    cityInput.value = 'Pune';
    cityInput.dispatchEvent(new Event('input'));
    detectChanges();
    await whenStable();

    root().querySelector<HTMLButtonElement>('.su-foot button:last-child')!.click();
    detectChanges();
    await whenStable();

    const req = http.expectOne(`${BASE}/steps/1`);
    expect(req.request.body).toEqual(
      expect.objectContaining({ name: 'Shree Sai Family Clinic', city: 'Pune' }),
    );
    req.flush({ currentStep: 2, answers: {}, limits: { doctors: 2, users: 5 } });
    detectChanges();
    await whenStable();

    expect(root().textContent).toContain('Step 2 of 5');
  });
});
