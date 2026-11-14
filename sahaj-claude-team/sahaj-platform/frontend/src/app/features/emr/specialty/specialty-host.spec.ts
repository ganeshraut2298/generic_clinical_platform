import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { casePaperContext, field, recordSection, section } from '../emr.fixtures';
import { EmrLayoutSection, EmrRecordView } from '../emr.model';
import { EmrRecordStore } from '../record/emr-record.store';
import { SectionCardComponent } from '../record/section-card.component';
import { specialtyRecord } from './specialty.fixtures';
import { SpecialtyContext } from './specialty.context';

const EMR = '/api/v1/opd/emr/records';

/** A section card with a specialty editor (ADR-023 D1): the registry decides, the generic renderer is the fallback. */
describe('SectionCardComponent with a specialty editor', () => {
  let fixture: ComponentFixture<SectionCardComponent>;
  let http: HttpTestingController;
  let store: EmrRecordStore;
  let el: HTMLElement;

  async function boot(sec: EmrLayoutSection, over: Partial<EmrRecordView> = {}, values: Record<string, unknown> = {}, expectHost = true): Promise<void> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([problemDetailInterceptor])), provideHttpClientTesting(), EmrRecordStore, SpecialtyContext, { provide: MatSnackBar, useValue: { open: vi.fn() } }],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(EmrRecordStore);
    const base = specialtyRecord('physio', [], {}, over);
    store.load('r1');
    http.expectOne(`${EMR}/r1`).flush({ ...base, layout: { ...base.layout, sections: [sec] }, sections: [recordSection(sec.key, { values, status: sec.required ? 'REQUIRED' : 'OPTIONAL', legacyValues: over.sections?.[0]?.legacyValues })] });
    http.expectOne(`${EMR}/r1/case-paper`).flush(casePaperContext());
    fixture = TestBed.createComponent(SectionCardComponent);
    fixture.componentRef.setInput('section', sec);
    fixture.detectChanges();
    el = fixture.nativeElement as HTMLElement;
    if (expectHost) {
      await settle();
    }
  }

  /** The editor loads with a dynamic import: give it a moment, then draw. */
  async function settle(): Promise<void> {
    for (let i = 0; i < 300 && (!el.querySelector('app-specialty-host') || el.querySelector('app-specialty-host p[aria-busy]')); i++) {
      await new Promise((r) => setTimeout(r, 10));
      fixture.detectChanges();
    }
    fixture.detectChanges();
  }

  it('loads the editor of the registry for a known component, in place of the generic fields', async () => {
    const sec = section({ key: 'pain', name: 'Pain today', kind: 'SPECIALTY', fields: [field({ key: 'score', valueKind: 'COVERED' })], specialtyEditor: { component: 'PAIN_SCORE', profile: 'physio' } });
    await boot(sec);
    expect(el.querySelector('app-specialty-host')).not.toBeNull();
    expect(el.querySelectorAll('button.pn')).toHaveLength(11);
    expect(el.querySelector('app-field-renderer')).toBeNull();
  });

  it('shows an unknown component with the generic renderer', async () => {
    const sec = section({
      key: 'pain',
      name: 'Pain today',
      fields: [field({ key: 'note', valueKind: 'TEXT', type: 'TEXT', label: 'Note' })],
      specialtyEditor: { component: 'SOMETHING_NEW' as never, profile: 'physio' },
    });
    await boot(sec, {}, {}, false);
    expect(el.querySelector('app-specialty-host')).toBeNull();
    expect(el.querySelector('app-field-renderer')).not.toBeNull();
  });

  it('shows earlier generic values read-only until the first specialty change replaces them', async () => {
    const sec = section({ key: 'pain', name: 'Pain today', kind: 'SPECIALTY', specialtyEditor: { component: 'PAIN_SCORE', profile: 'physio' } });
    await boot(sec, { sections: [recordSection('pain', { legacyValues: { pain_score: '6' } })] });
    expect(el.querySelector('[data-testid="legacy-values"]')?.textContent).toContain('pain_score: 6');
    (el.querySelectorAll('button.pn')[3] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="legacy-values"]')).toBeNull();
  });

  it('writes through the shared store so Done, the footer and the draft save follow', async () => {
    const sec = section({ key: 'pain', name: 'Pain today', kind: 'SPECIALTY', required: true, specialtyEditor: { component: 'PAIN_SCORE', profile: 'physio' } });
    await boot(sec);
    expect(el.querySelector('[data-testid="section-status"]')?.textContent?.trim()).toBe('Required');
    (el.querySelectorAll('button.pn')[5] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="section-status"]')?.textContent?.trim()).toBe('Done');
    expect(store.dirty()).toBe(true);
  });

  it('opens read-only when the record is read-only', async () => {
    const sec = section({ key: 'pain', name: 'Pain today', kind: 'SPECIALTY', specialtyEditor: { component: 'PAIN_SCORE', profile: 'physio' } });
    await boot(sec, { readOnly: true, readOnlyReason: 'HISTORY' });
    expect((el.querySelector('button.pn') as HTMLButtonElement).disabled).toBe(true);
  });
});
