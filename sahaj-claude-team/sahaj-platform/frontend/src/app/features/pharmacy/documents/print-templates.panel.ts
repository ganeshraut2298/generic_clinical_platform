import { ChangeDetectionStrategy, Component, OnInit, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ApiError } from '../../../core/http/problem-detail';
import { formatDisplayDate, istToday } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { applyFieldErrors, loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { PaperSize, PrintTemplate } from '../pharmacy.model';
import {
  FIXED_FIELD_LABELS,
  FOOTER_LIMIT,
  HEADER_LINE_LIMIT,
  PRINT_DOC_LABELS,
  PreviewModel,
  headerLineValidator,
  missingNotes,
  previewModel,
  printHtml,
  safePaperSize,
} from './documents.util';

const PAPER_SIZES: readonly PaperSize[] = ['A4', 'A5'];

/**
 * One print template card: Header lines (up to 3, each up to 80 characters; empty means the unit name,
 * address and phone of the clinic profile), a Footer note (up to 200 characters), Paper size and a preview
 * marked "Sample" (story 78-10). The fixed fields are listed and cannot be hidden. "Print preview" opens the
 * browser print view with sample values; the server makes no file.
 */
@Component({
  selector: 'app-print-template-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, IconComponent],
  styles: `
    :host {
      display: block;
    }
    .card {
      padding: 12px 0;
      border-top: 1px solid var(--line);
    }
    .layout {
      display: flex;
      flex-wrap: wrap;
      gap: 16px;
      align-items: flex-start;
    }
    .fields {
      flex: 1 1 340px;
      display: flex;
      flex-direction: column;
      gap: 10px;
      min-width: 0;
    }
    .preview {
      flex: 1 1 280px;
      border: 1px dashed var(--line);
      border-radius: 8px;
      padding: 12px;
      min-width: 0;
    }
    .preview dl {
      margin: 8px 0;
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 2px 12px;
    }
    .preview dt {
      color: var(--ink-3);
    }
    .preview dd {
      margin: 0;
    }
    .foot {
      font-size: 0.8rem;
      color: var(--ink-3);
      margin: 6px 0 0;
    }
    .fixed {
      font-size: 0.8rem;
      color: var(--ink-3);
    }
    .row-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      align-items: center;
    }
  `,
  template: `
    <div class="card" [attr.data-testid]="'template-' + template().docType">
      <h3>{{ title() }}</h3>
      <div class="layout">
        @if (editable()) {
          <form class="fields" [formGroup]="form" (ngSubmit)="save()" novalidate>
            @for (n of lines; track n) {
              <mat-form-field appearance="outline" subscriptSizing="dynamic">
                <mat-label>Header line {{ n + 1 }}</mat-label>
                <input
                  matInput
                  [formControl]="form.controls.headerLines.controls[n]"
                  [attr.maxlength]="headerLimit"
                  autocomplete="off"
                  [attr.data-testid]="'template-' + template().docType + '-header-' + (n + 1)"
                />
                @if (headerMessage(n); as text) {
                  <mat-error>{{ text }}</mat-error>
                }
              </mat-form-field>
            }
            <p class="fixed">
              Leave the header lines empty to print the unit name, address and phone from the clinic profile.
            </p>
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Footer note</mat-label>
              <textarea
                matInput
                rows="2"
                formControlName="footerNote"
                [attr.maxlength]="footerLimit"
                [attr.data-testid]="'template-' + template().docType + '-footer'"
              ></textarea>
              @if (footerMessage(); as text) {
                <mat-error>{{ text }}</mat-error>
              }
            </mat-form-field>
            <mat-form-field appearance="outline" subscriptSizing="dynamic">
              <mat-label>Paper size</mat-label>
              <mat-select
                formControlName="paperSize"
                [attr.data-testid]="'template-' + template().docType + '-paper'"
              >
                @for (size of paperSizes; track size) {
                  <mat-option [value]="size">{{ size }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
            @for (text of extraErrors(); track text) {
              <div class="notice err" role="alert"><app-icon name="alert" [size]="16" /><span>{{ text }}</span></div>
            }
            @if (formError(); as text) {
              <div class="notice err" role="alert" [attr.data-testid]="'template-' + template().docType + '-error'">
                <app-icon name="alert" [size]="16" />
                <span>{{ text }}</span>
              </div>
            }
            <div class="row-actions">
              <button
                mat-stroked-button
                type="submit"
                [disabled]="saving()"
                [attr.data-testid]="'template-' + template().docType + '-save'"
                [attr.aria-label]="'Save ' + title() + ' template'"
              >
                Save template
              </button>
              <button
                mat-stroked-button
                type="button"
                [attr.data-testid]="'template-' + template().docType + '-print'"
                [attr.aria-label]="'Print preview of ' + title()"
                (click)="printPreview()"
              >
                Print preview
              </button>
              @if (savedNote()) {
                <span class="saved" role="status">Saved</span>
              }
            </div>
          </form>
        } @else {
          <dl class="fields" [attr.data-testid]="'template-' + template().docType + '-read'">
            <div>
              <dt class="fixed">Header lines</dt>
              <dd>{{ readHeader() }}</dd>
            </div>
            <div>
              <dt class="fixed">Footer note</dt>
              <dd>{{ template().footerNote || '—' }}</dd>
            </div>
            <div>
              <dt class="fixed">Paper size</dt>
              <dd>{{ template().paperSize }}</dd>
            </div>
            <div class="row-actions">
              <button
                mat-stroked-button
                type="button"
                [attr.data-testid]="'template-' + template().docType + '-print'"
                [attr.aria-label]="'Print preview of ' + title()"
                (click)="printPreview()"
              >
                Print preview
              </button>
            </div>
          </dl>
        }

        <div class="preview" [attr.data-testid]="'template-' + template().docType + '-preview'" aria-label="Preview">
          @for (line of preview().header; track $index) {
            <div>{{ line }}</div>
          }
          <strong>{{ preview().title }}</strong>
          <dl>
            @for (field of preview().fields; track field.label) {
              <dt>{{ field.label }}</dt>
              <dd>{{ field.value }}</dd>
            }
          </dl>
          @if (preview().footer) {
            <p class="foot">{{ preview().footer }}</p>
          }
          <p class="foot">Paper size {{ preview().paperSize }}</p>
          <p class="fixed" [attr.data-testid]="'template-' + template().docType + '-fixed'">
            Fixed fields, always printed: {{ fixedList() }}.
          </p>
        </div>
      </div>
      @for (text of notes(); track text) {
        <div class="notice warn" role="status" [attr.data-testid]="'template-' + template().docType + '-missing'">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
      }
    </div>
  `,
})
export class PrintTemplateCard {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  readonly template = input.required<PrintTemplate>();
  readonly editable = input(false);
  /** The template was saved, or the server says it changed: the list is read again. */
  readonly changed = output<void>();

  protected readonly paperSizes = PAPER_SIZES;
  protected readonly lines = [0, 1, 2] as const;
  protected readonly headerLimit = HEADER_LINE_LIMIT;
  protected readonly footerLimit = FOOTER_LIMIT;
  protected readonly title = computed(() => PRINT_DOC_LABELS[this.template().docType]);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly extraErrors = signal<string[]>([]);
  protected readonly savedNote = signal(false);
  protected readonly notes = computed(() => missingNotes(this.template()));
  protected readonly fixedList = computed(() =>
    this.template()
      .fixedFields.map((f) => FIXED_FIELD_LABELS[f])
      .join(', '),
  );

  protected readonly form = new FormGroup({
    headerLines: new FormGroup({
      0: new FormControl('', { nonNullable: true, validators: [headerLineValidator] }),
      1: new FormControl('', { nonNullable: true, validators: [headerLineValidator] }),
      2: new FormControl('', { nonNullable: true, validators: [headerLineValidator] }),
    }),
    footerNote: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(FOOTER_LIMIT)] }),
    paperSize: new FormControl<PaperSize>('A4', { nonNullable: true }),
  });

  private readonly draft = signal<{ headerLines: string[]; footerNote: string; paperSize: PaperSize }>({
    headerLines: [],
    footerNote: '',
    paperSize: 'A4',
  });

  protected readonly preview = computed<PreviewModel>(() =>
    previewModel(
      this.template(),
      this.editable() ? this.draft() : this.savedDraft(),
      this.context.unitName(),
      formatDisplayDate(istToday()),
    ),
  );

  private readonly savedDraft = computed(() => ({
    headerLines: this.template().headerLines,
    footerNote: this.template().footerNote ?? '',
    paperSize: safePaperSize(this.template().paperSize),
  }));

  constructor() {
    effect(() => {
      const t = this.template();
      this.form.reset(
        {
          headerLines: {
            0: t.headerLines[0] ?? '',
            1: t.headerLines[1] ?? '',
            2: t.headerLines[2] ?? '',
          },
          footerNote: t.footerNote ?? '',
          paperSize: t.paperSize,
        },
        { emitEvent: false },
      );
      this.readDraft();
    });
    this.form.valueChanges.subscribe(() => {
      this.readDraft();
      this.savedNote.set(false);
    });
  }

  private readDraft(): void {
    const raw = this.form.getRawValue();
    this.draft.set({
      headerLines: [raw.headerLines[0], raw.headerLines[1], raw.headerLines[2]],
      footerNote: raw.footerNote,
      paperSize: safePaperSize(raw.paperSize),
    });
  }

  protected readHeader(): string {
    const lines = this.template().headerLines;
    return lines.length > 0 ? lines.join(' · ') : 'Unit name, address and phone from the clinic profile';
  }

  protected headerMessage(n: 0 | 1 | 2): string | null {
    const control = this.form.controls.headerLines.controls[n];
    const server = control.errors?.['server'];
    if (typeof server === 'string') {
      return server;
    }
    return control.errors?.['maxlength'] ? 'Keep each header line to 80 characters' : null;
  }

  protected footerMessage(): string | null {
    const control = this.form.controls.footerNote;
    const server = control.errors?.['server'];
    if (typeof server === 'string') {
      return server;
    }
    return control.value.length > FOOTER_LIMIT ? 'Keep the footer to 200 characters' : null;
  }

  protected save(): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    this.extraErrors.set([]);
    this.savedNote.set(false);
    const raw = this.form.getRawValue();
    const lines = [raw.headerLines[0], raw.headerLines[1], raw.headerLines[2]]
      .map((l) => l.trim())
      .filter((l) => l !== '');
    if (lines.some((l) => l.length > HEADER_LINE_LIMIT) || raw.footerNote.length > FOOTER_LIMIT) {
      this.form.markAllAsTouched();
      return;
    }
    const footer = raw.footerNote.trim();
    this.saving.set(true);
    this.api
      .savePrintTemplate(this.template().docType, {
        version: this.template().version,
        headerLines: lines,
        footerNote: footer === '' ? null : footer,
        paperSize: raw.paperSize,
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.savedNote.set(true);
          this.changed.emit();
        },
        error: (error: unknown) => {
          this.saving.set(false);
          if (error instanceof ApiError && error.status === 400 && error.fieldErrors.length > 0) {
            const rest = applyFieldErrors(
              {
                footerNote: this.form.controls.footerNote,
                paperSize: this.form.controls.paperSize,
              },
              error.fieldErrors,
            );
            this.extraErrors.set(rest);
            return;
          }
          this.formError.set(saveErrorMessage(error));
          if (problemSlug(error) === 'settings-changed') {
            this.changed.emit();
          }
        },
      });
  }

  /** Opens the browser print view with the sample values; no file is made by the server (DEC-061). */
  protected printPreview(): void {
    const html = printHtml(this.preview());
    const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }));
    const view = window.open(url, '_blank');
    if (view) {
      view.addEventListener('load', () => {
        view.print();
        URL.revokeObjectURL(url);
      });
    }
  }
}

/** "Print templates" panel (story 78-10): Invoice, Issue slip and Debit note, as the server returns them. */
@Component({
  selector: 'app-print-templates',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, PrintTemplateCard],
  styles: `
    :host {
      display: block;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="pt-h" data-testid="print-templates">
      <div class="panel-h">
        <h2 id="pt-h">Print templates</h2>
      </div>
      @if (loading()) {
        <p class="loading" role="status" data-testid="templates-loading">Loading print templates</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="templates-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
        <div class="actions">
          <button mat-stroked-button type="button" (click)="load()">Try again</button>
        </div>
      } @else {
        @for (template of items(); track template.docType) {
          <app-print-template-card [template]="template" [editable]="canEdit()" (changed)="load(true)" />
        }
        <p class="note">
          A saved change applies to documents printed afterwards. Documents already printed are not changed. The
          print view is made by your browser.
        </p>
      }
    </section>
  `,
})
export class PrintTemplatesPanel implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  protected readonly canEdit = this.context.canEditSetup;
  protected readonly items = signal<PrintTemplate[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected load(quiet = false): void {
    if (!quiet) {
      this.loading.set(true);
    }
    this.loadError.set(null);
    this.api.printTemplates().subscribe({
      next: (items) => {
        this.items.set(items);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(loadErrorMessage(error));
      },
    });
  }
}
