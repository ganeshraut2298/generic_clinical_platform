import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Provider, Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { casePaperContext } from '../emr.fixtures';
import { EmrRecordView } from '../emr.model';
import { EmrRecordStore } from '../record/emr-record.store';
import { SpecialtyContext } from './specialty.context';
import { EmrSpecialtyValue } from './specialty.model';

export const EMR_RECORDS = '/api/v1/opd/emr/records';
export const FP = 'f'.repeat(64);

export interface Harness<T> {
  fixture: ComponentFixture<T>;
  store: EmrRecordStore;
  ctx: SpecialtyContext;
  http: HttpTestingController;
  el: HTMLElement;
}

/**
 * Mounts a specialty editor next to a real record store and context, on a record of the given
 * view. Values typed before the screen opens are put in with `values` (section key to value).
 */
export function mountEditor<T>(
  type: Type<T>,
  view: EmrRecordView,
  sectionKey: string,
  values: Record<string, EmrSpecialtyValue> = {},
  extraProviders: Provider[] = [],
): Harness<T> {
  TestBed.configureTestingModule({
    imports: [type],
    providers: [
      provideHttpClient(withInterceptors([problemDetailInterceptor])),
      provideHttpClientTesting(),
      provideRouter([]),
      EmrRecordStore,
      SpecialtyContext,
      ...extraProviders,
    ],
  });
  const store = TestBed.inject(EmrRecordStore);
  const http = TestBed.inject(HttpTestingController);
  store.load(view.id);
  http.expectOne(`${EMR_RECORDS}/${view.id}`).flush(view);
  http.expectOne(`${EMR_RECORDS}/${view.id}/case-paper`).flush(casePaperContext());
  for (const [key, value] of Object.entries(values)) {
    store.setSpecialty(key, value);
  }
  const ctx = TestBed.inject(SpecialtyContext);
  const fixture = TestBed.createComponent(type);
  if (sectionKey) {
    fixture.componentRef.setInput('sectionKey', sectionKey);
  }
  fixture.detectChanges();
  return { fixture, store, ctx, http, el: fixture.nativeElement as HTMLElement };
}

export function click(el: HTMLElement, selector: string, index = 0): void {
  const target = el.querySelectorAll<HTMLElement>(selector)[index];
  if (!target) {
    throw new Error(`Nothing matches ${selector} (${index})`);
  }
  target.click();
}

export function byText(el: HTMLElement, selector: string, text: string): HTMLElement {
  const found = Array.from(el.querySelectorAll<HTMLElement>(selector)).find((e) => (e.textContent ?? '').replace(/\s+/g, ' ').trim().includes(text));
  if (!found) {
    throw new Error(`No ${selector} with text "${text}"`);
  }
  return found;
}

export function typeInto(input: HTMLInputElement | HTMLTextAreaElement, value: string, event: 'input' | 'change' = 'change'): void {
  input.value = value;
  input.dispatchEvent(new Event(event, { bubbles: true }));
}

export function flushReference(http: HttpTestingController, profile: string, body: object = { profile, tagNote: '' }): void {
  http.expectOne(`/api/v1/opd/emr/specialty-reference/${profile}`).flush(body);
}
