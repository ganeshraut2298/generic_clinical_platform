import { Injectable, inject } from '@angular/core';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { EnquirySheetComponent, EnquirySheetData } from './enquiry-sheet.component';
import { EnquirySource } from './sales-enquiry.model';

/** Opens the enquiry sheet for Contact sales (CONTACT_SALES) or Book a Demo (BOOK_DEMO). */
@Injectable({ providedIn: 'root' })
export class EnquirySheet {
  private readonly dialog = inject(MatDialog);

  open(source: EnquirySource): MatDialogRef<EnquirySheetComponent, boolean> {
    return this.dialog.open<EnquirySheetComponent, EnquirySheetData, boolean>(
      EnquirySheetComponent,
      {
        data: { source },
        width: '580px',
        maxWidth: '100vw',
        panelClass: 'sheet-panel',
        ariaLabelledBy: 'enquiry-title',
        autoFocus: 'input[formControlName="name"]',
      },
    );
  }
}
