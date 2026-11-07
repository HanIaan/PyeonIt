"""universities.yaml -> universities·crawl_sources upsert. 값 검증은 DB CHECK 제약이 한다."""

from pathlib import Path

import yaml


def load_seed(conn, path: Path) -> int:
    entries = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or []
    with conn.transaction():
        for entry in entries:
            university_id = conn.execute(
                """INSERT INTO universities (name, campus, region) VALUES (%s, %s, %s)
                   ON CONFLICT (name, campus) DO UPDATE SET region = EXCLUDED.region
                   RETURNING id""",
                (entry["name"], entry.get("campus") or "", entry["region"]),
            ).fetchone()["id"]
            for source in entry.get("sources") or []:
                conn.execute(
                    """INSERT INTO crawl_sources (university_id, kind, url) VALUES (%s, %s, %s)
                       ON CONFLICT (url) DO UPDATE
                       SET university_id = EXCLUDED.university_id, kind = EXCLUDED.kind""",
                    (university_id, source["kind"], source["url"]),
                )
    return len(entries)
