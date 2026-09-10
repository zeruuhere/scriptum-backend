package com.scriptum.backend.infrastructure.database.repository;

import com.scriptum.backend.infrastructure.database.jpa.UserJpaEntity;
import com.scriptum.backend.infrastructure.database.jpa.VerificationToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@DisplayName("IVerificationTokenRepository derived queries")
class IVerificationTokenRepositoryIT {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private IVerificationTokenRepository tokenRepository;

    private UserJpaEntity owner;
    private UserJpaEntity stranger;

    @BeforeEach
    void persistOwners() {
        owner = persistUser("owner@example.invalid");
        stranger = persistUser("stranger@example.invalid");
    }

    private UserJpaEntity persistUser(String email) {
        UserJpaEntity user = new UserJpaEntity();
        user.setName("User " + email);
        user.setEmail(email);
        user.setPassword("hashed-password");
        user.setEmailVerified(false);
        return entityManager.persistAndFlush(user);
    }

    private VerificationToken persistToken(UserJpaEntity user, String token, boolean verified) {
        return entityManager.persistAndFlush(VerificationToken.builder()
                .token(token)
                .user(user)
                .expiryDate(LocalDateTime.now().plusMinutes(1440))
                .verified(verified)
                .build());
    }

    @Test
    @DisplayName("findByToken finds the token and its owning user")
    void findByTokenResolvesOwner() {
        persistToken(owner, "a-token", false);

        assertThat(tokenRepository.findByToken("a-token"))
                .isPresent()
                .get()
                .satisfies(token -> assertThat(token.getUser().getId()).isEqualTo(owner.getId()));
    }

    @Test
    @DisplayName("findByToken returns an empty Optional for an unknown token")
    void findByTokenReturnsEmptyForUnknownToken() {
        assertThat(tokenRepository.findByToken("ghost-token")).isEmpty();
    }

    @Test
    @DisplayName("findByUserIdAndVerifiedFalse finds the user's pending token")
    void findPendingTokenForUser() {
        persistToken(owner, "pending", false);

        assertThat(tokenRepository.findByUserIdAndVerifiedFalse(owner.getId()))
                .isPresent()
                .get()
                .satisfies(token -> assertThat(token.getToken()).isEqualTo("pending"));
    }

    @Test
    @DisplayName("findByUserIdAndVerifiedFalse ignores a token that is already verified")
    void findPendingTokenIgnoresVerified() {
        persistToken(owner, "already-verified", true);

        assertThat(tokenRepository.findByUserIdAndVerifiedFalse(owner.getId())).isEmpty();
    }

    @Test
    @DisplayName("findByUserIdAndVerifiedFalse does not return another user's pending token")
    void findPendingTokenScopesToUser() {
        persistToken(stranger, "theirs", false);

        assertThat(tokenRepository.findByUserIdAndVerifiedFalse(owner.getId())).isEmpty();
    }

    @Test
    @DisplayName("isExpired is false for a token whose expiry is still ahead")
    void isExpiredIsFalseForFutureExpiry() {
        VerificationToken saved = persistToken(owner, "fresh", false);

        assertThat(saved.isExpired()).isFalse();
    }

    @Test
    @DisplayName("isExpired is true for a token whose expiry has passed")
    void isExpiredIsTrueForPastExpiry() {
        VerificationToken stale = entityManager.persistAndFlush(VerificationToken.builder()
                .token("stale")
                .user(owner)
                .expiryDate(LocalDateTime.now().minusMinutes(1))
                .verified(false)
                .build());

        assertThat(stale.isExpired()).isTrue();
    }

    @Test
    @DisplayName("deleting a token leaves the user in place")
    void deletingTokenKeepsUser() {
        VerificationToken saved = persistToken(owner, "to-delete", false);

        tokenRepository.delete(saved);
        entityManager.flush();

        assertThat(tokenRepository.findByToken("to-delete")).isEmpty();
        assertThat(entityManager.find(UserJpaEntity.class, owner.getId())).isNotNull();
    }
}
