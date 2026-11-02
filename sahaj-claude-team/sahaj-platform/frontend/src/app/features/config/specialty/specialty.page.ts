import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { forkJoin } from 'rxjs';
import { ShellStore } from '../../../core/shell/shell.store';
import { IconComponent } from '../../../shared/icon/icon.component';
import { problemMessage } from '../config-format';
import { ClinicApi } from '../platform/clinic.api';
import { Specialty } from '../platform/clinic.model';

/**
 * Specialty template (SAH-7), mockup `cfgSpec`: templates in use and "Phase 2" templates.
 * Applying one keeps all data: it only adds what is missing. Non-owners see it read-only.
 */
@Component({
  selector: 'app-specialty',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styleUrl: './specialty.page.scss',
  templateUrl: './specialty.page.html',
})
export class SpecialtyPage implements OnInit {
  private readonly api = inject(ClinicApi);
  private readonly store = inject(ShellStore);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly specialties = signal<Specialty[]>([]);
  protected readonly inUse = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly applying = signal<string | null>(null);
  protected readonly isOwner = computed(() => this.store.me()?.role === 'OWNER');

  ngOnInit(): void {
    forkJoin({ list: this.api.listSpecialties(), profile: this.api.getProfile() }).subscribe({
      next: ({ list, profile }) => {
        this.specialties.set(list);
        this.inUse.set(profile.specialtyCode ?? null);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.loadError.set(problemMessage(error, 'We could not load the templates. Try again.'));
      },
    });
  }

  protected apply(specialty: Specialty): void {
    if (!this.isOwner() || specialty.phase > 1 || this.applying() || this.inUse() === specialty.code) {
      return;
    }
    this.applying.set(specialty.code);
    this.api.applySpecialty(specialty.code).subscribe({
      next: (profile) => {
        this.inUse.set(profile.specialtyCode ?? specialty.code);
        this.applying.set(null);
        this.snackBar.open(`${specialty.name} template applied. Your data is unchanged.`, 'Close', {
          duration: 5000,
        });
      },
      error: (error: unknown) => {
        this.applying.set(null);
        this.snackBar.open(
          problemMessage(error, 'We could not apply that template. Try again.'),
          'Close',
          { duration: 5000 },
        );
      },
    });
  }
}
