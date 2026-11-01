import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CHARACTERS, CharacterKey, CharacterPose } from './characters';

/** A person from the Sahaj cast, drawn as SVG. Decorative, so hidden from screen readers. */
@Component({
  selector: 'app-character',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true' },
  styleUrl: './character.component.scss',
  templateUrl: './character.component.html',
})
export class CharacterComponent {
  readonly person = input.required<CharacterKey>();
  readonly pose = input<CharacterPose>('idle');
  readonly flip = input(false);
  /** Delay of the idle animation in seconds, so a group does not move in step. */
  readonly delay = input(0);

  protected readonly c = computed(() => CHARACTERS[this.person()]);
  protected readonly hasObject = computed(() => ['tablet', 'file', 'clip'].includes(this.pose()));
}
