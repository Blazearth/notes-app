/**
 * Screen content, transcribed from the Claude Design mockups.
 *
 * The backend can already serve `GET /v1/saves`, but nothing in the app signs
 * in yet — no save has been created with a real Supabase JWT. Until the auth
 * flow lands these screens render from here, so the shape of each item is kept
 * close to the API's `SaveResponse` (a `knowledgeType` key, a source line) to
 * make the swap mechanical.
 */

import type { GlyphName } from '@/components/Glyph';

export interface ContinueItem {
  id: string;
  title: string;
  thumbLabel: string;
  /** Either a progress bar (0–1) or a plain metadata line, as in the mockups. */
  progress?: number;
  meta?: string;
}

export interface SaveItem {
  id: string;
  title: string;
  source: string;
  knowledgeType: string;
  /** Which Library filter chip this item answers to. */
  category?: LibraryFilter;
}

export interface SpaceSummary {
  id: string;
  name: string;
  memberCount: number;
}

export interface GroupItem {
  id: string;
  name: string;
  path: string;
  count: number;
  /** One tile per screen is accent-filled in the mockups. */
  featured?: boolean;
  category: LibraryFilter;
}

export interface CaptureOption {
  id: string;
  label: string;
  glyph: GlyphName;
}

export interface TaskItem {
  id: string;
  title: string;
  done: boolean;
}

export const GREETING_NAME = 'Maya';

export const CONTINUE_ITEMS: ContinueItem[] = [
  { id: 'c1', title: 'Miso ramen', thumbLabel: 'recipe photo', progress: 0.7 },
  { id: 'c2', title: 'Kyoto itinerary', thumbLabel: 'kyoto map', meta: '3 of 5 days planned' },
  { id: 'c3', title: 'Everything Everywhere', thumbLabel: 'film still', meta: 'Saved by Sam' },
];

export const WEEKLY_DIGEST =
  'You saved 14 items this week — mostly recipes and Kyoto travel spots. Your ramen bookmark is 3 days from expiring reservations.';

export const ACTIVE_SPACES: SpaceSummary[] = [
  { id: 's1', name: 'Japan Trip', memberCount: 4 },
  { id: 's2', name: 'Book Club', memberCount: 6 },
];

export const RECENTLY_CAPTURED: SaveItem[] = [
  {
    id: 'r1',
    title: 'Cafe Nakamura, Kyoto',
    source: 'Instagram · shared by Sam',
    knowledgeType: 'restaurant',
  },
  { id: 'r2', title: 'Resistance band workout', source: 'Web article', knowledgeType: 'workout' },
];

export const LIBRARY_FILTERS = ['All', 'Recipes', 'Travel', 'Articles', 'Watchlist'] as const;
export type LibraryFilter = (typeof LIBRARY_FILTERS)[number];

export const AI_GROUPS: GroupItem[] = [
  { id: 'g1', name: 'Food', path: 'Recipes → Italian → Dinner', count: 32, category: 'Recipes' },
  {
    id: 'g2',
    name: 'Travel',
    path: 'Japan → Kyoto → Cafes',
    count: 18,
    featured: true,
    category: 'Travel',
  },
  { id: 'g3', name: 'Reading', path: 'Articles → Long-form', count: 54, category: 'Articles' },
  { id: 'g4', name: 'Watchlist', path: 'Movies → Weekend', count: 11, category: 'Watchlist' },
];

export const RECENTLY_ORGANIZED: SaveItem[] = [
  {
    id: 'o1',
    title: 'Matcha cafes, Kyoto',
    source: 'Travel → Japan → Kyoto',
    knowledgeType: 'place',
    category: 'Travel',
  },
  {
    id: 'o2',
    title: 'Sourdough starter guide',
    source: 'Food → Recipes → Bread',
    knowledgeType: 'recipe',
    category: 'Recipes',
  },
];

export const SPACE_DETAIL = {
  name: 'Japan Trip',
  visibleAvatars: 2,
  extraMembers: 2,
  tabs: [
    { value: 'saves', label: 'Saves' },
    { value: 'chat', label: 'Chat' },
    { value: 'tasks', label: 'Tasks' },
    { value: 'calendar', label: 'Calendar' },
  ] as const,
  sharedSaves: [
    { id: 'ss1', title: 'Ichiran Ramen', source: 'Shared by Sam', thumbLabel: 'restaurant' },
    { id: 'ss2', title: 'Matcha House', source: 'Shared by Ana', thumbLabel: 'cafe photo' },
  ],
  tasks: [
    { id: 't1', title: 'Book flights', done: false },
    { id: 't2', title: 'Reserve Ichiran table', done: true },
  ] as TaskItem[],
  chat: [{ id: 'm1', author: 'Sam', body: 'Found this ramen spot, added it to Saves' }],
};

export const CAPTURE_OPTIONS: CaptureOption[] = [
  { id: 'link', label: 'Paste Link', glyph: 'roundedSquare' },
  { id: 'scan', label: 'Scan Doc', glyph: 'bars' },
  { id: 'camera', label: 'Camera', glyph: 'ring' },
  { id: 'screenshot', label: 'Screenshot', glyph: 'square' },
  { id: 'voice', label: 'Voice Note', glyph: 'capsule' },
  { id: 'file', label: 'Upload File', glyph: 'arch' },
  { id: 'note', label: 'Text Note', glyph: 'page' },
  { id: 'import', label: 'Import', glyph: 'diamond' },
];

export const CAPTURE_SUBTITLE =
  'Everything goes through the same pipeline — capture first, organize later.';
