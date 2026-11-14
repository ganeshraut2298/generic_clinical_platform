import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { EmrFormList, EmrFormStarterSummary } from '../../emr/emr-phase3.model';
import { formView } from '../../emr/forms/form.fixtures';
import { FormsListPage } from './forms-list.page';

const EMR = '/api/v1/opd/emr';

const STARTERS: EmrFormStarterSummary[] = [
  { starterKey: 'diabetic_foot', name: 'Diabetic foot examination', description: 'Risk score, ulcer follow-up, advice', suitedFor: 'General Medicine, Diabetology' },
  { starterKey: 'gcs', name: 'Glasgow Coma Scale', description: 'Eye, verbal, motor · total and severity', suitedFor: 'General Medicine, Emergency' },
  { starterKey: 'morse', name: 'Morse fall risk scale', description: 'IPD and day care · nursing', suitedFor: 'IPD wards' },
  { starterKey: 'mlc_injury', name: 'MLC injury report', description: 'Medico-legal case · body map · police details', suitedFor: 'General Medicine, Emergency, Orthopaedics' },
  { starterKey: 'pre_anaesthetic', name: 'Pre-anaesthetic check-up', description: 'ASA grade, airway, fasting, consent', suitedFor: 'Surgery, Dental, Obstetrics & Gynaecology' },
  { starterKey: 'untitled', name: 'Untitled form', description: 'Start with an empty page', suitedFor: 'General Medicine' },
];

function list(canEdit: boolean): EmrFormList {
  return {
    canEdit,
    canApprove: canEdit,
    forms: [
      { formKey: 'f_abcdefghij', name: 'Diabetic foot examination', state: 'DRAFT', chipVersionNo: 3, liveVersionNo: 2, starterKey: 'diabetic_foot', useIn: { departmentKeys: ['gm'], places: ['EMERGENCY'] }, emrPart: 'ASSESSMENT', fieldCount: 12, logicCount: 2, scoredCount: 3, updatedAt: '2026-11-12T05:00:00Z' },
      { formKey: 'f_klmnopqrst', name: 'GCS', state: 'LIVE', chipVersionNo: 1, liveVersionNo: 1, starterKey: 'gcs', useIn: { departmentKeys: [], places: [] }, emrPart: 'TREATMENT', fieldCount: 6, logicCount: 0, scoredCount: 2, updatedAt: '2026-11-12T05:00:00Z' },
    ],
  };
}

describe('FormsListPage (SAH-59-1, SAH-59-6)', () => {
  let fixture: ComponentFixture<FormsListPage>;
  let http: HttpTestingController;
  let el: HTMLElement;

  function boot(canEdit = true): void {
    TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FormsListPage);
    fixture.detectChanges();
    http.expectOne(`${EMR}/forms`).flush(list(canEdit));
    http.expectOne(`${EMR}/form-starters`).flush(STARTERS);
    http.expectOne(`${EMR}/departments`).flush({ canEdit, departments: [{ departmentKey: 'gm', name: 'General Medicine', status: 'TEMPLATE', publishedVersion: 0 }] });
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);
  const qa = (sel: string) => Array.from(el.querySelectorAll<HTMLElement>(sel));

  afterEach(() => http.verify());

  it('shows the title, the path and the clinic forms with their chip, where they are used and the counts', () => {
    boot();
    expect(q('h2')!.textContent).toBe('Form builder — custom clinical forms');
    expect(q('.crumb')!.textContent!.replace(/\s+/g, ' ')).toContain('Settings / EMR configuration / Form builder');
    const forms = qa('[data-testid=forms] .form');
    expect(forms).toHaveLength(2);
    expect(forms[0].textContent).toContain('v3 · draft');
    expect(forms[0].textContent).toContain('General Medicine, Emergency');
    expect(forms[0].textContent).toContain('12 fields · 2 with logic · 3 scored');
    expect(forms[1].textContent).toContain('v1 · live');
    expect(forms[1].textContent).toContain('No department yet');
  });

  it('offers the six starters with their one-line description and the departments they suit', () => {
    boot();
    const starters = qa('[data-testid=starters] .starter');
    expect(starters.map((s) => s.querySelector('strong')!.textContent)).toEqual(STARTERS.map((s) => s.name));
    expect(starters[0].textContent).toContain('Risk score, ulcer follow-up, advice');
    expect(starters[0].textContent).toContain('General Medicine, Diabetology');
  });

  it('starts a form from a starter for the owner and opens it in the builder', async () => {
    boot();
    const router = TestBed.inject(Router);
    const go = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    q<HTMLButtonElement>('[data-starter=gcs]')!.click();
    const req = http.expectOne(`${EMR}/forms`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ starterKey: 'gcs', name: null });
    expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    req.flush(formView({ formKey: 'f_newformkey' }));
    expect(go).toHaveBeenCalledWith(['/app/config/forms', 'f_newformkey']);
  });

  it('shows the server message and stays when the form could not be started', () => {
    boot();
    q<HTMLButtonElement>('[data-starter=gcs]')!.click();
    http.expectOne(`${EMR}/forms`).flush({ type: 'https://sahaj.in/problems/form-limit', title: 'x', status: 400, detail: 'A clinic can have 100 forms.' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(q('.notice.err')!.textContent).toContain('A clinic can have 100 forms.');
    expect(q<HTMLButtonElement>('[data-starter=gcs]')!.disabled).toBe(false);
  });

  it('lets every other role read the list, but offers no "Use this starter"', () => {
    boot(false);
    expect(q('[data-testid=forms-readonly]')!.textContent).toContain('Only the owner can change forms.');
    expect(q('[data-starter]')).toBeNull();
    expect(qa('[data-testid=forms] .form a').some((a) => a.textContent!.trim() === 'View')).toBe(true);
  });

  it('shows an error with a retry when the forms cannot be loaded', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FormsListPage);
    fixture.detectChanges();
    http.expectOne(`${EMR}/forms`).flush({}, { status: 500, statusText: 'Server Error' });
    http.expectOne(`${EMR}/form-starters`).flush([]);
    http.expectOne(`${EMR}/departments`).flush({ canEdit: false, departments: [] });
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
    expect(q('[role=alert]')!.textContent).toContain('We could not load the forms');
    q<HTMLButtonElement>('[data-act=retry]')!.click();
    http.expectOne(`${EMR}/forms`).flush(list(true));
    http.expectOne(`${EMR}/form-starters`).flush(STARTERS);
    http.expectOne(`${EMR}/departments`).flush({ canEdit: true, departments: [] });
    fixture.detectChanges();
    expect(qa('[data-testid=forms] .form')).toHaveLength(2);
  });
});
