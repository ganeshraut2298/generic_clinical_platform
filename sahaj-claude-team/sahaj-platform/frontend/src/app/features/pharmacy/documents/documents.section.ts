import { ChangeDetectionStrategy, Component } from '@angular/core';
import { NumberingPanel } from './numbering.panel';
import { PrintTemplatesPanel } from './print-templates.panel';
import { ReasonsPanel } from './reasons.panel';

/**
 * "Numbering and documents" section of Inventory setup (story 78-10; a NEW section: no mockup screen draws it,
 * and the accepted design of DEC-062 Q1 puts the three parts in one section): Numbering, Reasons and Print
 * templates, one panel each. Each panel reads what the server returns; a reader of setup sees the same panels
 * as text and no button.
 */
@Component({
  selector: 'app-documents',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NumberingPanel, ReasonsPanel, PrintTemplatesPanel],
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 12px;
      min-width: 0;
    }
  `,
  template: `
    <app-numbering />
    <app-reasons />
    <app-print-templates />
  `,
})
export class DocumentsSection {}
