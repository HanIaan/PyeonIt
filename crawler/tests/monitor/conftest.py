"""실제 Postgres(docker compose)의 임시 스키마에 V1·V2 마이그레이션을 적용해 테스트한다."""

import os
import uuid
from pathlib import Path

import psycopg
import pytest
from psycopg.rows import dict_row

MIGRATIONS = (
    Path(__file__).resolve().parents[3] / "backend/src/main/resources/db/migration"
)
DB_URL = os.environ.get(
    "TEST_DATABASE_URL", "postgresql://pyeonit:pyeonit@localhost:5434/pyeonit"
)


@pytest.fixture
def conn():
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
