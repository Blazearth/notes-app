package com.weavr.api.account;

import java.util.UUID;

import com.weavr.api.auth.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountController {

    private final AccountService accountService;

    AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Permanently deletes the caller's account and everything it owns —
     * Spaces it owns, saves anywhere (including ones shared into other
     * people's Spaces), its memberships elsewhere, and every row scoped to
     * its profile. Returns 204 whether or not there was anything left to
     * delete, so a retried request after a partial failure succeeds instead
     * of 404ing on a step an earlier attempt already finished.
     */
    @DeleteMapping("/v1/me")
    ResponseEntity<Void> deleteAccount(@CurrentUser UUID userId) {
        accountService.deleteAccount(userId);
        return ResponseEntity.noContent().build();
    }
}
