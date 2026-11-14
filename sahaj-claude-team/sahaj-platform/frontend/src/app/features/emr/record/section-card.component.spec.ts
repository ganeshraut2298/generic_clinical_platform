import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { casePaperContext, field, record, recordSection, section } from '../emr.fixtures';
import { EmrLayoutSection, EmrRecordView } from '../emr.model';
import { footDefinition } from '../forms/form.fixtures';
import { EmrRecordStore } from './emr-record.store';
import { SectionCardComponent } from './section-card.component';

const EMR = '/api/v1/opd/emr/records';

const formSection = (): EmrLayoutSection =>
  section({ key: 'f_abcdefghij', name: 'Diabetic foot examination', source: 'FORM', formKey: 'f_abcdefghij', formVersionNo: 3, form: footDefinition(), fields: [], kind: 'SPECIALTY', required: true });

const library = (): EmrLayoutSection =>
  section({
    key: 'complaints',
    name: 'Chief complaints',
    fields: [
      field({ key: 'duration', valueKind: 'COVERED', type: 'NUMBER_UNIT', label: 'Duration' }),
      field({ key: 'note', valueKind: 'TEXT', type: 'TEXT', label: 'Note' }),
    ],
  });

describe('SectionCardComponent for forms and components (SAH-59-5, ADR-022 D2)', () => {
  let fixture: ComponentFixture<SectionCardComponent>;
  let http: HttpTestingController;
  let store: EmrRecordStore;
  let snack: ReturnType<typeof vi.fn>;
  let el: HTMLElement;

  function boot(sec: EmrLayoutSection, over: Partial<EmrRecordView> = {}): void {
    snack = vi.fn();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting(), EmrRecordStore, { provide: MatSnackBar, useValue: { open: snack } }],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(EmrRecordStore);
    const base = record(over);
    store.load('r1');
    http.expectOne(`${EMR}/r1`).flush({ ...base, layout: { ...base.layout, sections: [sec] }, sections: [recordSection(sec.key, { status: sec.required ? 'REQUIRED' : 'OPTIONAL' })] });
    http.expectOne(`${EMR}/r1/case-paper`).flush(casePaperContext());
    fixture = TestBed.createComponent(SectionCardComponent);
    fixture.componentRef.setInput('section', sec);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
  }

  const q = <T extends Element = HTMLElement>(sel: string) => el.querySelector<T>(sel);
  const press = (key: string, label: string) =>
    Array.from(q(`[data-field=${key}]`)!.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent!.trim() === label)!;

  afterEach(() => http.verify());

  it('draws a clinic form with the tag "Custom"', () => {
    boot(formSection());
    expect(q('[data-testid=custom-tag]')!.textContent).toBe('Custom');
    expect(q('app-form-fill')).not.toBeNull();
    expect(q('app-field-renderer')).toBeNull();
  });

  it('keeps what is filled in the form with the visit values, and turns Done when the required fields are filled', () => {
    boot(formSection());
    expect(q('[data-testid=section-status]')!.textContent).toBe('Required');
    press('ulcer', 'Yes').click();
    q<HTMLInputElement>('[data-field=mono] input')!.value = '9';
    q<HTMLInputElement>('[data-field=mono] input')!.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(store.values()['f_abcdefghij']).toMatchObject({ ulcer: true, mono: 9 });
    expect(store.stateOf('f_abcdefghij')?.done).toBe(true);
  });

  it('saves with "Save to patient record" and says where it was saved', () => {
    boot(formSection());
    press('ulcer', 'No').click();
    q<HTMLButtonElement>('[data-act=save-form]')!.click();
    const req = http.expectOne(`${EMR}/r1/draft`);
    expect(req.request.body.sections[0]).toMatchObject({ key: 'f_abcdefghij', values: { ulcer: false } });
    req.flush(record({ revision: 4 }));
    expect(snack).toHaveBeenCalledWith('Saved to the patient\'s EMR under Assessment as “Diabetic foot examination” (v3).', 'Close', { duration: 3500 });
  });

  it('is read-only on a signed visit: no save button and no active control', () => {
    boot(formSection(), { readOnly: true, status: 'SIGNED', readOnlyReason: 'SIGNED' });
    expect(q('[data-act=save-form]')).toBeNull();
    expect(Array.from(el.querySelectorAll<HTMLButtonElement>('.pick')).every((b) => b.disabled)).toBe(true);
  });

  it('leaves a covered sibling field out of a library section', () => {
    boot(library());
    expect(el.querySelectorAll('app-field-renderer')).toHaveLength(1);
    expect(q('[data-testid=custom-tag]')).toBeNull();
  });
});
