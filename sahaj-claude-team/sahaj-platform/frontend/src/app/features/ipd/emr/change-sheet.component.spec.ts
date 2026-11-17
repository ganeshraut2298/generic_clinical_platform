import { ChangeSheetComponent } from './change-sheet.component';
import { ORDER, problem, sheetOf } from './emr.fixtures';
import { MedicineOrderRow } from './emr.model';
import { Rig, order, rig, settle, squash } from './order-sheets.testing';

describe('ChangeSheetComponent (SAH-76-1)', () => {
  let r: Rig;
  const url = '/api/v1/ipd/medicine-orders/o1/change';
  const q = <T extends HTMLElement>(sel: string) => (r.fixture.nativeElement as HTMLElement).querySelector<T>(sel);
  const type = async (sel: string, value: string): Promise<void> => {
    const input = q<HTMLInputElement>(sel)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle(r.fixture);
  };
  const choose = async (sel: string, value: string): Promise<void> => {
    const select = q<HTMLSelectElement>(sel)!;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    await settle(r.fixture);
  };
  const open = async (current: MedicineOrderRow = order()): Promise<void> => {
    r = await rig(ChangeSheetComponent, { admissionId: 'a1', order: current, sheet: sheetOf() });
  };
  const save = async (): Promise<void> => {
    q<HTMLButtonElement>('[data-act=chg-ok]')!.click();
    await settle(r.fixture);
  };

  afterEach(() => r.http.verify());

  it('is prefilled with the current version and has no Start and no medicine field', async () => {
    await open();
    expect(squash(q('h2'))).toBe('Change · Tab. Paracetamol 650 mg');
    expect(q<HTMLInputElement>('#chg-dose')!.value).toBe('1');
    expect(q<HTMLSelectElement>('#chg-unit')!.value).toBe('TABLET');
    expect(q<HTMLSelectElement>('#chg-route')!.value).toBe('ORAL');
    expect(q<HTMLSelectElement>('#chg-freq')!.value).toBe('od');
    expect(q('#chg-start')).toBeNull();
    expect(q('#emr-drug')).toBeNull();
  });

  it('says "Nothing was changed." and sends nothing when the values repeat the current version', async () => {
    await open();
    await type('#chg-reason', 'Because');
    await save();
    r.http.expectNone(url);
    expect(squash(q('[data-testid=change-failure]'))).toBe('Nothing was changed.');
  });

  it('lists "Changed: <old> → <new>" for each edited field before saving', async () => {
    await open();
    await type('#chg-dose', '2');
    await choose('#chg-route', 'IV');
    const lines = Array.from((r.fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid=change-lines] span')).map(
      (l) => squash(l),
    );
    expect(lines).toEqual(['Changed: Dose: 1 tablet → 2 tablet', 'Changed: Route: Oral → IV']);
  });

  it('requires the reason of 4 to 200 characters', async () => {
    await open();
    await type('#chg-dose', '2');
    await type('#chg-reason', 'abc');
    await save();
    r.http.expectNone(url);
    expect(squash(q('#chg-reason-err'))).toBe('Write why you are changing this order.');
  });

  it('names a missing dose before anything is sent', async () => {
    await open();
    await type('#chg-dose', '');
    await type('#chg-reason', 'Because of weight');
    await save();
    r.http.expectNone(url);
    expect(squash(q('#chg-dose-err'))).toBe('Enter the dose.');
  });

  it('sends the full new values, the version and the reason; no start unless edited', async () => {
    await open();
    await type('#chg-dose', '2');
    await type('#chg-reason', '  Not enough effect  ');
    await save();
    const req = r.http.expectOne(url);
    expect(req.request.body).toEqual({
      clientKey: expect.stringMatching(/^[0-9a-f-]{36}$/),
      orderVersion: 2,
      reason: 'Not enough effect',
      doseAmount: 2,
      doseUnit: 'TABLET',
      routeKey: 'ORAL',
      frequencyKey: 'od',
      durationDays: null,
      indication: null,
      priority: 'ROUTINE',
      prnMinIntervalMinutes: null,
      prnMaxPer24h: null,
    });
    const sheet = sheetOf();
    req.flush(sheet);
    await settle(r.fixture);
    expect(r.close).toHaveBeenCalledWith({ kind: 'done', sheet, toast: 'Tab. Paracetamol 650 mg changed.' });
  });

  it('turns a change to STAT into a one-dose order with no frequency and no duration', async () => {
    await open();
    await choose('#chg-priority', 'STAT');
    await type('#chg-reason', 'Needed now');
    await save();
    const req = r.http.expectOne(url);
    expect(req.request.body).toMatchObject({ priority: 'STAT', frequencyKey: null, durationDays: null });
    req.flush(sheetOf());
  });

  it('closes with the server text on 409 order-changed so the EMR sheet reads again', async () => {
    await open();
    await type('#chg-dose', '2');
    await type('#chg-reason', 'Not enough effect');
    await save();
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

  it('shows the field messages of a 400 and keeps the sheet open', async () => {
    await open();
    await type('#chg-dose', '2');
    await type('#chg-reason', 'Not enough effect');
    await save();
    r.http.expectOne(url).flush(
      problem('validation', 400, 'x', [{ field: 'doseAmount', message: 'Enter the dose.' }]),
      { status: 400, statusText: 'Bad Request' },
    );
    await settle(r.fixture);
    expect(squash(q('#chg-dose-err'))).toBe('Enter the dose.');
    expect(r.close).not.toHaveBeenCalled();
  });

  it('shows the PRN fields prefilled for a PRN order, with hours when the interval is whole hours', async () => {
    const prn = order({
      details: {
        ...ORDER.details,
        kind: 'PRN',
        frequencyKey: 'prn',
        frequencyLabel: 'As needed (PRN)',
        indication: 'Fever',
        prnMinIntervalMinutes: 360,
        prnMaxPer24h: 4,
      },
    });
    await open(prn);
    expect(q<HTMLInputElement>('#chg-interval')!.value).toBe('6');
    expect(q<HTMLInputElement>('#chg-max')!.value).toBe('4');
    expect(q<HTMLInputElement>('#chg-indication')!.value).toBe('Fever');
  });
});
