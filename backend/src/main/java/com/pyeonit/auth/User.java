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
@Table(name = "users")
public class User {

	public enum Role { USER, ADMIN }

	public enum Status { ACTIVE, SUSPENDED, WITHDRAWN }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	String nickname;

	@Enumerated(EnumType.STRING)
	Role role = Role.USER;

	@Enumerated(EnumType.STRING)
	Status status = Status.ACTIVE;

	OffsetDateTime createdAt = OffsetDateTime.now();

	OffsetDateTime withdrawnAt;

	OffsetDateTime nicknameChangedAt;

	protected User() {
	}

	User(String nickname, Role role) {
		this.nickname = nickname;
		this.role = role;
	}

	public Long getId() {
		return id;
	}

	public Status getStatus() {
		return status;
	}

}
