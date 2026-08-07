package com.llm.app.auth;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 관리 API TDD 테스트.
 * 역할 모델: ADMIN(관리자) / USER(일반사용자). 사용자 관리 API는 ADMIN 전용.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtProvider jwtProvider;

    private Long adminId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        adminRepository.deleteAll();
        Admin admin = saveAdmin("admin", "adminpass", UserRole.ADMIN);
        Admin member = saveAdmin("member1", "memberpass", UserRole.USER);
        adminId = admin.getId();
        memberId = member.getId();
    }

    private Admin saveAdmin(String username, String password, UserRole role) {
        Admin admin = new Admin();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setRole(role);
        admin.setCreatedAt(Instant.now());
        return adminRepository.saveAndFlush(admin);
    }

    private String adminToken() {
        return "Bearer " + jwtProvider.generateToken("admin");
    }

    private String memberToken() {
        return "Bearer " + jwtProvider.generateToken("member1");
    }

    private String createRequest(String username, String password, String role) {
        return """
            {
              "username": "%s",
              "password": "%s",
              "role": "%s"
            }
            """.formatted(username, password, role);
    }

    private void loginAndExpect(String username, String password, org.springframework.test.web.servlet.ResultMatcher status) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "%s"
                    }
                    """.formatted(username, password)))
            .andExpect(status);
    }

    // ---------- 목록 ----------

    @Test
    void listUsersWithoutTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void listUsersWithUserRoleShouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                .header("Authorization", memberToken()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void listUsersWithAdminRoleShouldReturnAllUsers() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                .header("Authorization", adminToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].id", notNullValue()))
            .andExpect(jsonPath("$[0].username").value("admin"))
            .andExpect(jsonPath("$[0].role").value("ADMIN"))
            .andExpect(jsonPath("$[0].createdAt", notNullValue()))
            .andExpect(jsonPath("$[1].username").value("member1"))
            .andExpect(jsonPath("$[1].role").value("USER"))
            .andExpect(jsonPath("$[0].password").doesNotExist())
            .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void listUsersWithQueryShouldFilterByUsername() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                .param("query", "MEMBER")
                .header("Authorization", adminToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].username").value("member1"));
    }

    // ---------- 생성 ----------

    @Test
    void createUserShouldReturn201AndAllowLogin() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("user2", "pass1234", "USER")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id", notNullValue()))
            .andExpect(jsonPath("$.username").value("user2"))
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.createdAt", notNullValue()))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist());

        loginAndExpect("user2", "pass1234", status().isOk());
    }

    @Test
    void createUserWithLowercaseRoleShouldNormalize() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("user3", "pass1234", "user")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void createUserWithDuplicateUsernameShouldReturn409() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("member1", "pass1234", "USER")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_USERNAME"));
    }

    @Test
    void createUserWithNonAlphanumericUsernameShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("사용자", "pass1234", "USER")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void createUserWithInvalidRoleShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("user4", "pass1234", "SUPER")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void createUserWithBlankPasswordShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("user5", "   ", "USER")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void createUserWithShortPasswordShouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("user6", "abc", "USER")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void createUserByUserRoleShouldReturn403() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                .header("Authorization", memberToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createRequest("user7", "pass1234", "USER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ---------- 수정 ----------

    @Test
    void updateUserRoleShouldReturn200() throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}", memberId)
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "role": "ADMIN" }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(memberId))
            .andExpect(jsonPath("$.username").value("member1"))
            .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void updateUserPasswordShouldReplacePassword() throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}", memberId)
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "password": "newpass99", "role": "USER" }
                    """))
            .andExpect(status().isOk());

        loginAndExpect("member1", "memberpass", status().isUnauthorized());
        loginAndExpect("member1", "newpass99", status().isOk());
    }

    @Test
    void updateUserWithoutPasswordShouldKeepExistingPassword() throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}", memberId)
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "role": "USER" }
                    """))
            .andExpect(status().isOk());

        loginAndExpect("member1", "memberpass", status().isOk());
    }

    @Test
    void demoteLastAdminShouldReturn409() throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}", adminId)
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "role": "USER" }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTED"));
    }

    @Test
    void updateUserWithInvalidRoleShouldReturn400() throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}", memberId)
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "role": "SUPER" }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void updateNonExistentUserShouldReturn404() throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}", 999999L)
                .header("Authorization", adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "role": "USER" }
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void updateUserByUserRoleShouldReturn403() throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}", adminId)
                .header("Authorization", memberToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "role": "USER" }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ---------- 삭제 ----------

    @Test
    void deleteUserShouldReturn204AndBlockLogin() throws Exception {
        mockMvc.perform(delete("/api/v1/users/{id}", memberId)
                .header("Authorization", adminToken()))
            .andExpect(status().isNoContent());

        loginAndExpect("member1", "memberpass", status().isUnauthorized());
    }

    @Test
    void deleteLastAdminShouldReturn409() throws Exception {
        mockMvc.perform(delete("/api/v1/users/{id}", adminId)
                .header("Authorization", adminToken()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTED"));
    }

    @Test
    void deleteSelfShouldReturn409EvenWhenOtherAdminExists() throws Exception {
        saveAdmin("admin2", "adminpass2", UserRole.ADMIN);

        mockMvc.perform(delete("/api/v1/users/{id}", adminId)
                .header("Authorization", adminToken()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SELF_DELETE_NOT_ALLOWED"));
    }

    @Test
    void deleteNonExistentUserShouldReturn404() throws Exception {
        mockMvc.perform(delete("/api/v1/users/{id}", 999999L)
                .header("Authorization", adminToken()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void deleteUserByUserRoleShouldReturn403() throws Exception {
        mockMvc.perform(delete("/api/v1/users/{id}", adminId)
                .header("Authorization", memberToken()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void deleteUserWithoutTokenShouldReturn401() throws Exception {
        mockMvc.perform(delete("/api/v1/users/{id}", memberId))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ---------- 로그인/me 역할 ----------

    @Test
    void loginShouldReturnRole() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "username": "admin", "password": "adminpass" }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("ADMIN"));

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "username": "member1", "password": "memberpass" }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void meShouldReturnRole() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", adminToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("admin"))
            .andExpect(jsonPath("$.role").value("ADMIN"));

        mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", memberToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void meForDeletedUserShouldReturn401() throws Exception {
        String token = memberToken();
        adminRepository.deleteById(memberId);

        mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", token))
            .andExpect(status().isUnauthorized());
    }
}
