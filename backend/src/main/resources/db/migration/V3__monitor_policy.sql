-- 감시 정책: Gemini 재시도·할당량 대기, PDF 없는/실패한 공지 재확인, 요강 페이지 검토, 운영자 알림

-- DETECTED = 추출 대기(한 주기의 확인이 끝난 뒤 모아서 추출).
-- RETRY_PENDING = Gemini 일시 오류(503·타임아웃). 다음 주기 재분석, 5주기 연속 실패 시 FAILED.
-- QUOTA_WAIT = Gemini 429. retry_after 이후 재개, retry_count에 포함하지 않음
ALTER TABLE admission_documents DROP CONSTRAINT admission_documents_status_check;
ALTER TABLE admission_documents ADD CONSTRAINT admission_documents_status_check
    CHECK (status IN ('DETECTED', 'RETRY_PENDING', 'QUOTA_WAIT', 'EXTRACTED', 'NEEDS_REVIEW',
                      'APPROVED', 'REJECTED', 'FAILED'));
ALTER TABLE admission_documents
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0,  -- 복구 시 0으로 되돌림
    ADD COLUMN retry_after TIMESTAMPTZ;

-- DONE = 처리 완료(다른 학년도 PDF로 건너뛴 글 포함). AWAITING_PDF = 매칭됐지만 PDF 없음(다음 주기 재확인).
-- RETRY = 게시글 다운로드 실패(failures회). NEEDS_REVIEW = 7일간 PDF 없음 또는 다운로드 3회 실패
ALTER TABLE crawl_seen_items
    ADD COLUMN status VARCHAR(15) NOT NULL DEFAULT 'DONE'
        CHECK (status IN ('DONE', 'AWAITING_PDF', 'RETRY', 'NEEDS_REVIEW')),
    ADD COLUMN failures INT NOT NULL DEFAULT 0,
    ADD COLUMN file_sha256 CHAR(64);  -- 받은 PDF. 같은 파일 반복 다운로드·분석 방지
CREATE INDEX ON crawl_seen_items (file_sha256) WHERE file_sha256 IS NOT NULL;

-- 요강 고정 페이지에서 대상 학년도 PDF 후보가 0개(지난 요강도 없음) 또는 2개 이상일 때의 사유. 정상이면 NULL
ALTER TABLE crawl_sources ADD COLUMN review_reason TEXT;

CREATE TABLE operator_alerts (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind        VARCHAR(30) NOT NULL,
    source_id   BIGINT REFERENCES crawl_sources(id) ON DELETE CASCADE,
    document_id BIGINT REFERENCES admission_documents(id) ON DELETE CASCADE,
    message     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON operator_alerts (kind, created_at DESC);
