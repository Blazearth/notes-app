/**
 * The fixture set behind `mockRepository`.
 *
 * Two rules held throughout, because the point of this data is to make design
 * decisions on and both are easy to violate by accident:
 *
 * 1. **It is shaped exactly like real pipeline output.** Every save carries the
 *    `structuredData` its `knowledgeType` actually produces — `recipe` has
 *    `ingredients`, `place` names its title field `name` and not `title`,
 *    `movie` uses `synopsis` — so the real `buildCardModel` and
 *    `buildDetailModel` render it through their real branches. Fixtures shaped
 *    to whatever the UI wanted would validate nothing.
 * 2. **It is not uniform.** Titles run long and short, some saves are still
 *    `processing`, one has `failed`, lifecycle is spread across the four
 *    states. A grid of equal-length titles hides every truncation and
 *    alignment bug there is, which is the usual way mock data flatters a
 *    layout it should be stress-testing.
 */

import type {
  ActivityEntry,
  SaveComment,
  SaveResponse,
  ShoppingListResponse,
  Space,
  SpaceMember,
} from '@/api/types';
import type { KnowledgeGroup } from './repository';

/** Fixed clock so the fixtures do not drift into "3 months ago" over time. */
const NOW = Date.now();
const hoursAgo = (h: number) => new Date(NOW - h * 3_600_000).toISOString();
const daysAgo = (d: number) => new Date(NOW - d * 86_400_000).toISOString();

export const MOCK_USER_ID = 'mock-user-0001';
export const MOCK_USER_NAME = 'Maya';

/**
 * Shorthand for a leaf: a subgroup with its own items and no children.
 *
 * `count` is how many saves it holds. Only a few of them map to real fixture
 * saves — the rest are a number, because the point of the tree is the shape and
 * the navigation, and inventing ninety plausible saves to make a badge honest
 * would be a lot of noise for no additional signal.
 */
function leaf(id: string, name: string, count: number, saveIds: string[] = []): KnowledgeGroup {
  return { id, name, itemCount: count, subgroups: [], saveIds };
}

/**
 * A branch, whose count is derived rather than declared.
 *
 * Writing the total by hand is the obvious version and it goes stale the first
 * time a subgroup changes — a parent claiming 38 while its children sum to 41
 * is exactly the sort of detail that makes an interface feel untrustworthy.
 */
function branch(
  id: string,
  name: string,
  subgroups: KnowledgeGroup[],
  saveIds: string[] = [],
): KnowledgeGroup {
  const itemCount = saveIds.length + subgroups.reduce((sum, g) => sum + g.itemCount, 0);
  return { id, name, itemCount, subgroups, saveIds };
}

/**
 * The group tree.
 *
 * Two levels deep today, and nothing here or in the UI assumes that: a subgroup
 * is the same shape as its parent, so making "Japan" contain "Tokyo" and
 * "Osaka" is a data change with no code behind it.
 */
export const MOCK_GROUPS: KnowledgeGroup[] = [
  branch('g-watchlist', 'Watchlist', [
    leaf('g-watchlist-movies', 'Movies', 14, ['sv-05']),
    leaf('g-watchlist-tv', 'TV Series', 9),
    leaf('g-watchlist-anime', 'Anime', 6),
    leaf('g-watchlist-docs', 'Documentaries', 5),
    leaf('g-watchlist-youtube', 'YouTube Videos', 4),
  ]),
  branch('g-workout', 'Workout', [
    leaf('g-workout-push', 'Push', 5),
    leaf('g-workout-pull', 'Pull', 4),
    leaf('g-workout-legs', 'Legs', 4),
    leaf('g-workout-cardio', 'Cardio', 3, ['sv-01']),
    leaf('g-workout-mobility', 'Mobility', 3),
    leaf('g-workout-nutrition', 'Nutrition', 2),
  ]),
  branch('g-shopping', 'Shopping', [
    leaf('g-shopping-groceries', 'Groceries', 11),
    leaf('g-shopping-wishlist', 'Wishlist', 6),
    leaf('g-shopping-electronics', 'Electronics', 4, ['sv-09']),
    leaf('g-shopping-home', 'Home', 3),
    leaf('g-shopping-fashion', 'Fashion', 2),
  ]),
  branch('g-travel', 'Travel', [
    leaf('g-travel-japan', 'Japan', 7, ['sv-06']),
    leaf('g-travel-kyoto', 'Kyoto', 6, ['sv-02']),
    leaf('g-travel-restaurants', 'Restaurants', 5),
    leaf('g-travel-cafes', 'Cafes', 4),
    leaf('g-travel-hotels', 'Hotels', 3),
    leaf('g-travel-itineraries', 'Itineraries', 2),
  ]),
  branch('g-recipes', 'Recipes', [
    leaf('g-recipes-breakfast', 'Breakfast', 6, ['sv-04']),
    leaf('g-recipes-lunch', 'Lunch', 5),
    leaf('g-recipes-dinner', 'Dinner', 12, ['sv-03']),
    leaf('g-recipes-desserts', 'Desserts', 6),
    leaf('g-recipes-drinks', 'Drinks', 3),
  ]),
  branch('g-reading', 'Reading', [
    leaf('g-reading-articles', 'Articles', 21),
    leaf('g-reading-essays', 'Essays', 13),
    leaf('g-reading-books', 'Books', 9, ['sv-10']),
    leaf('g-reading-papers', 'Research Papers', 7),
    leaf('g-reading-newsletters', 'Newsletters', 4),
  ]),
];

/**
 * Newest first, matching `GET /v1/saves`.
 *
 * The first five are what Home shows; the rest exist so the Library has enough
 * to filter and so a capped list is visibly a cap rather than the whole set.
 */
export const MOCK_SAVES: SaveResponse[] = [
  {
    id: 'sv-01',
    sourceType: 'url',
    sourceUrl: 'https://www.youtube.com/watch?v=r-band-full',
    status: 'ready',
    favorite: false,
    archived: false,
    knowledgeType: 'workout',
    confidence: 0.93,
    lifecycleStatus: 'started',
    // The full post-2026-08-07 workout shape, exercises included, so mock mode
    // renders the new nested-object detail layout through its real branch.
    structuredData: {
      title: 'Resistance Band Full-Body Workout',
      summary:
        'A 25-minute circuit using a single loop band — three rounds of six movements, no floor work, designed for a hotel room.',
      category: 'Strength',
      goal: 'full-body maintenance while travelling',
      muscleGroups: ['legs', 'back', 'chest', 'core'],
      duration: '25 min',
      difficulty: '[unclear]',
      equipment: ['loop resistance band'],
      warmup: ['2 min marching in place', 'arm circles, 30s each way'],
      exercises: [
        {
          name: 'Banded squat',
          sets: '3',
          reps: '15',
          rest: '45s',
          tempo: '[unclear]',
          cues: ['band above knees', 'drive the knees out'],
          alternatives: [],
        },
        {
          name: 'Banded row',
          sets: '3',
          reps: '12',
          rest: '45s',
          tempo: '2-1-2',
          cues: ['anchor at chest height', 'squeeze the shoulder blades'],
          alternatives: ['Single-arm row'],
        },
        {
          name: 'Band pull-apart',
          sets: '3',
          reps: '20',
          rest: '30s',
          tempo: '[unclear]',
          cues: ['keep the elbows soft'],
          alternatives: [],
        },
      ],
      cooldown: ['chest doorway stretch, 30s per side'],
      progression: 'Move to the next band thickness once all rounds feel easy',
      warnings: ['Check the band for tears before loading it'],
    },
    createdAt: hoursAgo(3),
    updatedAt: hoursAgo(1),
  },
  {
    id: 'sv-02',
    sourceType: 'url',
    sourceUrl: 'https://kyotofoodie.com/matcha-cafes',
    status: 'ready',
    favorite: true,
    archived: false,
    knowledgeType: 'place',
    confidence: 0.88,
    lifecycleStatus: 'planned',
    structuredData: {
      name: 'Kyoto Cafe List',
      address: 'Higashiyama, Kyoto',
      cuisine: 'Matcha & pastries',
      highlights: ['Walk-in only', 'Opens 08:00', 'Cash preferred'],
    },
    createdAt: hoursAgo(9),
    updatedAt: hoursAgo(9),
  },
  {
    id: 'sv-03',
    sourceType: 'url',
    sourceUrl: 'https://www.instagram.com/reel/miso-ramen',
    status: 'ready',
    favorite: true,
    archived: false,
    knowledgeType: 'recipe',
    confidence: 0.95,
    lifecycleStatus: 'started',
    structuredData: {
      title: 'Weeknight Miso Ramen',
      servings: '2',
      totalTime: '35 min',
      // Post-2026-08-07 structured shape: {name, quantity, note} objects.
      ingredients: [
        { name: 'white miso', quantity: '2 tbsp', note: '[unclear]' },
        { name: 'toasted sesame oil', quantity: '1 tbsp', note: '[unclear]' },
        { name: 'garlic', quantity: '4 cloves', note: 'grated' },
        { name: 'ginger', quantity: '1 thumb', note: 'grated' },
        { name: 'chicken stock', quantity: '600 ml', note: '[unclear]' },
        { name: 'fresh ramen noodles', quantity: '2 portions', note: '[unclear]' },
        { name: 'eggs', quantity: '2', note: 'soft-boiled' },
        { name: 'spinach', quantity: '1 handful', note: '[unclear]' },
      ],
      steps: [
        'Fry the garlic and ginger in the sesame oil until fragrant, about 90 seconds.',
        'Whisk in the miso, then loosen with a ladle of stock before adding the rest.',
        'Simmer 10 minutes without boiling — boiling makes miso grainy.',
        'Cook the noodles separately and drain well, or the broth turns cloudy.',
        'Assemble with the eggs and spinach, and eat immediately.',
      ],
    },
    createdAt: daysAgo(1),
    updatedAt: hoursAgo(5),
  },
  {
    id: 'sv-04',
    sourceType: 'url',
    sourceUrl: 'https://www.tiktok.com/@sourdough/video/starter-guide',
    status: 'ready',
    favorite: false,
    archived: false,
    knowledgeType: 'recipe',
    confidence: 0.91,
    lifecycleStatus: 'saved',
    structuredData: {
      title: 'Sourdough Starter, Day by Day',
      servings: '1 starter',
      totalTime: '7 days',
      // Deliberately the LEGACY flat-string shape: saves classified before
      // 2026-08-07 hold this forever (no reprocess path), so one fixture must
      // keep exercising the old-shape rendering path.
      ingredients: ['100 g wholemeal flour', '100 g strong white flour', '200 ml water at 28°C'],
      steps: [
        'Day 1: mix 50 g wholemeal with 50 ml water, cover loosely, leave at room temperature.',
        'Days 2–4: discard half, feed 50 g white flour and 50 ml water every 24 hours.',
        'Days 5–7: feed every 12 hours. It is ready when it doubles within 4 hours.',
      ],
    },
    createdAt: daysAgo(2),
    updatedAt: daysAgo(2),
  },
  {
    id: 'sv-05',
    sourceType: 'url',
    sourceUrl: 'https://letterboxd.com/film/perfect-days',
    status: 'ready',
    favorite: false,
    archived: false,
    knowledgeType: 'movie',
    confidence: 0.97,
    lifecycleStatus: 'planned',
    structuredData: {
      title: 'Perfect Days',
      year: '2023',
      director: 'Wim Wenders',
      runtime: '124 min',
      synopsis:
        'A Tokyo toilet cleaner moves through an unvarying routine that slowly reveals itself as a deliberate life rather than a small one.',
    },
    createdAt: daysAgo(3),
    updatedAt: daysAgo(3),
  },
  {
    id: 'sv-06',
    sourceType: 'url',
    sourceUrl: 'https://www.nytimes.com/interactive/kyoto-walks',
    status: 'ready',
    favorite: false,
    archived: false,
    knowledgeType: 'other',
    confidence: 0.84,
    lifecycleStatus: 'completed',
    structuredData: {
      title: 'Three Walks Through Kyoto',
      summary:
        'Philosopher’s Path at dawn, the Fushimi Inari back route to avoid the crowds, and a river walk that ends at a standing bar.',
      highlights: ['Dawn start', 'Back routes', '2–3 hours each'],
    },
    createdAt: daysAgo(4),
    updatedAt: daysAgo(3),
  },
  {
    id: 'sv-07',
    sourceType: 'url',
    sourceUrl: 'https://www.instagram.com/reel/ichiran-counter',
    status: 'processing',
    favorite: false,
    archived: false,
    createdAt: daysAgo(5),
    updatedAt: daysAgo(5),
  },
  {
    id: 'sv-08',
    sourceType: 'url',
    sourceUrl: 'https://example.com/a-page-that-would-not-load',
    status: 'failed',
    favorite: false,
    archived: false,
    errorCode: 'EXTRACTION_FAILED',
    errorMessage: 'We couldn’t read anything from that page.',
    createdAt: daysAgo(6),
    updatedAt: daysAgo(6),
  },
  {
    id: 'sv-09',
    sourceType: 'text',
    status: 'ready',
    favorite: false,
    archived: true,
    knowledgeType: 'other',
    confidence: 0.72,
    lifecycleStatus: 'saved',
    structuredData: {
      title: 'Standing desk — measurements before ordering',
      summary: 'Desk height 74 cm seated, 108 cm standing. Cable tray needs 60 cm clearance behind.',
    },
    createdAt: daysAgo(8),
    updatedAt: daysAgo(8),
  },
  {
    id: 'sv-10',
    sourceType: 'url',
    sourceUrl: 'https://www.goodreads.com/book/pachinko',
    status: 'ready',
    favorite: false,
    archived: false,
    knowledgeType: 'movie',
    confidence: 0.9,
    lifecycleStatus: 'started',
    structuredData: {
      title: 'Pachinko',
      year: '2017',
      director: 'Min Jin Lee',
      runtime: '490 pages',
      synopsis: 'Four generations of a Korean family in Japan, told through what each one is willing to give up.',
    },
    createdAt: daysAgo(11),
    updatedAt: daysAgo(9),
  },
  {
    id: 'sv-11',
    sourceType: 'url',
    sourceUrl: 'https://www.tiktok.com/@anime/video/romance-top-5',
    status: 'ready',
    favorite: false,
    archived: false,
    knowledgeType: 'recommendation_list',
    confidence: 0.94,
    lifecycleStatus: 'saved',
    // K2/K3 fixture: shares "Blue Box" with sv-12 below, so the mock
    // collections merge path (@/collections/merge) has a real overlapping
    // entity to exercise — not just the happy singleton. Mirrors the two
    // real dev-database saves K0 measured (docs/knowledge-collections.md).
    structuredData: {
      title: 'Top 5 Romance Anime This Season',
      medium: 'anime',
      summary: 'Five picks if you want the slow-burn kind, not the confession-in-episode-one kind.',
      orderMatters: 'no',
      items: [
        {
          name: 'Blue Box',
          kind: 'anime',
          year: '2024',
          genre: ['romance', 'sports'],
          reason: 'Best enemies-to-lovers arc this year',
          platform: 'Crunchyroll',
        },
        {
          name: 'Call of the Night',
          kind: 'anime',
          year: '2022',
          genre: ['romance', 'comedy'],
          reason: 'Slower pace, more atmosphere than plot',
          platform: 'HIDIVE',
        },
      ],
    },
    createdAt: daysAgo(1),
    updatedAt: daysAgo(1),
  },
  {
    id: 'sv-12',
    sourceType: 'url',
    sourceUrl: 'https://www.instagram.com/reel/anime-watchlist-2026',
    status: 'ready',
    favorite: false,
    archived: false,
    knowledgeType: 'recommendation_list',
    confidence: 0.9,
    lifecycleStatus: 'saved',
    structuredData: {
      title: 'Underrated Romance Anime Nobody Talks About',
      medium: 'anime',
      summary: 'Deep cuts from a Reel with 40k likes and zero mainstream coverage.',
      orderMatters: 'no',
      items: [
        {
          name: 'Blue Box',
          kind: 'anime',
          year: '[unclear]',
          genre: ['romance'],
          reason: 'Underrated gem, criminally low ratings',
          platform: '[unclear]',
        },
        {
          name: 'The Fragrant Flower Blooms With Dignity',
          kind: 'anime',
          year: '2025',
          genre: ['romance'],
          reason: 'Two-episode short, but it lands',
          platform: 'Crunchyroll',
        },
      ],
    },
    createdAt: hoursAgo(6),
    updatedAt: hoursAgo(6),
  },
];

/** Where each save came from, for the "YouTube • Workout" line on Home. */
export const MOCK_SOURCE_LABELS: Record<string, string> = {
  'sv-01': 'YouTube',
  'sv-02': 'Web',
  'sv-03': 'Instagram',
  'sv-04': 'TikTok',
  'sv-05': 'Letterboxd',
  'sv-06': 'Web',
  'sv-07': 'Instagram',
  'sv-08': 'Web',
  'sv-09': 'Note',
  'sv-10': 'Goodreads',
  'sv-11': 'TikTok',
  'sv-12': 'Instagram',
};

/** The category shown beside the source. Deliberately the *group* name. */
export const MOCK_CATEGORIES: Record<string, string> = {
  'sv-01': 'Workout',
  'sv-02': 'Travel',
  'sv-03': 'Recipes',
  'sv-04': 'Recipes',
  'sv-05': 'Watchlist',
  'sv-06': 'Travel',
  'sv-07': 'Recipes',
  'sv-08': 'Reading',
  'sv-09': 'Shopping',
  'sv-10': 'Reading',
  'sv-11': 'Watchlist',
  'sv-12': 'Watchlist',
};

export const MOCK_SPACES: Space[] = [
  {
    id: 'sp-japan',
    name: 'Japan Trip',
    type: 'travel',
    ownerId: MOCK_USER_ID,
    myRole: 'owner',
    memberCount: 4,
    saveCount: 12,
    createdAt: daysAgo(21),
    lastActivityAt: hoursAgo(4),
  },
  {
    id: 'sp-book',
    name: 'Book Club',
    type: 'general',
    ownerId: 'mock-user-sam',
    myRole: 'editor',
    memberCount: 6,
    saveCount: 9,
    createdAt: daysAgo(40),
    lastActivityAt: daysAgo(9),
  },
];

export const MOCK_MEMBERS: Record<string, SpaceMember[]> = {
  'sp-japan': [
    { userId: MOCK_USER_ID, displayName: 'Maya', role: 'owner', joinedAt: daysAgo(21) },
    { userId: 'mock-user-sam', displayName: 'Sam', role: 'editor', joinedAt: daysAgo(20) },
    { userId: 'mock-user-ana', displayName: 'Ana', role: 'editor', joinedAt: daysAgo(18) },
    { userId: 'mock-user-lee', displayName: 'Lee', role: 'viewer', joinedAt: daysAgo(9) },
  ],
  'sp-book': [
    { userId: 'mock-user-sam', displayName: 'Sam', role: 'owner', joinedAt: daysAgo(40) },
    { userId: MOCK_USER_ID, displayName: 'Maya', role: 'editor', joinedAt: daysAgo(38) },
  ],
};

export const MOCK_ACTIVITY: Record<string, ActivityEntry[]> = {
  'sp-japan': [
    { id: 'ac-1', userId: 'mock-user-sam', displayName: 'Sam', saveId: 'sv-02', saveTitle: 'Kyoto Cafe List', type: 'save_added', createdAt: hoursAgo(4) },
    { id: 'ac-2', userId: 'mock-user-ana', displayName: 'Ana', saveId: 'sv-06', saveTitle: 'Three Walks Through Kyoto', type: 'save_completed', createdAt: daysAgo(1) },
    { id: 'ac-3', userId: 'mock-user-lee', displayName: 'Lee', type: 'member_joined', createdAt: daysAgo(9) },
  ],
  'sp-book': [
    { id: 'ac-4', userId: MOCK_USER_ID, displayName: 'Maya', saveId: 'sv-10', saveTitle: 'Pachinko', type: 'save_added', createdAt: daysAgo(9) },
  ],
};

export const MOCK_COMMENTS: Record<string, SaveComment[]> = {
  'sv-02': [
    { id: 'cm-1', userId: 'mock-user-sam', displayName: 'Sam', body: 'The one by the river opens at 8, the rest not till 11.', createdAt: hoursAgo(6), mine: false },
    { id: 'cm-2', userId: MOCK_USER_ID, displayName: 'Maya', body: 'Booked the Tuesday slot.', createdAt: hoursAgo(2), mine: true },
  ],
  'sv-03': [
    { id: 'cm-3', userId: 'mock-user-ana', displayName: 'Ana', body: 'Doubled the garlic, no regrets.', createdAt: daysAgo(1), mine: false },
  ],
};

export const MOCK_SHOPPING_LIST: ShoppingListResponse = {
  id: 'sl-1',
  categories: ['Produce', 'Dairy & Eggs', 'Pantry', 'Bakery'],
  items: [
    { id: 'it-1', name: 'Garlic', quantity: '7', unit: 'cloves', category: 'Produce', checked: false, sources: ['sv-03'] },
    { id: 'it-2', name: 'Ginger', quantity: '1', unit: 'thumb', category: 'Produce', checked: false, sources: ['sv-03'] },
    { id: 'it-3', name: 'Spinach', quantity: '1', unit: 'handful', category: 'Produce', checked: true, sources: ['sv-03'] },
    { id: 'it-4', name: 'Eggs', quantity: '6', category: 'Dairy & Eggs', checked: false, sources: ['sv-03'] },
    { id: 'it-5', name: 'White miso', quantity: '2', unit: 'tbsp', category: 'Pantry', checked: false, sources: ['sv-03'] },
    { id: 'it-6', name: 'Toasted sesame oil', quantity: '1', unit: 'tbsp', category: 'Pantry', checked: false, sources: ['sv-03'] },
    { id: 'it-7', name: 'Strong white flour', quantity: '100', unit: 'g', category: 'Pantry', checked: false, sources: ['sv-04'] },
    { id: 'it-8', name: 'Fresh ramen noodles', quantity: '2', unit: 'portions', category: 'Bakery', checked: false, sources: ['sv-03'] },
  ],
};

export const MOCK_WEEKLY_DIGEST =
  'You saved 14 items this week — mostly recipes and Kyoto travel spots. Your ramen bookmark is 3 days from expiring reservations.';
