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
  searchSaves: client.searchSaves,

  getMe: client.getMe,

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
   * No endpoint serves AI groups yet.
   *
   * An empty array rather than a throw, because the Home and Library grids are
   * written to hide themselves when there is nothing to show — so with the real
   * backend selected those sections simply do not appear, which is the honest
   * rendering of "the server cannot group anything yet". When the endpoint
   * lands this becomes a one-line delegation like every other method.
   */
  listGroups: () => Promise.resolve([]),
};
