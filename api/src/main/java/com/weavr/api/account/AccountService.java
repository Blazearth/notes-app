package com.weavr.api.account;

import java.util.List;
import java.util.UUID;

import com.weavr.api.common.NotFoundException;
import com.weavr.api.config.SupabaseAdminClient;
import com.weavr.api.save.Save;
import com.weavr.api.save.SaveRepository;
import com.weavr.api.save.SaveService;
import com.weavr.api.space.SpaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Permanently deletes an account and everything it owns.
 *
 * <p>Spring connects to Postgres as a service role, so — same rule as
 * {@link SpaceService} and {@link SaveService} — nothing here trusts an id
 * from the caller; every step is scoped to the authenticated user's own id.
 *
 * <h2>Not one transaction</h2>
 * <p>{@link SpaceService#delete} and {@link SaveService#delete} are each
 * already {@code @Transactional} and commit on their own. Wrapping the loops
 * below in one outer transaction would mean Postgres aborting the whole
 * transaction on a single failed statement — a documented trap elsewhere in
 * this codebase — rolls back every Space and save already deleted, not just
 * the one that failed, turning a retry into redoing work it had already
 * finished. Instead, every step here tolerates "already gone"
 * ({@link NotFoundException}), which is what makes calling this twice safe: a
 * retried request after a partial failure finishes the job instead of
 * erroring on rows the first attempt already removed.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final JdbcClient jdbc;
    private final SpaceService spaces;
    private final SaveService saveService;
    private final SaveRepository saveRepository;
    private final SupabaseAdminClient supabaseAdmin;

    AccountService(JdbcClient jdbc, SpaceService spaces, SaveService saveService,
                   SaveRepository saveRepository, SupabaseAdminClient supabaseAdmin) {
        this.jdbc = jdbc;
        this.spaces = spaces;
        this.saveService = saveService;
        this.saveRepository = saveRepository;
        this.supabaseAdmin = supabaseAdmin;
    }

    public void deleteAccount(UUID userId) {
        // Spaces this account owns, through the same path a manual delete
        // uses: tombstones fire for every other member, and the audience is
        // read before the Space (and its members) disappear.
        for (UUID spaceId : ownedSpaceIds(userId)) {
            try {
                spaces.delete(userId, spaceId);
            } catch (NotFoundException e) {
                // Already gone — a retry after a partial earlier attempt.
            }
        }

        // Every save this account owns, including one sitting in a Space
        // someone else owns. A bare `profiles` cascade would remove those
        // silently; routing through SaveService.delete records a SAVE
        // tombstone for each; so that Space's other members learn it is
        // gone instead of holding a reference nothing can explain.
        for (Save save : saveRepository.findByUserId(userId)) {
            try {
                saveService.delete(userId, save.getId());
            } catch (NotFoundException e) {
                // Already gone.
            }
        }

        // Memberships in Spaces this account does not own — the owner and
        // every other member learn this account left, exactly as if it had
        // tapped "Leave" itself.
        for (UUID spaceId : memberSpaceIds(userId)) {
            try {
                spaces.removeMember(userId, spaceId, userId);
            } catch (NotFoundException e) {
                // Already gone.
            }
        }

        // Everything else this account owns outright — subscriptions, usage
        // counters, digests, entity states, item states, collection
        // overrides, idempotency keys, its own tombstones, and any comment,
        // vote or pin it left in a Space it does not own — cascades away
        // with the profile row. None of that has an audience to tombstone:
        // it is either private to this account, or a small trace in someone
        // else's Space with no local cache to invalidate (see
        // TombstoneService's own note on why COMMENT and VOTE deletions are
        // recorded server-side but never applied client-side).
        deleteProfile(userId);

        // Best-effort, and last: revokes the ability to sign in at all and
        // invalidates refresh tokens. Every row this app owns is already
        // gone by this point, so a failure here is a login-capability leak,
        // not a data leak — but it does mean a retry of this same call is
        // what closes it out, since every loop above will find nothing left
        // to do on a second attempt and only this step will run again.
        supabaseAdmin.deleteUser(userId);

        log.info("Account {} deleted", userId);
    }

    private List<UUID> ownedSpaceIds(UUID userId) {
        return jdbc.sql("select id from spaces where owner_id = ?")
                .param(userId)
                .query(UUID.class)
                .list();
    }

    private List<UUID> memberSpaceIds(UUID userId) {
        return jdbc.sql("select space_id from space_members where user_id = ?")
                .param(userId)
                .query(UUID.class)
                .list();
    }

    @Transactional
    void deleteProfile(UUID userId) {
        jdbc.sql("delete from profiles where id = ?").param(userId).update();
    }
}
