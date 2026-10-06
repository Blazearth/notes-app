import React, { useCallback, useState } from 'react';

import { useOutbox } from '@/local/useSync';
import { ConfirmSheet } from './ConfirmSheet';

/**
 * Signing out, with a warning when it would throw away unsent changes.
 *
 * Sign-out wipes the local store, outbox included (`sync.reset`) — it must,
 * because replaying one account's queued writes under the next account's
 * session would attribute them to the wrong person. But a note edited, or a
 * list ticked, offline and not yet delivered was then deleted without a word.
 * This asks first, and only when there is something to lose.
 *
 * Returns the handler for the button and the sheet to render beside it.
 */
export function useGuardedSignOut(signOut: () => void): {
  requestSignOut: () => void;
  signOutSheet: React.ReactNode;
} {
  const { pending, failed } = useOutbox();
  const unsent = pending.length + failed.length;
  const [asking, setAsking] = useState(false);

  const requestSignOut = useCallback(() => {
    if (unsent > 0) setAsking(true);
    else signOut();
  }, [unsent, signOut]);

  const signOutSheet = (
    <ConfirmSheet
      visible={asking}
      title="Log out with unsent changes?"
      message={`${unsent} ${unsent === 1 ? 'change hasn’t' : 'changes haven’t'} reached Weavr yet. Logging out now deletes ${unsent === 1 ? 'it' : 'them'}. Reconnect first to keep ${unsent === 1 ? 'it' : 'them'}.`}
      confirmLabel="Log out anyway"
      onConfirm={() => {
        setAsking(false);
        signOut();
      }}
      onCancel={() => setAsking(false)}
    />
  );

  return { requestSignOut, signOutSheet };
}
