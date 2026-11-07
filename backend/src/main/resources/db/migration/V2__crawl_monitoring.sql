-- 모집요강 감시 스케줄러 (crawler/src/pyeonit_crawler/monitor)

-- next_check_at <= now() 인 소스만 확인. content_hash = 요강 고정 페이지에서 마지막으로 본 PDF sha256
ALTER TABLE crawl_sources
    ADD COLUMN next_check_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN content_hash  CHAR(64);
CREATE INDEX ON crawl_sources (next_check_at) WHERE enabled;

-- 공지 게시판에서 이미 본 게시글. 신규 판정은 (source_id, item_key) 존재 여부
CREATE TABLE crawl_seen_items (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id     BIGINT NOT NULL REFERENCES crawl_sources(id) ON DELETE CASCADE,
    item_key      TEXT NOT NULL,
    url           TEXT NOT NULL,
    title         TEXT NOT NULL,
    matched       BOOLEAN NOT NULL,  -- 제목 필터(편입·모집·요강) 통과 여부
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_id, item_key)
);

-- 소스 확인 1회 = 1행. attempt = 연속 실패 횟수 + 1
CREATE TABLE crawl_runs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id   BIGINT NOT NULL REFERENCES crawl_sources(id) ON DELETE CASCADE,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    status      VARCHAR(10) NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    attempt     INT NOT NULL CHECK (attempt >= 1),
    error       TEXT
);
CREATE INDEX ON crawl_runs (source_id, started_at DESC);
