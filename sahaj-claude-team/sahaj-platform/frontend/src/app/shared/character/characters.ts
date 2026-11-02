/**
 * The cast of the landing page: one consistent set of people, drawn in SVG.
 * Colours here are illustration colours (skin, hair, clothes), not interface colours.
 */
export type CharacterKey = 'meera' | 'pooja' | 'anjali' | 'kiran' | 'sunita';
export type CharacterPose = 'idle' | 'point' | 'tablet' | 'file' | 'clip';
export type HairStyle = 'bun' | 'long' | 'bob' | 'short' | 'braid';

export interface Character {
  name: string;
  role: string;
  skin: string;
  skinD: string;
  hair: string;
  hs: HairStyle;
  top: string;
  topD: string;
  inner?: string;
  coat?: boolean;
  steth?: boolean;
  badge?: boolean;
  vneck?: boolean;
  collar?: boolean;
  dupatta?: string;
}

export const CHARACTERS: Record<CharacterKey, Character> = {
  meera: {
    name: 'Dr. Meera',
    role: 'Doctor and owner',
    skin: '#c68b67',
    skinD: '#a8704f',
    hair: '#221a16',
    hs: 'bun',
    top: '#ffffff',
    topD: '#d9e1ec',
    inner: '#3d6fd0',
    coat: true,
    steth: true,
  },
  pooja: {
    name: 'Pooja',
    role: 'Reception',
    skin: '#b97f5c',
    skinD: '#9a6545',
    hair: '#1c1512',
    hs: 'long',
    top: '#1f8f86',
    topD: '#187269',
    badge: true,
  },
  anjali: {
    name: 'Anjali',
    role: 'Nurse',
    skin: '#a8714f',
    skinD: '#8b5a3d',
    hair: '#251b16',
    hs: 'bob',
    top: '#79bfd3',
    topD: '#5aa6bb',
    badge: true,
    vneck: true,
  },
  kiran: {
    name: 'Kiran',
    role: 'Clinic manager and billing',
    skin: '#b47a58',
    skinD: '#956043',
    hair: '#1d1714',
    hs: 'short',
    top: '#2b3e63',
    topD: '#22324f',
    inner: '#e8eef7',
    collar: true,
    badge: true,
  },
  sunita: {
    name: 'Sunita',
    role: 'Patient',
    skin: '#c99070',
    skinD: '#a8745a',
    hair: '#2a1f19',
    hs: 'braid',
    top: '#d9785b',
    topD: '#bd6449',
    dupatta: '#f2c879',
  },
};
