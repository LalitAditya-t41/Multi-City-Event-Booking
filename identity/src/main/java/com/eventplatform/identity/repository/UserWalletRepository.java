package com.eventplatform.identity.repository;

import com.eventplatform.identity.domain.UserWallet;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserWalletRepository extends JpaRepository<UserWallet, Long> {

    Optional<UserWallet> findByUserId(Long userId);
}
