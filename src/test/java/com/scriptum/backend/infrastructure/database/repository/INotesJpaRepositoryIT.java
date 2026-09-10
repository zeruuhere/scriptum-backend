package com.scriptum.backend.infrastructure.database.repository;

import com.scriptum.backend.infrastructure.database.jpa.Notes;
import com.scriptum.backend.infrastructure.database.jpa.Tag;
import com.scriptum.backend.infrastructure.database.jpa.UserJpaEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the derived queries on {@link INotesJpaRepository} against a real schema.
 *
 * <p>Derived query methods are contracts expressed as method names: a typo or a
 * rename silently changes the generated SQL, and nothing else in the suite would
 * notice. These tests are what makes that a run failure.
 */
@DataJpaTest
@DisplayName("INotesJpaRepository derived queries")
class INotesJpaRepositoryIT {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private INotesJpaRepository notesJpaRepository;

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

    private Notes persistNote(UserJpaEntity user, String title, String content, Tag... tags) {
        return entityManager.persistAndFlush(Notes.builder()
                .title(title)
                .content(content)
                .user(user)
                .tags(Set.of(tags))
                .build());
    }

    @Test
    @DisplayName("findByUserIdOrderByModifiedAtDesc returns only the owner's notes")
    void findByUserIdScopesToOwner() {
        persistNote(owner, "mine", "body");
        persistNote(stranger, "theirs", "body");

        List<Notes> result = notesJpaRepository.findByUserIdOrderByModifiedAtDesc(owner.getId());

        assertThat(result).extracting(Notes::getTitle).containsExactly("mine");
    }

    @Test
    @DisplayName("findByUserIdOrderByModifiedAtDesc puts the most recently modified note first")
    void findByUserIdOrdersByModifiedAtDescending() {
        persistNote(owner, "older", "body");
        persistNote(owner, "newer", "body");

        List<Notes> result = notesJpaRepository.findByUserIdOrderByModifiedAtDesc(owner.getId());

        assertThat(result).extracting(Notes::getTitle).containsExactly("newer", "older");
    }

    @Test
    @DisplayName("findByUserIdOrderByModifiedAtDesc returns an empty list for a user with no notes")
    void findByUserIdReturnsEmptyForUserWithoutNotes() {
        assertThat(notesJpaRepository.findByUserIdOrderByModifiedAtDesc(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("findByUserIdAndTitleContainingIgnoreCase matches a case-insensitive fragment")
    void findByTitleIgnoresCase() {
        persistNote(owner, "Spring Boot Notes", "body");
        persistNote(owner, "unrelated", "body");

        List<Notes> result = notesJpaRepository
                .findByUserIdAndTitleContainingIgnoreCaseOrderByModifiedAtDesc(owner.getId(), "spring boot");

        assertThat(result).extracting(Notes::getTitle).containsExactly("Spring Boot Notes");
    }

    @Test
    @DisplayName("findByUserIdAndTitleContainingIgnoreCase does not leak another user's match")
    void findByTitleScopesToOwner() {
        persistNote(stranger, "Spring Boot Notes", "body");

        List<Notes> result = notesJpaRepository
                .findByUserIdAndTitleContainingIgnoreCaseOrderByModifiedAtDesc(owner.getId(), "spring");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByUserIdAndContentContainingIgnoreCase matches inside the TEXT column")
    void findByContentIgnoresCase() {
        persistNote(owner, "first", "A long piece of PROSE about persistence");
        persistNote(owner, "second", "something else");

        List<Notes> result = notesJpaRepository
                .findByUserIdAndContentContainingIgnoreCaseOrderByModifiedAtDesc(owner.getId(), "prose");

        assertThat(result).extracting(Notes::getTitle).containsExactly("first");
    }

    @Test
    @DisplayName("findByUserIdAndTagsId walks the NOTE_TAGS join table")
    void findByTagWalksJoinTable() {
        Tag java = persistTag(owner, "java");
        Tag spring = persistTag(owner, "spring");
        persistNote(owner, "tagged java", "body", java);
        persistNote(owner, "tagged spring", "body", spring);
        persistNote(owner, "untagged", "body");

        List<Notes> result = notesJpaRepository
                .findByUserIdAndTagsIdOrderByModifiedAtDesc(owner.getId(), java.getId());

        assertThat(result).extracting(Notes::getTitle).containsExactly("tagged java");
    }

    @Test
    @DisplayName("findByUserIdAndTagsId matches a note carrying several tags from either tag")
    void findByTagMatchesNoteWithSeveralTags() {
        Tag java = persistTag(owner, "java");
        Tag spring = persistTag(owner, "spring");
        persistNote(owner, "both", "body", java, spring);

        assertThat(notesJpaRepository
                .findByUserIdAndTagsIdOrderByModifiedAtDesc(owner.getId(), java.getId()))
                .extracting(Notes::getTitle)
                .containsExactly("both");
        assertThat(notesJpaRepository
                .findByUserIdAndTagsIdOrderByModifiedAtDesc(owner.getId(), spring.getId()))
                .extracting(Notes::getTitle)
                .containsExactly("both");
    }

    @Test
    @DisplayName("findByUserIdAndTagsId returns an empty list for a tag nothing carries")
    void findByTagReturnsEmptyForUnusedTag() {
        Tag unused = persistTag(owner, "unused");
        persistNote(owner, "untagged", "body");

        assertThat(notesJpaRepository
                .findByUserIdAndTagsIdOrderByModifiedAtDesc(owner.getId(), unused.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("persisting a note stamps createdAt and modifiedAt")
    void persistingStampsTimestamps() {
        Notes saved = persistNote(owner, "first", "body");

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getModifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("a note round-trips its TEXT content unchanged")
    void textContentRoundTrips() {
        String longContent = "line one\nline two\n" + "x".repeat(5000);
        Notes saved = persistNote(owner, "long", longContent);
        entityManager.clear();

        assertThat(notesJpaRepository.findById(saved.getId()))
                .isPresent()
                .get()
                .satisfies(note -> assertThat(note.getContent()).isEqualTo(longContent));
    }
}
