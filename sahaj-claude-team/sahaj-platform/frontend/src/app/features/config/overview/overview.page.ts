import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, catchError, of } from 'rxjs';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { CONFIG_SECTIONS } from '../config-sections';
import { IpdAccess } from '../ipd/ipd-access';
import { describeAction, describeEntity, formatWhen, problemMessage } from '../config-format';
import { OpdApi } from '../opd/opd.api';
import { ClinicApi } from '../platform/clinic.api';
import { AuditEvent, SetupOverview } from '../platform/clinic.model';
import { TeamApi } from '../team/team.api';
import {
  TileData,
  fixTarget,
  sortChecks,
  summarise,
  tileSummaries,
  todoBySection,
} from './overview.logic';

/**
 * Overview (SAH-7), mockup `cfgHome`: setup percentage, checklist with Fix links, a tile for each
 * area and the change history. The server computes the checklist and is owner-only, so other
 * roles see a note and the tiles. The change history is shown without Undo in this phase.
 */
@Component({
  selector: 'app-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, IconComponent],
  styleUrl: './overview.page.scss',
  templateUrl: './overview.page.html',
})
export class OverviewPage implements OnInit {
  private readonly clinic = inject(ClinicApi);
  private readonly opd = inject(OpdApi);
  private readonly team = inject(TeamApi);
  private readonly store = inject(ShellStore);
  private readonly ipdAccess = inject(IpdAccess);

  protected readonly formatWhen = formatWhen;
  protected readonly describeAction = describeAction;
  protected readonly describeEntity = describeEntity;
  protected readonly fixTarget = fixTarget;

  protected readonly overview = signal<SetupOverview | null>(null);
  protected readonly events = signal<AuditEvent[]>([]);
  protected readonly summaries = signal<Record<string, string>>({});
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly isOwner = computed(() => this.store.me()?.role === 'OWNER');
  protected readonly checks = computed(() => sortChecks(this.overview()?.checks ?? []));
  protected readonly counts = computed(() => summarise(this.overview()?.checks ?? []));
  protected readonly todo = computed(() => todoBySection(this.overview()?.checks ?? []));
  protected readonly tiles = computed(() =>
    CONFIG_SECTIONS.filter((s) => s.key !== 'home' && this.ipdAccess.visible(s)),
  );

  ngOnInit(): void {
    this.loadTiles();
    if (!this.isOwner()) {
      this.loading.set(false);
      return;
    }
    this.clinic.getOverview().subscribe({
      next: (overview) => {
        this.overview.set(overview);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(problemMessage(error, 'We could not load the setup progress. Try again.'));
      },
    });
    this.clinic
      .listAuditEvents(6)
      .pipe(catchError(() => of([] as AuditEvent[])))
      .subscribe((events) => this.events.set(events));
  }

  /** Each tile's line comes from its own call, so one failing call only empties one line. */
  private loadTiles(): void {
    const data: TileData = {
      profile: null,
      holidays: null,
      members: null,
      specialties: null,
      fees: null,
      paper: null,
    };
    const fill = <K extends keyof TileData>(key: K, call: Observable<TileData[K]>) =>
      call.pipe(catchError(() => of(null))).subscribe((value) => {
        data[key] = value as TileData[K];
        this.summaries.set(tileSummaries(data));
      });
    fill('profile', this.clinic.getProfile());
    fill('holidays', this.clinic.listHolidays());
    fill('specialties', this.clinic.listSpecialties());
    fill('members', this.team.list());
    fill('fees', this.opd.getFeeSettings());
    fill('paper', this.opd.getCasePaperSettings());
  }
}
