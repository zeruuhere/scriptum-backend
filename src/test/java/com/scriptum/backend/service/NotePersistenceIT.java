package com.scriptum.backend.service;

import com.scriptum.backend.domain.request.NoteRequestBody;
import com.scriptum.backend.domain.request.TagRequestBody;
import com.scriptum.backend.domain.response.NoteResponseBody;
import com.scriptum.backend.domain.response.TagResponseBody;
import com.scriptum.backend.infrastructure.database.jpa.Notes;
import com.scriptum.backend.infrastructure.database.jpa.UserJpaEntity;
import com.scriptum.backend.infrastructure.database.repository.INotesJpaRepository;
import com.scriptum.backend.infrastructure.database.repository.ITagJpaRepository;
import com.scriptum.backend.infrastructure.database.repository.IUserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the note and tag write path.
 *
 * <p>Everything below the service is real: {@code NoteRepositoryImpl},
 * {@code TagRepositoryImpl}, Hibernate and the H2 schema. The unit tests for
 * these services mock the repository ports, so a mapper that quietly drops a
 * field satisfies them while losing data against a real database. That is
 * exactly what happened twice here — a note save wiped its NOTE_TAGS rows, and
 * a tag was persisted with a null USER_ID — and neither could be caught with
 * mocks. These assertions read the rows back through JPA instead of trusting
 * the service's own return value.
 */
@SpringBootTest
@Transactional
@DisplayName("Note and tag persistence")
class NotePersistenceIT {

    @Autowired
    private NoteService noteService;

    @Autowired
    private TagService tagService;

    @Autowired
    private INotesJpaRepository notesJpaRepository;

    @Autowired
    private ITagJpaRepository tagJpaRepository;

    @Autowired
    private IUserJpaRepository userJpaRepository;

    private UUID ownerId;

    @BeforeEach
    void persistOwner() {
        UserJpaEntity owner = new UserJpaEntity();
        owner.setName("Owner");
        owner.setEmail("owner@example.invalid");
        owner.setPassword("hashed-password");
        owner.setEmailVerified(true);
        ownerId = userJpaRepository.saveAndFlush(owner).getId();
    }

    private UUID createTag(String name) {
        TagResponseBody tag = tagService.createTagFromRequest(new TagRequestBody(name, "#00FF00", ownerId));
        return tag.getId();
    }

    @Test
    @DisplayName("a tag created through the service is stored owned by its user")
    void createdTagIsStoredWithItsOwner() {
        UUID tagId = createTag("java");
        tagJpaRepository.flush();

        assertThat(tagJpaRepository.findById(tagId))
                .isPresent()
                .get()
                .satisfies(stored -> {
                    assertThat(stored.getName()).isEqualTo("java");
                    // A null owner here makes the tag invisible to every
                    // findByUserId* query, including the duplicate-name check.
                    assertThat(stored.getUser()).isNotNull();
                    assertThat(stored.getUser().getId()).isEqualTo(ownerId);
                });

        assertThat(tagJpaRepository.findByUserIdAndName(ownerId, "java")).isPresent();
    }

    @Test
    @DisplayName("a note created with tags keeps them in the join table")
    void createdNoteKeepsItsTagsInTheDatabase() {
        UUID tagId = createTag("java");

        NoteResponseBody created = noteService.createNoteFromRequest(
                new NoteRequestBody("first", "body text", ownerId, Set.of(tagId)));
        notesJpaRepository.flush();

        assertThat(created.getTags()).extracting(TagResponseBody::getId).containsExactly(tagId);

        Notes stored = notesJpaRepository.findById(created.getId()).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("first");
        assertThat(stored.getContent()).isEqualTo("body text");
        assertThat(stored.getUser().getId()).isEqualTo(ownerId);
        assertThat(stored.getTags())
                .extracting(com.scriptum.backend.infrastructure.database.jpa.Tag::getId)
                .containsExactly(tagId);
    }

    @Test
    @DisplayName("updating a note without naming its tags leaves them in place")
    void updatingNoteWithoutTagIdsPreservesStoredTags() {
        UUID tagId = createTag("java");
        NoteResponseBody created = noteService.createNoteFromRequest(
                new NoteRequestBody("first", "body text", ownerId, Set.of(tagId)));
        notesJpaRepository.flush();

        // A null tagIds means "leave the tags alone". The service returns the
        // updated note either way, so the check has to be against the rows.
        noteService.updateNoteFromRequest(
                created.getId(), new NoteRequestBody("renamed", "new body", ownerId, null));
        notesJpaRepository.flush();

        Notes stored = notesJpaRepository.findById(created.getId()).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo("renamed");
        assertThat(stored.getContent()).isEqualTo("new body");
        assertThat(stored.getTags())
                .extracting(com.scriptum.backend.infrastructure.database.jpa.Tag::getId)
                .containsExactly(tagId);
    }

    @Test
    @DisplayName("a stored note is found by the owner's derived queries")
    void storedNoteIsVisibleToOwnerQueries() {
        UUID tagId = createTag("java");
        noteService.createNoteFromRequest(
                new NoteRequestBody("Spring Boot", "about persistence", ownerId, Set.of(tagId)));
        notesJpaRepository.flush();

        assertThat(noteService.getAllNoteResponsesByUserId(ownerId))
                .extracting(NoteResponseBody::getTitle)
                .containsExactly("Spring Boot");
        assertThat(noteService.searchNoteResponsesByTitle(ownerId, "spring")).hasSize(1);
        assertThat(noteService.searchNoteResponsesByContent(ownerId, "PERSISTENCE")).hasSize(1);
        assertThat(noteService.getNoteResponsesByTag(ownerId, tagId)).hasSize(1);
    }

    @Test
    @DisplayName("deleting a note removes its row but keeps the tag")
    void deletingNoteKeepsTheTag() {
        UUID tagId = createTag("java");
        NoteResponseBody created = noteService.createNoteFromRequest(
                new NoteRequestBody("first", "body text", ownerId, Set.of(tagId)));
        notesJpaRepository.flush();

        noteService.deleteNote(created.getId());
        notesJpaRepository.flush();

        assertThat(notesJpaRepository.findById(created.getId())).isEmpty();
        assertThat(tagJpaRepository.findById(tagId)).isPresent();
    }
}
