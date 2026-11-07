-- PyeonIt v0 스키마 (PostgreSQL 15+)

-- ===== 회원 =====
CREATE TABLE users (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nickname     VARCHAR(20) UNIQUE,
    role         VARCHAR(10) NOT NULL DEFAULT 'USER'   CHECK (role IN ('USER', 'ADMIN')),
    status       VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'WITHDRAWN')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    withdrawn_at TIMESTAMPTZ,
    -- 탈퇴 시 닉네임을 비워 개인정보 제거 + 닉네임 재사용 허용
    CHECK (status = 'WITHDRAWN' OR nickname IS NOT NULL)
);

-- 한 회원이 여러 소셜 계정 연결. 탈퇴 시 행 삭제
CREATE TABLE social_accounts (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider         VARCHAR(10) NOT NULL CHECK (provider IN ('KAKAO', 'GOOGLE', 'APPLE')),
    provider_user_id VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_user_id)
);
CREATE INDEX ON social_accounts (user_id);

-- ===== 학교·공고 =====
CREATE TABLE universities (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(50) NOT NULL,
    campus        VARCHAR(30) NOT NULL DEFAULT '',
    region        VARCHAR(10) NOT NULL CHECK (region IN ('SEOUL', 'GYEONGGI', 'INCHEON')),
    admission_url VARCHAR(500),
    UNIQUE (name, campus)
);

CREATE TABLE favorite_universities (
    user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    university_id BIGINT NOT NULL REFERENCES universities(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, university_id)
);

CREATE TABLE crawl_sources (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    university_id        BIGINT NOT NULL REFERENCES universities(id),
    kind                 VARCHAR(20) NOT NULL CHECK (kind IN ('NOTICE_BOARD', 'GUIDE_PAGE')),
    url                  VARCHAR(500) NOT NULL UNIQUE,
    enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    last_checked_at      TIMESTAMPTZ,
    last_success_at      TIMESTAMPTZ,
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_error           TEXT
);

-- 감지된 모집요강 파일. 같은 학년도 수정본은 새 행, 최신 APPROVED가 게시본
CREATE TABLE admission_documents (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    university_id BIGINT NOT NULL REFERENCES universities(id),
    source_id     BIGINT REFERENCES crawl_sources(id),  -- NULL = 운영자 수동 업로드
    academic_year SMALLINT NOT NULL,
    title         VARCHAR(300) NOT NULL,
    page_url      VARCHAR(1000),
    file_url      VARCHAR(1000),
    file_sha256   CHAR(64) NOT NULL UNIQUE,              -- 같은 파일 재수집 방지
    storage_key   VARCHAR(500) NOT NULL,                 -- R2 원본 보관 위치
    status        VARCHAR(15) NOT NULL DEFAULT 'DETECTED'
                  CHECK (status IN ('DETECTED', 'EXTRACTED', 'NEEDS_REVIEW', 'APPROVED', 'REJECTED', 'FAILED')),
    detected_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by   BIGINT REFERENCES users(id),
    reviewed_at   TIMESTAMPTZ
);
CREATE INDEX ON admission_documents (university_id, academic_year, status);

-- AI 추출 이력 (재시도·모델 교체 비교용)
CREATE TABLE extraction_runs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES admission_documents(id) ON DELETE CASCADE,
    model       VARCHAR(100) NOT NULL,
    result      JSONB,
    validation  JSONB,   -- 합계 검증 결과
    error       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON extraction_runs (document_id);

-- 모집단위: 단과대(COLLEGE) → 학과(MAJOR) 계층
CREATE TABLE departments (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    university_id          BIGINT NOT NULL REFERENCES universities(id),
    parent_id              BIGINT REFERENCES departments(id),
    level                  VARCHAR(10) NOT NULL CHECK (level IN ('COLLEGE', 'MAJOR')),
    name                   VARCHAR(100) NOT NULL,
    previous_department_id BIGINT REFERENCES departments(id),  -- 학과명 변경 시 관리자가 전년도 학과와 연결
    UNIQUE (university_id, level, name)
);

-- 표준 전형
CREATE TABLE tracks (
    id   SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(30) NOT NULL
);

-- 학교별 원문 전형명 → 표준 전형. university_id NULL = 모든 학교 공통
CREATE TABLE track_aliases (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    university_id BIGINT REFERENCES universities(id),
    raw_name      VARCHAR(100) NOT NULL,
    track_id      SMALLINT NOT NULL REFERENCES tracks(id),
    UNIQUE NULLS NOT DISTINCT (university_id, raw_name)
);

-- TO. "1명 이내"·"약간명"은 RANGE(count NULL), "미선발"은 NOT_RECRUITING(count 0)
CREATE TABLE quotas (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id    BIGINT NOT NULL REFERENCES admission_documents(id) ON DELETE CASCADE,
    department_id  BIGINT NOT NULL REFERENCES departments(id),
    track_id       SMALLINT REFERENCES tracks(id),  -- NULL = 매핑 안 된 전형, 검수 필요
    raw_track_name VARCHAR(100) NOT NULL,           -- 같은 표준 전형이 여러 열일 수 있음 (성균관 일반 유형1/2)
    kind           VARCHAR(15) NOT NULL CHECK (kind IN ('EXACT', 'RANGE', 'NOT_RECRUITING')),
    count          INT CHECK (count >= 0),
    raw_text       VARCHAR(50) NOT NULL,
    UNIQUE (document_id, department_id, raw_track_name),
    CHECK ((kind = 'EXACT' AND count IS NOT NULL)
        OR (kind = 'RANGE' AND count IS NULL)
        OR (kind = 'NOT_RECRUITING' AND count = 0))
);
CREATE INDEX ON quotas (department_id);

CREATE TABLE schedules (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES admission_documents(id) ON DELETE CASCADE,
    type        VARCHAR(20) NOT NULL CHECK (type IN ('APPLICATION', 'DOCUMENT_SUBMISSION', 'EXAM', 'INTERVIEW',
                    'RESULT_FIRST_STAGE', 'RESULT_FINAL', 'REGISTRATION', 'ADDITIONAL', 'OTHER')),
    raw_label   VARCHAR(300) NOT NULL,
    starts_at   TIMESTAMPTZ NOT NULL,
    ends_at     TIMESTAMPTZ,
    all_day     BOOLEAN NOT NULL DEFAULT FALSE,  -- 시각 없이 날짜만 공지된 경우
    note        TEXT,
    CHECK (ends_at IS NULL OR ends_at >= starts_at)
);
CREATE INDEX ON schedules (document_id);
CREATE INDEX ON schedules (starts_at);

-- ===== 커뮤니티 =====
CREATE TABLE boards (
    id         SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(20) NOT NULL UNIQUE,
    name       VARCHAR(30) NOT NULL,
    sort_order SMALLINT NOT NULL DEFAULT 0
);

-- 삭제는 status = 'DELETED' (신고·분쟁 대응용으로 보관)
CREATE TABLE posts (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    board_id      SMALLINT NOT NULL REFERENCES boards(id),
    author_id     BIGINT NOT NULL REFERENCES users(id),
    title         VARCHAR(200) NOT NULL,
    body          TEXT NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED', 'HIDDEN', 'DELETED')),
    view_count    INT NOT NULL DEFAULT 0,
    like_count    INT NOT NULL DEFAULT 0,
    comment_count INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON posts (board_id, created_at DESC) WHERE status = 'PUBLISHED';
CREATE INDEX ON posts (author_id);

-- 학교 태그 (한 글에 여러 학교 가능: "A vs B" 비교글)
CREATE TABLE post_universities (
    post_id       BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    university_id BIGINT NOT NULL REFERENCES universities(id),
    PRIMARY KEY (post_id, university_id)
);
CREATE INDEX ON post_universities (university_id, post_id);

-- 업로드 후 글 작성 전까지 post_id NULL. 오래된 NULL 행은 정리 작업으로 삭제
CREATE TABLE post_images (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    post_id      BIGINT REFERENCES posts(id) ON DELETE CASCADE,
    uploader_id  BIGINT NOT NULL REFERENCES users(id),
    storage_key  VARCHAR(500) NOT NULL UNIQUE,
    content_type VARCHAR(50) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp', 'image/gif')),
    size_bytes   INT NOT NULL CHECK (size_bytes > 0),
    sort_order   SMALLINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON post_images (post_id);

-- 답글은 1단계만: parent는 최상위 댓글이어야 함 (앱에서 검사)
CREATE TABLE comments (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    post_id    BIGINT NOT NULL REFERENCES posts(id),
    parent_id  BIGINT REFERENCES comments(id),
    author_id  BIGINT NOT NULL REFERENCES users(id),
    body       TEXT NOT NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED', 'HIDDEN', 'DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON comments (post_id, created_at);

CREATE TABLE post_likes (
    post_id    BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

CREATE TABLE reports (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reporter_id BIGINT NOT NULL REFERENCES users(id),
    target_type VARCHAR(10) NOT NULL CHECK (target_type IN ('POST', 'COMMENT', 'USER')),
    target_id   BIGINT NOT NULL,
    reason      VARCHAR(15) NOT NULL CHECK (reason IN ('SPAM', 'ABUSE', 'ADVERTISEMENT', 'FALSE_INFO', 'OTHER')),
    detail      VARCHAR(500),
    status      VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RESOLVED', 'DISMISSED')),
    handled_by  BIGINT REFERENCES users(id),
    handled_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (reporter_id, target_type, target_id)
);
CREATE INDEX ON reports (status, created_at);

CREATE TABLE sanctions (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id),
    type       VARCHAR(10) NOT NULL CHECK (type IN ('WARNING', 'SUSPEND', 'BAN')),
    reason     VARCHAR(500) NOT NULL,
    starts_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ends_at    TIMESTAMPTZ,  -- NULL = 무기한
    created_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON sanctions (user_id);

-- ===== 기본 데이터 =====
INSERT INTO tracks (code, name) VALUES
    ('GENERAL', '일반편입'),
    ('BACHELOR', '학사편입'),
    ('RURAL', '농어촌'),
    ('LOW_INCOME', '저소득층'),
    ('MILITARY', '군위탁'),
    ('DISABLED', '특수교육대상자');

INSERT INTO track_aliases (raw_name, track_id)
SELECT a.raw_name, t.id FROM (VALUES
    ('일반', 'GENERAL'), ('일반편입', 'GENERAL'), ('일반편입학', 'GENERAL'),
    ('학사', 'BACHELOR'), ('학사편입', 'BACHELOR'), ('학사편입학', 'BACHELOR'),
    ('농어촌학생', 'RURAL'), ('저소득층', 'LOW_INCOME'),
    ('군위탁', 'MILITARY'), ('군위탁편입학', 'MILITARY')
) AS a(raw_name, code) JOIN tracks t ON t.code = a.code;

INSERT INTO boards (code, name, sort_order) VALUES
    ('QUESTION', '질문', 1),
    ('FREE', '자유', 2),
    ('INFO', '정보', 3);
