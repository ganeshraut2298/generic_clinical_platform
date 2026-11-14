import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { newClientKey } from '../../../shared/client-key';
import { IconComponent } from '../../../shared/icon/icon.component';
import { detailOr } from '../emr-errors';
import { EmrRecordStore } from '../record/emr-record.store';
import { CourseApi } from './course.api';
import { CoursePage } from './course.page';
import { TreatmentCourseKind } from './course.model';

/** The title of the empty panel by kind (the design's course names; the course itself brings its own name). */
export const KIND_TITLE: Readonly<Record<TreatmentCourseKind, string>> = {
  DENTAL: 'Dental treatment course',
  ANC: 'Pregnancy care · ANC course',
  REHAB: 'Physiotherapy package',
  SURGERY: 'Surgery episode',
  CHEMO: 'Chemotherapy course',
};

export const NO_COURSE = 'No course started yet.';

/**
 * The treatment course inside a doctor screen (SAH-75-3, ADR-026 D3): the open course of this patient and this
 * department's kind (newest ACTIVE first), shown by the same component as the course page; or, when the patient
 * has none, the title, "No course started yet." and one "Start course" button that opens a course for this visit
 * (kind and name filled by the server). The wording and layout of the empty state are not drawn (OQ-75-12).
 * The panel never computes money; a slow network shows a loading line and a failure a message with "Try again"
 * without hiding the rest of the screen.
 */
@Component({
  selector: 'app-course-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, IconComponent, CoursePage],
  template: `
    @if (loading()) {
      <p class="faint" aria-busy="true" data-testid="course-panel-loading">Opening the course</p>
    } @else if (error(); as message) {
      <div class="notice err" role="alert" data-testid="course-panel-error">
        <app-icon name="alert" [size]="16" />
        <span>{{ message }}</span>
        <button mat-stroked-button type="button" data-act="retry-panel" (click)="load()">Try again</button>
      </div>
    } @else if (courseId(); as id) {
      <app-course [courseId]="id" [embedded]="true" [visitId]="visitId()" [kind]="kind()" />
    } @else if (showStart()) {
      <section class="empty surf" [attr.aria-label]="title()" data-testid="course-panel-empty">
        <h3>{{ title() }}</h3>
        <p class="faint">{{ noCourse }}</p>
        @if (startError(); as message) {
          <p class="err-text" role="alert" data-testid="course-start-error">{{ message }}</p>
        }
        @if (!readOnly()) {
          <button mat-flat-button type="button" data-act="start-course" [disabled]="starting()" (click)="start()">
            {{ starting() ? 'Starting' : 'Start course' }}
          </button>
        }
      </section>
    }
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
      }
      .empty {
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 8px;
        padding: 14px;
      }
      h3 {
        margin: 0;
      }
      p {
        margin: 0;
      }
      button {
        min-height: 44px;
      }
    `,
  ],
})
export class CoursePanelComponent {
  private readonly api = inject(CourseApi);
  private readonly store = inject(EmrRecordStore);

  readonly kind = input.required<TreatmentCourseKind>();
  /** The Dental screen keeps its own start button, so its panel shows nothing when there is no course. */
  readonly showStart = input(true);

  protected readonly noCourse = NO_COURSE;
  protected readonly title = computed(() => KIND_TITLE[this.kind()]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly courseId = signal<string | null>(null);
  protected readonly starting = signal(false);
  protected readonly startError = signal<string | null>(null);
  protected readonly readOnly = this.store.readOnly;
  /** The OPD visit of this EMR: "Mark <unit> done" links an unlinked sitting to it. */
  protected readonly visitId = computed(() => {
    const r = this.store.record();
    return r?.encounterType === 'OPD_VISIT' ? r.encounterId : null;
  });
  private readonly startKey = newClientKey();

  constructor() {
    effect(() => {
      const patientId = this.store.record()?.header.patientId;
      this.kind();
      if (patientId) {
        untracked(() => this.load());
      }
    });
  }

  protected load(): void {
    const patientId = this.store.record()?.header.patientId;
    if (!patientId) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.api.listForPatient(patientId, this.kind()).subscribe({
      next: (list) => {
        this.courseId.set(list[0]?.id ?? null);
        this.loading.set(false);
      },
      error: (e: unknown) => {
        this.loading.set(false);
        this.error.set(detailOr(e, 'We could not open the course. Try again.'));
      },
    });
  }

  /** "Start course": the unsaved entries are saved first (the course starts from the saved record). */
  protected start(): void {
    const recordId = this.store.recordId();
    if (!recordId || this.starting()) {
      return;
    }
    const run = (): void => {
      this.starting.set(true);
      this.startError.set(null);
      this.api.create({ fromRecordId: recordId, kind: this.kind() }, this.startKey).subscribe({
        next: (course) => {
          this.starting.set(false);
          this.courseId.set(course.id);
        },
        error: (e: unknown) => {
          this.starting.set(false);
          this.startError.set(detailOr(e, 'We could not start the course. Try again.'));
        },
      });
    };
    if (this.store.dirty()) {
      this.store.save().subscribe((ok) => (ok ? run() : undefined));
    } else {
      run();
    }
  }
}
