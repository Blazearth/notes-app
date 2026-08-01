/**
 * The seam.
 *
 * Every screen imports `repo` from here and nothing else. Which implementation
 * that is depends on one boolean in `config.ts`, and no UI code can tell the
 * difference — that is the whole point, and it is why this file has no logic in
 * it beyond the choice itself.
 */

import { USE_MOCK_DATA } from './config';
import { apiRepository } from './apiRepository';
import { mockRepository } from './mockRepository';
import type { Repository } from './repository';

export const repo: Repository = USE_MOCK_DATA ? mockRepository : apiRepository;

export { USE_MOCK_DATA } from './config';
export type { KnowledgeGroup, Repository } from './repository';
export { MOCK_USER_NAME, MOCK_WEEKLY_DIGEST, MOCK_SOURCE_LABELS, MOCK_CATEGORIES } from './mockData';
