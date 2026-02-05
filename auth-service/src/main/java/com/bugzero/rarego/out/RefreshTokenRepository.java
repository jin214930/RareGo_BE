package com.bugzero.rarego.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bugzero.rarego.domain.RefreshToken;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
	Optional<RefreshToken> findByRefreshToken(String refreshTokenHash);

	long deleteByMemberPublicId(String memberPublicId);
}
