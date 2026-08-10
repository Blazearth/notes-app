/**
 * The real backend, behind the `Repository` interface.
 *
 * Deliberately nothing but delegation — no caching, no shaping, no defaults.
 * Every method here is a one-line forward to `@/api/client`, which is the only
 * module left in the app that knows about HTTP. If a behaviour ever needs to
 * differ between mock and real, it belongs in the screen or in the client, not
 * in this file: the moment this file starts *doing* something, "the mock and
 * the real thing behave differently" stops being answerable by reading it.
 */

import * as client from '@/api/client';
import type { Repository } from './repository';

export const apiRepository: Repository = {
  createSave: client.createSave,
  listSaves: client.listSaves,
  listSavesByLifecycle: client.listSavesByLifecycle,
  getSave: client.getSave,
  setSaveLifecycle: client.setSaveLifecycle,
  setSaveFlags: client.setSaveFlags,
  updateNote: client.updateNote,
  setSaveSpace: client.setSaveSpace,
  deleteSave: client.deleteSave,
  setSaveItemState: client.setSaveItemState,
  searchSaves: client.searchSaves,
  getRelatedSaves: client.getRelatedSaves,

  getMe: client.getMe,
  getWeeklyDigest: client.getDigest,

  convertToShoppingList: client.convertToShoppingList,
  getShoppingList: client.getShoppingList,
  setShoppingItemChecked: client.setShoppingItemChecked,
  deleteShoppingItem: client.deleteShoppingItem,
  clearCheckedShoppingItems: client.clearCheckedShoppingItems,

  listSpaces: client.listSpaces,
  getSpace: client.getSpace,
  createSpace: client.createSpace,
  renameSpace: client.renameSpace,
  deleteSpace: client.deleteSpace,
  listSpaceSaves: client.listSpaceSaves,
  listSpaceMembers: client.listSpaceMembers,
  getSpaceKnowledge: client.getSpaceKnowledge,
  listSpaceCollectionEntities: client.listSpaceCollectionEntities,
  listEntityComments: client.listEntityComments,
  addEntityComment: client.addEntityComment,
  deleteEntityComment: client.deleteEntityComment,
  listSpacePins: client.listSpacePins,
  pinInSpace: client.pinInSpace,
  unpinInSpace: client.unpinInSpace,
  getSpaceShoppingList: client.getSpaceShoppingList,
  clearCheckedSpaceShoppingItems: client.clearCheckedSpaceShoppingItems,
  setMemberRole: client.setMemberRole,
  removeMember: client.removeMember,
  createInvite: client.createInvite,
  listInvites: client.listInvites,
  revokeInvite: client.revokeInvite,
  previewInvite: client.previewInvite,
  acceptInvite: client.acceptInvite,
  getSpaceActivity: client.getSpaceActivity,
  listDuplicates: client.listDuplicates,
  dismissDuplicate: client.dismissDuplicate,
  mergeDuplicate: client.mergeDuplicate,

  listComments: client.listComments,
  addComment: client.addComment,
  deleteComment: client.deleteComment,
  setVote: client.setVote,

  /**
   * Real as of `GET /v1/groups`, and delegation like everything else here.
   *
   * The server derives the tree per request from each save's `knowledgeType`
   * and the facets the classify call already extracted, so the response shape
   * matches `KnowledgeGroup` exactly and no mapping is needed. An empty array
   * is the honest answer for a library with no `ready` saves yet, and the Home
   * grid hides itself on it.
   */
  listGroups: client.listGroups,
  getGroup: client.getGroup,
  listGroupSaves: client.listGroupSaves,

  listCollections: client.listCollections,
  listCollectionEntities: client.listCollectionEntities,
  setEntityState: client.setEntityState,

  mergeEntities: client.mergeEntities,
  unmergeEntity: client.unmergeEntity,
  renameEntity: client.renameEntity,
  renameCollection: client.renameCollection,

  pullSync: client.getSync,
};
