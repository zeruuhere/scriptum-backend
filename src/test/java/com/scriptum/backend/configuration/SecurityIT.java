package com.scriptum.backend.configuration;

import com.scriptum.backend.infrastructure.database.jpa.UserJpaEntity;
import com.scriptum.backend.infrastructure.database.repository.IUserJpaRepository;
import com.scriptum.backend.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end check of the real filter chain against the real security configuration.
 *
 * <p>This is the test that holds the two halves of authentication honest together:
 * {@code JwtAuthenticationFilter} actually populating the SecurityContext, and
 * {@code SecurityConfig} actually requiring it. Either one regressing on its own
 * turns these red.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("Security filter chain")
class SecurityIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IUserJpaRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private static final String EMAIL = "integration@example.invalid";

    private UserJpaEntity storedUser;

    @BeforeEach
    void persistUser() {
        UserJpaEntity user = new UserJpaEntity();
        user.setName("Integration User");
        user.setEmail(EMAIL);
        user.setPassword(passwordEncoder.encode("secret123"));
        user.setEmailVerified(true);
        user.setNewUser(false);
        storedUser = userRepository.save(user);
    }

    private String bearerFor(String email) {
        return "Bearer " + jwtService.generateToken(email);
    }

    @Test
    @DisplayName("permits the health endpoint without a token")
    void permitsHealthWithoutToken() throws Exception {
        mockMvc.perform(get("/api/auth/health"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "GET {0} without a token is 401")
    @ValueSource(strings = {"/api/notes", "/api/tags", "/api/users/me"})
    @DisplayName("rejects the protected endpoints without a token")
    void rejectsProtectedEndpointsWithoutToken(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("admits a request carrying a valid bearer token")
    void admitsValidBearerToken() throws Exception {
        mockMvc.perform(get("/api/notes")
                        .param("userId", storedUser.getId().toString())
                        .header("Authorization", bearerFor(EMAIL)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("resolves the authenticated principal on /api/users/me")
    void resolvesAuthenticatedPrincipal() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", bearerFor(EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.name").value("Integration User"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @ParameterizedTest(name = "Authorization: {0} is 401")
    @ValueSource(strings = {
            "Bearer not-a-jwt",
            "Bearer a.b.c",
            "Bearer ",
            "Basic aW50ZWdyYXRpb246c2VjcmV0",
            "not-a-scheme-at-all"
    })
    @DisplayName("rejects unusable Authorization headers")
    void rejectsUnusableAuthorizationHeaders(String header) throws Exception {
        mockMvc.perform(get("/api/notes").header("Authorization", header))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rejects a token signed with a different key")
    void rejectsForeignSignature() throws Exception {
        JwtService foreignIssuer = new JwtService();
        ReflectionTestUtils.setField(
                foreignIssuer, "secretKey", "a-different-signing-key-of-at-least-32-bytes-9876543210");
        ReflectionTestUtils.setField(foreignIssuer, "jwtExpiration", 86_400_000L);

        mockMvc.perform(get("/api/notes")
                        .header("Authorization", "Bearer " + foreignIssuer.generateToken(EMAIL)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rejects a token whose expiry has passed")
    void rejectsExpiredToken() throws Exception {
        JwtService staleIssuer = new JwtService();
        ReflectionTestUtils.setField(
                staleIssuer, "secretKey",
                "test-secret-key-for-unit-tests-only-do-not-use-in-production-0123456789");
        ReflectionTestUtils.setField(staleIssuer, "jwtExpiration", -86_400_000L);

        mockMvc.perform(get("/api/notes")
                        .header("Authorization", "Bearer " + staleIssuer.generateToken(EMAIL)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rejects a well-signed token whose subject is not a known user")
    void rejectsTokenForUnknownSubject() throws Exception {
        mockMvc.perform(get("/api/notes")
                        .header("Authorization", bearerFor("ghost@example.invalid")))
                .andExpect(status().isUnauthorized());
    }
}
