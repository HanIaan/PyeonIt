package com.pyeonit.auth;

import java.time.OffsetDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "social_accounts")
class SocialAccount {

	enum Provider { KAKAO, GOOGLE, APPLE }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	Long userId;

	@Enumerated(EnumType.STRING)
	Provider provider;

	String providerUserId;

	OffsetDateTime createdAt = OffsetDateTime.now();

	protected SocialAccount() {
	}

	SocialAccount(Long userId, Provider provider, String providerUserId) {
		this.userId = userId;
		this.provider = provider;
		this.providerUserId = providerUserId;
	}

}
