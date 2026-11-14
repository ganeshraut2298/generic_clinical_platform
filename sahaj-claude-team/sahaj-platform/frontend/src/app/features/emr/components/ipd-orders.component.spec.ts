import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EMR_COMPONENT_CONTEXT } from '../emr-context';
import { EmrIpdOrderRow, EmrIpdOrdersValue } from '../emr-finish.model';
import { isComponentEmpty, sanitizeComponentValue } from './component-values.logic';
import { IpdOrdersComponent, ipdRowsOf } from './ipd-orders.component';

const OPTIONS = {
  drugs: [
    { key: 'PARACETAMOL', name: 'Paracetamol 500 mg', route: 'Oral', highAlert: null },
    { key: 'INSULIN', name: 'Insulin regular', route: 'SC', highAlert: 'high-alert' },
  ],
  frequencies: [
    { key: 'OD', label: 'Once a day' },
    { key: 'BD', label: 'Twice a day' },
  ],
  overrideReasons: [{ key: 'CLINICAL_NEED', label: 'Clinical need' }],
};

@Component({
  imports: [IpdOrdersComponent],
  template: `<app-ipd-orders inputId="f-rx-drug" [required]="true" [value]="value()" [disabled]="disabled()" (valueChange)="changed = $event" />`,
})
class HostComponent {
  readonly value = signal<unknown>(null);
  readonly disabled = signal(false);
  changed: EmrIpdOrdersValue | null = null;
}

describe('IpdOrdersComponent (SAH-67-1)', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  let http: HttpTestingController;
  let el: HTMLElement;

  function boot(disabled = false): void {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: EMR_COMPONENT_CONTEXT, useValue: { recordId: () => 'r1' } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    host.disabled.set(disabled);
    el = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  }

  const select = (act: string) => el.querySelector<HTMLSelectElement>(`[data-act=${act}]`)!;
  const pick = (act: string, value: string) => {
    const s = select(act);
    s.value = value;
    s.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  };

  afterEach(() => http.verify());

  it('reads the IPD catalogue for this record and shows the hint', () => {
    boot();
    http.expectOne('/api/v1/opd/emr/records/r1/ipd-order-options').flush(OPTIONS);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=ipd-hint]')!.textContent).toContain('A pharmacist checks every order before the first dose.');
    expect(Array.from(select('drug').options).map((o) => o.textContent!.trim())).toEqual([
      'Choose a medicine',
      'Paracetamol 500 mg',
      'Insulin regular · high-alert',
    ]);
  });

  it('adds a row with the drug and the frequency, with a client id, and sends no order id', () => {
    boot();
    http.expectOne('/api/v1/opd/emr/records/r1/ipd-order-options').flush(OPTIONS);
    fixture.detectChanges();
    const add = el.querySelector<HTMLButtonElement>('[data-act=add-order]')!;
    expect(add.disabled).toBe(true);
    pick('drug', 'PARACETAMOL');
    pick('frequency', 'BD');
    expect(add.disabled).toBe(false);
    add.click();
    expect(host.changed!.component).toBe('IPD_ORDERS');
    expect(host.changed!.rows).toHaveLength(1);
    expect(host.changed!.rows[0]).toMatchObject({ drugKey: 'PARACETAMOL', frequencyKey: 'BD' });
    expect(host.changed!.rows[0].id).toMatch(/^[0-9a-f-]{36}$/);
    expect(sanitizeComponentValue(host.changed!)).toEqual({ component: 'IPD_ORDERS', schemaVersion: 1, rows: [{ id: host.changed!.rows[0].id, drugKey: 'PARACETAMOL', frequencyKey: 'BD' }] });
  });

  it('lists the rows with names, lets a new row go, and keeps an ordered row as it is', () => {
    boot();
    http.expectOne('/api/v1/opd/emr/records/r1/ipd-order-options').flush(OPTIONS);
    const rows: EmrIpdOrderRow[] = [
      { id: 'a', drugKey: 'PARACETAMOL', frequencyKey: 'OD' },
      { id: 'b', drugKey: 'INSULIN', frequencyKey: 'BD', orderId: 'o1', orderedAt: '2026-11-13T05:30:00Z' },
    ];
    host.value.set({ component: 'IPD_ORDERS', schemaVersion: 1, rows });
    fixture.detectChanges();
    const items = Array.from(el.querySelectorAll('[data-testid=ipd-row]'));
    expect(items).toHaveLength(2);
    expect(items[0].textContent).toContain('Paracetamol 500 mg');
    expect(items[1].textContent).toContain('Ordered');
    expect(items[1].querySelector('[data-act=remove-order]')).toBeNull();
    items[0].querySelector<HTMLButtonElement>('[data-act=remove-order]')!.click();
    expect(host.changed!.rows.map((r) => r.id)).toEqual(['b']);
  });

  it('is read-only and loads nothing when the record is read-only', () => {
    boot(true);
    host.value.set({ component: 'IPD_ORDERS', schemaVersion: 1, rows: [{ id: 'a', drugKey: 'X', frequencyKey: 'OD' }] });
    fixture.detectChanges();
    expect(el.querySelector('[data-act=drug]')).toBeNull();
    expect(el.querySelector('[data-act=remove-order]')).toBeNull();
  });

  it('shows a message and a retry when the list cannot be read', () => {
    boot();
    http.expectOne('/api/v1/opd/emr/records/r1/ipd-order-options').flush(null, { status: 500, statusText: 'x' });
    fixture.detectChanges();
    expect(el.textContent).toContain('We could not load the medicine list. Try again.');
    el.querySelector<HTMLButtonElement>('[data-act=retry-options]')!.click();
    http.expectOne('/api/v1/opd/emr/records/r1/ipd-order-options').flush(OPTIONS);
  });

  it('is Done with at least one row', () => {
    expect(isComponentEmpty({ component: 'IPD_ORDERS', schemaVersion: 1, rows: [] })).toBe(true);
    expect(isComponentEmpty({ component: 'IPD_ORDERS', schemaVersion: 1, rows: [{ id: 'a', drugKey: 'X', frequencyKey: 'OD' }] })).toBe(false);
    expect(ipdRowsOf(undefined)).toEqual([]);
  });
});
