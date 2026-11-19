import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { MAP_STAFF_CONFIG } from '../setup/map-staff.dialog';
import { STORE_FORM_CONFIG } from '../setup/store-form.dialog';
import { CONFIRM_SHEET_CONFIG, ConfirmSheetComponent } from './confirm-sheet.component';

describe('ConfirmSheetComponent (opened through MatDialog)', () => {
  it('gives the dialog an accessible name: aria-labelledby points to the sheet title', async () => {
    const ref = TestBed.inject(MatDialog).open(ConfirmSheetComponent, {
      ...CONFIRM_SHEET_CONFIG,
      data: { title: 'Change to Standalone mode?', body: 'Existing records are kept.', confirmLabel: 'Change' },
    });
    await firstValueFrom(ref.afterOpened());
    const container = document.querySelector('mat-dialog-container') as HTMLElement;
    const labelledBy = container.getAttribute('aria-labelledby');
    expect(labelledBy).toBe('confirm-title');
    expect(document.getElementById(labelledBy as string)?.textContent).toBe('Change to Standalone mode?');
    ref.close();
  });

  it('names the store and mapping sheets by the ids of their titles', () => {
    expect(STORE_FORM_CONFIG.ariaLabelledBy).toBe('store-form-title');
    expect(MAP_STAFF_CONFIG.ariaLabelledBy).toBe('map-title');
  });

  it('is a column capped by the screen whose body scrolls (QA-78-5)', async () => {
    const ref = TestBed.inject(MatDialog).open(ConfirmSheetComponent, {
      ...CONFIRM_SHEET_CONFIG,
      data: { title: 'Switch off?', body: 'Its history is kept.', confirmLabel: 'Switch off' },
    });
    await firstValueFrom(ref.afterOpened());
    const host = getComputedStyle(document.querySelector('app-confirm-sheet') as HTMLElement);
    expect(host.display).toBe('flex');
    expect(host.maxHeight).toMatch(/^92d?vh$/);
    expect(getComputedStyle(document.querySelector('app-confirm-sheet .sheet-b') as HTMLElement).overflowY).toBe('auto');
    ref.close();
  });
});
