import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Subject } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { ScopeRefusals } from '../ipd-scope-refusal';
import { ORDER, problem, sheetOf } from './emr.fixtures';
import { MedicineOrderRow } from './emr.model';

/** Helpers shared by the spec files of the order action sheets (hold, stop, change). Not part of the app. */
export const order = (over: Partial<MedicineOrderRow> = {}): MedicineOrderRow => ({ ...ORDER, orderVersion: 2, ...over });

export interface Rig {
  fixture: ComponentFixture<unknown>;
  http: HttpTestingController;
  close: ReturnType<typeof vi.fn>;
  report: ReturnType<typeof vi.fn>;
}

export async function rig(component: Type<unknown>, data: object): Promise<Rig> {
  const close = vi.fn();
  const report = vi.fn();
  await TestBed.configureTestingModule({
    imports: [component],
    providers: [
      provideHttpClient(withInterceptors([problemDetailInterceptor])),
      provideHttpClientTesting(),
      { provide: MAT_DIALOG_DATA, useValue: data },
      { provide: MatDialogRef, useValue: { close } },
      { provide: ScopeRefusals, useValue: { report, refusals: new Subject(), watch: vi.fn() } },
    ],
  }).compileComponents();
  const http = TestBed.inject(HttpTestingController);
  const fixture = TestBed.createComponent(component);
  fixture.detectChanges();
  return { fixture, http, close, report };
}

export async function settle(f: ComponentFixture<unknown>): Promise<void> {
  f.detectChanges();
  await f.whenStable();
  f.detectChanges();
}

export const squash = (node: Element | null | undefined) => (node?.textContent ?? '').replace(/\s+/g, ' ').trim();

export interface ReasonSheetCase {
  name: string;
  component: Type<unknown>;
  path: string;
  idPrefix: string;
  label: string;
  missing: string;
  ok: string;
  cancel: string;
  failure: string;
  toast: string;
}

/** The behaviour hold and stop share: one reason box, version and key sent, the same refusals. */
export function describeReasonSheet(c: ReasonSheetCase): void {
  describe(`${c.name} (SAH-76-1)`, () => {
  let r: Rig;
  const url = `/api/v1/ipd/medicine-orders/o1/${c.path}`;
  const q = <T extends HTMLElement>(sel: string) => (r.fixture.nativeElement as HTMLElement).querySelector<T>(sel);

  const open = async (): Promise<void> => {
    r = await rig(c.component, { admissionId: 'a1', order: order() });
  };
  const submitWith = async (text: string): Promise<void> => {
    const box = q<HTMLTextAreaElement>(`#${c.idPrefix}-reason`)!;
    box.value = text;
    box.dispatchEvent(new Event('input'));
    q<HTMLButtonElement>(c.ok)!.click();
    await settle(r.fixture);
  };

  afterEach(() => r.http.verify());

  it('has the reason box with its label and a 200 character limit', async () => {
    await open();
    expect(squash(q(`label[for=${c.idPrefix}-reason]`))).toBe(c.label);
    expect(q<HTMLTextAreaElement>(`#${c.idPrefix}-reason`)!.maxLength).toBe(200);
  });

  it.each(['', '   ', 'abc'])('sends nothing for the reason "%s" and says why', async (text) => {
    await open();
    await submitWith(text);
    r.http.expectNone(url);
    expect(squash(q(`#${c.idPrefix}-err`))).toBe(c.missing);
  });

  it('sends the version the doctor saw, the trimmed reason and a client key, then closes with the sheet and the toast', async () => {
    await open();
    await submitWith('  Nil by mouth today  ');
    const req = r.http.expectOne(url);
    expect(req.request.body).toEqual({
      clientKey: expect.stringMatching(/^[0-9a-f-]{36}$/),
      orderVersion: 2,
      reason: 'Nil by mouth today',
    });
    expect(q<HTMLButtonElement>(c.ok)!.disabled).toBe(true);
    const sheet = sheetOf();
    req.flush(sheet);
    await settle(r.fixture);
    expect(r.close).toHaveBeenCalledWith({ kind: 'done', sheet, toast: c.toast });
  });

  it('closes with the server text when the order moved on (409 order-changed)', async () => {
    await open();
    await submitWith('Nil by mouth today');
    r.http
      .expectOne(url)
      .flush(problem('order-changed', 409, 'This order was changed by someone else. Reload and check the new order.'), {
        status: 409,
        statusText: 'Conflict',
      });
    await settle(r.fixture);
    expect(r.close).toHaveBeenCalledWith({
      kind: 'conflict',
      message: 'This order was changed by someone else. Reload and check the new order.',
    });
  });

  it('keeps the sheet and the typing on a lost connection', async () => {
    await open();
    await submitWith('Nil by mouth today');
    r.http.expectOne(url).error(new ProgressEvent('error'));
    await settle(r.fixture);
    expect(squash(q(c.failure))).not.toBe('');
    expect(q<HTMLTextAreaElement>(`#${c.idPrefix}-reason`)!.value).toBe('Nil by mouth today');
    expect(r.close).not.toHaveBeenCalled();
    expect(q<HTMLButtonElement>(c.ok)!.disabled).toBe(false);
  });

  it('shows the field message of a 400 under the box', async () => {
    await open();
    await submitWith('Nil by mouth today');
    r.http.expectOne(url).flush(
      problem('validation', 400, 'x', [{ field: 'reason', message: c.missing }]),
      { status: 400, statusText: 'Bad Request' },
    );
    await settle(r.fixture);
    expect(squash(q(`#${c.idPrefix}-err`))).toBe(c.missing);
  });

  it('tells the page and closes for 403 outside-scope, without a toast', async () => {
    await open();
    await submitWith('Nil by mouth today');
    r.http.expectOne(url).flush(problem('outside-scope', 403, 'This patient is not in your wards.'), {
      status: 403,
      statusText: 'Forbidden',
    });
    await settle(r.fixture);
    expect(r.report).toHaveBeenCalledWith('a1', true);
    expect(r.close).toHaveBeenCalledWith();
  });

  it('takes a new client key after 409 client-key-reused and stays open', async () => {
    await open();
    await submitWith('Nil by mouth today');
    const first = r.http.expectOne(url);
    first.flush(problem('client-key-reused', 409, 'Reload the page and try again.'), {
      status: 409,
      statusText: 'Conflict',
    });
    await settle(r.fixture);
    expect(r.close).not.toHaveBeenCalled();
    q<HTMLButtonElement>(c.ok)!.click();
    await settle(r.fixture);
    const second = r.http.expectOne(url);
    expect(second.request.body.clientKey).not.toBe(first.request.body.clientKey);
    second.flush(sheetOf());
  });

  it('Cancel closes without an answer', async () => {
    await open();
    q<HTMLButtonElement>(c.cancel)!.click();
    expect(r.close).toHaveBeenCalledWith();
  });
  });
}
