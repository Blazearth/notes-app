import type { SaveResponse, SaveStatus } from '@/api/types';

/**
 * Presentation helpers for a save that the pipeline has not touched yet.
 *
 * This is the whole reason the feed needs care: `POST /v1/saves` returns a row
 * with nothing but a source and a status. `knowledgeType`, `structuredData` and
 * every derived field arrive later — and today they never arrive at all, because
 * nothing consumes the job queue. So a save has to look deliberate, not broken,
 * with only a URL to work from.
 */

export function saveTitle(save: SaveResponse): string {
  // `place` names its field `name`, not `title` — see KnowledgeTypeRegistry.
  const structuredTitle = save.structuredData?.title ?? save.structuredData?.name;
  if (typeof structuredTitle === 'string' && structuredTitle.trim()) {
    return structuredTitle.trim();
  }

  // Text saves: use the first line of rawCaption as the title so the card is
  // immediately meaningful before the pipeline classifies the note.
  if (save.sourceType === 'text' && save.rawCaption) {
    const firstLine = save.rawCaption.split('\n')[0].trim();
    if (firstLine) return firstLine.length > 72 ? `${firstLine.slice(0, 72)}…` : firstLine;
    return 'Untitled note';
  }

  if (save.sourceUrl) {
    try {
      const url = new URL(save.sourceUrl);
      const path = url.pathname.replace(/\/$/, '');
      return path && path !== '/' ? `${url.hostname}${path}` : url.hostname;
    } catch {
      return save.sourceUrl;
    }
  }

  return 'Untitled save';

}

/** The metadata line: what we know so far, which early on is just the source. */
export function saveSubtitle(save: SaveResponse): string {
  const parts: string[] = [];

  if (save.knowledgeType) parts.push(save.knowledgeType);
  else parts.push(SOURCE_LABELS[save.sourceType]);

  if (save.status === 'failed' && save.errorMessage) parts.push(save.errorMessage);
  else if (save.status !== 'ready') parts.push(STATUS_LABELS[save.status]);

  return parts.join(' · ');
}

/**
 * "youtube.com" → "YouTube" for the save-detail Source row — a platform name
 * reads as provenance, where the raw URL reads as a technical field nobody
 * asked to see. Falls back to the bare hostname for anything unrecognised,
 * never to the full URL.
 */
const PLATFORM_HOSTS: Array<[RegExp, string]> = [
  [/(^|\.)youtube\.com$|^youtu\.be$/, 'YouTube'],
  [/(^|\.)instagram\.com$/, 'Instagram'],
  [/(^|\.)tiktok\.com$/, 'TikTok'],
  [/(^|\.)reddit\.com$/, 'Reddit'],
  [/(^|\.)(twitter\.com|x\.com)$/, 'X'],
  [/(^|\.)pinterest\.[a-z.]+$/, 'Pinterest'],
  [/(^|\.)facebook\.com$/, 'Facebook'],
];

export function sourcePlatformName(url: string): string {
  try {
    const hostname = new URL(url).hostname.replace(/^www\./, '');
    const match = PLATFORM_HOSTS.find(([pattern]) => pattern.test(hostname));
    return match ? match[1] : hostname;
  } catch {
    return url;
  }
}

const SOURCE_LABELS: Record<SaveResponse['sourceType'], string> = {
  url: 'Link',
  text: 'Note',
  image: 'Image',
  pdf: 'PDF',
  audio: 'Audio',
};

/**
 * The bare noun for "Saving this ___" on a still-processing save — read from
 * the URL's own shape (host + path), never guessed from content the pipeline
 * hasn't extracted yet. `Short`/`Reel` keep the platform's own capitalised
 * feature name; everything else is a lowercase common noun.
 */
export function sourceKindLabel(save: SaveResponse): string {
  if (save.sourceType !== 'url' || !save.sourceUrl) {
    return { url: 'link', text: 'note', image: 'image', pdf: 'PDF', audio: 'audio' }[save.sourceType];
  }
  try {
    const url = new URL(save.sourceUrl);
    const host = url.hostname.replace(/^www\./, '');
    const path = url.pathname;
    if (/(^|\.)youtube\.com$/.test(host) || host === 'youtu.be') return /\/shorts\//.test(path) ? 'Short' : 'video';
    if (/(^|\.)instagram\.com$/.test(host)) return /\/reel/i.test(path) ? 'Reel' : 'post';
    if (/(^|\.)tiktok\.com$/.test(host)) return 'video';
    if (/(^|\.)reddit\.com$/.test(host)) return 'post';
    if (/(^|\.)(twitter\.com|x\.com)$/.test(host)) return 'post';
    if (/(^|\.)pinterest\.[a-z.]+$/.test(host)) return 'pin';
    if (/(^|\.)facebook\.com$/.test(host)) return 'post';
  } catch {
    // Malformed URL: fall through to the generic noun below.
  }
  return 'link';
}

/** "https://www.youtube.com/shorts/WnNPIKikQYI" → "youtube.com/shorts/WnNPIKikQYI" — for a de-emphasised secondary line, never the hero. */
export function bareUrl(url: string): string {
  return url.replace(/^https?:\/\//, '').replace(/^www\./, '').replace(/\/$/, '');
}

/**
 * "3h ago" for an activity feed or a comment.
 *
 * Cuts off at a week and shows a date instead: past that point the exact day is
 * what people actually want, and "23 days ago" is arithmetic the reader has to
 * do themselves.
 */
export function relativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';

  const seconds = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (seconds < 60) return 'just now';

  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;

  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;

  const days = Math.round(hours / 24);
  if (days < 7) return `${days}d ago`;

  return new Date(then).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

/**
 * Illustrative only, for an empty Home/Library's "Try it with" row — every
 * chip opens the same Capture sheet as the "Add something" button. There is
 * no per-platform capture path to link to, so naming a spread of sources here
 * is purely to make "paste anything" concrete on an otherwise-empty screen,
 * never a claim that these do something different from one another.
 */
export const TRY_IT_EXAMPLES = ['YouTube', 'Instagram', 'Reddit', 'Article', 'Product'];

export const STATUS_LABELS: Record<SaveStatus, string> = {
  processing: 'Processing',
  // Not a failure. The daily AI budget was spent, so the save waits for the next
  // window — the user is told, but never shown an error.
  pending: 'Queued for tomorrow',
  ready: 'Ready',
  failed: 'Failed',
};
