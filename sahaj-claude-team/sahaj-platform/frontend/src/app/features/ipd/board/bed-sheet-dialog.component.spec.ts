import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { BedBoard, BoardBed } from '../ipd.model';
import { BedSheetData, BedSheetDialogComponent } from './bed-sheet-dialog.component';

const IPD = '/api/v1/ipd';
const BOARD = {
  state: 'on',
  roomLevel: true,
  canAdmit: true,
  canChangeBeds: true,
  counts: { free: 1, occ: 0, clean: 0, res: 0, blk: 0 },
  wards: [],
} as BedBoard;

function bed(status: BoardBed['status']): BoardBed {
  return { id: 'b1', code: '101-B', categoryKey: 'gen', categoryName: 'General', ratePaise: 80000, status };
}

describe('BedSheetDialogComponent', () => {
  let fixture: ComponentFixture<BedSheetDialogComponent>;
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;
  let view: HTMLElement;

  function setup(status: BoardBed['status'], canChange = true): void {
    close = vi.fn();
    const data: BedSheetData = { bed: bed(status), wardName: 'General ward', canChange };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(BedSheetDialogComponent);
    view = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  }

  const buttons = () => Array.from(view.querySelectorAll<HTMLElement>('.sheet-f button')).map((b) => b.textContent!.trim());
  const click = (text: string) =>
    Array.from(view.querySelectorAll<HTMLElement>('button')).find((b) => b.textContent!.includes(text))!.click();

  afterEach(() => http.verify());

  it('shows the title, subtitle, status pill and the cleaning text', () => {
    setup('clean');
    expect(view.querySelector('h2')!.textContent).toBe('Bed 101-B');
    expect(view.querySelector('.sub')!.textContent!.replace(/\s+/g, ' ').trim()).toBe(
      'General · ₹800 a day · General ward',
    );
    expect(view.querySelector('.pill')!.textContent).toContain('Cleaning');
    expect(view.querySelector('.txt')!.textContent).toContain('Housekeeping is preparing this bed');
    expect(buttons()).toEqual(['Close', 'Block bed', 'Cleaned · ready']);
  });

  it('offers only Unblock for a blocked bed', () => {
    setup('blk');
    expect(view.querySelector('.txt')!.textContent).toContain('This bed is out of use');
    expect(buttons()).toEqual(['Close', 'Unblock · ready']);
  });

  it('offers only Close to users who cannot change beds', () => {
    setup('clean', false);
    expect(buttons()).toEqual(['Close']);
  });

  it('marks the bed cleaned and closes with the new board', () => {
    setup('clean');
    click('Cleaned');
    http.expectOne({ method: 'POST', url: `${IPD}/beds/b1/ready` }).flush(BOARD);
    expect(close).toHaveBeenCalledWith({ board: BOARD, message: 'Bed 101-B cleaned and ready for admission' });
  });

  it('unblocks with its own toast text', () => {
    setup('blk');
    click('Unblock');
    http.expectOne(`${IPD}/beds/b1/ready`).flush(BOARD);
    expect(close).toHaveBeenCalledWith({ board: BOARD, message: 'Bed 101-B unblocked and ready for admission' });
  });

  it('blocks a cleaning bed', () => {
    setup('clean');
    click('Block bed');
    http.expectOne({ method: 'POST', url: `${IPD}/beds/b1/block` }).flush(BOARD);
    expect(close).toHaveBeenCalledWith({ board: BOARD, message: 'Bed 101-B blocked' });
  });

  it('closes with the server message on a 409', () => {
    setup('clean');
    click('Block bed');
    http.expectOne(`${IPD}/beds/b1/block`).flush(
      { type: 'bed-state', title: 'Bed state', status: 409, detail: 'This bed cannot be changed from Available.' },
      { status: 409, statusText: 'Conflict' },
    );
    expect(close).toHaveBeenCalledWith({ failed: true, message: 'This bed cannot be changed from Available.' });
  });

  it('closes without a result from Close', () => {
    setup('clean');
    click('Close');
    expect(close).toHaveBeenCalledWith(null);
  });
});
