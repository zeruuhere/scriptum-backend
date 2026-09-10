package com.scriptum.backend.infrastructure.database.repository;

import com.scriptum.backend.infrastructure.database.jpa.Tag;
import com.scriptum.backend.infrastructure.database.jpa.UserJpaEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@DisplayName("ITagJpaRepository derived queries")
class ITagJpaRepositoryIT {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ITagJpaRepository tagJpaRepository;

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
        user.setEmailVerified(true);
        return entityManager.persistAndFlush(user);
    }

    private Tag persistTag(UserJpaEntity user, String name) {
        return entityManager.persistAndFlush(Tag.builder()
                .name(name)
                .color("#00FF00")
                .user(user)
                .build());
    }

    @Test
    @DisplayName("findByUserIdOrderByNameAsc returns only the owner's tags, alphabetically")
    void findByUserIdScopesToOwnerAndSortsByName() {
        persistTag(owner, "spring");
        persistTag(owner, "java");
        persistTag(owner, "docker");
        persistTag(stranger, "aardvark");

        List<Tag> result = tagJpaRepository.findByUserIdOrderByNameAsc(owner.getId());

        assertThat(result).extracting(Tag::getName).containsExactly("docker", "java", "spring");
    }

    @Test
    @DisplayName("findByUserIdAndName finds the owner's tag by exact name")
    void findByUserIdAndNameMatchesExactly() {
        persistTag(owner, "java");

        assertThat(tagJpaRepository.findByUserIdAndName(owner.getId(), "java"))
                .isPresent()
                .get()
                .satisfies(tag -> assertThat(tag.getUser().getId()).isEqualTo(owner.getId()));
    }

    @Test
    @DisplayName("findByUserIdAndName is case sensitive")
    void findByUserIdAndNameIsCaseSensitive() {
        persistTag(owner, "java");

        assertThat(tagJpaRepository.findByUserIdAndName(owner.getId(), "JAVA")).isEmpty();
    }

    @Test
    @DisplayName("findByUserIdAndName does not find another user's tag of the same name")
    void findByUserIdAndNameScopesToOwner() {
        persistTag(stranger, "java");

        assertThat(tagJpaRepository.findByUserIdAndName(owner.getId(), "java")).isEmpty();
    }

    @Test
    @DisplayName("findByUserIdAndName lets two users hold the same tag name independently")
    void sameNameIsAllowedForDifferentOwners() {
        persistTag(owner, "java");
        persistTag(stranger, "java");

        assertThat(tagJpaRepository.findByUserIdAndName(owner.getId(), "java")).isPresent();
        assertThat(tagJpaRepository.findByUserIdAndName(stranger.getId(), "java")).isPresent();
    }

    @Test
    @DisplayName("findByUserIdAndNameContainingIgnoreCase matches the fragment regardless of case")
    void findByNameContainingIgnoresCase() {
        persistTag(owner, "JavaScript");
        persistTag(owner, "java");
        persistTag(owner, "docker");

        List<Tag> result = tagJpaRepository
                .findByUserIdAndNameContainingIgnoreCaseOrderByNameAsc(owner.getId(), "JAV");

        assertThat(result).extracting(Tag::getName).containsExactlyInAnyOrder("java", "JavaScript");
    }

    @Test
    @DisplayName("OrderByNameAsc sorts on the raw column, so the ordering is case sensitive")
    void orderByNameIsCaseSensitive() {
        persistTag(owner, "apple");
        persistTag(owner, "Zebra");

        List<Tag> result = tagJpaRepository.findByUserIdOrderByNameAsc(owner.getId());

        // Only IgnoreCase is applied to the *filter*; the ORDER BY has no collation
        // override, so uppercase sorts ahead of lowercase. "Zebra" before "apple"
        // is what the database actually does, not what a reader would guess.
        assertThat(result).extracting(Tag::getName).containsExactly("Zebra", "apple");
    }

    @Test
    @DisplayName("findByUserIdAndNameContainingIgnoreCase returns an empty list when nothing matches")
    void findByNameContainingReturnsEmptyWhenNoMatch() {
        persistTag(owner, "java");

        assertThat(tagJpaRepository
                .findByUserIdAndNameContainingIgnoreCaseOrderByNameAsc(owner.getId(), "kotlin"))
                .isEmpty();
    }

    @Test
    @DisplayName("persisting a tag stamps createdAt and modifiedAt")
    void persistingStampsTimestamps() {
        Tag saved = persistTag(owner, "java");

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getModifiedAt()).isNotNull();
    }
}
