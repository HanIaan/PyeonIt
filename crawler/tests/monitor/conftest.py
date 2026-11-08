"""실제 Postgres(docker compose)의 임시 스키마에 V1·V2·V4·V7·V8 마이그레이션을 적용해 테스트한다."""

import os
import uuid
from pathlib import Path

import psycopg
import pytest
from psycopg.rows import dict_row

MIGRATIONS = (
    Path(__file__).resolve().parents[3] / "backend/src/main/resources/db/migration"
)
DB_URL = os.environ.get("TEST_DATABASE_URL")


@pytest.fixture
def conn():
    if not DB_URL:
        pytest.skip("TEST_DATABASE_URL에 전용 테스트 DB를 지정해야 합니다")
    try:
        admin = psycopg.connect(DB_URL, autocommit=True)
    except psycopg.OperationalError:
        pytest.skip(
            "Postgres 필요: docker compose up -d postgres (TEST_DATABASE_URL로 변경 가능)"
        )
    schema = f"t_{uuid.uuid4().hex[:12]}"
    admin.execute(f"CREATE SCHEMA {schema}")
    try:
        with psycopg.connect(
            DB_URL,
            autocommit=True,
            row_factory=dict_row,
            options=f"-c search_path={schema}",
        ) as c:
            for sql in sorted(
                MIGRATIONS.glob("V*.sql"), key=lambda p: int(p.name[1:].split("__")[0])
            ):
                c.execute(sql.read_text(encoding="utf-8"))
            yield c
    finally:
        admin.execute(f"DROP SCHEMA {schema} CASCADE")
        admin.close()


def build_pdf(text: str) -> bytes:
    """텍스트 1줄짜리 최소 PDF(Helvetica, ASCII만)."""
    stream = f"BT /F1 12 Tf 10 50 Td ({text}) Tj ET"
    objs = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 100] /Contents 4 0 R "
        "/Resources << /Font << /F1 5 0 R >> >> >>",
        f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out, offsets = b"%PDF-1.4\n", []
    for i, obj in enumerate(objs, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n{obj}\nendobj\n".encode()
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
    out += b"".join(f"{o:010d} 00000 n \n".encode() for o in offsets)
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return out


@pytest.fixture
def make_pdf():
    return build_pdf
