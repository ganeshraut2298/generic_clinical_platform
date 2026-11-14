import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { problemDetailInterceptor } from '../../../core/http/problem-detail.interceptor';
import { Patient } from '../patient.model';
import { MergeConfirmDialogComponent, MergeConfirmDialogData } from './merge-confirm-dialog.component';

const URL = '/api/v1/patient/patients/merge';

const KEEP: Patient = {
  id: 'keep1',
  uhid: 'PT-00001',
  name: 'Rohit Patil',
  age: 30,
  ageDetail: { value: 30, unit: 'YEARS', fromDateOfBirth: false },
  sex: 'M',
  mobile: '9876543210',
  language: 'mr',
  abhaLinked: false,
  createdAt: '2026-01-01T00:00:00Z',
};
const MERGE_INTO: Patient = {
  id: 'merge1',
  uhid: 'PT-00009',
  name: 'Rohit Patel',
  age: 31,
  ageDetail: { value: 31, unit: 'YEARS', fromDateOfBirth: false },
  sex: 'M',
  mobile: '9876543210',
  language: 'mr',
  abhaLinked: false,
  createdAt: '2026-10-01T00:00:00Z',
};

describe('MergeConfirmDialogComponent', () => {
  let fixture: ComponentFixture<MergeConfirmDialogComponent>;
  let http: HttpTestingController;
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  async function setup(data: MergeConfirmDialogData = { keep: KEEP, mergeInto: MERGE_INTO }) {
    dialogRef = { close: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [MergeConfirmDialogComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRef },
        provideHttpClient(withInterceptors([problemDetailInterceptor])),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MergeConfirmDialogComponent);
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;

  afterEach(() => http.verify());

  it('shows both records, the keeper first', async () => {
    await setup();
    expect(el().textContent).toContain('Rohit Patil');
    expect(el().textContent).toContain('PT-00001');
    expect(el().textContent).toContain('Rohit Patel');
    expect(el().textContent).toContain('PT-00009');
  });

  it('merges and closes with the result', async () => {
    await setup();
    Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.includes('Merge records'))!.click();
    const req = http.expectOne(URL);
    expect(req.request.body).toEqual({ keepId: 'keep1', mergeId: 'merge1' });
    req.flush(KEEP);
    expect(dialogRef.close).toHaveBeenCalledWith(KEEP);
  });

  it('closes without merging on "Not the same person"', async () => {
    await setup();
    Array.from(el().querySelectorAll('button'))
      .find((b) => b.textContent!.includes('Not the same person'))!
      .click();
    expect(dialogRef.close).toHaveBeenCalledWith(null);
    http.expectNone(URL);
  });

  it('shows an error and does not close when the merge fails', async () => {
    await setup();
    Array.from(el().querySelectorAll('button')).find((b) => b.textContent!.includes('Merge records'))!.click();
    http.expectOne(URL).flush({ title: 'Not found', status: 404 }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    expect(el().querySelector('[role="alert"]')).toBeTruthy();
    expect(dialogRef.close).not.toHaveBeenCalled();
  });
});
