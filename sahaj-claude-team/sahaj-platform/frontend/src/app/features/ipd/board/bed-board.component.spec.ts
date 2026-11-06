import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { BedBoard, BoardBed } from '../ipd.model';
import { BedBoardComponent } from './bed-board.component';
import { BedSheetResult } from './bed-sheet-dialog.component';

const BOARD_URL = '/api/v1/ipd/board';

function bed(partial: Partial<BoardBed> & Pick<BoardBed, 'id' | 'code' | 'status'>): BoardBed {
  return { categoryKey: 'gen', categoryName: 'General', ratePaise: 80000, ...partial };
}

function board(partial: Partial<BedBoard> = {}): BedBoard {
  return {
    state: 'on',
    roomLevel: true,
    canAdmit: true,
    canChangeBeds: true,
    counts: { free: 1, occ: 1, clean: 1, res: 1, blk: 1 },
    wards: [
      {
        id: 'w1',
        name: 'General ward',
        station: 'Nursing station 1',
        occupied: 1,
        total: 5,
        hasFreeBed: true,
        firstFreeBedId: 'b3',
        rooms: [
          {
            id: 'r1',
            name: 'Room 101',
            beds: [
              bed({
                id: 'b1',
                code: '101-A',
                status: 'occ',
                admissionId: 'a1',
                patientName: 'Sunil Jadhav',
                dayNumber: 2,
                doctorShortName: 'Dr. Meera',
              }),
              bed({ id: 'b2', code: '101-B', status: 'clean' }),
              bed({ id: 'b3', code: '101-C', status: 'free' }),
            ],
          },
          {
            id: null,
            name: '',
            beds: [bed({ id: 'b4', code: '103-C', status: 'blk' }), bed({ id: 'b5', code: '104-A', status: 'res' })],
          },
        ],
      },
    ],
    ...partial,
  };
}

describe('BedBoardComponent', () => {
  let fixture: ComponentFixture<BedBoardComponent>;
  let http: HttpTestingController;
  let dialog: { open: ReturnType<typeof vi.fn> };
  let snack: ReturnType<typeof vi.fn>;
  let view: HTMLElement;
  const emitted = { admission: [] as string[], admit: [] as (string | undefined)[], changed: 0 };

  function setup(sheetResult: BedSheetResult | null = null): void {
    snack = vi.fn();
    dialog = { open: vi.fn(() => ({ afterClosed: () => of(sheetResult) })) };
    emitted.admission = [];
    emitted.admit = [];
    emitted.changed = 0;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(BedBoardComponent);
    fixture.componentInstance.openAdmission.subscribe((id) => emitted.admission.push(id));
    fixture.componentInstance.admit.subscribe((id) => emitted.admit.push(id));
    fixture.componentInstance.changed.subscribe(() => emitted.changed++);
    view = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function loadOk(data: BedBoard = board()): Promise<void> {
    http.expectOne(BOARD_URL).flush(data);
    await settle();
  }

  const tile = (name: string) => view.querySelector<HTMLElement>(`button[aria-label^="${name}"]`)!;
  const buttonText = (text: string) =>
    Array.from(view.querySelectorAll<HTMLElement>('button')).find((b) => b.textContent!.includes(text));

  afterEach(() => http.verify());

  it('shows a loading state, then the legend with five counts and icons', async () => {
    setup();
    expect(view.querySelector('[aria-busy=true]')).not.toBeNull();
    await loadOk();
    const items = Array.from(view.querySelectorAll('.blegend [role=listitem]'));
    expect(items.map((i) => i.textContent!.replace(/\s+/g, ' ').trim())).toEqual([
      'Available 1',
      'Occupied 1',
      'Cleaning 1',
      'Reserved 1',
      'Blocked 1',
    ]);
    items.forEach((i) => expect(i.querySelector('app-icon')).not.toBeNull());
  });

  it('flags an occupied bed with Deposit low and nothing on the other tiles', async () => {
    setup();
    const data = board();
    data.wards[0].rooms[0].beds[0].depositLow = true;
    await loadOk(data);
    const flag = tile('Bed 101-A').querySelector('.bflag.low')!;
    expect(flag.textContent!.trim()).toBe('Deposit low');
    expect(flag.querySelector('app-icon')).not.toBeNull();
    expect(tile('Bed 101-A').getAttribute('aria-label')).toContain('deposit low');
    expect(view.querySelectorAll('.bflag').length).toBe(1);
  });

  it('shows ward heading, station text, occupancy bar and room headings', async () => {
    setup();
    await loadOk();
    const ward = view.querySelector('.ward')!;
    expect(ward.querySelector('h2')!.textContent).toBe('General ward');
    expect(ward.querySelector('.faint')!.textContent).toBe('Nursing station 1 · 1 of 5 occupied');
    const bar = ward.querySelector('.occbar')!;
    expect(bar.getAttribute('role')).toBe('img');
    expect(bar.getAttribute('aria-label')).toBe('1 of 5 beds occupied');
    expect(ward.querySelector<HTMLElement>('.occbar i')!.style.width).toBe('20%');
    const headings = Array.from(ward.querySelectorAll('.roomh')).map((h) => h.textContent);
    expect(headings).toEqual(['Room 101']);
    expect(ward.querySelectorAll('.room').length).toBe(2);
  });

  it('shows a ward with no beds as an empty bar', async () => {
    setup();
    const data = board();
    data.wards[0] = { ...data.wards[0], occupied: 0, total: 0, rooms: [], hasFreeBed: false, firstFreeBedId: null };
    await loadOk(data);
    expect(view.querySelector<HTMLElement>('.occbar i')!.style.width).toBe('0%');
  });

  it('names tiles for screen readers and shows the content of each status', async () => {
    setup();
    await loadOk();
    expect(tile('Bed 101-A').getAttribute('aria-label')).toBe('Bed 101-A, General, Occupied, Sunil Jadhav');
    expect(tile('Bed 101-B').getAttribute('aria-label')).toBe('Bed 101-B, General, Cleaning');
    expect(tile('Bed 101-C').getAttribute('aria-label')).toBe('Bed 101-C, General, Available');
    const occ = tile('Bed 101-A').textContent!.replace(/\s+/g, ' ');
    expect(occ).toContain('Sunil Jadhav');
    expect(occ).toContain('Day 2 · Dr. Meera');
    expect(tile('Bed 101-C').textContent).toContain('₹800 a day');
    expect(tile('Bed 101-B').textContent).not.toContain('a day');
    expect(tile('Bed 104-A').textContent).not.toContain('a day');
    expect(tile('Bed 104-A').className).toContain('st-res');
    expect(tile('Bed 101-A').tagName).toBe('BUTTON');
  });

  it('opens the admission for an occupied bed', async () => {
    setup();
    await loadOk();
    tile('Bed 101-A').click();
    expect(emitted.admission).toEqual(['a1']);
    expect(dialog.open).not.toHaveBeenCalled();
  });

  it('starts an admission for a free bed only when the user can admit', async () => {
    setup();
    await loadOk();
    tile('Bed 101-C').click();
    expect(emitted.admit).toEqual(['b3']);
  });

  it('does nothing for a free bed when the user cannot admit', async () => {
    setup();
    await loadOk(board({ canAdmit: false }));
    tile('Bed 101-C').click();
    tile('Bed 104-A').click();
    expect(emitted.admit).toEqual([]);
    expect(dialog.open).not.toHaveBeenCalled();
    expect(buttonText('Admit here')).toBeUndefined();
  });

  it('marks tiles that do nothing as aria-disabled and leaves the others alone', async () => {
    setup();
    await loadOk(board({ canAdmit: false }));
    expect(tile('Bed 101-C').getAttribute('aria-disabled')).toBe('true');
    expect(tile('Bed 104-A').getAttribute('aria-disabled')).toBe('true');
    expect(tile('Bed 101-A').hasAttribute('aria-disabled')).toBe(false);
    expect(tile('Bed 101-B').hasAttribute('aria-disabled')).toBe(false);
  });

  it('does not mark a free bed aria-disabled when the user can admit', async () => {
    setup();
    await loadOk();
    expect(tile('Bed 101-C').hasAttribute('aria-disabled')).toBe(false);
    expect(tile('Bed 104-A').getAttribute('aria-disabled')).toBe('true');
  });

  it('shows Admit here on a ward with a free bed and uses the first free bed', async () => {
    setup();
    await loadOk();
    buttonText('Admit here')!.click();
    expect(emitted.admit).toEqual(['b3']);
  });

  it('hides Admit here when the ward has no free bed', async () => {
    setup();
    const data = board();
    data.wards[0] = { ...data.wards[0], hasFreeBed: false, firstFreeBedId: null };
    await loadOk(data);
    expect(buttonText('Admit here')).toBeUndefined();
  });

  it('opens the Bed sheet for cleaning and blocked beds, and toasts and updates after an action', async () => {
    const updated = board({ counts: { free: 2, occ: 1, clean: 0, res: 1, blk: 1 } });
    setup({ board: updated, message: 'Bed 101-B cleaned and ready for admission' });
    await loadOk();
    tile('Bed 101-B').click();
    const config = dialog.open.mock.calls[0][1];
    expect(config.data.bed.code).toBe('101-B');
    expect(config.data.wardName).toBe('General ward');
    expect(config.data.canChange).toBe(true);
    expect(snack).toHaveBeenCalledWith('Bed 101-B cleaned and ready for admission', 'Close', expect.anything());
    expect(emitted.changed).toBe(1);
    await settle();
    expect(view.querySelector('.blegend .st-free b')!.textContent).toBe('2');
    tile('Bed 103-C').click();
    expect(dialog.open.mock.calls[1][1].data.bed.code).toBe('103-C');
  });

  it('toasts the server message and reloads the board when a bed change is refused', async () => {
    setup({ failed: true, message: 'This bed cannot be changed from Available.' });
    await loadOk();
    tile('Bed 101-B').click();
    expect(snack).toHaveBeenCalledWith('This bed cannot be changed from Available.', 'Close', expect.anything());
    http.expectOne(BOARD_URL).flush(board());
    await settle();
    expect(emitted.changed).toBe(1);
  });

  it('does nothing when the Bed sheet is closed without a change', async () => {
    setup(null);
    await loadOk();
    tile('Bed 101-B').click();
    expect(snack).not.toHaveBeenCalled();
    expect(emitted.changed).toBe(0);
  });

  it('refreshes from the Refresh button and keeps the board while loading', async () => {
    setup();
    await loadOk();
    buttonText('Refresh')!.click();
    expect(view.querySelector('.ward')).not.toBeNull();
    http.expectOne(BOARD_URL).flush(board({ counts: { free: 4, occ: 0, clean: 0, res: 0, blk: 0 } }));
    await settle();
    expect(view.querySelector('.blegend .st-free b')!.textContent).toBe('4');
  });

  it('shows an error with a retry when the board cannot be loaded', async () => {
    setup();
    http
      .expectOne(BOARD_URL)
      .flush({ type: 'x', title: 'x', status: 500, detail: 'Server trouble' }, { status: 500, statusText: 'Error' });
    await settle();
    expect(view.querySelector('[role=alert]')!.textContent).toContain('Server trouble');
    buttonText('Try again')!.click();
    await loadOk();
    expect(view.querySelector('[role=alert]')).toBeNull();
    expect(view.querySelector('.ward')).not.toBeNull();
  });

  it('uses a plain message when the error has no detail', async () => {
    setup();
    http.expectOne(BOARD_URL).error(new ProgressEvent('error'));
    await settle();
    expect(view.querySelector('[role=alert]')!.textContent).toContain('We could not load the bed board. Try again.');
  });

  it('shows the go flag instead of Deposit low, and names it for screen readers', async () => {
    setup();
    const data = board();
    data.wards[0].rooms[0].beds[0].goingHomeToday = true;
    data.wards[0].rooms[0].beds[0].depositLow = true;
    await loadOk(data);
    const flags = Array.from(tile('Bed 101-A').querySelectorAll('.bflag')).map((f) => f.textContent!.trim());
    expect(flags).toEqual(['Going home today']);
    expect(tile('Bed 101-A').querySelector('.bflag.go')).not.toBeNull();
    expect(tile('Bed 101-A').querySelector('.bflag.low')).toBeNull();
    expect(tile('Bed 101-A').getAttribute('aria-label')).toContain('going home today');
    expect(tile('Bed 101-A').getAttribute('aria-label')).not.toContain('deposit low');
  });

  it('shows only the going-home flag when the deposit is fine', async () => {
    setup();
    const data = board();
    data.wards[0].rooms[0].beds[0].goingHomeToday = true;
    await loadOk(data);
    expect(tile('Bed 101-A').querySelectorAll('.bflag').length).toBe(1);
    expect(tile('Bed 101-A').querySelector('.bflag.go')!.textContent!.trim()).toBe('Going home today');
  });
});
