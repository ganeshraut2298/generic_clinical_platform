import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { formatDisplayDateTimeOfInstant } from '../../../shared/format/ist-date';
import { IconComponent } from '../../../shared/icon/icon.component';
import { EMR_COMPONENT_CONTEXT } from '../emr-context';
import { BodyCause, BodyFinding, BodySeverity, BodySide, EmrBodyMapPin, EmrBodyMapValue } from '../emr-phase3.model';
import { BOX_H, BOX_W, BodySex, allRegions, buildBody, detailPath, regionAt, regionName } from './body-geometry';
import {
  BODY_HINT,
  CAUSES,
  FINDINGS,
  MLC_NOTE,
  MLC_CAUSES,
  NOTE_MAX,
  PINS_MAX,
  SEVERITIES,
  causeLabel,
  emptyBodyMap,
  findingLabel,
  newPin,
  nextPinNo,
  pinPoint,
  pinsOf,
  registerSideText,
  registerSize,
  severityLabel,
  sizeText,
  stepSize,
  withCause,
} from './body-map.logic';

type Tone = 'light' | 'wheat' | 'dark';

const TOASTS_MS = 3000;
const TAP_SLOP = 5;
const ZOOM_STEP = 1.5;
const ZOOM_MAX = 4;
const NUDGE = 2;

interface DragState {
  kind: 'pin' | 'pan' | 'tap';
  pinNo: number | null;
  startX: number;
  startY: number;
  centreX: number;
  centreY: number;
  moved: boolean;
  /** Last position of a dragged pin, as 0 to 1 of the drawing box. */
  last: [number, number] | null;
}

/**
 * The body map (SAH-57; mockup #BodyMap): a vector body, male, female or other, front and back,
 * three skin tones and zoom; tap to drop a numbered pin, drag to move it, then record the finding,
 * what happened, severity, size and a note; a findings register; and the medico-legal flag. The
 * drawing is built in code (no photo). Sex, skin tone, view and zoom are display state and are not
 * stored. Lists, defaults and the MLC rule are values from the EMR mockup, accepted by the human in
 * DEC-039, not clinically reviewed (C-10, L-1). Findings and notes are clinical data: never logged.
 */
@Component({
  selector: 'app-body-map',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styleUrl: './body-map.component.scss',
  templateUrl: './body-map.component.html',
})
export class BodyMapComponent {
  private readonly ctx = inject(EMR_COMPONENT_CONTEXT, { optional: true });
  private readonly destroyRef = inject(DestroyRef);

  readonly title = input('Local examination · body map');
  readonly value = input<unknown>(null);
  readonly disabled = input(false);
  readonly inputId = input.required<string>();
  /** Show the "No local findings" confirmation (a required body map with nothing to mark). */
  readonly allowNoFindings = input(true);

  readonly valueChange = output<EmrBodyMapValue>();

  protected readonly findings = FINDINGS;
  protected readonly causes = CAUSES;
  protected readonly severities = SEVERITIES;
  protected readonly hint = BODY_HINT;
  protected readonly mlcNote = MLC_NOTE;
  protected readonly noteMax = NOTE_MAX;
  protected readonly boxW = BOX_W;
  protected readonly boxH = BOX_H;
  protected readonly tones: readonly { key: Tone; label: string }[] = [
    { key: 'light', label: 'Light' },
    { key: 'wheat', label: 'Wheatish' },
    { key: 'dark', label: 'Dark' },
  ];
  protected readonly sexes: readonly { key: BodySex; label: string }[] = [
    { key: 'male', label: 'Male' },
    { key: 'female', label: 'Female' },
    { key: 'other', label: 'Other' },
  ];

  protected readonly sex = signal<BodySex | null>(null);
  protected readonly tone = signal<Tone>('wheat');
  protected readonly view = signal<BodySide>('FRONT');
  protected readonly zoom = signal(1);
  protected readonly centre = signal<[number, number]>([BOX_W / 2, BOX_H / 2]);
  protected readonly selected = signal<number | null>(null);
  protected readonly toast = signal<string | null>(null);
  protected readonly turning = signal(false);

  /** The recorded sex of the patient (Male, Female), else Other, until the doctor picks. */
  protected readonly bodySex = computed<BodySex>(() => {
    const chosen = this.sex();
    if (chosen) {
      return chosen;
    }
    const recorded = this.ctx?.patientSex();
    return recorded === 'M' ? 'male' : recorded === 'F' ? 'female' : 'other';
  });
  protected readonly body = computed(() => (this.value() as EmrBodyMapValue | null) ?? emptyBodyMap());
  protected readonly pins = computed(() => pinsOf(this.value()));
  protected readonly regions = computed(() => buildBody(this.bodySex(), this.view()));
  protected readonly details = computed(() => detailPath(this.bodySex(), this.view()));
  protected readonly partOptions = computed(() => allRegions(this.bodySex()));
  protected readonly viewPins = computed(() => this.pins().filter((p) => p.side === this.view()));
  protected readonly selectedPin = computed(() => this.pins().find((p) => p.no === this.selected()) ?? null);
  protected readonly nextNo = computed(() => nextPinNo(this.body()));
  protected readonly sideText = computed(() => registerSideText(this.pins()));
  protected readonly confirmed = computed(() => this.body().noFindingsConfirmed);
  protected readonly selectedRegionId = computed(() => {
    const pin = this.selectedPin();
    return pin && pin.side === this.view() ? pin.region : null;
  });
  protected readonly viewBox = computed(() => {
    const z = this.zoom();
    const w = BOX_W / z;
    const h = BOX_H / z;
    const [cx, cy] = this.centre();
    const x = Math.min(BOX_W - w, Math.max(0, cx - w / 2));
    const y = Math.min(BOX_H - h, Math.max(0, cy - h / 2));
    return `${x.toFixed(1)} ${y.toFixed(1)} ${w.toFixed(1)} ${h.toFixed(1)}`;
  });
  protected readonly ariaLabel = computed(
    () =>
      `${this.bodySex() === 'other' ? 'Body' : this.bodySex() === 'male' ? 'Male body' : 'Female body'}, ${this.view() === 'FRONT' ? 'front' : 'back'} view, ${this.viewPins().length} pin(s)`,
  );
  protected readonly canAddPin = computed(() => !this.disabled() && this.pins().length < PINS_MAX);
  protected readonly sizeLabel = computed(() => sizeText(this.selectedPin()?.sizeCm ?? 0));

  private drag: DragState | null = null;
  private toastTimer: ReturnType<typeof setTimeout> | null = null;
  private turnTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => {
      if (this.toastTimer) {
        clearTimeout(this.toastTimer);
      }
      if (this.turnTimer) {
        clearTimeout(this.turnTimer);
      }
    });
  }

  // ---- display choices ----

  protected setSex(sex: BodySex): void {
    this.sex.set(sex);
  }

  protected setTone(tone: Tone): void {
    this.tone.set(tone);
  }

  protected setView(view: BodySide): void {
    if (view === this.view()) {
      return;
    }
    this.view.set(view);
    this.zoom.set(1);
    this.centre.set([BOX_W / 2, BOX_H / 2]);
    this.turning.set(true);
    if (this.turnTimer) {
      clearTimeout(this.turnTimer);
    }
    this.turnTimer = setTimeout(() => this.turning.set(false), 200);
  }

  protected turn(): void {
    this.setView(this.view() === 'FRONT' ? 'BACK' : 'FRONT');
  }

  protected zoomIn(): void {
    const pin = this.selectedPin();
    this.zoom.update((z) => Math.min(ZOOM_MAX, z * ZOOM_STEP));
    if (pin && pin.side === this.view()) {
      this.centre.set(pinPoint(pin));
    }
  }

  protected zoomOut(): void {
    const z = Math.max(1, this.zoom() / ZOOM_STEP);
    this.zoom.set(z);
    if (z === 1) {
      this.centre.set([BOX_W / 2, BOX_H / 2]);
    }
  }

  protected fit(): void {
    this.zoom.set(1);
    this.centre.set([BOX_W / 2, BOX_H / 2]);
  }

  // ---- pointer: tap to drop, drag a pin, drag the background when zoomed ----

  private toBox(event: PointerEvent, svg: SVGSVGElement): [number, number] {
    const rect = svg.getBoundingClientRect();
    const z = this.zoom();
    const w = BOX_W / z;
    const h = BOX_H / z;
    const [vx, vy] = this.viewBox().split(' ').map(Number);
    const width = rect.width || 1;
    const height = rect.height || 1;
    return [vx + ((event.clientX - rect.left) / width) * w, vy + ((event.clientY - rect.top) / height) * h];
  }

  protected onSvgDown(event: PointerEvent): void {
    this.drag = {
      kind: 'tap',
      pinNo: null,
      startX: event.clientX,
      startY: event.clientY,
      centreX: this.centre()[0],
      centreY: this.centre()[1],
      moved: false,
      last: null,
    };
  }

  protected onPinDown(event: PointerEvent, pin: EmrBodyMapPin): void {
    event.stopPropagation();
    this.selected.set(pin.no);
    if (this.disabled()) {
      return;
    }
    this.drag = {
      kind: 'pin',
      pinNo: pin.no,
      startX: event.clientX,
      startY: event.clientY,
      centreX: this.centre()[0],
      centreY: this.centre()[1],
      moved: false,
      last: null,
    };
  }

  protected onSvgMove(event: PointerEvent): void {
    const d = this.drag;
    if (!d) {
      return;
    }
    const svg = event.currentTarget as SVGSVGElement;
    const dx = event.clientX - d.startX;
    const dy = event.clientY - d.startY;
    if (Math.abs(dx) + Math.abs(dy) > TAP_SLOP) {
      d.moved = true;
    }
    if (d.kind === 'pin' && d.pinNo !== null && d.moved) {
      const [x, y] = this.toBox(event, svg);
      const fx = Math.min(1, Math.max(0, x / BOX_W));
      const fy = Math.min(1, Math.max(0, y / BOX_H));
      d.last = [fx, fy];
      this.replacePin(d.pinNo, (p) => ({ ...p, x: fx, y: fy }));
    } else if (d.kind === 'tap' && d.moved && this.zoom() > 1) {
      const rect = svg.getBoundingClientRect();
      const z = this.zoom();
      this.centre.set([d.centreX - (dx / (rect.width || 1)) * (BOX_W / z), d.centreY - (dy / (rect.height || 1)) * (BOX_H / z)]);
    }
  }

  protected onSvgUp(event: PointerEvent): void {
    const d = this.drag;
    this.drag = null;
    if (!d) {
      return;
    }
    const svg = event.currentTarget as SVGSVGElement;
    if (d.kind === 'pin' && d.pinNo !== null) {
      if (d.moved && d.last) {
        this.settlePin(d.pinNo, d.last[0], d.last[1]);
      }
      return;
    }
    if (d.moved) {
      return;
    }
    const [x, y] = this.toBox(event, svg);
    this.tapAt(x, y);
  }

  protected onSvgLeave(): void {
    this.drag = null;
  }

  /** A tap on the drawing: drop a pin on the region under it, or clear the selection on empty space. */
  tapAt(x: number, y: number): void {
    const region = regionAt(this.regions(), x, y);
    if (!region) {
      this.selected.set(null);
      return;
    }
    this.place(region.id, region.name, x, y);
  }

  /** Keyboard: Enter on a region drops a pin at its centre. */
  protected dropAtRegion(id: string): void {
    const region = this.regions().find((r) => r.id === id);
    if (region) {
      this.place(region.id, region.name, region.cx, region.cy);
    }
  }

  private place(regionId: string, name: string, x: number, y: number): void {
    if (!this.canAddPin()) {
      return;
    }
    const no = this.nextNo();
    const pin = newPin(no, this.view(), x, y, regionId, name);
    this.emit([...this.pins(), pin], no + 1);
    this.selected.set(no);
    this.say(`Pin ${no} on ${name.toLowerCase()}. Pick the finding and what happened.`);
  }

  /** After a drag or a nudge the region follows the new spot (x and y as 0 to 1 of the drawing box). */
  private settlePin(no: number, fx: number, fy: number): void {
    const pin = this.pins().find((p) => p.no === no);
    if (!pin) {
      return;
    }
    const region = regionAt(buildBody(this.bodySex(), pin.side), fx * BOX_W, fy * BOX_H);
    if (region) {
      const name = region.name;
      this.replacePin(no, (p) => ({ ...p, x: fx, y: fy, region: region.id, where: name }));
      this.say(`Pin ${no} moved to ${name.toLowerCase()}`);
    }
  }

  // ---- the pin form ----

  protected select(no: number): void {
    const pin = this.pins().find((p) => p.no === no);
    this.selected.set(no);
    if (pin && pin.side !== this.view()) {
      this.setView(pin.side);
    }
    if (pin && this.zoom() > 1) {
      this.centre.set(pinPoint(pin));
    }
  }

  protected pickFinding(finding: BodyFinding): void {
    this.updateSelected((p) => ({ ...p, finding }));
  }

  protected pickCause(cause: BodyCause): void {
    const pin = this.selectedPin();
    if (!pin || this.disabled()) {
      return;
    }
    const { pin: next, markedMlc } = withCause(pin, cause);
    this.replacePin(pin.no, () => next);
    if (markedMlc && MLC_CAUSES.has(cause)) {
      this.say(`Marked as medico-legal (MLC) for ${causeLabel(cause).toLowerCase()}`);
    }
  }

  protected pickSeverity(severity: BodySeverity): void {
    this.updateSelected((p) => ({ ...p, severity }));
  }

  protected size(direction: 1 | -1): void {
    this.updateSelected((p) => ({ ...p, sizeCm: stepSize(p.sizeCm, direction) }));
  }

  protected toggleMlc(event: Event): void {
    const mlc = (event.target as HTMLInputElement).checked;
    this.updateSelected((p) => ({ ...p, mlc }));
  }

  protected onNote(event: Event): void {
    const note = (event.target as HTMLInputElement).value.slice(0, NOTE_MAX);
    this.updateSelected((p) => ({ ...p, note }));
  }

  /** The "Where" list: a selected pin takes the part; with no pin selected a new pin drops on it. */
  protected pickPart(event: Event): void {
    const select = event.target as HTMLSelectElement;
    const [side, id] = select.value.split(':') as [BodySide, string];
    select.value = '';
    if (!side || !id || this.disabled()) {
      return;
    }
    const regions = buildBody(this.bodySex(), side);
    const region = regions.find((r) => r.id === id);
    if (!region) {
      return;
    }
    const pin = this.selectedPin();
    if (pin) {
      // The pin stays where it is on the drawing unless the part is on the other side: then it moves there and the view turns.
      this.replacePin(pin.no, (p) =>
        p.side === side
          ? { ...p, region: region.id, where: region.name }
          : { ...p, side, region: region.id, where: region.name, x: region.cx / BOX_W, y: region.cy / BOX_H },
      );
      if (pin.side !== side) {
        this.setView(side);
      }
      return;
    }
    if (side !== this.view()) {
      this.setView(side);
    }
    const no = this.nextNo();
    if (!this.canAddPin()) {
      return;
    }
    this.emit([...this.pins(), newPin(no, side, region.cx, region.cy, region.id, region.name)], no + 1);
    this.selected.set(no);
    this.say(`Pin ${no} on ${region.name.toLowerCase()}. Pick the finding and what happened.`);
  }

  protected deletePin(no: number): void {
    if (this.disabled()) {
      return;
    }
    this.emit(
      this.pins().filter((p) => p.no !== no),
      this.nextNo(),
    );
    if (this.selected() === no) {
      this.selected.set(null);
    }
    this.say(`Pin ${no} deleted`);
  }

  /** Arrow keys move the selected pin by small steps (accessibility). */
  protected nudge(event: KeyboardEvent, pin: EmrBodyMapPin): void {
    const delta: Record<string, [number, number]> = {
      ArrowLeft: [-NUDGE, 0],
      ArrowRight: [NUDGE, 0],
      ArrowUp: [0, -NUDGE],
      ArrowDown: [0, NUDGE],
    };
    const d = delta[event.key];
    if (!d || this.disabled()) {
      return;
    }
    event.preventDefault();
    const fx = Math.min(1, Math.max(0, pin.x + d[0] / BOX_W));
    const fy = Math.min(1, Math.max(0, pin.y + d[1] / BOX_H));
    const region = regionAt(buildBody(this.bodySex(), pin.side), fx * BOX_W, fy * BOX_H);
    this.replacePin(pin.no, (p) => ({
      ...p,
      x: fx,
      y: fy,
      ...(region ? { region: region.id, where: region.name } : {}),
    }));
  }

  protected toggleNoFindings(): void {
    if (this.disabled()) {
      return;
    }
    this.valueChange.emit({ ...this.body(), noFindingsConfirmed: !this.confirmed() });
  }

  // ---- helpers for the template ----

  protected findingLabel = findingLabel;
  protected causeLabel = causeLabel;
  protected severityLabel = severityLabel;
  protected registerSize = registerSize;

  protected whereText(pin: EmrBodyMapPin): string {
    return `${pin.where}${pin.side === 'BACK' ? ' (back)' : ''}`;
  }

  protected recorded(pin: EmrBodyMapPin): string {
    return pin.recordedAt
      ? `${formatDisplayDateTimeOfInstant(pin.recordedAt)}${pin.recordedByName ? ` · ${pin.recordedByName}` : ''}`
      : 'Not saved yet';
  }

  protected pinRadius(pin: EmrBodyMapPin): number {
    return (pin.no === this.selected() ? 9 : 7.5) / Math.sqrt(this.zoom());
  }

  protected pinX(pin: EmrBodyMapPin): number {
    return pin.x * BOX_W;
  }

  protected pinY(pin: EmrBodyMapPin): number {
    return pin.y * BOX_H;
  }

  protected partName(side: BodySide, id: string): string {
    return regionName(id, side);
  }

  private updateSelected(fn: (p: EmrBodyMapPin) => EmrBodyMapPin): void {
    const pin = this.selectedPin();
    if (pin && !this.disabled()) {
      this.replacePin(pin.no, fn);
    }
  }

  private replacePin(no: number, fn: (p: EmrBodyMapPin) => EmrBodyMapPin): void {
    this.emit(
      this.pins().map((p) => (p.no === no ? fn(p) : p)),
      this.nextNo(),
    );
  }

  private emit(pins: EmrBodyMapPin[], next: number): void {
    this.valueChange.emit({ ...this.body(), component: 'BODY_MAP', schemaVersion: 1, pins, nextPinNo: next });
  }

  private say(message: string): void {
    this.toast.set(message);
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
    this.toastTimer = setTimeout(() => this.toast.set(null), TOASTS_MS);
  }
}
