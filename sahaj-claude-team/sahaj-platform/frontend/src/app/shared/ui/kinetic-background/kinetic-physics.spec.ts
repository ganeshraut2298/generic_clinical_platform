import {
  buildGrid,
  capDpr,
  clampFriction,
  clampRadius,
  clampSpacing,
  exclusionFactor,
  nodeOffset,
  rectDistance,
  smoothstep,
  springStep,
  targetSplit,
} from './kinetic-physics';

describe('kinetic physics', () => {
  describe('targetSplit', () => {
    it('is 1 on the dot, 0.5 halfway and 0 at the radius and beyond', () => {
      expect(targetSplit(0, 90)).toBe(1);
      expect(targetSplit(45, 90)).toBe(0.5);
      expect(targetSplit(90, 90)).toBe(0);
      expect(targetSplit(95, 90)).toBe(0);
    });
  });

  describe('clampRadius', () => {
    it('cuts 150 to the hard maximum of 90 and keeps 60', () => {
      expect(clampRadius(150)).toBe(90);
      expect(clampRadius(60)).toBe(60);
      expect(clampRadius(90)).toBe(90);
    });
    it('falls back to 90 for zero, negative and not-a-number', () => {
      expect(clampRadius(0)).toBe(90);
      expect(clampRadius(-5)).toBe(90);
      expect(clampRadius(Number.NaN)).toBe(90);
      expect(clampRadius(Infinity)).toBe(90);
    });
  });

  describe('clampFriction and clampSpacing', () => {
    it('keeps a friction that settles and replaces one that never would', () => {
      expect(clampFriction(0.5)).toBe(0.5);
      expect(clampFriction(1)).toBe(0.75);
      expect(clampFriction(0)).toBe(0.75);
      expect(clampFriction(Number.NaN)).toBe(0.75);
    });
    it('keeps a sane spacing and replaces a tiny or broken one', () => {
      expect(clampSpacing(40)).toBe(40);
      expect(clampSpacing(4)).toBe(28);
      expect(clampSpacing(Number.NaN)).toBe(28);
    });
  });

  describe('capDpr', () => {
    it('caps at 2 and keeps 1', () => {
      expect(capDpr(3)).toBe(2);
      expect(capDpr(2)).toBe(2);
      expect(capDpr(1.5)).toBe(1.5);
      expect(capDpr(1)).toBe(1);
    });
    it('uses 1 for a broken value', () => {
      expect(capDpr(0)).toBe(1);
      expect(capDpr(Number.NaN)).toBe(1);
    });
  });

  describe('buildGrid', () => {
    it('places dots every 28 px, centred, on half pixels (1440 x 900)', () => {
      const grid = buildGrid(1440, 900, 28);
      expect(grid.columns).toBe(52);
      expect(grid.rows).toBe(33);
      expect(grid.xs.length).toBe(52 * 33);
      // 1440 mod 28 = 12, so the x offset is 6; 900 mod 28 = 4, so the y offset is 2.
      expect(grid.xs[0]).toBe(6.5);
      expect(grid.ys[0]).toBe(2.5);
      expect(grid.xs[1] - grid.xs[0]).toBe(28);
      expect(grid.ys[52] - grid.ys[0]).toBe(28);
      expect(grid.xs[51]).toBe(6 + 51 * 28 + 0.5);
    });
    it('covers a phone viewport (360 x 740) and stays inside it', () => {
      const grid = buildGrid(360, 740, 28);
      expect(grid.columns).toBe(13);
      expect(grid.rows).toBe(27);
      expect(Math.max(...grid.xs)).toBeLessThanOrEqual(360);
      expect(Math.max(...grid.ys)).toBeLessThanOrEqual(740);
      expect(Math.min(...grid.xs)).toBeGreaterThan(0);
    });
    it('uses the spacing it is given', () => {
      const grid = buildGrid(100, 100, 50);
      expect(grid.columns).toBe(3);
      expect(grid.xs[1] - grid.xs[0]).toBe(50);
    });
  });

  describe('springStep', () => {
    it('follows v += (target - s) * 0.14; v *= friction; s += v, frame by frame', () => {
      let s = 1;
      let v = 0;
      const seen: number[] = [];
      for (let frame = 0; frame < 3; frame++) {
        const expectedV = (v + (0 - s) * 0.14) * 0.75;
        const expectedS = s + expectedV;
        const next = springStep(s, v, 0, 0.75);
        expect(next.v).toBeCloseTo(expectedV, 12);
        expect(next.s).toBeCloseTo(expectedS, 12);
        ({ s, v } = next);
        seen.push(s);
      }
      // First frame: v = -0.105, s = 0.895.
      expect(seen[0]).toBeCloseTo(0.895, 10);
    });

    it('a different friction changes the settling', () => {
      const run = (friction: number) => {
        let s = 1;
        let v = 0;
        let frames = 0;
        for (; frames < 500; frames++) {
          const next = springStep(s, v, 0, friction);
          ({ s, v } = next);
          if (next.settled) {
            break;
          }
        }
        return frames;
      };
      expect(run(0.5)).not.toBe(run(0.75));
      expect(run(0.75)).toBeLessThan(500);
    });

    it('snaps to exactly 0 when both split and speed are under 0.02', () => {
      const next = springStep(0.01, 0.005, 0, 0.75);
      expect(next.s).toBe(0);
      expect(next.v).toBe(0);
      expect(next.settled).toBe(true);
    });

    it('does not snap while speed is still 0.02 or more', () => {
      const next = springStep(0.0, -0.1, 0, 0.75);
      expect(next.settled).toBe(false);
      expect(next.v).not.toBe(0);
    });

    it('always ends at exactly 0 after a split of 1 returns home', () => {
      let s = 1;
      let v = 0;
      let settled = false;
      for (let frame = 0; frame < 300 && !settled; frame++) {
        ({ s, v, settled } = springStep(s, v, 0, 0.75));
      }
      expect(settled).toBe(true);
      expect(s).toBe(0);
      expect(v).toBe(0);
    });

    it('settles on a non-zero target at that target', () => {
      let s = 0;
      let v = 0;
      let settled = false;
      for (let frame = 0; frame < 300 && !settled; frame++) {
        ({ s, v, settled } = springStep(s, v, 0.5, 0.75));
      }
      expect(settled).toBe(true);
      expect(s).toBe(0.5);
      expect(v).toBe(0);
    });
  });

  describe('nodeOffset', () => {
    it('puts the nodes 11 px away along the diagonal at split 1 (7.78 px per axis)', () => {
      const { axis, radius } = nodeOffset(1);
      expect(axis).toBeCloseTo(11 / Math.SQRT2, 10);
      expect(Math.hypot(axis, axis)).toBeCloseTo(11, 10);
      expect(radius).toBe(1.25);
    });
    it('puts them 5.5 px away at split 0.5', () => {
      const { axis, radius } = nodeOffset(0.5);
      expect(Math.hypot(axis, axis)).toBeCloseTo(5.5, 10);
      expect(radius).toBeCloseTo(0.625, 10);
    });
    it('has no node at split 0', () => {
      expect(nodeOffset(0)).toEqual({ axis: 0, radius: 0 });
    });
  });

  describe('rectDistance', () => {
    const box = { left: 100, top: 50, right: 300, bottom: 110 };
    it('is 0 inside and on the edge', () => {
      expect(rectDistance(200, 80, box)).toBe(0);
      expect(rectDistance(100, 50, box)).toBe(0);
    });
    it('measures to the edge beside and below the rectangle', () => {
      expect(rectDistance(200, 170, box)).toBe(60);
      expect(rectDistance(40, 80, box)).toBe(60);
    });
    it('measures to the corner diagonally', () => {
      expect(rectDistance(60, 10, box)).toBe(Math.hypot(40, 40));
    });
  });

  describe('smoothstep and exclusionFactor', () => {
    it('is 0 at the edge, 0.5 at 60 px and 1 from 120 px', () => {
      expect(smoothstep(0)).toBe(0);
      expect(smoothstep(60)).toBe(0.5);
      expect(smoothstep(120)).toBe(1);
      expect(smoothstep(500)).toBe(1);
    });
    it('measures from the rectangle edge, not the centre', () => {
      const header = [{ left: 0, top: 0, right: 1000, bottom: 80 }];
      expect(exclusionFactor(500, 80, header)).toBe(0);
      expect(exclusionFactor(500, 140, header)).toBe(0.5);
      expect(exclusionFactor(500, 200, header)).toBe(1);
      expect(exclusionFactor(500, 40, header)).toBe(0);
    });
    it('is 1 everywhere when no element is excluded', () => {
      expect(exclusionFactor(10, 10, [])).toBe(1);
    });
    it('takes the strongest exclusion of several elements', () => {
      const boxes = [
        { left: 0, top: 0, right: 100, bottom: 10 },
        { left: 0, top: 100, right: 100, bottom: 110 },
      ];
      expect(exclusionFactor(50, 70, boxes)).toBe(smoothstep(30));
    });
  });
});
