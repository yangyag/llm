package com.llm.app.auth;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 관리 API — ADMIN 전용. 일반 USER 호출은 403 FORBIDDEN. */
@RestController
@RequestMapping("/api/v1/users")
public class UserManagementController {

    private final JwtProvider jwtProvider;
    private final UserManagementService userManagementService;

    public UserManagementController(JwtProvider jwtProvider, UserManagementService userManagementService) {
        this.jwtProvider = jwtProvider;
        this.userManagementService = userManagementService;
    }

    @GetMapping
    public List<UserResponse> listUsers(
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @RequestParam(required = false) String query
    ) {
        String username = jwtProvider.authenticate(authHeader);
        return userManagementService.listUsers(username, query);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @Valid @RequestBody CreateUserRequest request
    ) {
        String username = jwtProvider.authenticate(authHeader);
        return userManagementService.createUser(username, request);
    }

    @PutMapping("/{id}")
    public UserResponse updateUser(
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @PathVariable Long id,
        @Valid @RequestBody UpdateUserRequest request
    ) {
        String username = jwtProvider.authenticate(authHeader);
        return userManagementService.updateUser(username, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @PathVariable Long id
    ) {
        String username = jwtProvider.authenticate(authHeader);
        userManagementService.deleteUser(username, id);
    }
}
