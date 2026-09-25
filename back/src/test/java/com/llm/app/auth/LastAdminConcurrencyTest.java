package com.llm.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llm.app.auth.api.UserRole;
import com.llm.app.auth.exception.LastAdminProtectedException;
import com.llm.app.auth.internal.Admin;
import com.llm.app.auth.internal.AdminRepository;
import com.llm.app.auth.internal.UpdateUserRequest;
import com.llm.app.auth.internal.UserManagementService;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 둘이 동시에 서로를 강등·삭제해도 관리자가 0명이 되지 않는지 확인한다.
 * 첫 요청의 트랜잭션을 커밋 직전에 붙잡아 두고, 두 번째 요청이 관리자 행 잠금에서 기다리는지 본다.
 */
@SpringBootTest(properties =
	"spring.datasource.url=jdbc:h2:mem:last-admin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000")
class LastAdminConcurrencyTest {

	@Autowired
	private UserManagementService userManagementService;

	@Autowired
	private AdminRepository adminRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private Long firstAdminId;
	private Long secondAdminId;

	@BeforeEach
	void setUp() {
		adminRepository.deleteAll();
		firstAdminId = saveAdmin("racefirst").getId();
		secondAdminId = saveAdmin("racesecond").getId();
	}

	@Test
	void mutualDemotionShouldLeaveOneAdmin() throws Exception {
		assertSecondRequestWaitsAndIsRejected((requester, target) ->
			userManagementService.updateUser(requester, target, new UpdateUserRequest(null, "USER")));
	}

	@Test
	void mutualDeletionShouldLeaveOneAdmin() throws Exception {
		assertSecondRequestWaitsAndIsRejected(userManagementService::deleteUser);
	}

	private void assertSecondRequestWaitsAndIsRejected(BiConsumer<Long, Long> demoteOrDelete) throws Exception {
		CountDownLatch firstApplied = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				demoteOrDelete.accept(firstAdminId, secondAdminId);
				firstApplied.countDown();
				awaitQuietly(releaseFirst);
			}));
			assertThat(firstApplied.await(10, TimeUnit.SECONDS)).isTrue();

			Future<Throwable> second = executor.submit(() -> {
				try {
					demoteOrDelete.accept(secondAdminId, firstAdminId);
					return null;
				} catch (RuntimeException exception) {
					return exception;
				}
			});
			// 잠금이 없으면 두 번째 요청은 커밋 전 상태(관리자 2명)를 보고 바로 성공한다.
			assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

			releaseFirst.countDown();
			first.get(10, TimeUnit.SECONDS);
			assertThat(second.get(10, TimeUnit.SECONDS)).isInstanceOf(LastAdminProtectedException.class);
		} finally {
			releaseFirst.countDown();
			executor.shutdownNow();
		}
		assertThat(adminRepository.countByRole(UserRole.ADMIN)).isEqualTo(1);
		assertThat(adminRepository.existsByIdAndRole(firstAdminId, UserRole.ADMIN)).isTrue();
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	private Admin saveAdmin(String username) {
		Admin admin = new Admin();
		admin.setUsername(username);
		admin.setPasswordHash(passwordEncoder.encode("racepass"));
		admin.setRole(UserRole.ADMIN);
		admin.setCreatedAt(Instant.now());
		return adminRepository.saveAndFlush(admin);
	}
}
