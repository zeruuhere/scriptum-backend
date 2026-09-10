package com.scriptum.backend.system;

import com.scriptum.backend.domain.request.UserRequestBody;
import com.scriptum.backend.domain.response.AuthResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * System test for the API's access rules, over real HTTP.
 *
 * <p>{@code SecurityIT} covers the same rules through MockMvc, which calls the
 * filter chain directly and never touches a socket. This runs the same
 * questions past a real servlet container, so a header the container rewrites
 * or an entry point that behaves differently under Tomcat shows up here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("API access rules over HTTP")
class ApiSecuritySystemIT {

    @Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private JavaMailSender mailSender;

    private String token;
    private UUID userId;

    @BeforeEach
    void registerCaller() {
        String email = "security-" + UUID.randomUUID() + "@example.invalid";

        ResponseEntity<AuthResponseBody> registered = rest.postForEntity(
                "/api/auth/register",
                new UserRequestBody("Security User", email, "secret123", null),
                AuthResponseBody.class);

        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody()).isNotNull();
        token = registered.getBody().getToken();
        userId = registered.getBody().getUserId();
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static HttpHeaders authorization(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, value);
        return headers;
    }

    @Test
    @DisplayName("serves the health endpoint without any credential")
    void healthIsPublic() {
        ResponseEntity<String> response = get("/api/auth/health", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("API running...");
    }

    @ParameterizedTest(name = "GET {0} without a token is 401")
    @ValueSource(strings = {"/api/notes", "/api/tags", "/api/users/me"})
    @DisplayName("rejects the protected endpoints without a token")
    void rejectsProtectedEndpointsWithoutToken(String path) {
        assertThat(get(path, new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest(name = "Authorization: {0} is 401")
    @ValueSource(strings = {
            "Bearer not-a-jwt",
            "Bearer a.b.c",
            "Basic c2VjdXJpdHk6c2VjcmV0",
            "not-a-scheme-at-all"
    })
    @DisplayName("rejects unusable Authorization headers")
    void rejectsUnusableAuthorizationHeaders(String header) {
        assertThat(get("/api/notes?userId=" + userId, authorization(header)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("admits the caller with the token registration issued")
    void admitsValidToken() {
        ResponseEntity<String> response =
                get("/api/notes?userId=" + userId, authorization("Bearer " + token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    @DisplayName("never returns the password hash on the profile endpoint")
    void profileNeverExposesThePasswordHash() {
        ResponseEntity<String> response = get("/api/users/me", authorization("Bearer " + token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"email\"")
                // UserResponseBody declares a password field that the mapper
                // never fills, so it serialises as null. Asserting on the null
                // rather than on the key's absence is what actually guards the
                // hash: the day someone populates it, this fails.
                .contains("\"password\":null")
                .doesNotContain("$2a$")
                .doesNotContain("$2b$");
    }
}
