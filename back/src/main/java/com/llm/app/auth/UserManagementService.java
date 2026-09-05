package com.llm.app.auth;

import com.llm.app.board.exception.NotFoundException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자(관리자/일반사용자) 관리 서비스. 모든 호출자는 ADMIN이어야 한다.
 * 보호 규칙:
 * - 마지막 남은 ADMIN은 삭제/강등 불가 (관리자 잠금 방지)
 * - 자기 자신 삭제 불가
 */
@Service
public class UserManagementService {

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(AdminRepository adminRepository, PasswordEncoder passwordEncoder) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers(Long requester, String query) {
        requireAdmin(requester);
        List<Admin> users = adminRepository.findAll();
        if (query != null && !query.isBlank()) {
            String keyword = query.trim().toLowerCase();
            users = users.stream()
                .filter(user -> user.getUsername().toLowerCase().contains(keyword))
                .toList();
        }
        return users.stream()
            .sorted(Comparator.comparing(Admin::getId))
            .map(UserManagementService::toResponse)
            .toList();
    }

    @Transactional
    public UserResponse createUser(Long requester, CreateUserRequest request) {
        requireAdmin(requester);
        if (adminRepository.findByUsername(request.username()).isPresent()) {
            throw new DuplicateUsernameException("username already exists: " + request.username());
        }
        Admin admin = new Admin();
        admin.setUsername(request.username());
        admin.setPasswordHash(passwordEncoder.encode(request.password()));
        admin.setRole(UserRole.from(request.role()));
        admin.setCreatedAt(Instant.now());
        return toResponse(adminRepository.save(admin));
    }

    @Transactional
    public UserResponse updateUser(Long requester, Long id, UpdateUserRequest request) {
        requireAdmin(requester);
        Admin target = adminRepository.findById(id)
            .orElseThrow(() -> NotFoundException.user(id));

        UserRole newRole = UserRole.from(request.role());
        if (target.getRole() == UserRole.ADMIN && newRole == UserRole.USER && countAdmins() <= 1) {
            throw new LastAdminProtectedException("cannot demote the last remaining admin");
        }
        target.setRole(newRole);

        if (request.password() != null && !request.password().isBlank()) {
            target.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        return toResponse(target);
    }

    @Transactional
    public void deleteUser(Long requester, Long id) {
        Admin requesterAdmin = requireAdmin(requester);
        Admin target = adminRepository.findById(id)
            .orElseThrow(() -> NotFoundException.user(id));

        // 마지막 ADMIN 보호가 자기 자신 삭제 체크보다 먼저 오도록 순서 유지.
        if (target.getRole() == UserRole.ADMIN && countAdmins() <= 1) {
            throw new LastAdminProtectedException("cannot delete the last remaining admin");
        }
        if (target.getId().equals(requesterAdmin.getId())) {
            throw new SelfDeleteNotAllowedException("cannot delete your own account");
        }
        adminRepository.delete(target);
    }

    private Admin requireAdmin(Long userId) {
        Admin admin = adminRepository.findById(userId)
            .orElseThrow(() -> new InvalidCredentialsException("User no longer exists"));
        if (admin.getRole() != UserRole.ADMIN) {
            throw new ForbiddenException("admin role is required");
        }
        return admin;
    }

    private long countAdmins() {
        return adminRepository.countByRole(UserRole.ADMIN);
    }

    private static UserResponse toResponse(Admin admin) {
        return new UserResponse(admin.getId(), admin.getUsername(), admin.getRole(), admin.getCreatedAt());
    }
}
