-- dev 프로필에서만 실행하며 기존 데이터를 덮어쓰지 않는다.
INSERT INTO users(nickname) VALUES ('편입새싹'),('합격준비생') ON CONFLICT DO NOTHING;
INSERT INTO universities(name,campus,region) VALUES
 ('서강대학교','','SEOUL'),('성균관대학교','','SEOUL') ON CONFLICT(name,campus) DO NOTHING;

INSERT INTO posts(board_id,author_id,title,body)
SELECT b.id,u.id,v.title,v.body
FROM (VALUES
 ('QUESTION','편입새싹','서강대 편입 영어 준비 질문','기출 풀이와 어휘 공부 비중을 어떻게 잡으시나요?'),
 ('FREE','합격준비생','오늘도 편입 공부 출석','함께 준비하는 분들 모두 힘내요.'),
 ('INFO','합격준비생','서강대와 성균관대 일정 확인','학교별 모집요강과 접수 일정을 꼭 확인하세요.')
) v(code,nickname,title,body)
JOIN boards b ON b.code=v.code JOIN users u ON u.nickname=v.nickname
WHERE NOT EXISTS (SELECT 1 FROM posts p WHERE p.author_id=u.id AND p.title=v.title);

INSERT INTO post_universities(post_id,university_id)
SELECT p.id,u.id FROM posts p JOIN users a ON a.id=p.author_id
JOIN universities u ON u.campus='' AND
 ((p.title='서강대 편입 영어 준비 질문' AND u.name='서강대학교')
 OR (p.title='서강대와 성균관대 일정 확인' AND u.name IN ('서강대학교','성균관대학교')))
WHERE a.nickname IN ('편입새싹','합격준비생')
ON CONFLICT DO NOTHING;

INSERT INTO comments(post_id,author_id,body)
SELECT p.id,u.id,'기출을 풀며 부족한 어휘를 정리해 보세요.'
FROM posts p JOIN users a ON a.id=p.author_id JOIN users u ON u.nickname='합격준비생'
WHERE p.title='서강대 편입 영어 준비 질문' AND a.nickname='편입새싹'
AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.post_id=p.id AND c.author_id=u.id AND c.body='기출을 풀며 부족한 어휘를 정리해 보세요.');

INSERT INTO comments(post_id,parent_id,author_id,body)
SELECT p.id,c.id,u.id,'조언 감사합니다. 오늘부터 실천해 볼게요!'
FROM posts p JOIN comments c ON c.post_id=p.id AND c.parent_id IS NULL
JOIN users u ON u.nickname='편입새싹'
WHERE p.title='서강대 편입 영어 준비 질문' AND p.author_id=u.id
AND c.body='기출을 풀며 부족한 어휘를 정리해 보세요.'
AND NOT EXISTS (SELECT 1 FROM comments r WHERE r.parent_id=c.id AND r.author_id=u.id AND r.body='조언 감사합니다. 오늘부터 실천해 볼게요!');

UPDATE posts p SET comment_count=(SELECT count(*) FROM comments c WHERE c.post_id=p.id AND c.status='PUBLISHED')
WHERE p.author_id IN (SELECT id FROM users WHERE nickname IN ('편입새싹','합격준비생'));
