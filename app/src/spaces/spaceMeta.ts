import type { GlyphName } from '@/components/Glyph';

/**
 * Visual identity for a Space, derived client-side rather than stored.
 *
 * `Space.type` is a free-text field the server just passes through (`general`
 * unless the client sends something else) — there is no `color`/`icon` column,
 * and adding one would be a migration for a purely cosmetic feature. Deriving
 * from `type` (for the templates below) with a hash of `id` as the fallback
 * (for anything typed by hand, or created before templates existed) gets the
 * "spaces are visually distinct at a glance" result without one.
 */

export interface SpaceTemplate {
  type: string;
  label: string;
  glyph: GlyphName;
  color: string;
  placeholder: string;
}

export const SPACE_TEMPLATES: SpaceTemplate[] = [
  { type: 'travel', label: 'Travel', glyph: 'compass', color: '#5C6BC0', placeholder: 'Japan Trip' },
  { type: 'study', label: 'Study', glyph: 'book', color: '#7E57C2', placeholder: 'Semester 5' },
  { type: 'work', label: 'Work', glyph: 'briefcase', color: '#8D6E63', placeholder: 'Startup' },
  { type: 'family', label: 'Family', glyph: 'home', color: '#EF6C00', placeholder: 'Family' },
  { type: 'gaming', label: 'Gaming', glyph: 'gamepad', color: '#26A69A', placeholder: 'Game Night' },
  { type: 'movies', label: 'Movies', glyph: 'film', color: '#AB47BC', placeholder: 'Watchlist' },
];

const TEMPLATES_BY_TYPE: Record<string, SpaceTemplate> = Object.fromEntries(
  SPACE_TEMPLATES.map((t) => [t.type, t]),
);

/** A small, fixed set of vivid hues for spaces whose type isn't a known template — same idea as `TYPE_COLORS`, independent of the user's chosen accent so cards stay distinct regardless of theme. */
const FALLBACK_COLORS = [
  '#5C6BC0',
  '#7E57C2',
  '#8D6E63',
  '#EF6C00',
  '#26A69A',
  '#AB47BC',
  '#546E7A',
  '#42A5F5',
  '#EC407A',
  '#78909C',
];

function hashString(value: string): number {
  let h = 0;
  for (let i = 0; i < value.length; i++) h = (h * 31 + value.charCodeAt(i)) | 0;
  return Math.abs(h);
}

export interface SpaceIdentity {
  glyph: GlyphName;
  color: string;
}

/** Icon + colour for a Space's icon tile — from its template if `type` matches one, otherwise a stable hash of its id. */
export function spaceIdentity(space: { id: string; type: string }): SpaceIdentity {
  const template = TEMPLATES_BY_TYPE[space.type];
  if (template) return { glyph: template.glyph, color: template.color };
  return { glyph: 'layers', color: FALLBACK_COLORS[hashString(space.id) % FALLBACK_COLORS.length] };
}

/** Same hash, reused for member avatars — a stable colour per person rather than per Space. */
export function personColor(id: string): string {
  return FALLBACK_COLORS[hashString(id) % FALLBACK_COLORS.length];
}
