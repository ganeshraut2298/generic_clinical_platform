/** The four values a page can set on the background, plus the constants of the effect. */
export interface KineticBackgroundConfig {
  /** Pointer reach in px. Never more than `maxRadius`. */
  interactionRadius: number;
  /** Distance between dots in px. */
  gridSpacing: number;
  /** Spring friction, between 0 and 1 (not included). */
  friction: number;
  /** When false nothing is drawn and nothing is listened to. */
  enabled: boolean;
  /** Radius of a resting dot in px. */
  dotRadius: number;
  /** Radius of a micro-node at full split in px. */
  nodeRadius: number;
  /** Distance of a micro-node from its dot at full split, along the diagonal, in px. */
  nodeTravel: number;
  /** Spring pull per frame. */
  spring: number;
  /** A dot is at rest when its split and speed are both below this. */
  settle: number;
  /** Hard maximum of `interactionRadius` in px. */
  maxRadius: number;
  /** Dots closer than this to a `data-dot-exclude` element fade out, in px. */
  exclusion: number;
  /** Highest pixel ratio the canvas uses. */
  dprCap: number;
}

export const KINETIC_DEFAULTS: Readonly<KineticBackgroundConfig> = {
  interactionRadius: 90,
  gridSpacing: 28,
  friction: 0.75,
  enabled: true,
  dotRadius: 1.6,
  nodeRadius: 1.25,
  nodeTravel: 11,
  spring: 0.14,
  settle: 0.02,
  maxRadius: 90,
  exclusion: 120,
  dprCap: 2,
};

/** The colours of the theme tokens. */
export interface KineticColours {
  dot: string;
  nodeA: string;
  nodeB: string;
  nodeC: string;
  nodeD: string;
}

/** Spec values, used when a token is missing, empty or not a colour. */
export const TOKEN_FALLBACKS: Readonly<KineticColours> = {
  dot: '#E2E8F0',
  nodeA: 'rgba(59,130,246,0.45)',
  nodeB: 'rgba(248,113,113,0.40)',
  nodeC: 'rgba(52,211,153,0.45)',
  nodeD: 'rgba(167,139,250,0.45)',
};

/** The custom property that holds each colour. */
export const TOKEN_NAMES: Readonly<Record<keyof KineticColours, string>> = {
  dot: '--dot-base',
  nodeA: '--node-a',
  nodeB: '--node-b',
  nodeC: '--node-c',
  nodeD: '--node-d',
};
