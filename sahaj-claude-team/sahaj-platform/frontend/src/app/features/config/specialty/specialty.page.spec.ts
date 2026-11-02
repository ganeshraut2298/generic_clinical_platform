import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Role } from '../../../core/platform/platform.model';
import { ShellStore } from '../../../core/shell/shell.store';
import { SpecialtyPage } from './specialty.page';

const BASE = '/api/v1/platform';

const LIST = [
  { code: 'gp', name: 'General practice', features: ['Repeat prescription'], phase: 1 },
  { code: 'dental', name: 'Dental', features: ['Tooth chart'], phase: 1 },
  { code: 'ayur', name: 'Ayurveda', features: ['Prakriti assessment'], phase: 2 },
];

describe('SpecialtyPage', () => {
  let fixture: ComponentFixture<SpecialtyPage>;
  let http: HttpTestingController;

  async function open(role: Role) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: ShellStore, useValue: { me: () => ({ userId: 'u1', role }) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SpecialtyPage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/specialties`).flush(LIST);
    http.expectOne(`${BASE}/clinic/profile`).flush({ name: 'Clinic', specialtyCode: 'gp' });
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const card = (el: HTMLElement, name: string) =>
    Array.from(el.querySelectorAll<HTMLButtonElement>('.spec')).find((b) => b.textContent?.includes(name))!;

  afterEach(() => http.verify());

  it('shows the template in use and the Phase 2 templates as unavailable', async () => {
    const el = await open('OWNER');
    expect(card(el, 'General practice').textContent).toContain('In use');
    expect(card(el, 'Ayurveda').textContent).toContain('Phase 2');
    expect(card(el, 'Ayurveda').disabled).toBe(true);
  });

  it('applies another template with only its code', async () => {
    const el = await open('OWNER');
    card(el, 'Dental').click();
    const req = http.expectOne(`${BASE}/clinic/specialty`);
    expect(req.request.body).toEqual({ code: 'dental' });
    req.flush({ name: 'Clinic', specialtyCode: 'dental' });
    fixture.detectChanges();
    expect(card(el, 'Dental').textContent).toContain('In use');
    expect(card(el, 'General practice').textContent).not.toContain('In use');
  });

  it('is read-only for a nurse', async () => {
    const el = await open('NURSE');
    expect(card(el, 'Dental').disabled).toBe(true);
  });
});
