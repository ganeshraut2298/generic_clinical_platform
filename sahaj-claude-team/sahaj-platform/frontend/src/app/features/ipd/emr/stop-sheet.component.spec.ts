import { StopSheetComponent } from './stop-sheet.component';
import { describeReasonSheet } from './order-sheets.testing';

describeReasonSheet({
  name: 'StopSheetComponent',
  component: StopSheetComponent,
  path: 'stop',
  idPrefix: 'ord-stop',
  label: 'Reason for stopping',
  missing: 'Write why you are stopping this medicine.',
  ok: '[data-act=stop-ok]',
  cancel: '[data-act=stop-cancel]',
  failure: '[data-testid=stop-failure]',
  toast: 'Tab. Paracetamol 650 mg stopped. Remaining doses are removed from the MAR.',
});
