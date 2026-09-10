package com.scriptum.backend.infrastructure.database.repository;

import com.scriptum.backend.infrastructure.database.jpa.Notes;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every query here is fetched with its tags and owner.
 *
 * <p>{@code Notes.tags} is a lazy many-to-many, and {@code NoteRepositoryImpl}
 * maps it into the domain object after the repository's own transaction has
 * closed. With open-in-view disabled there is no session left at that point, so
 * without these graphs a plain read of a note that has tags fails with a
 * LazyInitializationException — a 500 for any user with notes. Fetching in the
 * query also removes the extra tag query that would otherwise run per note.
 */
@Repository
public interface INotesJpaRepository extends JpaRepository<Notes, UUID> {

    @Override
    @EntityGraph(attributePaths = {"tags", "user"})
    Optional<Notes> findById(UUID id);

    @EntityGraph(attributePaths = {"tags", "user"})
    List<Notes> findByUserIdOrderByModifiedAtDesc(UUID userId);

    @EntityGraph(attributePaths = {"tags", "user"})
    List<Notes> findByUserIdAndTitleContainingIgnoreCaseOrderByModifiedAtDesc(UUID userId, String title);

    @EntityGraph(attributePaths = {"tags", "user"})
    List<Notes> findByUserIdAndContentContainingIgnoreCaseOrderByModifiedAtDesc(UUID userId, String content);

    @EntityGraph(attributePaths = {"tags", "user"})
    List<Notes> findByUserIdAndTagsIdOrderByModifiedAtDesc(UUID userId, UUID tagId);

}
