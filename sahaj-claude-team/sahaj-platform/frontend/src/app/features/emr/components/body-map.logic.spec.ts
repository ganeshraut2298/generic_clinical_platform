import { EmrBodyMapPin } from '../emr-phase3.model';
import { BOX_H, BOX_W, allRegions, buildBody, pointInPolygon, regionAt, regionName, smoothPath } from './body-geometry';
import {
  CAUSES,
  FINDINGS,
  SEVERITIES,
  emptyBodyMap,
  mlcCount,
  newPin,
  nextPinNo,
  pinLine,
  pinPoint,
  registerSideText,
  registerSize,
  removePin,
  sizeText,
  stepSize,
  withCause,
} from './body-map.logic';

function pin(over: Partial<EmrBodyMapPin> = {}): EmrBodyMapPin {
  return { ...newPin(1, 'FRONT', 120, 180, 'torso2', 'Abdomen'), ...over };
}

describe('body map geometry (SAH-57-1)', () => {
  it('draws 17 separate tap areas on each side, left and right apart', () => {
    for (const view of ['FRONT', 'BACK'] as const) {
      const regions = buildBody('male', view);
      expect(regions).toHaveLength(17);
      expect(new Set(regions.map((r) => r.id)).size).toBe(17);
      for (const id of ['r_uarm', 'l_uarm', 'r_farm', 'l_farm', 'r_hand', 'l_hand', 'r_thigh', 'l_thigh', 'r_leg', 'l_leg', 'r_foot', 'l_foot']) {
        expect(regions.some((r) => r.id === id)).toBe(true);
      }
    }
  });

  it('names the regions of the front and of the back as the story does', () => {
    expect(regionName('head', 'FRONT')).toBe('Head and face');
    expect(regionName('torso1', 'FRONT')).toBe('Chest');
    expect(regionName('torso2', 'FRONT')).toBe('Abdomen');
    expect(regionName('pelvis', 'FRONT')).toBe('Groin and pelvis');
    expect(regionName('r_uarm', 'FRONT')).toBe('Right shoulder and upper arm');
    expect(regionName('l_leg', 'FRONT')).toBe('Left knee and leg');
    expect(regionName('head', 'BACK')).toBe('Back of head');
    expect(regionName('torso1', 'BACK')).toBe('Upper back');
    expect(regionName('torso2', 'BACK')).toBe('Lower back');
    expect(regionName('pelvis', 'BACK')).toBe('Buttocks');
    expect(regionName('l_foot', 'BACK')).toBe('Left heel and sole');
  });

  it('puts the patient right on the viewer left in the front view and mirrors it at the back', () => {
    const front = buildBody('male', 'FRONT').find((r) => r.id === 'r_thigh')!;
    const back = buildBody('male', 'BACK').find((r) => r.id === 'r_thigh')!;
    expect(front.cx).toBeLessThan(BOX_W / 2);
    expect(back.cx).toBeGreaterThan(BOX_W / 2);
  });

  it('draws a different build for male, female and other', () => {
    const wide = (sex: 'male' | 'female') => buildBody(sex, 'FRONT').find((r) => r.id === 'torso1')!.points.map((p) => p[0]);
    expect(Math.max(...wide('male'))).not.toBe(Math.max(...wide('female')));
    expect(buildBody('other', 'FRONT')).toHaveLength(17);
  });

  it('finds the region under a point, and none on empty space', () => {
    const regions = buildBody('male', 'FRONT');
    const head = regions.find((r) => r.id === 'head')!;
    expect(regionAt(regions, head.cx, head.cy)?.id).toBe('head');
    expect(regionAt(regions, 2, 2)).toBeNull();
    expect(pointInPolygon(5, 5, [[0, 0], [10, 0], [10, 10], [0, 10]])).toBe(true);
    expect(pointInPolygon(15, 5, [[0, 0], [10, 0], [10, 10], [0, 10]])).toBe(false);
  });

  it('lists the parts of both sides, front first, for the Choose list', () => {
    const all = allRegions('female');
    expect(all).toHaveLength(34);
    expect(all[0].view).toBe('FRONT');
    expect(all[17].view).toBe('BACK');
  });

  it('makes a closed smooth path', () => {
    const d = smoothPath([[0, 0], [10, 0], [10, 10]]);
    expect(d.startsWith('M0.0 0.0')).toBe(true);
    expect(d.endsWith('Z')).toBe(true);
  });
});

describe('body map rules (SAH-57-2, SAH-57-3)', () => {
  it('has the ten findings, eight causes and three severities of the mockup', () => {
    expect(FINDINGS.map((f) => f.label)).toEqual(['Wound', 'Swelling', 'Tenderness', 'Bruise', 'Burn', 'Rash', 'Lump', 'Fracture?', 'Scar', 'Pain']);
    expect(CAUSES.map((c) => c.label)).toEqual(['No injury (illness)', 'Fall', 'Road accident', 'Assault', 'Animal bite', 'Burn', 'Sports', 'Work injury']);
    expect(SEVERITIES.map((s) => s.label)).toEqual(['Mild', 'Moderate', 'Severe']);
  });

  it('drops a pin with the defaults: wound, fall, mild, 2 cm, no note, not MLC', () => {
    const p = newPin(3, 'BACK', 60, 240, 'torso1', 'Upper back');
    expect(p).toMatchObject({ no: 3, side: 'BACK', finding: 'WOUND', cause: 'FALL', severity: 'MILD', sizeCm: 2, note: '', mlc: false, region: 'torso1', where: 'Upper back' });
    expect(p.x).toBeCloseTo(60 / BOX_W, 3);
    expect(p.y).toBeCloseTo(240 / BOX_H, 3);
    expect(pinPoint(p)[0]).toBeCloseTo(60, 0);
  });

  it('never reuses a pin number', () => {
    const v = { ...emptyBodyMap(), pins: [pin({ no: 1 }), pin({ no: 2 })], nextPinNo: 3 };
    expect(nextPinNo(v)).toBe(3);
    expect(nextPinNo(removePin(v, 2))).toBe(3);
    expect(nextPinNo(removePin(v, 2)) > Math.max(...removePin(v, 2).pins.map((p) => p.no))).toBe(true);
    expect(nextPinNo(null)).toBe(1);
  });

  it('keeps the size to whole and half centimetres between 0 and 50', () => {
    expect(stepSize(2, 1)).toBe(2.5);
    expect(stepSize(0.5, -1)).toBe(0);
    expect(stepSize(0, -1)).toBe(0);
    expect(stepSize(50, 1)).toBe(50);
    expect(sizeText(0)).toBe('not measured');
    expect(sizeText(2.5)).toBe('2.5 cm');
    expect(registerSize(0)).toBe('—');
    expect(registerSize(2)).toBe('2 cm');
  });

  describe('medico-legal flag (L-1, tagged)', () => {
    it('is set by a road accident, an assault or a burn', () => {
      for (const cause of ['ROAD_ACCIDENT', 'ASSAULT', 'BURN'] as const) {
        const r = withCause(pin(), cause);
        expect(r.pin.mlc).toBe(true);
        expect(r.markedMlc).toBe(true);
      }
    });

    it('is not set by the other causes, and stays on when the cause is changed away', () => {
      expect(withCause(pin(), 'FALL').pin.mlc).toBe(false);
      const marked = withCause(pin(), 'ASSAULT').pin;
      const away = withCause(marked, 'NO_INJURY');
      expect(away.pin.mlc).toBe(true);
      expect(away.markedMlc).toBe(false);
    });

    it('says the message only the first time the flag is set', () => {
      const marked = withCause(pin(), 'ASSAULT').pin;
      expect(withCause(marked, 'BURN').markedMlc).toBe(false);
    });

    it('counts medico-legal pins for the register', () => {
      expect(mlcCount([pin(), pin({ no: 2, mlc: true })])).toBe(1);
      expect(registerSideText([pin(), pin({ no: 2, mlc: true })])).toBe('1 medico-legal');
      expect(registerSideText([pin()])).toBe('Tap a number to find the pin');
    });
  });

  it('writes a short line for the case paper', () => {
    expect(pinLine(pin({ finding: 'TENDERNESS', severity: 'MODERATE', sizeCm: 2 }))).toBe('Abdomen: tenderness, moderate, 2 cm');
    expect(pinLine(pin({ side: 'BACK', where: 'Lower back', finding: 'PAIN', severity: 'MILD', sizeCm: 0 }))).toBe('Lower back (back): pain, mild');
  });
});
