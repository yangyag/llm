package com.llm.app.auth.internal;

import com.llm.app.auth.api.ForbiddenException;
import com.llm.app.auth.api.InvalidCredentialsException;
import com.llm.app.auth.api.UserRole;
import com.llm.app.auth.exception.DuplicateUsernameException;
import com.llm.app.auth.exception.LastAdminProtectedException;
import com.llm.app.auth.exception.SelfDeleteNotAllowedException;
import com.llm.app.auth.exception.UserNotFoundException;
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

    /**
     * 사용자 관리에 필요한 저장소와 비밀번호 암호화기를 초기화한다.
     *
     * @param adminRepository 관리자 계정 저장소
     * @param passwordEncoder 비밀번호 해시 생성기
     */
    public UserManagementService(AdminRepository adminRepository, PasswordEncoder passwordEncoder) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 관리자 권한을 확인한 뒤 사용자 목록을 조회한다.
     * 검색어가 있으면 사용자 이름에 해당 문자열을 포함하는 계정만 반환한다.
     *
     * @param requester 요청자 계정 ID
     * @param query 사용자 이름 검색어. 비어 있으면 전체 사용자를 조회한다.
     * @return 계정 ID 오름차순으로 정렬된 사용자 목록
     * @throws InvalidCredentialsException 요청자 계정이 존재하지 않을 때
     * @throws ForbiddenException 요청자가 관리자가 아닐 때
     */
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

    /**
     * 관리자 권한을 확인한 뒤 새 사용자를 생성한다.
     * 입력 비밀번호는 저장 전에 해시로 변환한다.
     *
     * @param requester 요청자 계정 ID
     * @param request 생성할 사용자의 이름, 비밀번호, 권한을 담은 요청
     * @return 생성된 사용자 정보
     * @throws InvalidCredentialsException 요청자 계정이 존재하지 않을 때
     * @throws ForbiddenException 요청자가 관리자가 아닐 때
     * @throws DuplicateUsernameException 같은 사용자 이름이 이미 존재할 때
     */
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

    /**
     * 관리자 권한을 확인한 뒤 대상 사용자의 권한과 비밀번호를 수정한다.
     * 마지막 관리자 계정이 일반 사용자로 강등되지 않도록 보호한다.
     *
     * @param requester 요청자 계정 ID
     * @param id 수정할 사용자 계정 ID
     * @param request 변경할 권한과 선택적 비밀번호를 담은 요청
     * @return 수정된 사용자 정보
     * @throws InvalidCredentialsException 요청자 계정이 존재하지 않을 때
     * @throws ForbiddenException 요청자가 관리자가 아닐 때
     * @throws UserNotFoundException 대상 계정이 존재하지 않을 때
     * @throws LastAdminProtectedException 마지막 관리자 계정을 강등하려 할 때
     */
    @Transactional
    public UserResponse updateUser(Long requester, Long id, UpdateUserRequest request) {
        requireAdmin(requester);
        Admin target = adminRepository.findById(id)
            .orElseThrow(() -> UserNotFoundException.user(id));

        UserRole newRole = UserRole.from(request.role());
        if (target.getRole() == UserRole.ADMIN && newRole == UserRole.USER && isLastAdmin(target.getId())) {
            throw new LastAdminProtectedException("cannot demote the last remaining admin");
        }
        target.setRole(newRole);

        if (request.password() != null && !request.password().isBlank()) {
            target.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        return toResponse(target);
    }

    /**
     * 관리자 권한을 확인한 뒤 대상 사용자 계정을 삭제한다.
     * 마지막 관리자 계정과 요청자 본인의 계정은 삭제할 수 없다.
     *
     * @param requester 요청자 계정 ID
     * @param id 삭제할 사용자 계정 ID
     * @throws InvalidCredentialsException 요청자 계정이 존재하지 않을 때
     * @throws ForbiddenException 요청자가 관리자가 아닐 때
     * @throws UserNotFoundException 대상 계정이 존재하지 않을 때
     * @throws LastAdminProtectedException 마지막 관리자 계정을 삭제하려 할 때
     * @throws SelfDeleteNotAllowedException 요청자 본인의 계정을 삭제하려 할 때
     */
    @Transactional
    public void deleteUser(Long requester, Long id) {
        Admin requesterAdmin = requireAdmin(requester);
        Admin target = adminRepository.findById(id)
            .orElseThrow(() -> UserNotFoundException.user(id));

        // 마지막 ADMIN 보호가 자기 자신 삭제 체크보다 먼저 오도록 순서 유지.
        if (target.getRole() == UserRole.ADMIN && isLastAdmin(target.getId())) {
            throw new LastAdminProtectedException("cannot delete the last remaining admin");
        }
        if (target.getId().equals(requesterAdmin.getId())) {
            throw new SelfDeleteNotAllowedException("cannot delete your own account");
        }
        adminRepository.delete(target);
    }

    /**
     * 사용자 계정을 조회하고 관리자 권한을 확인한다.
     *
     * @param userId 권한을 확인할 계정 ID
     * @return 확인된 관리자 계정
     * @throws InvalidCredentialsException 계정이 존재하지 않을 때
     * @throws ForbiddenException 계정이 관리자 권한을 갖지 않을 때
     */
    private Admin requireAdmin(Long userId) {
        Admin admin = adminRepository.findById(userId)
            .orElseThrow(() -> new InvalidCredentialsException("User no longer exists"));
        if (admin.getRole() != UserRole.ADMIN) {
            throw new ForbiddenException("admin role is required");
        }
        return admin;
    }

    /**
     * 관리자 행을 잠근 뒤 대상이 남은 유일한 관리자인지 확인한다.
     * 관리자 둘이 동시에 서로를 강등·삭제해도 잠금 때문에 한 요청씩 판단하므로 관리자가 0명이 되지 않는다.
     *
     * @param targetId 강등·삭제하려는 계정 ID
     * @return 대상이 현재 유일한 관리자이면 {@code true}
     */
    private boolean isLastAdmin(Long targetId) {
        adminRepository.lockAllByRole(UserRole.ADMIN);
        // 잠금을 기다리는 동안 다른 요청이 커밋한 변경을 반영하도록 잠근 뒤에 다시 조회한다.
        return adminRepository.existsByIdAndRole(targetId, UserRole.ADMIN)
            && adminRepository.countByRole(UserRole.ADMIN) <= 1;
    }

    /**
     * 관리자 엔티티를 외부 응답 객체로 변환한다.
     *
     * @param admin 변환할 관리자 엔티티
     * @return 사용자 응답 객체
     */
    private static UserResponse toResponse(Admin admin) {
        return new UserResponse(admin.getId(), admin.getUsername(), admin.getRole(), admin.getCreatedAt());
    }
}
