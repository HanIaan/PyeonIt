CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX posts_title_trgm_idx ON posts USING gin (title gin_trgm_ops);
CREATE INDEX posts_body_trgm_idx ON posts USING gin (body gin_trgm_ops);
CREATE INDEX universities_name_trgm_idx ON universities USING gin (name gin_trgm_ops);
CREATE INDEX users_nickname_trgm_idx ON users USING gin (nickname gin_trgm_ops);

CREATE TABLE university_aliases (
    university_name VARCHAR(50) NOT NULL,
    alias VARCHAR(50) NOT NULL,
    UNIQUE (university_name, alias)
);
CREATE INDEX university_aliases_alias_trgm_idx ON university_aliases USING gin (alias gin_trgm_ops);

-- Match all campuses by exact school name, including schools loaded after this migration.
INSERT INTO university_aliases (university_name, alias) VALUES
    ('성균관대학교', '성대'),
    ('한국외국어대학교', '외대'),
    ('연세대학교', '연대'),
    ('고려대학교', '고대'),
    ('서울과학기술대학교', '서울과기대'),
    ('서울과학기술대학교', '과기대'),
    ('한양대학교', '한대'),
    ('서울대학교', '서울대'),
    ('서강대학교', '서강대'),
    ('중앙대학교', '중대'),
    ('경희대학교', '경희대'),
    ('이화여자대학교', '이대'),
    ('건국대학교', '건대'),
    ('동국대학교', '동대'),
    ('홍익대학교', '홍대'),
    ('숙명여자대학교', '숙대'),
    ('국민대학교', '국민대'),
    ('숭실대학교', '숭실대'),
    ('세종대학교', '세종대'),
    ('단국대학교', '단대');
