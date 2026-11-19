import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { IconComponent } from '../../../shared/icon/icon.component';
import { PharmacyContextStore } from '../pharmacy-context.store';
import { loadErrorMessage, problemSlug, saveErrorMessage } from '../pharmacy-display.util';
import { PharmacyApi } from '../pharmacy.api';
import { DocumentSeries } from '../pharmacy.model';
import {
  SERIES_MESSAGES,
  SeriesDraft,
  docTypeLabel,
  seriesExample,
  seriesInput,
  validateSeries,
} from './documents.util';

const LENGTHS = [4, 5, 6, 7, 8] as const;

/**
 * One numbering series: Prefix, Financial year part, Sequence length, "Restarts every 1 April" and the live
 * example (story 78-10). Once a number of the current financial year has been issued the whole series is
 * read-only with the note "Numbers have already been issued this year." (contract 0.2.1, DEC-078 item 4);
 * until then the prefix and the format can be changed. A reader sees the values as text. Each series saves on
 * its own, as the contract has one call per document type.
 */
@Component({
  selector: 'app-series-row',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, IconComponent],
  styles: `
    :host {
      display: block;
    }
    .row {
      display: grid;
      grid-template-columns: minmax(150px, 1.2fr) repeat(4, minmax(120px, 1fr)) minmax(160px, 1.2fr);
      gap: 12px;
      align-items: start;
      padding: 10px 0;
      border-top: 1px solid var(--line);
    }
    .name {
      font-weight: 600;
      padding-top: 14px;
    }
    .example {
      padding-top: 6px;
    }
    .example code {
      font-weight: 600;
    }
    .sub {
      font-size: 0.76rem;
      color: var(--ink-3);
    }
    .actions-row {
      grid-column: 1 / -1;
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      align-items: center;
    }
    @media (max-width: 900px) {
      .row {
        grid-template-columns: 1fr 1fr;
      }
      .name {
        grid-column: 1 / -1;
        padding-top: 0;
      }
    }
  `,
  template: `
    @if (editable() && !locked()) {
      <form class="row" [formGroup]="form" (ngSubmit)="save()" novalidate [attr.data-testid]="'series-' + series().docType">
        <div class="name">{{ name() }}</div>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Prefix</mat-label>
          <input
            matInput
            formControlName="prefix"
            maxlength="6"
            autocomplete="off"
            [attr.data-testid]="'series-' + series().docType + '-prefix'"
          />
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Financial year part</mat-label>
          <mat-select
            formControlName="financialYearPart"
            [attr.data-testid]="'series-' + series().docType + '-fy'"
          >
            <mat-option [value]="true">On</mat-option>
            <mat-option [value]="false">Off</mat-option>
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Sequence length</mat-label>
          <mat-select
            formControlName="sequenceLength"
            [attr.data-testid]="'series-' + series().docType + '-length'"
          >
            @for (n of lengths; track n) {
              <mat-option [value]="n">{{ n }}</mat-option>
            }
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Restarts every 1 April</mat-label>
          <mat-select
            formControlName="restartsEachFinancialYear"
            [attr.data-testid]="'series-' + series().docType + '-restart'"
          >
            <mat-option [value]="true">Yes</mat-option>
            <mat-option [value]="false">No</mat-option>
          </mat-select>
        </mat-form-field>

        <div class="example">
          <span class="sub">Example</span><br />
          <code [attr.data-testid]="'series-' + series().docType + '-example'">{{ example() }}</code>
        </div>

        @if (prefixMessage(); as text) {
          <div class="notice err" role="alert" [attr.data-testid]="'series-' + series().docType + '-prefix-error'">
            <app-icon name="alert" [size]="16" /><span>{{ text }}</span>
          </div>
        }
        @for (text of problems(); track text) {
          <div class="notice err" role="alert"><app-icon name="alert" [size]="16" /><span>{{ text }}</span></div>
        }
        @if (formError(); as text) {
          <div class="notice err" role="alert" [attr.data-testid]="'series-' + series().docType + '-error'">
            <app-icon name="alert" [size]="16" />
            <span>{{ text }}</span>
          </div>
        }
        <div class="actions-row">
          <button
            mat-stroked-button
            type="submit"
            [disabled]="saving()"
            [attr.data-testid]="'series-' + series().docType + '-save'"
            [attr.aria-label]="'Save ' + name() + ' numbering'"
          >
            Save
          </button>
          @if (savedNote()) {
            <span class="saved" role="status">Saved</span>
          }
        </div>
      </form>
    } @else {
      <div class="row" [attr.data-testid]="'series-' + series().docType">
        <div class="name">{{ name() }}</div>
        <div>
          <span class="sub">Prefix</span><br />
          <span [attr.data-testid]="'series-' + series().docType + '-prefix'">{{ series().prefix }}</span>
        </div>
        <div>
          <span class="sub">Financial year part</span><br />{{ series().financialYearPart ? 'On' : 'Off' }}
        </div>
        <div><span class="sub">Sequence length</span><br />{{ series().sequenceLength }}</div>
        <div>
          <span class="sub">Restarts every 1 April</span><br />{{ series().restartsEachFinancialYear ? 'Yes' : 'No' }}
        </div>
        <div class="example">
          <span class="sub">Example</span><br />
          <code [attr.data-testid]="'series-' + series().docType + '-example'">{{ series().example }}</code>
        </div>
        @if (locked()) {
          <div class="notice teal" [attr.data-testid]="'series-' + series().docType + '-locked'">
            <app-icon name="lock" [size]="16" />
            <span>{{ lockedText }}</span>
          </div>
        }
      </div>
    }
  `,
})
export class SeriesRowComponent {
  private readonly api = inject(PharmacyApi);

  readonly series = input.required<DocumentSeries>();
  readonly editable = input(false);
  /** The series was saved, or the server says it changed: the list is read again. */
  readonly changed = output<void>();

  protected readonly lengths = LENGTHS;
  protected readonly lockedText = SERIES_MESSAGES.locked;
  protected readonly name = computed(() => docTypeLabel(this.series().docType));
  protected readonly locked = computed(() => this.series().numbersIssued);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly savedNote = signal(false);
  protected readonly problems = signal<string[]>([]);
  private readonly serverPrefix = signal<string | null>(null);
  private readonly touched = signal(false);

  protected readonly form = new FormGroup({
    prefix: new FormControl('', { nonNullable: true }),
    financialYearPart: new FormControl(false, { nonNullable: true }),
    sequenceLength: new FormControl(4, { nonNullable: true }),
    restartsEachFinancialYear: new FormControl(false, { nonNullable: true }),
  });
  private readonly draft = signal<SeriesDraft>({
    prefix: '',
    financialYearPart: false,
    sequenceLength: 4,
    restartsEachFinancialYear: false,
  });

  protected readonly example = computed(() => seriesExample(this.draft()));
  protected readonly prefixMessage = computed(() => {
    const server = this.serverPrefix();
    if (server) {
      return server;
    }
    return this.touched() ? validateSeries(this.draft()).prefix : null;
  });

  constructor() {
    effect(() => {
      const s = this.series();
      this.form.reset({
        prefix: s.prefix,
        financialYearPart: s.financialYearPart,
        sequenceLength: s.sequenceLength,
        restartsEachFinancialYear: s.restartsEachFinancialYear,
      }, { emitEvent: false });
      this.draft.set(this.form.getRawValue());
    });
    this.form.valueChanges.subscribe(() => {
      this.draft.set(this.form.getRawValue());
      this.serverPrefix.set(null);
      this.savedNote.set(false);
    });
  }

  protected save(): void {
    if (this.saving()) {
      return;
    }
    this.formError.set(null);
    this.problems.set([]);
    this.touched.set(true);
    const draft = this.form.getRawValue();
    const errors = validateSeries(draft);
    const rest = [errors.sequenceLength, errors.restart, errors.tooLong].filter(
      (m): m is string => m !== null,
    );
    if (errors.prefix || rest.length > 0) {
      this.problems.set(rest);
      return;
    }
    this.saving.set(true);
    this.api.saveDocumentSeries(this.series().docType, seriesInput(this.series().version, draft)).subscribe({
      next: () => {
        this.saving.set(false);
        this.savedNote.set(true);
        this.changed.emit();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        const slug = problemSlug(error);
        if (slug === 'prefix-in-use') {
          this.serverPrefix.set(SERIES_MESSAGES.samePrefix);
          return;
        }
        if (slug === 'series-locked') {
          // A number was issued while the page was open: the series is read-only now, so read it again.
          this.formError.set(SERIES_MESSAGES.locked);
          this.changed.emit();
          return;
        }
        this.formError.set(saveErrorMessage(error));
        if (slug === 'settings-changed') {
          this.changed.emit();
        }
      },
    });
  }
}

/** "Numbering" panel (story 78-10): the series the server returns, one row each; never a fixed five. */
@Component({
  selector: 'app-numbering',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, SeriesRowComponent],
  styles: `
    :host {
      display: block;
    }
  `,
  template: `
    <section class="panel surf" aria-labelledby="nm-h" data-testid="numbering">
      <div class="panel-h">
        <h2 id="nm-h">Numbering</h2>
      </div>
      @if (loading()) {
        <p class="loading" role="status" data-testid="numbering-loading">Loading numbering</p>
      } @else if (loadError(); as text) {
        <div class="notice err" role="alert" data-testid="numbering-error">
          <app-icon name="alert" [size]="16" />
          <span>{{ text }}</span>
        </div>
        <div class="actions">
          <button mat-stroked-button type="button" (click)="load()">Try again</button>
        </div>
      } @else {
        @for (series of items(); track series.docType) {
          <app-series-row [series]="series" [editable]="canEdit()" (changed)="load(true)" />
        }
        <p class="note">
          A change applies to numbers created after it. Numbers already issued are never changed, reused or
          skipped. The bill series is a legal matter: your accountant confirms it before real billing.
        </p>
      }
    </section>
  `,
})
export class NumberingPanel implements OnInit {
  private readonly api = inject(PharmacyApi);
  private readonly context = inject(PharmacyContextStore);

  protected readonly canEdit = this.context.canEditSetup;
  protected readonly items = signal<DocumentSeries[]>([]);
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
    this.api.documentSeries().subscribe({
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
