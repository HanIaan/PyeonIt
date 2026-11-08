"""검증·채택된 로컬 backfill 결과의 멱등 DB 적재."""

import hashlib
import json
import os
from pathlib import Path

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from .monitor.seed import load_seed


def load_artifacts(
    conn, directory: Path, storage_dir: Path, universities: Path
) -> dict:
    from .cli import load_universities, matches_request
    from .validation import validate_totals

    schools = load_universities(universities)
    report = {"loaded": 0, "skipped": 0, "documents": [], "reasons": []}
    for path in sorted(directory.glob("*-2026.json")):
        reason = None
        try:
            artifact = json.loads(path.read_text(encoding="utf-8"))
            source = artifact["source"]
            data = artifact.get("data")
            validation = artifact.get("validation") or {}
            if (
                artifact.get("adopted") is not True
                or validation.get("passed") is not True
            ):
                reason = "NOT_ADOPTED_OR_NOT_PASSED"
            elif source.get("year") != 2026 or not isinstance(data, dict):
                reason = "INVALID_YEAR_OR_DATA"
            else:
                entry = schools.get(path.name.removesuffix("-2026.json"))
                if (
                    entry is None
                    or any(
                        source.get(k) != entry[k] for k in ("name", "campus", "region")
                    )
                    or not matches_request(data, entry["name"], 2026)
                    or data.get("문서종류") != "편입학 모집요강"
                ):
                    reason = "IDENTITY_MISMATCH"
                elif (
                    validation != validate_totals(data)
                    or not validate_totals(data)["passed"]
                ):
                    reason = "VALIDATION_MISMATCH"
                else:
                    pdf = path.with_suffix(".pdf")
                    content = pdf.read_bytes()
                    digest = hashlib.sha256(content).hexdigest()
                    if not content.lstrip().startswith(
                        b"%PDF-"
                    ) or digest != source.get("sha256"):
                        reason = "PDF_HASH_MISMATCH"
                    elif (
                        not source.get("model")
                        or not source.get("page_url")
                        or not source.get("pdf_url")
                    ):
                        reason = "MISSING_PROVENANCE"
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            reason = "INVALID_ARTIFACT_OR_MISSING_PDF"
        if reason:
            report["skipped"] += 1
            report["reasons"].append({"file": path.name, "reason": reason})
            continue
        with conn.transaction():
            university = conn.execute(
                "SELECT id FROM universities WHERE name=%s AND campus=%s",
                (entry["name"], entry["campus"]),
            ).fetchone()
            if university is None:
                load_seed(conn, universities)
                university = conn.execute(
                    "SELECT id FROM universities WHERE name=%s AND campus=%s",
                    (entry["name"], entry["campus"]),
                ).fetchone()
            storage_key = f"{digest}.pdf"
            storage_dir.mkdir(parents=True, exist_ok=True)
            target = storage_dir / storage_key
            if (
                not target.exists()
                or hashlib.sha256(target.read_bytes()).hexdigest() != digest
            ):
                temporary = target.with_suffix(".pdf.tmp")
                temporary.write_bytes(content)
                temporary.replace(target)
            document = conn.execute(
                """INSERT INTO admission_documents
                   (university_id, academic_year, title, page_url, file_url, file_sha256, storage_key, status)
                   VALUES (%s, 2026, %s, %s, %s, %s, %s, 'EXTRACTED')
                   ON CONFLICT (file_sha256) DO NOTHING RETURNING id""",
                (
                    university["id"],
                    f"2026학년도 {entry['name']} 편입학 모집요강",
                    source["page_url"],
                    source["pdf_url"],
                    digest,
                    storage_key,
                ),
            ).fetchone()
            if document is None:
                report["skipped"] += 1
                report["reasons"].append(
                    {"file": path.name, "reason": "DUPLICATE_SHA256"}
                )
                continue
            conn.execute(
                """INSERT INTO extraction_runs (document_id, model, result, validation)
                   VALUES (%s, %s, %s, %s)""",
                (document["id"], source["model"], Jsonb(data), Jsonb(validation)),
            )
            report["loaded"] += 1
            report["documents"].append(
                {"id": document["id"], "school": entry["name"], "sha256": digest}
            )
    return report


def run_load(args) -> int:
    url = args.database_url or os.environ.get("DATABASE_URL")
    if not url:
        print("DATABASE_URL 또는 --database-url이 필요합니다")
        return 2
    try:
        with psycopg.connect(url, autocommit=True, row_factory=dict_row) as conn:
            report = load_artifacts(
                conn, args.input, args.storage_dir, args.universities
            )
    except psycopg.Error:
        print("DB 적재 실패: 연결·스키마·제약 조건을 확인하세요")
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0
