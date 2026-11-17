import { HoldSheetComponent } from './hold-sheet.component';
import { describeReasonSheet } from './order-sheets.testing';

describeReasonSheet({
  name: 'HoldSheetComponent',
  component: HoldSheetComponent,
  path: 'hold',
  idPrefix: 'ord-hold',
  label: 'Reason for the hold',
  missing: 'Write why you are holding this medicine.',
  ok: '[data-act=hold-ok]',
  cancel: '[data-act=hold-cancel]',
  failure: '[data-testid=hold-failure]',
  toast: 'Tab. Paracetamol 650 mg is on hold.',
});
