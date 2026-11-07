package com.pyeonit.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

	Optional<SocialAccount> findByProviderAndProviderUserId(SocialAccount.Provider provider, String providerUserId);

	List<SocialAccount> findByUserIdOrderById(Long userId);

	void deleteByUserId(Long userId);

}
