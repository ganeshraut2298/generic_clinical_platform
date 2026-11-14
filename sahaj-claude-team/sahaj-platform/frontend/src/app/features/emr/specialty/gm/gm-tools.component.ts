import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { newClientKey } from '../../../../shared/client-key';
import { detailOr } from '../../emr-errors';
import { EmrPrescriptionRow, EmrRxFrequency, EmrRxWhen } from '../../emr-phase3.model';
import { EmrListKind } from '../../emr.model';
import { SpecialtyApi } from '../specialty.api';
import { SpecialtyContext } from '../specialty.context';
import { EmrVisitTemplate, EmrVisitTemplateItems } from '../specialty.model';
import { GmTrendComponent } from './gm-editors';

const LISTS: { field: 'complaints' | 'diagnoses' | 'investigations' | 'services' | 'advice'; kind: EmrListKind }[] = [
  { field: 'complaints', kind: 'COMPLAINTS' },
  { field: 'diagnoses', kind: 'DIAGNOSIS' },
  { field: 'investigations', kind: 'INVESTIGATIONS' },
  { field: 'services', kind: 'SERVICES' },
  { field: 'advice', kind: 'ADVICE' },
];

interface Undo {
  name: string;
  lists: { kind: EmrListKind; ids: string[] }[];
  rxIds: string[];
}

/**
 * "Quick tools" of General Medicine (SAH-60-4, ADR-023 D10): "Start from a visit template" and the
 * trend of the last visits. Applying a template is a merge into what is on screen (the same text twice
 * is skipped, then the normal draft save applies the allergy check and the bill lines); Undo takes out
 * exactly what the merge added. Saving the current visit as a template is an analyst addition (H12): the
 * clinic's doctors share it. Service prices are the clinic tariff's.
 */
@Component({
  selector: 'app-gm-tools',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [GmTrendComponent],
  styleUrl: '../specialty.shared.scss',
  template: `
    <div class="sp">
      <h4 class="sp-h">Start from a visit template</h4>
      <p class="sp-sub">Fills complaints, diagnosis, tests, medicines and advice in one tap. Change anything after.</p>
      @if (error(); as e) {
        <p class="msg crit" role="alert" data-testid="tpl-error">{{ e }}</p>
      }
      <div class="sp-col">
        @for (t of templates(); track t.id) {
          <button type="button" class="tile" [disabled]="readOnly() || busy()" data-act="apply-template" (click)="apply(t)">
            <b>{{ t.name }}</b><span class="sp-sub">{{ t.subLine }}</span>
          </button>
        } @empty {
          <p class="sp-sub" data-testid="tpl-empty">No visit templates yet.</p>
        }
      </div>
      @if (message(); as m) {
        <div class="msg info" role="status">
          <span>{{ m }}</span>
          @if (undo()) {
            <button type="button" class="link" data-act="undo-template" (click)="doUndo()">Undo</button>
          }
        </div>
      }
      @if (!readOnly()) {
        <details>
          <summary class="link">Save this visit as a template</summary>
          <div class="sp-col save">
            <label class="field"><span class="sp-lbl">Name</span><input class="input" type="text" maxlength="60" [value]="name()" (input)="name.set(val($event))" /></label>
            <label class="field"><span class="sp-lbl">Short line under the name</span><input class="input" type="text" maxlength="120" [value]="subLine()" (input)="subLine.set(val($event))" /></label>
            <button type="button" class="btn" data-act="save-template" [disabled]="!name().trim() || busy()" (click)="saveTemplate()">Save template</button>
          </div>
        </details>
      }
      <app-gm-trend />
    </div>
  `,
  styles: ['.sp-col{display:flex;flex-direction:column;gap:8px}.save{margin-top:8px}'],
})
export class GmToolsComponent implements OnInit {
  private readonly api = inject(SpecialtyApi);
  private readonly ctx = inject(SpecialtyContext);
  protected readonly readOnly = this.ctx.readOnly;
  protected readonly templates = signal<EmrVisitTemplate[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly undo = signal<Undo | null>(null);
  protected readonly name = signal('');
  protected readonly subLine = signal('');

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.api.listVisitTemplates().subscribe({
      next: (t) => this.templates.set(t),
      error: (e: unknown) => this.error.set(detailOr(e, 'We could not load the visit templates. Try again.')),
    });
  }

  protected val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected apply(t: EmrVisitTemplate): void {
    const store = this.ctx.store;
    const undo: Undo = { name: t.name, lists: [], rxIds: [] };
    for (const l of LISTS) {
      const entries = t.items[l.field] ?? [];
      if (entries.length > 0) {
        undo.lists.push({ kind: l.kind, ids: store.addListEntries(l.kind, entries) });
      }
    }
    const meds = t.items.medicines ?? [];
    if (meds.length > 0) {
      const rows: EmrPrescriptionRow[] = meds.map((m) => ({
        id: newClientKey(),
        medicineId: m.medicineId,
        name: m.name,
        form: null,
        frequency: (m.frequency as EmrRxFrequency | null | undefined) ?? '1-0-1',
        days: m.days ?? 5,
        when: (m.when as EmrRxWhen | null | undefined) ?? 'AFTER_FOOD',
        source: m.medicineId ? 'SEARCH' : 'TYPED',
      }));
      const before = new Set(store.prescriptionRows().map((r) => r.name.toLowerCase()));
      store.addPrescriptionRows(rows);
      undo.rxIds = rows.filter((r) => !before.has(r.name.toLowerCase())).map((r) => r.name);
    }
    this.undo.set(undo);
    this.message.set(`Template applied: ${t.name}. Review each section.`);
    if (t.items.repeatLastRx) {
      store.copyItems({ source: 'LAST_SIGNED_PRESCRIPTION', what: 'MEDICINES' }).subscribe();
    }
  }

  protected doUndo(): void {
    const u = this.undo();
    if (!u) {
      return;
    }
    for (const l of u.lists) {
      this.ctx.store.removeListItems(l.kind, l.ids);
    }
    this.ctx.removeRx(u.rxIds);
    this.undo.set(null);
    this.message.set('Template undone.');
  }

  protected saveTemplate(): void {
    const store = this.ctx.store;
    const items: EmrVisitTemplateItems = {
      complaints: store.listEntries('COMPLAINTS'),
      diagnoses: store.listEntries('DIAGNOSIS'),
      investigations: store.listEntries('INVESTIGATIONS'),
      services: store.listEntries('SERVICES'),
      advice: store.listEntries('ADVICE'),
      medicines: store.prescriptionRows().map((r) => ({ medicineId: r.medicineId, name: r.name, frequency: r.frequency, days: r.days, when: r.when })),
      repeatLastRx: false,
    };
    this.busy.set(true);
    this.error.set(null);
    this.api.createVisitTemplate({ name: this.name().trim(), subLine: this.subLine().trim() || null, items }).subscribe({
      next: (t) => {
        this.busy.set(false);
        this.templates.update((all) => [...all, t].sort((a, b) => a.name.localeCompare(b.name)));
        this.name.set('');
        this.subLine.set('');
        this.message.set(`Saved as a visit template: ${t.name}.`);
        this.undo.set(null);
      },
      error: (e: unknown) => {
        this.busy.set(false);
        this.error.set(detailOr(e, 'We could not save the template. Try again.'));
      },
    });
  }
}
