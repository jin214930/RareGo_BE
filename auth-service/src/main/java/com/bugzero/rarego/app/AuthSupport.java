package com.bugzero.rarego.app;

import com.bugzero.rarego.domain.Account;
import com.bugzero.rarego.domain.Provider;
import com.bugzero.rarego.out.AccountRepository;
import com.bugzero.rarego.global.exception.CustomException;
import com.bugzero.rarego.global.response.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthSupport {
    private final AccountRepository accountRepository;

    public boolean existsByProviderAndProviderId(String provider, String providerId) {
        Provider provider1 = Provider.valueOf(provider.toUpperCase());
        return accountRepository.existsByProviderAndProviderId(provider1, providerId);
    }

    public Account findByPublicId(String publicId) {
        return accountRepository.findByMemberPublicId(publicId)
                .orElseThrow(() -> new CustomException(ErrorType.AUTH_ACCOUNT_NOT_FOUND));

    }
}
