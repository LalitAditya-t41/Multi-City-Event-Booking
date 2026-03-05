package com.eventplatform.identity.repository;

import com.eventplatform.identity.domain.PasswordResetToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

  Optional<PasswordResetToken> findByTokenHash(String tokenHash);

  Optional<PasswordResetToken> findTopByUserIdOrderByCreatedAtDesc(Long userId);

  @Modifying
  @Query(
      """
        update PasswordResetToken prt
        set prt.consumedAt = :consumedAt
        where prt.user.id = :userId
          and prt.consumedAt is null
        """)
  int invalidateActiveByUserId(
      @Param("userId") Long userId, @Param("consumedAt") Instant consumedAt);
}
