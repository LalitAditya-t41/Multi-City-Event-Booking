package com.eventplatform.identity.service;

import com.eventplatform.identity.api.dto.response.UserWalletResponse;
import com.eventplatform.identity.mapper.UserWalletMapper;
import com.eventplatform.identity.repository.UserWalletRepository;
import com.eventplatform.shared.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserWalletService {

    private final UserWalletRepository userWalletRepository;
    private final UserWalletMapper userWalletMapper;

    public UserWalletService(UserWalletRepository userWalletRepository, UserWalletMapper userWalletMapper) {
        this.userWalletRepository = userWalletRepository;
        this.userWalletMapper = userWalletMapper;
    }

    @Transactional(readOnly = true)
    public UserWalletResponse getWallet(Long userId) {
        return userWalletRepository.findByUserId(userId)
            .map(userWalletMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found", "WALLET_NOT_FOUND"));
    }
}
