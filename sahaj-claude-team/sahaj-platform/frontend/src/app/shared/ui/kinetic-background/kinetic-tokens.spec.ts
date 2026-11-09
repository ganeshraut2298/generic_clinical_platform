import { TOKEN_FALLBACKS } from './kinetic-background.config';
import { readTokens } from './kinetic-tokens';

function style(values: Record<string, string | undefined>) {
  return { getPropertyValue: (name: string) => values[name] ?? '' };
}

describe('readTokens', () => {
  it('reads the six colours from the theme and trims them', () => {
    const colours = readTokens(
      style({
        '--dot-base': ' #112233 ',
        '--node-a': ' rgba(1, 2, 3, 0.5)',
        '--node-b': 'red',
        '--node-c': 'green',
        '--node-d': 'blue',
      }),
    );
    expect(colours).toEqual({
      dot: '#112233',
      nodeA: 'rgba(1, 2, 3, 0.5)',
      nodeB: 'red',
      nodeC: 'green',
      nodeD: 'blue',
    });
  });

  it('uses the spec value for a missing or empty token', () => {
    expect(readTokens(style({ '--dot-base': '   ' }))).toEqual(TOKEN_FALLBACKS);
    expect(readTokens(style({}))).toEqual(TOKEN_FALLBACKS);
  });

  it('uses the spec value for a token the browser does not accept as a colour', () => {
    const colours = readTokens(style({ '--dot-base': 'nonsense', '--node-a': 'blue' }), (v) => v !== 'nonsense');
    expect(colours.dot).toBe(TOKEN_FALLBACKS.dot);
    expect(colours.nodeA).toBe('blue');
  });

  it('does not throw when the style cannot be read', () => {
    const broken = {
      getPropertyValue: () => {
        throw new Error('no style');
      },
    };
    expect(readTokens(broken)).toEqual(TOKEN_FALLBACKS);
  });

  it('keeps the spec colours', () => {
    expect(TOKEN_FALLBACKS.dot).toBe('#E2E8F0');
    expect(TOKEN_FALLBACKS.nodeA).toBe('rgba(59,130,246,0.45)');
    expect(TOKEN_FALLBACKS.nodeB).toBe('rgba(248,113,113,0.40)');
    expect(TOKEN_FALLBACKS.nodeC).toBe('rgba(52,211,153,0.45)');
    expect(TOKEN_FALLBACKS.nodeD).toBe('rgba(167,139,250,0.45)');
  });
});
