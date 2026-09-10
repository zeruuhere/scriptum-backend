package com.scriptum.backend.system;

import com.scriptum.backend.domain.request.AuthRequestBody;
import com.scriptum.backend.domain.request.NoteRequestBody;
import com.scriptum.backend.domain.request.TagRequestBody;
import com.scriptum.backend.domain.request.UserRequestBody;
import com.scriptum.backend.domain.response.AuthResponseBody;
import com.scriptum.backend.domain.response.NoteResponseBody;
import com.scriptum.backend.domain.response.TagResponseBody;
import com.scriptum.backend.domain.response.UserResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * System test: the whole application over real HTTP.
 *
 * <p>Tomcat is started on a random port and driven with an HTTP client, so
 * unlike the MockMvc tests this exercises the servlet container, the real
 * filter chain, Jackson on both ends and H2 — end to end, as a caller sees it.
 *
 * <p>The one stub is {@link JavaMailSender}. SMTP is a third-party system, and
 * registration sends a verification message without catching failures, so a
 * real send attempt against a host with nothing listening would answer 500.
 * Nothing belonging to this application is mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("User journey over HTTP")
class UserJourneySystemIT {

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private JavaMailSender mailSender;

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private static String uniqueEmail() {
        return "journey-" + UUID.randomUUID() + "@example.invalid";
    }

    @Test
    @DisplayName("registers, authenticates, then creates, reads, updates and deletes a note")
    void completesTheWholeJourney() {
        String email = uniqueEmail();

        // --- register ---------------------------------------------------
        ResponseEntity<AuthResponseBody> registered = rest.postForEntity(
                "/api/auth/register",
                new UserRequestBody("Journey User", email, "secret123", null),
                AuthResponseBody.class);

        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody()).isNotNull();
        assertThat(registered.getBody().getEmail()).isEqualTo(email);
        assertThat(registered.getBody().getToken()).isNotBlank();
        assertThat(registered.getBody().isEmailVerified()).isFalse();

        UUID userId = registered.getBody().getUserId();

        // --- log in and use the token from that call onwards -------------
        ResponseEntity<AuthResponseBody> loggedIn = rest.postForEntity(
                "/api/auth/login",
                new AuthRequestBody(email, "secret123"),
                AuthResponseBody.class);

        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loggedIn.getBody()).isNotNull();
        assertThat(loggedIn.getBody().getUserId()).isEqualTo(userId);

        String token = loggedIn.getBody().getToken();

        // --- the token identifies the caller on a protected endpoint -----
        ResponseEntity<UserResponseBody> me = rest.exchange(
                "/api/users/me", HttpMethod.GET, new HttpEntity<>(bearer(token)), UserResponseBody.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).isNotNull();
        assertThat(me.getBody().getEmail()).isEqualTo(email);
        assertThat(me.getBody().getName()).isEqualTo("Journey User");

        // --- create a tag, then a note carrying it ----------------------
        ResponseEntity<TagResponseBody> tag = rest.exchange(
                "/api/tags",
                HttpMethod.POST,
                new HttpEntity<>(new TagRequestBody("journey", "#00FF00", userId), bearer(token)),
                TagResponseBody.class);

        assertThat(tag.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(tag.getBody()).isNotNull();
        UUID tagId = tag.getBody().getId();

        ResponseEntity<NoteResponseBody> created = rest.exchange(
                "/api/notes",
                HttpMethod.POST,
                new HttpEntity<>(
                        new NoteRequestBody("Primeira nota", "conteudo da nota", userId, Set.of(tagId)),
                        bearer(token)),
                NoteResponseBody.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().getTitle()).isEqualTo("Primeira nota");
        assertThat(created.getBody().getTags()).extracting(TagResponseBody::getId).containsExactly(tagId);

        UUID noteId = created.getBody().getId();

        // --- read it back through the listing ---------------------------
        ResponseEntity<List<NoteResponseBody>> listed = rest.exchange(
                "/api/notes?userId=" + userId,
                HttpMethod.GET,
                new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<List<NoteResponseBody>>() {
                });

        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody())
                .extracting(NoteResponseBody::getId)
                .contains(noteId);

        // --- update it, leaving the tags unnamed ------------------------
        ResponseEntity<NoteResponseBody> updated = rest.exchange(
                "/api/notes/" + noteId,
                HttpMethod.PUT,
                new HttpEntity<>(
                        new NoteRequestBody("Nota renomeada", "conteudo novo", userId, null),
                        bearer(token)),
                NoteResponseBody.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().getTitle()).isEqualTo("Nota renomeada");
        assertThat(updated.getBody().getTags())
                .as("an update that does not name the tags must not drop them")
                .extracting(TagResponseBody::getId)
                .containsExactly(tagId);

        // --- delete it, and confirm it is gone --------------------------
        ResponseEntity<Void> deleted = rest.exchange(
                "/api/notes/" + noteId, HttpMethod.DELETE, new HttpEntity<>(bearer(token)), Void.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<List<NoteResponseBody>> afterDelete = rest.exchange(
                "/api/notes?userId=" + userId,
                HttpMethod.GET,
                new HttpEntity<>(bearer(token)),
                new ParameterizedTypeReference<List<NoteResponseBody>>() {
                });

        assertThat(afterDelete.getBody())
                .extracting(NoteResponseBody::getId)
                .doesNotContain(noteId);
    }

    @Test
    @DisplayName("refuses a second registration of the same address")
    void refusesDuplicateRegistration() {
        String email = uniqueEmail();
        UserRequestBody request = new UserRequestBody("Journey User", email, "secret123", null);

        assertThat(rest.postForEntity("/api/auth/register", request, AuthResponseBody.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(rest.postForEntity("/api/auth/register", request, AuthResponseBody.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }
}
