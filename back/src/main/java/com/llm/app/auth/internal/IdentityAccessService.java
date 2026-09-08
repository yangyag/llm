package com.llm.app.auth.internal;

import com.llm.app.auth.api.AuthenticatedUser;
import com.llm.app.auth.api.IdentityAccess;
import com.llm.app.auth.api.InvalidCredentialsException;

import org.springframework.stereotype.Service;

@Service
class IdentityAccessService implements IdentityAccess {

    private final AdminRepository adminRepository;

    IdentityAccessService(AdminRepository adminRepository) {
        this.adminRepository = adminRepository;
    }

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
