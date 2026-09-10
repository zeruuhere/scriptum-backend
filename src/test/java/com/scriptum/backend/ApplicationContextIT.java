package com.scriptum.backend;

import com.scriptum.backend.api.controllers.AuthController;
import com.scriptum.backend.api.controllers.NoteController;
import com.scriptum.backend.api.controllers.TagController;
import com.scriptum.backend.api.controllers.UserController;
import com.scriptum.backend.domain.repositories.INoteRepository;
import com.scriptum.backend.domain.repositories.ITagRepository;
import com.scriptum.backend.infrastructure.database.repository.INotesJpaRepository;
import com.scriptum.backend.infrastructure.database.repository.ITagJpaRepository;
import com.scriptum.backend.infrastructure.database.repository.IUserJpaRepository;
import com.scriptum.backend.infrastructure.database.repository.IVerificationTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the whole context starts, and the wiring that is easy to break silently
 * is actually present.
 *
 * <p>The repository assertions are deliberate. {@code @EnableJpaRepositories} was
 * removed from the bootstrap class in favour of the auto-configuration; this is what
 * proves the Spring Data proxies still get created.
 */
@SpringBootTest
@DisplayName("Application context")
class ApplicationContextIT {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("starts and exposes every controller")
    void contextExposesControllers() {
        assertThat(context.getBean(AuthController.class)).isNotNull();
        assertThat(context.getBean(NoteController.class)).isNotNull();
        assertThat(context.getBean(TagController.class)).isNotNull();
        assertThat(context.getBean(UserController.class)).isNotNull();
    }

    @Test
    @DisplayName("creates the Spring Data JPA repository proxies")
    void contextCreatesJpaRepositories() {
        assertThat(context.getBean(IUserJpaRepository.class)).isNotNull();
        assertThat(context.getBean(INotesJpaRepository.class)).isNotNull();
        assertThat(context.getBean(ITagJpaRepository.class)).isNotNull();
        assertThat(context.getBean(IVerificationTokenRepository.class)).isNotNull();
    }

    @Test
    @DisplayName("binds the domain repository ports to their JPA implementations")
    void contextBindsDomainPorts() {
        assertThat(context.getBean(INoteRepository.class)).isNotNull();
        assertThat(context.getBean(ITagRepository.class)).isNotNull();
    }

    @Test
    @DisplayName("registers the security filter chain")
    void contextRegistersSecurityFilterChain() {
        assertThat(context.getBean(SecurityFilterChain.class)).isNotNull();
    }
}
