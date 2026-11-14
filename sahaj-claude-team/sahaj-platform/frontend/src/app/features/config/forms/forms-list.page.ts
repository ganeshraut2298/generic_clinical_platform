import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Router, RouterLink } from '@angular/router';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EmrApi } from '../../emr/emr.api';
import { EmrFormApi } from '../../emr/emr-form.api';
import { detailOr } from '../../emr/emr-errors';
import { EmrFormList, EmrFormStarterKey, EmrFormStarterSummary } from '../../emr/emr-phase3.model';
import { departmentsText, summaryChip } from './form-builder.logic';

const LOAD_ERROR = 'We could not load the forms. Try again.';
const CREATE_ERROR = 'We could not start the form. Try again.';

/**
 * Form builder, start page (SAH-59-1, SAH-59-6; mockup #FormBuilder under Clinic setup): the
 * clinic's forms with their state chip, and the six starter forms ("Start from a form clinics in
 * India already use. Everything stays editable."). Every clinic role reads the list; only the
 * owner can start a form from a starter. The starters are global; the owner gets a copy.
 */
@Component({
  selector: 'app-forms-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, RouterLink, IconComponent],
  styleUrl: './forms-list.page.scss',
  templateUrl: './forms-list.page.html',
})
export class FormsListPage implements OnInit {
  private readonly api = inject(EmrFormApi);
  private readonly emrApi = inject(EmrApi);
  private readonly router = inject(Router);

  protected readonly list = signal<EmrFormList | null>(null);
  protected readonly starters = signal<EmrFormStarterSummary[]>([]);
  protected readonly departmentNames = signal<ReadonlyMap<string, string>>(new Map());
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly creating = signal<EmrFormStarterKey | null>(null);
  protected readonly createError = signal<string | null>(null);

  protected readonly canEdit = computed(() => this.list()?.canEdit ?? false);
  protected readonly forms = computed(() => this.list()?.forms ?? []);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list().subscribe({
      next: (list) => {
        this.list.set(list);
        this.loading.set(false);
      },
      error: (e: unknown) => {
        this.loading.set(false);
        this.error.set(detailOr(e, LOAD_ERROR));
      },
    });
    this.api.listStarters().subscribe({ next: (s) => this.starters.set(s), error: () => this.starters.set([]) });
    this.emrApi.listDepartments().subscribe({
      next: (r) => this.departmentNames.set(new Map(r.departments.map((d) => [d.departmentKey, d.name]))),
      error: () => undefined,
    });
  }

  protected chip(form: Parameters<typeof summaryChip>[0]): string {
    return summaryChip(form);
  }

  protected where(form: EmrFormList['forms'][number]): string {
    return departmentsText(form.useIn, this.departmentNames());
  }

  protected counts(form: EmrFormList['forms'][number]): string {
    return `${form.fieldCount} fields · ${form.logicCount} with logic · ${form.scoredCount} scored`;
  }

  /** "Use this starter": a copy opens in Build mode as the clinic's own draft v1; the starter never changes. */
  protected use(starter: EmrFormStarterSummary): void {
    if (!this.canEdit() || this.creating()) {
      return;
    }
    this.creating.set(starter.starterKey);
    this.createError.set(null);
    this.api.create(starter.starterKey, null, newClientKey()).subscribe({
      next: (form) => {
        this.creating.set(null);
        void this.router.navigate(['/app/config/forms', form.formKey]);
      },
      error: (e: unknown) => {
        this.creating.set(null);
        this.createError.set(detailOr(e, CREATE_ERROR));
      },
    });
  }
}
