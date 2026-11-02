import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { QrSampleComponent } from '../../../shared/qr-sample/qr-sample.component';
import { addDays, formatDate, formatDayMonth, istToday, problemMessage } from '../config-format';
import { OpdApi } from '../opd/opd.api';
import {
  CasePaperSettings,
  FeeSettings,
  PAPER_SECTIONS,
  PaperSize,
  SecondLanguage,
} from '../opd/opd.model';
import { ClinicApi } from '../platform/clinic.api';
import { ClinicProfile } from '../platform/clinic.model';
import { TeamApi } from '../team/team.api';
import { TeamMember, isDoctorRole } from '../team/team.model';
import { LANGUAGE_CHOICES, PAPER_LABELS } from './paper.labels';
import { MedicineFavouritesComponent } from './medicine-favourites.component';

/** Medicine names in capitals when the clinic asks for it. */
export function capitalise(text: string, caps: boolean): string {
  return caps ? text.toUpperCase() : text;
}

/**
 * Case paper and Rx (SAH-7), mockup `cfgPaper`: printing options, sections and a live print
 * preview, with Medicine favourites at the bottom. The Rx section and the doctor's registration
 * number are always on. Changes save as they are made. Non-owners see it read-only.
 */
@Component({
  selector: 'app-paper',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonToggleModule,
    IconComponent,
    QrSampleComponent,
    MedicineFavouritesComponent,
  ],
  styleUrl: './paper.page.scss',
  templateUrl: './paper.page.html',
})
export class PaperPage implements OnInit {
  private readonly opd = inject(OpdApi);
  private readonly clinic = inject(ClinicApi);
  private readonly team = inject(TeamApi);
  private readonly store = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly sectionList = PAPER_SECTIONS;
  protected readonly sizes: readonly PaperSize[] = ['A5', 'A4'];
  protected readonly languages = LANGUAGE_CHOICES;
  protected readonly capitalise = capitalise;
  protected readonly today = formatDate(istToday());

  protected readonly settings = signal<CasePaperSettings | null>(null);
  protected readonly profile = signal<ClinicProfile | null>(null);
  protected readonly doctor = signal<TeamMember | null>(null);
  protected readonly fees = signal<FeeSettings | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  private saved: CasePaperSettings | null = null;
  private pending = 0;

  protected readonly isOwner = computed(() => this.store.me()?.role === 'OWNER');

  protected readonly labels = computed(() => {
    const lang = this.settings()?.secondLanguage;
    return lang ? PAPER_LABELS[lang] : null;
  });

  protected readonly localName = computed(() => {
    const labels = this.labels();
    return labels ? (this.profile()?.nameLocal ?? '') : '';
  });

  protected readonly nextVisit = computed(() => {
    const fees = this.fees();
    if (!fees) {
      return null;
    }
    const until = formatDayMonth(addDays(istToday(), fees.followUpDays));
    const rule =
      fees.followUpFee === 'free'
        ? `Free follow-up till ${until}`
        : `Follow-up till ${until} at ${fees.followUpFee === 'half' ? '50%' : 'full'} fee`;
    return { until, rule };
  });

  ngOnInit(): void {
    forkJoin({
      settings: this.opd.getCasePaperSettings(),
      profile: this.clinic.getProfile().pipe(catchError(() => of(null))),
      members: this.team.list().pipe(catchError(() => of([] as TeamMember[]))),
      fees: this.opd.getFeeSettings().pipe(catchError(() => of(null))),
    }).subscribe({
      next: ({ settings, profile, members, fees }) => {
        this.settings.set(settings);
        this.saved = settings;
        this.profile.set(profile);
        this.fees.set(fees);
        this.doctor.set(
          members.find((m) => m.status === 'ACTIVE' && isDoctorRole(m.role)) ??
            members.find((m) => m.doctor != null) ??
            null,
        );
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(problemMessage(error, 'We could not load the case paper settings. Try again.'));
      },
    });
  }

  protected on(key: string): boolean {
    return key === 'rx' || this.settings()?.sections[key] === true;
  }

  protected setSize(size: PaperSize): void {
    this.change({ size });
  }

  protected setLanguage(value: SecondLanguage | ''): void {
    this.change({ secondLanguage: value || null });
  }

  protected togglePreprinted(): void {
    this.change({ preprinted: !this.settings()?.preprinted });
  }

  protected toggleCaps(): void {
    this.change({ caps: !this.settings()?.caps });
  }

  protected toggleSection(key: string): void {
    const s = this.settings();
    if (!s || key === 'rx') {
      return;
    }
    this.change({ sections: { ...s.sections, [key]: !this.on(key) } });
  }

  /** Shows the change at once, then saves the whole settings object. A failure puts the old values back. */
  private change(patch: Partial<CasePaperSettings>): void {
    const current = this.settings();
    if (!current || !this.isOwner()) {
      return;
    }
    const next: CasePaperSettings = {
      ...current,
      ...patch,
      sections: { ...(patch.sections ?? current.sections), rx: true },
    };
    this.settings.set(next);
    this.pending++;
    this.opd.saveCasePaperSettings(next).subscribe({
      next: (saved) => {
        this.pending--;
        if (this.pending === 0) {
          this.settings.set(saved);
        }
        this.saved = saved;
      },
      error: (error: unknown) => {
        this.pending--;
        if (this.pending === 0 && this.saved) {
          this.settings.set(this.saved);
        }
        this.snackBar.open(
          problemMessage(error, 'We could not save that change. Try again.'),
          'Close',
          { duration: 5000 },
        );
      },
    });
  }
}
