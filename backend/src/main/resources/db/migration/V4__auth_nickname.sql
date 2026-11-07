-- 닉네임 변경 30일 제한. NULL = 변경 이력 없음(가입 시 최초 설정은 이력이 아님)
ALTER TABLE users ADD COLUMN nickname_changed_at TIMESTAMPTZ;

-- 닉네임 중복은 대소문자 무시. 탈퇴 회원의 NULL 은 유니크 대상이 아니다
CREATE UNIQUE INDEX users_lower_nickname_key ON users (lower(nickname));
