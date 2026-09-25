package com.llm.app.auth.internal;

import com.llm.app.auth.api.UserRole;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface AdminRepository extends JpaRepository<Admin, Long> {
    Optional<Admin> findByUsername(String username);
    long countByRole(UserRole role);
    boolean existsByIdAndRole(Long id, UserRole role);

    // id 순서로 잠가 동시에 잠그는 요청끼리 교착되지 않게 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Admin a where a.role = :role order by a.id")
    List<Admin> lockAllByRole(@Param("role") UserRole role);
}
