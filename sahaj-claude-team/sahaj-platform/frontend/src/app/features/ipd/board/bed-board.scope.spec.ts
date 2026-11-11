import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { BedBoard } from '../ipd.model';
import { BedBoardComponent } from './bed-board.component';

/** SAH-34 AC 9: an occupied bed outside the scope keeps its status; the patient fields are null and nothing opens. */
describe('BedBoardComponent: a bed outside the nurse wards (SAH-34)', () => {
  it('shows the status "Occupied" only: no name, no day line, no flag, and no action on the tile', async () => {
    const board: BedBoard = {
      state: 'on',
      roomLevel: false,
      canAdmit: true,
      canChangeBeds: true,
      counts: { free: 0, occ: 2, clean: 0, res: 0, blk: 0 },
      wards: [
        {
          id: 'w1',
          name: 'General ward',
          station: 'Nursing station 1',
          occupied: 2,
          total: 2,
          hasFreeBed: false,
          rooms: [
            {
              id: null,
              name: '',
              beds: [
                {
                  id: 'b1',
                  code: '101-A',
                  categoryKey: 'gen',
                  categoryName: 'General',
                  ratePaise: 80000,
                  status: 'occ',
                  admissionId: 'a1',
                  patientName: 'Sunil Jadhav',
                  dayNumber: 2,
                  doctorShortName: 'Dr. Meera',
                  depositLow: false,
                  goingHomeToday: false,
                },
                {
                  id: 'b2',
                  code: '102-A',
                  categoryKey: 'gen',
                  categoryName: 'General',
                  ratePaise: 80000,
                  status: 'occ',
                  admissionId: null,
                  patientName: null,
                  dayNumber: null,
                  doctorShortName: null,
                  depositLow: null,
                  goingHomeToday: null,
                  outsideScope: true,
                },
              ],
            },
          ],
        },
      ],
    };
    const opened: string[] = [];
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
        { provide: MatDialog, useValue: { open: vi.fn() } },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(BedBoardComponent);
    fixture.componentInstance.openAdmission.subscribe((id) => opened.push(id));
    fixture.detectChanges();
    http.expectOne('/api/v1/ipd/board').flush(board);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const tiles = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button.bed'));
    expect(tiles.length).toBe(2);
    expect(tiles[0].textContent).toContain('Sunil Jadhav');
    const hidden = tiles[1];
    expect(hidden.textContent).toContain('102-A');
    expect(hidden.textContent).toContain('Occupied');
    expect(hidden.textContent).not.toContain('Day');
    expect(hidden.textContent).not.toContain('null');
    expect(hidden.querySelector('.bpt')).toBeNull();
    expect(hidden.getAttribute('aria-label')).toBe('Bed 102-A, General, Occupied');
    expect(hidden.getAttribute('aria-disabled')).toBe('true');
    hidden.click();
    expect(opened).toEqual([]);
    tiles[0].click();
    expect(opened).toEqual(['a1']);
    http.verify();
  });
});
