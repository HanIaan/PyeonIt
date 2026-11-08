package com.pyeonit.auth;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pyeonit.config.ApiException;
import com.pyeonit.admin.community.SanctionBlocklist;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 회원 흐름. 세션 principal 이름: 회원은 users.id 숫자 문자열, 가입 대기는 pending:&lt;provider&gt;:&lt;providerUserId&gt;
 * (provider 는 소문자 registrationId). api/README.md (a)절.
 */
@Service
class AuthService {

	static final Duration NICKNAME_CHANGE_INTERVAL = Duration.ofDays(30);

	private static final String PENDING = "pending:";

	private final UserRepository users;

	private final SocialAccountRepository socials;

	private final JdbcTemplate jdbc;

	private final List<String> forbiddenWords;

	private final SanctionBlocklist blocklist;

	private final WithdrawalCleanup withdrawalCleanup;

	AuthService(UserRepository users, SocialAccountRepository socials, JdbcTemplate jdbc,
			@Value("${pyeonit.nickname.forbidden-words}") List<String> forbiddenWords, SanctionBlocklist blocklist,
			WithdrawalCleanup withdrawalCleanup) {
		this.withdrawalCleanup = withdrawalCleanup;
		this.blocklist = blocklist;
		this.users = users;
		this.socials = socials;
		this.jdbc = jdbc;
		this.forbiddenWords = forbiddenWords.stream().map(String::trim).filter(w -> !w.isEmpty())
				.map(w -> w.toLowerCase(Locale.ROOT)).toList();
	}

	record SocialAccountView(SocialAccount.Provider provider, OffsetDateTime linkedAt) {
	}

	record Me(Long id, String nickname, OffsetDateTime nicknameChangeableAt, User.Role role, User.Status status,
			OffsetDateTime suspendedUntil, List<SocialAccountView> socialAccounts, OffsetDateTime createdAt) {
	}

	/** 닉네임 변경 30일 제한. 컨트롤러가 429 + Retry-After + nicknameChangeableAt 으로 바꾼다. */
	static class NicknameChangeTooSoon extends RuntimeException {

		final OffsetDateTime changeableAt;

		NicknameChangeTooSoon(OffsetDateTime changeableAt) {
			this.changeableAt = changeableAt;
		}

	}

	// ===== 세션 =====

	static Authentication pendingAuthentication(String registrationId, String providerUserId) {
		return UsernamePasswordAuthenticationToken.authenticated(PENDING + registrationId + ":" + providerUserId, null,
				List.of());
	}

	static Authentication memberAuthentication(User user) {
		return UsernamePasswordAuthenticationToken.authenticated(String.valueOf(user.id), null,
				List.of(new SimpleGrantedAuthority("ROLE_" + user.role)));
	}

	private static final HttpSessionSecurityContextRepository SESSIONS = new HttpSessionSecurityContextRepository();

	/** 인증을 현재 요청과 세션에 저장한다. */
	static void startSession(Authentication auth, HttpServletRequest req, HttpServletResponse res) {
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(auth);
		SecurityContextHolder.setContext(context);
		SESSIONS.saveContext(context, req, res);
	}

	/** users.id 가 필요한 API 에서 가입 대기 세션은 403 SIGNUP_REQUIRED. */
	static long memberId(Authentication auth) {
		try {
			return Long.parseLong(auth.getName());
		}
		catch (NumberFormatException e) {
			throw new ApiException(HttpStatus.FORBIDDEN, "SIGNUP_REQUIRED", "닉네임을 설정해 가입을 완료해 주세요");
		}
	}

	/** 탈퇴했거나 없어진 회원의 남은 세션은 로그인 안 된 것으로 본다. */
	User member(Authentication auth) {
		User user = users.findById(memberId(auth)).orElse(null);
		if (user == null || user.status == User.Status.WITHDRAWN) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다");
		}
		return user;
	}

	/** OAuth2 로그인 성공 후 세션에 넣을 인증. 연결된 소셜 계정이 있으면 회원, 없으면 가입 대기. */
	@Transactional(readOnly = true)
	Authentication login(String registrationId, String providerUserId) {
		return socials.findByProviderAndProviderUserId(provider(registrationId), providerUserId)
				.flatMap(s -> users.findById(s.userId))
				.filter(u -> u.status != User.Status.WITHDRAWN)
				.map(AuthService::memberAuthentication)
				.orElseGet(() -> pendingAuthentication(registrationId, providerUserId));
	}

	// ===== 가입·내 정보 =====

	@Transactional
	User signup(Authentication auth, String nickname) {
		String name = auth.getName();
		if (!name.startsWith(PENDING)) {
			throw alreadyRegistered();
		}
		String[] parts = name.split(":", 3);
		SocialAccount.Provider provider = provider(parts[1]);
		if (socials.findByProviderAndProviderUserId(provider, parts[2]).isPresent()) {
			throw alreadyRegistered();
		}
		checkNickname(nickname, null);
		try {
			if (blocklist.isBlocked(provider.name(), parts[2])) {
				throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED", "가입할 수 없는 계정입니다");
			}
			User user = users.saveAndFlush(new User(nickname, User.Role.USER));
			socials.saveAndFlush(new SocialAccount(user.id, provider, parts[2]));
			return user;
		}
		catch (DataIntegrityViolationException e) {
			// 동시에 같은 닉네임·같은 소셜 계정으로 가입한 경우
			throw nicknameTaken();
		}
	}

	@Transactional(readOnly = true)
	Me me(User user) {
		OffsetDateTime changeableAt = user.nicknameChangedAt == null ? null
				: user.nicknameChangedAt.plus(NICKNAME_CHANGE_INTERVAL);
		List<SocialAccountView> accounts = socials.findByUserIdOrderById(user.id).stream()
				.map(s -> new SocialAccountView(s.provider, s.createdAt))
				.toList();
		return new Me(user.id, user.nickname, changeableAt, user.role, user.status, suspendedUntil(user), accounts,
				user.createdAt);
	}

	// shortcut: sanctions 는 B4 소유라 읽기만 한다. 해제 컬럼이 생기면 조건에 추가
	private OffsetDateTime suspendedUntil(User user) {
		if (user.status != User.Status.SUSPENDED) {
			return null;
		}
		List<OffsetDateTime> ends = jdbc.query("""
				SELECT ends_at FROM sanctions
				WHERE user_id = ? AND type IN ('SUSPEND', 'BAN') AND starts_at <= now()
				  AND (ends_at IS NULL OR ends_at > now())
				ORDER BY ends_at DESC NULLS FIRST LIMIT 1""",
				(rs, i) -> rs.getObject(1, OffsetDateTime.class), user.id);
		return ends.isEmpty() ? null : ends.getFirst();
	}

	@Transactional
	User changeNickname(Authentication auth, String nickname) {
		User user = member(auth);
		if (nickname.equals(user.nickname)) {
			return user;
		}
		OffsetDateTime now = OffsetDateTime.now();
		if (user.nicknameChangedAt != null && now.isBefore(user.nicknameChangedAt.plus(NICKNAME_CHANGE_INTERVAL))) {
			throw new NicknameChangeTooSoon(user.nicknameChangedAt.plus(NICKNAME_CHANGE_INTERVAL));
		}
		checkNickname(nickname, user.id);
		user.nickname = nickname;
		user.nicknameChangedAt = now;
		try {
			return users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException e) {
			throw nicknameTaken();
		}
	}

	/** 형식(^[가-힣a-zA-Z0-9]{2,12}$)은 요청 검증에서 끝났다. 금지어 422, 중복(대소문자 무시) 409. */
	private void checkNickname(String nickname, Long selfId) {
		String lower = nickname.toLowerCase(Locale.ROOT);
		if (forbiddenWords.stream().anyMatch(lower::contains)) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NICKNAME_FORBIDDEN", "사용할 수 없는 단어가 포함된 닉네임입니다");
		}
		if (users.findByNicknameIgnoreCase(nickname).filter(u -> !u.id.equals(selfId)).isPresent()) {
			throw nicknameTaken();
		}
	}

	/** LAST_SOCIAL_ACCOUNT 와 무관하게 소셜 계정 전부 삭제. 글·댓글은 users 행이 남으므로 유지된다. */
	@Transactional
	void withdraw(User user) {
		blocklist.recordOnWithdraw(user.id);
		socials.deleteByUserId(user.id);
		withdrawalCleanup.deletePersonalSettings(user.id);
		user.nickname = null;
		user.status = User.Status.WITHDRAWN;
		user.withdrawnAt = OffsetDateTime.now();
		users.save(user);
	}

	// ===== 소셜 계정 =====

	/** 연결 결과. 실패면 웹 linkError 코드, 성공이면 null. */
	@Transactional
	String link(long userId, String registrationId, String providerUserId) {
		SocialAccount.Provider provider = provider(registrationId);
		var existing = socials.findByProviderAndProviderUserId(provider, providerUserId);
		if (existing.isPresent()) {
			return existing.get().userId.equals(userId) ? null : "SOCIAL_ACCOUNT_IN_USE";
		}
		if (hasProvider(userId, provider)) {
			return "PROVIDER_ALREADY_LINKED";
		}
		try {
			socials.saveAndFlush(new SocialAccount(userId, provider, providerUserId));
		}
		catch (DataIntegrityViolationException e) {
			return "SOCIAL_ACCOUNT_IN_USE";
		}
		return null;
	}

	boolean hasProvider(long userId, SocialAccount.Provider provider) {
		return socials.findByUserIdOrderById(userId).stream().anyMatch(s -> s.provider == provider);
	}

	@Transactional
	void unlink(User user, String registrationId) {
		SocialAccount.Provider provider = provider(registrationId);
		List<SocialAccount> accounts = socials.findByUserIdOrderById(user.id);
		SocialAccount target = accounts.stream().filter(s -> s.provider == provider).findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "연결된 계정이 없습니다"));
		if (accounts.size() == 1) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "LAST_SOCIAL_ACCOUNT",
					"마지막 로그인 수단은 해제할 수 없습니다");
		}
		socials.delete(target);
	}

	/** 경로·principal 의 registrationId(kakao, google) → DB provider. 그 외는 404. */
	static SocialAccount.Provider provider(String registrationId) {
		return switch (registrationId) {
			case "kakao" -> SocialAccount.Provider.KAKAO;
			case "google" -> SocialAccount.Provider.GOOGLE;
			default -> throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "지원하지 않는 로그인 제공자입니다");
		};
	}

	// ===== dev =====

	/** dev 테스트 로그인: 닉네임으로 찾고 없으면 만든다. 역할·정지 여부는 요청대로 덮어쓴다. 닉네임 규칙은 검사하지 않는다. */
	@Transactional
	User devUser(String nickname, User.Role role, boolean suspended) {
		User user = users.findByNicknameIgnoreCase(nickname).orElseGet(() -> new User(nickname, role));
		user.role = role;
		user.status = suspended ? User.Status.SUSPENDED : User.Status.ACTIVE;
		return users.saveAndFlush(user);
	}

	private static ApiException alreadyRegistered() {
		return new ApiException(HttpStatus.CONFLICT, "ALREADY_REGISTERED", "이미 가입한 회원입니다");
	}

	private static ApiException nicknameTaken() {
		return new ApiException(HttpStatus.CONFLICT, "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다");
	}

}
