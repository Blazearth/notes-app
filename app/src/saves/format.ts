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
  const structuredTitle = save.structuredData?.title;
  if (typeof structuredTitle === 'string' && structuredTitle.trim()) {
    return structuredTitle.trim();
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

const SOURCE_LABELS: Record<SaveResponse['sourceType'], string> = {
  url: 'Link',
  text: 'Note',
  image: 'Image',
  pdf: 'PDF',
  audio: 'Audio',
};

export const STATUS_LABELS: Record<SaveStatus, string> = {
  processing: 'Processing',
  // Not a failure. The daily AI budget was spent, so the save waits for the next
  // window — the user is told, but never shown an error.
  pending: 'Queued for tomorrow',
  ready: 'Ready',
  failed: 'Failed',
};
