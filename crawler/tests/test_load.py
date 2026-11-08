"""실제 Postgres 임시 스키마에서 적재 경계·멱등성·롤백 검증."""

import hashlib
import json
import os
import uuid
from pathlib import Path

import psycopg
import pytest
from psycopg.rows import dict_row
from test_cli import extraction

from pyeonit_crawler.cli import DEFAULT_UNIVERSITIES, load_universities
from pyeonit_crawler.load import load_artifacts
from pyeonit_crawler.validation import validate_totals


@pytest.fixture
def conn():
    url = os.environ.get(
        "TEST_DATABASE_URL", "postgresql://pyeonit:pyeonit@localhost:5434/pyeonit"
    )
    admin = psycopg.connect(url, autocommit=True)
    schema = f"t8_{uuid.uuid4().hex[:12]}"
    admin.execute(f"CREATE SCHEMA {schema}")
    try:
        with psycopg.connect(
            url,
            autocommit=True,
            row_factory=dict_row,
            options=f"-c search_path={schema}",
        ) as connection:
            migrations = (
                Path(__file__).resolve().parents[2]
                / "backend/src/main/resources/db/migration"
            )
            for name in ("V1__init.sql", "V2__crawl_monitoring.sql"):
                connection.execute((migrations / name).read_text())
            yield connection
    finally:
        admin.execute(f"DROP SCHEMA {schema} CASCADE")
        admin.close()


def artifact(tmp_path, slug="sogang", *, adopted=True, total=3):
    entry = load_universities(DEFAULT_UNIVERSITIES)[slug]
    content = b"%PDF-" + slug.encode()
    (tmp_path / f"{slug}-2026.pdf").write_bytes(content)
    data = extraction(entry["name"], total=total)
    result = {
        "adopted": adopted,
        "data": data,
        "validation": validate_totals(data),
        "source": {
            **entry,
            "year": 2026,
            "sha256": hashlib.sha256(content).hexdigest(),
            "model": "gemini-3.8-flash-actual",
            "page_url": "https://example.test/guide",
            "pdf_url": "https://example.test/guide.pdf",
        },
    }
    path = tmp_path / f"{slug}-2026.json"
    path.write_text(json.dumps(result))
    return path, result


def test_load_passed_adopted_seeds_and_is_idempotent(conn, tmp_path):
    _, result = artifact(tmp_path)
    artifact(tmp_path, "skku", total=4)
    artifact(tmp_path, "yonsei", adopted=False)
    storage = tmp_path / "store"
    first = load_artifacts(conn, tmp_path, storage, DEFAULT_UNIVERSITIES)
    assert (first["loaded"], first["skipped"]) == (1, 2)
    row = conn.execute("SELECT * FROM admission_documents").fetchone()
    assert row["status"] == "EXTRACTED" and row["academic_year"] == 2026
    assert row["storage_key"] == result["source"]["sha256"] + ".pdf"
    assert (storage / row["storage_key"]).read_bytes() == b"%PDF-sogang"
    run = conn.execute("SELECT * FROM extraction_runs").fetchone()
    assert run["model"] == "gemini-3.8-flash-actual" and run["result"] == result["data"]
    assert run["validation"]["passed"] is True
    second = load_artifacts(conn, tmp_path, storage, DEFAULT_UNIVERSITIES)
    assert second["loaded"] == 0 and second["skipped"] == 3
    assert conn.execute("SELECT count(*) n FROM extraction_runs").fetchone()["n"] == 1
    assert conn.execute("SELECT count(*) n FROM universities").fetchone()["n"] == 44


@pytest.mark.parametrize("corruption", ["hash", "validation", "identity", "year"])
def test_rejects_corrupt_provenance(conn, tmp_path, corruption):
    path, result = artifact(tmp_path)
    if corruption == "hash":
        result["source"]["sha256"] = "0" * 64
    elif corruption == "validation":
        result["data"]["총계"][0]["인원"] = 99
    elif corruption == "identity":
        result["source"]["name"] = "다른학교"
    else:
        result["source"]["year"] = 2027
    path.write_text(json.dumps(result))
    report = load_artifacts(conn, tmp_path, tmp_path / "store", DEFAULT_UNIVERSITIES)
    assert report["loaded"] == 0 and report["skipped"] == 1
    assert (
        conn.execute("SELECT count(*) n FROM admission_documents").fetchone()["n"] == 0
    )


def test_document_and_run_roll_back_together(conn, tmp_path):
    path, result = artifact(tmp_path)
    result["source"]["model"] = "x" * 101
    path.write_text(json.dumps(result))
    with pytest.raises(psycopg.errors.StringDataRightTruncation):
        load_artifacts(conn, tmp_path, tmp_path / "store", DEFAULT_UNIVERSITIES)
    assert (
        conn.execute("SELECT count(*) n FROM admission_documents").fetchone()["n"] == 0
    )
