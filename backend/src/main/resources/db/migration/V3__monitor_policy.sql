-- 감시 정책: Gemini 일시 오류 재시도, PDF 없는 매칭 공지 재확인, 운영자 알림

-- RETRY_PENDING = Gemini 일시 오류(429/503/타임아웃). 다음 감시 주기에 재분석, 5주기 연속 실패 시 FAILED
ALTER TABLE admission_documents DROP CONSTRAINT admission_documents_status_check;
ALTER TABLE admission_documents ADD CONSTRAINT admission_documents_status_check
    CHECK (status IN ('DETECTED', 'RETRY_PENDING', 'EXTRACTED', 'NEEDS_REVIEW', 'APPROVED', 'REJECTED', 'FAILED'));
ALTER TABLE admission_documents ADD COLUMN retry_count INT NOT NULL DEFAULT 0;  -- 복구 시 0으로 되돌림

-- DONE = 처리 완료. AWAITING_PDF = 제목은 매칭됐지만 PDF 없음(다음 주기 재확인).
-- NEEDS_REVIEW = first_seen_at부터 7일간 PDF 없음(HWP 전용·JS 뷰어 가능성), 운영자 확인 필요
ALTER TABLE crawl_seen_items ADD COLUMN status VARCHAR(15) NOT NULL DEFAULT 'DONE'
    CHECK (status IN ('DONE', 'AWAITING_PDF', 'NEEDS_REVIEW'));

CREATE TABLE operator_alerts (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind        VARCHAR(30) NOT NULL,
    source_id   BIGINT REFERENCES crawl_sources(id) ON DELETE CASCADE,
    document_id BIGINT REFERENCES admission_documents(id) ON DELETE CASCADE,
    message     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
