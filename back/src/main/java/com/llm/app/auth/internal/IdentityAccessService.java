package com.llm.app.auth.internal;

import com.llm.app.auth.api.AuthenticatedUser;
import com.llm.app.auth.api.IdentityAccess;
import com.llm.app.auth.api.InvalidCredentialsException;

import org.springframework.stereotype.Service;

@Service
class IdentityAccessService implements IdentityAccess {

    private final AdminRepository adminRepository;

    /**
     * 인증된 사용자 정보를 조회하는 서비스에 필요한 저장소를 초기화한다.
     *
     * @param adminRepository 관리자 계정 저장소
     */
    IdentityAccessService(AdminRepository adminRepository) {
        this.adminRepository = adminRepository;
    }

    /**
     * 사용자 ID에 해당하는 계정을 조회해 인증된 사용자 정보로 반환한다.
     *
     * @param userId 인증된 사용자 계정 ID
     * @return 인증된 사용자의 ID, 이름, 권한 정보
     * @throws InvalidCredentialsException 사용자 ID가 없거나 해당 계정이 존재하지 않을 때
     */
    @Override
    public AuthenticatedUser requireUser(Long userId) {
        if (userId == null) {
            throw new InvalidCredentialsException("User no longer exists");
        }
        return adminRepository.findById(userId)
            .map(user -> new AuthenticatedUser(user.getId(), user.getUsername(), user.getRole()))
            .orElseThrow(() -> new InvalidCredentialsException("User no longer exists"));
    }
}
