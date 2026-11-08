import { test, expect, type Page, type BrowserContext } from '@playwright/test';
import { writeFile } from 'node:fs/promises';
import type { components } from '../lib/api-types';

type S = components['schemas'];
const apiURL = process.env.E2E_API_URL ?? 'http://localhost:8081';
const unique = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 4);
const nick = () => `q${unique()}`.slice(0, 12);
async function request(context: BrowserContext, path: string, method = 'GET', data?: unknown) {
  const headers: Record<string, string> = {};
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const csrf = await context.request.get(`${apiURL}/api/csrf`);
    expect(csrf.ok()).toBeTruthy();
    headers['X-XSRF-TOKEN'] = (await csrf.json()).token;
  }
  return context.request.fetch(`${apiURL}${path}`, { method, data, headers });
}
async function api<T>(context: BrowserContext, path: string, method = 'GET', data?: unknown): Promise<T> {
  const response = await request(context, path, method, data);
  expect(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
  return response.status() === 204 ? undefined as T : response.json();
}
async function login(page: Page, role: 'USER' | 'ADMIN' = 'USER', nickname = nick()) {
  await page.goto('/login');
  await page.getByLabel('닉네임', { exact: true }).fill(nickname);
  await page.getByRole('combobox', { name: '권한', exact: true }).selectOption(role);
  const response = page.waitForResponse(r => r.url() === `${apiURL}/api/dev/login` && r.request().method() === 'POST');
  await page.getByRole('button', { name: '테스트 로그인', exact: true }).click();
  const result = await response;
  expect(result.ok(), 'dev 로그인은 실제 API를 호출해야 한다').toBeTruthy();
  await expect(page).not.toHaveURL(/\/login/);
  return await result.json() as S['Me'];
}

test('Q1-1 dev 로그인과 닉네임 변경·30일 제한', async ({ page, context }) => {
  await login(page);
  await page.goto('/me');
  await expect(page.getByText('닉네임은 30일에 한 번 변경할 수 있습니다.', { exact: true })).toBeVisible();
  const changed = nick();
  await page.getByRole('textbox').fill(changed);
  await page.getByRole('button', { name: '닉네임 변경', exact: true }).click();
  await expect(page.getByText('닉네임을 변경했습니다.', { exact: true })).toBeVisible();
  await expect(page.getByRole('textbox')).toBeDisabled();
  await expect(page.getByText(/^변경 가능:/)).toBeVisible();
  const response = await request(context, '/api/me/nickname', 'PATCH', { nickname: nick() });
  expect(response.status()).toBe(429);
  expect((await response.json()).code).toBe('NICKNAME_CHANGE_TOO_SOON');
  await page.reload();
  await expect(page.getByRole('textbox')).toBeDisabled();
});

test('Q1-2 공고 필터·학교 TO 계층·RANGE·미선발·캘린더', async ({ page, context }) => {
  const notices = await api<S['NoticePage']>(context, '/api/notices?size=100');
  test.skip(!notices.content.length, '검증 불가: 게시된 공고 없음 (기존 데이터 개수는 단정하지 않음)');
  const notice = notices.content[0];
  await page.goto('/notices');
  await expect(page.getByRole('heading', { name: '모집 공고', exact: true })).toBeVisible();
  await page.getByRole('combobox', { name: '학년도', exact: true }).selectOption(String(notice.academicYear));
  await page.getByRole('combobox', { name: '지역', exact: true }).selectOption(notice.university.region);
  await page.getByRole('combobox', { name: '페이지당 공고', exact: true }).selectOption('1');
  await expect(page).toHaveURL(/size=1/);
  await expect(page.getByRole('link', { name: notice.title, exact: true })).toBeVisible();
  const schools = await api<S['University'][]>(context, '/api/universities');
  let rangeCount = 0, notRecruitingCount = 0;
  for (const school of schools) {
    const detail = await api<S['UniversityDetail']>(context, `/api/universities/${school.id}?academicYear=${notice.academicYear}`);
    if (!detail.guide) continue;
    await page.goto(`/universities/${school.id}?academicYear=${notice.academicYear}`);
    const table = page.getByRole('table');
    await expect(table).toBeVisible();
    async function check(nodes: S['DepartmentNode'][], depth = 0) {
      for (const node of nodes) {
        const row = table.getByRole('row').filter({ has: page.getByRole('rowheader', { name: node.name, exact: true }) });
        await expect(row).toBeVisible();
        await expect(row.getByRole('rowheader')).toHaveCSS('padding-left', `${12 + depth * 20}px`);
        for (const quota of node.quotas) {
          if (quota.kind === 'RANGE') {
            rangeCount++;
            expect(quota.count).toBeNull();
            await test.step(`Q1b RANGE 원문 표시: ${school.name} ${node.name}`, async () => {
              await expect(row.getByRole('cell', { name: quota.rawText, exact: true })).toBeVisible();
            });
          }
          if (quota.kind === 'NOT_RECRUITING') {
            notRecruitingCount++;
            await expect(row.getByRole('cell', { name: '미선발', exact: true }).first()).toBeVisible();
          }
        }
        await check(node.children, depth + 1);
      }
    }
    await check(detail.guide.departments);
  }
  await test.step('Q1b RANGE 사례를 실제로 검증했는지 확인', async () => {
    expect(rangeCount, 'RANGE 시드가 적용된 전용 백엔드에서 실행해야 한다').toBeGreaterThan(0);
  });
  test.info().annotations.push({ type: 'coverage', description: `RANGE ${rangeCount}, NOT_RECRUITING ${notRecruitingCount}` });
  const detail = await api<S['UniversityDetail']>(context, `/api/universities/${notice.university.id}?academicYear=${notice.academicYear}`);
  const schedule = detail.guide?.schedules[0];
  if (!schedule) { test.info().annotations.push({ type: 'coverage', description: '일정 표시 검증 불가: 실제 일정 없음' }); return; }
  const day = new Date(schedule.startsAt).toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' });
  const month = day.slice(0, 7);
  await page.goto(`/calendar?month=${month}&universityId=${notice.university.id}`);
  await page.getByRole('button', { name: '다음 달', exact: true }).click();
  await expect(page).not.toHaveURL(new RegExp(`month=${month}`));
  await page.getByRole('button', { name: '이전 달', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`month=${month}`));
  await page.getByRole('button', { name: new RegExp(`^${Number(day.slice(5, 7))}월 ${Number(day.slice(8))}일, 일정 [1-9]`) }).click();
  await expect(page.getByRole('link', { name: `${notice.university.name} · ${schedule.rawLabel}`, exact: true })).toBeVisible();
});

test('Q1-3 관심학교 추가·해제 후 새로고침 유지', async ({ page }) => {
  await login(page);
  await page.goto('/universities');
  const toggle = page.getByRole('button', { name: '관심학교 추가', exact: true }).first();
  await toggle.click();
  await expect(page.getByRole('button', { name: '관심학교 해제', exact: true })).toHaveCount(1);
  await page.reload();
  await page.getByRole('button', { name: '관심학교 해제', exact: true }).click();
  await expect(page.getByRole('button', { name: '관심학교 해제', exact: true })).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole('button', { name: '관심학교 해제', exact: true })).toHaveCount(0);
});

test('Q1-4 커뮤니티 → 신고 → 관리자 처리·정지 → 탈퇴', async ({ page, context, browser }, testInfo) => {
  const author = await login(page);
  const reporterContext = await browser.newContext({ baseURL: testInfo.project.use.baseURL });
  const adminContext = await browser.newContext({ baseURL: testInfo.project.use.baseURL });
  const reporter = await reporterContext.newPage(), admin = await adminContext.newPage();
  const failures: string[] = [];
  // Keep later independent steps running; preserve every error and fail the test at the end.
  async function step(name: string, work: () => Promise<void>) {
    try { await test.step(name, work); }
    catch (error) {
      failures.push(`${name}: ${String(error)}`);
      await testInfo.attach(`failure-${failures.length}`, { body: String(error), contentType: 'text/plain' });
      await testInfo.attach(`failure-${failures.length}-screen`, { body: await page.screenshot(), contentType: 'image/png' });
    }
  }
  let postId = 0;
  const title = `Q1 통합 ${unique()}`;
  try {
    await login(reporter);
    await login(admin, 'ADMIN');
    const schools = (await api<S['University'][]>(context, '/api/universities')).slice(0, 2);
    expect(schools, '학교 태그 2개 검증을 위한 환경 데이터').toHaveLength(2);
    await step('글쓰기: 생성한 PNG와 학교 태그 2개', async () => {
      await page.goto('/community/write');
      await page.getByLabel('제목', { exact: true }).fill(title);
      await page.getByLabel('본문', { exact: true }).fill(`Q1 자동 검증 ${unique()}`);
      for (const school of schools) await page.getByRole('checkbox', { name: school.name, exact: false }).check();
      const imagePath = testInfo.outputPath('q1.png');
      await writeFile(imagePath, Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aL1sAAAAASUVORK5CYII=', 'base64'));
      await page.locator('input[type=file]').setInputFiles(imagePath);
      const preview = page.getByRole('img', { name: '첨부 이미지 1', exact: true });
      await expect(preview).toBeVisible();
      await expect.poll(() => preview.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true);
      await page.getByRole('button', { name: '등록', exact: true }).click();
      await expect(page).toHaveURL(/\/community\/\d+$/);
      postId = Number(new URL(page.url()).pathname.split('/').pop());
    });
    if (!postId) {
      testInfo.annotations.push({ type: 'setup-fallback', description: '글쓰기 UI 실패 후 후속 단계만 검증하기 위해 API로 별도 글 생성. 앞선 실패는 유지.' });
      const boards = await api<S['Board'][]>(context, '/api/boards');
      const post = await api<S['PostDetail']>(context, '/api/posts', 'POST', { boardId: boards[0].id, title, body: title, universityIds: schools.map(s => s.id), imageIds: [] });
      postId = post.id;
    }
    await step('글 상세 이미지 로딩·학교 태그', async () => {
      await page.goto(`/community/${postId}`);
      await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
      const image = page.getByRole('img', { name: '첨부 이미지 1', exact: true });
      await expect(image).toBeVisible();
      for (const school of schools) await expect(page.locator(`a[href="/community?universityId=${school.id}"]`)).toBeVisible();
      const src = await image.getAttribute('src');
      expect(src).toBeTruthy();
      const webImageURL = new URL(src!, page.url()).href;
      const apiImageURL = new URL(src!, apiURL).href;
      const webImage = await context.request.get(webImageURL);
      const apiImage = await context.request.get(apiImageURL);
      await testInfo.attach('image-http-evidence', {
        body: JSON.stringify({ src, webImageURL, webStatus: webImage.status(), apiImageURL, apiStatus: apiImage.status() }, null, 2),
        contentType: 'application/json',
      });
      // Q1-001 stays a real failure until the product serves the uploaded image in the browser.
      await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0), {
        message: 'Q1-001: 업로드 성공 후 이미지 URL이 웹 서버로 해석되어 이미지 로딩 실패',
      }).toBe(true);
    });
    await step('댓글·1단계 답글·재답글 UI/API 차단', async () => {
      const comment = `댓글 ${unique()}`, reply = `답글 ${unique()}`;
      await page.getByLabel('댓글 작성', { exact: true }).fill(comment);
      await page.getByRole('button', { name: '등록', exact: true }).click();
      const root = page.getByRole('listitem').filter({ has: page.getByText(comment, { exact: true }) }).first();
      await root.getByRole('button', { name: '답글', exact: true }).click();
      await page.getByLabel('답글 작성', { exact: true }).fill(reply);
      await page.getByRole('button', { name: '등록', exact: true }).click();
      const child = page.getByRole('listitem').filter({ has: page.getByText(reply, { exact: true }) }).last();
      await expect(child).toBeVisible();
      await expect(child.getByRole('button', { name: '답글', exact: true })).toHaveCount(0);
      const comments = await api<S['CommentPage']>(context, `/api/posts/${postId}/comments?size=100`);
      const replyId = comments.content.find(c => c.body === comment)?.replies.find(r => r.body === reply)?.id;
      expect(replyId).toBeTruthy();
      const response = await request(context, `/api/posts/${postId}/comments`, 'POST', { body: '차단되어야 할 재답글', parentId: replyId });
      expect(response.status()).toBe(422);
      expect((await response.json()).code).toBe('REPLY_DEPTH_EXCEEDED');
    });
    await step('추천 추가·해제', async () => {
      const button = page.getByRole('button', { name: /^추천 \d+$/ });
      await button.click();
      await expect(button).toHaveAttribute('aria-pressed', 'true');
      await expect(button).toBeEnabled();
      await button.click();
      await expect(button).toHaveAttribute('aria-pressed', 'false');
    });
    await step('별도 USER 신고·중복 신고 안내', async () => {
      await reporter.goto(`/community/${postId}`);
      await reporter.getByRole('button', { name: '신고', exact: true }).click();
      await reporter.getByRole('button', { name: '신고 접수', exact: true }).click();
      await expect(reporter.getByText('신고가 접수되었습니다.', { exact: true })).toBeVisible();
      await reporter.getByRole('button', { name: '신고', exact: true }).click();
      await reporter.getByRole('button', { name: '신고 접수', exact: true }).click();
      await expect(reporter.getByText('이미 신고한 글입니다.', { exact: true })).toBeVisible();
    });
    await step('관리자 /admin·신고 RESOLVED·글 숨김', async () => {
      await admin.goto('/admin');
      await expect(admin.getByRole('heading', { name: '운영 관리', exact: true })).toBeVisible();
      await admin.goto('/admin/reports');
      // Traverse pages so reruns do not depend on the number/order of existing reports.
      const row = admin.getByRole('row').filter({ hasText: title });
      for (let i = 0; i < 100 && !(await row.count()); i++) {
        const next = admin.getByRole('button', { name: '다음', exact: true });
        if (await next.isDisabled()) break;
        await next.click();
        await expect(admin.getByRole('table')).toBeVisible();
      }
      await row.getByRole('button', { name: '상세', exact: true }).click();
      await admin.getByRole('combobox', { name: '처리 결과', exact: true }).selectOption('RESOLVE');
      await admin.getByLabel('대상 글·댓글 숨기기').check();
      await admin.getByRole('button', { name: '처리 검토', exact: true }).click();
      await admin.getByRole('button', { name: '확인 후 처리', exact: true }).click();
      await expect(admin.getByText(/신고 #\d+을 처리 완료했습니다/)).toBeVisible();
      const hidden = await reporterContext.request.get(`${apiURL}/api/posts/${postId}`);
      expect(hidden.status()).toBe(404);
    });
    await step('글 작성자 SUSPEND 등록', async () => {
      await admin.goto('/admin/sanctions');
      await admin.getByLabel('대상 회원 ID').fill(String(author.id));
      await admin.getByRole('combobox', { name: '제재 유형', exact: true }).selectOption('SUSPEND');
      await admin.getByLabel('사유 (필수, 최대 500자)', { exact: true }).fill(`Q1 ${unique()}`);
      const end = new Date(Date.now() + 86400000).toLocaleString('sv-SE', { timeZone: 'Asia/Seoul' }).slice(0, 16).replace(' ', 'T');
      await admin.getByLabel('종료 시각 (한국시간)').fill(end);
      await admin.getByRole('button', { name: '등록 검토', exact: true }).click();
      await admin.getByRole('button', { name: '확인 후 처리', exact: true }).click();
      await expect(admin.getByText(`회원 #${author.id}에게 기간 정지 제재를 등록했습니다.`, { exact: true })).toBeVisible();
    });
    // Use the reporter's own new post as an accessible read/like/report target, even after the original is hidden.
    const boards = await api<S['Board'][]>(reporterContext, '/api/boards');
    const readable = await api<S['PostDetail']>(reporterContext, '/api/posts', 'POST', { boardId: boards[0].id, title: `Q1 읽기 ${unique()}`, body: '정지 사용자 읽기 검증', universityIds: [], imageIds: [] });
    await step('기존 정지 세션: 읽기·글쓰기·추천·신고 차단', async () => {
      await page.goto('/me');
      await expect(page.getByText('이용 정지', { exact: true })).toBeVisible();
      await page.goto('/community/write');
      await expect(page.getByText('정지 중이라 작성할 수 없습니다. 읽기는 가능합니다.', { exact: true })).toBeVisible();
      await expect(page.getByLabel('제목', { exact: true })).toBeDisabled();
      await page.goto(`/community/${readable.id}`);
      await expect(page.getByRole('heading', { name: readable.title, exact: true })).toBeVisible();
      await page.getByRole('button', { name: /^추천 \d+$/ }).click();
      await expect(page.getByText('정지 중이라 작성할 수 없습니다. 읽기는 가능합니다.', { exact: true })).toBeVisible();
      await page.getByRole('button', { name: '신고', exact: true }).click();
      await expect(page.getByRole('heading', { name: '글 신고', exact: true })).toHaveCount(0);
      for (const [path, method, data] of [
        ['/api/posts', 'POST', { boardId: boards[0].id, title: '차단 검증', body: '차단 검증', universityIds: [], imageIds: [] }],
        [`/api/posts/${readable.id}/like`, 'PUT', undefined],
        ['/api/reports', 'POST', { targetType: 'POST', targetId: readable.id, reason: 'SPAM', detail: '차단 검증' }],
      ] as const) {
        const response = await request(context, path, method, data);
        expect.soft(response.status(), path).toBe(403);
        expect.soft((await response.json()).code, path).toBe('ACCOUNT_SUSPENDED');
      }
    });
    await step('정지 중 탈퇴', async () => {
      await page.goto('/me');
      await page.getByRole('button', { name: '회원 탈퇴', exact: true }).click();
      await page.getByRole('button', { name: '안내 확인, 계속', exact: true }).click();
      await page.getByRole('button', { name: '최종 확인 및 탈퇴', exact: true }).click();
      await expect(page).toHaveURL(/\/login$/);
      const response = await context.request.get(`${apiURL}/api/me`);
      expect(response.status()).toBe(401);
    });
    testInfo.annotations.push({ type: 'coverage', description: '가입 최초 닉네임: pending 세션 dev 로그인 도달 불가. 탈퇴 후 동일 소셜 계정 차단: dev 계정에 소셜 식별자 없음; dev 로그인은 닉네임 조회/생성 및 정지 상태 덮어쓰기이므로 검증 불가.' });
  } finally {
    await reporterContext.close();
    await adminContext.close();
  }
  expect(failures, '제품 실패 또는 테스트 문제: 첨부된 단계별 오류 확인').toEqual([]);
});

test('Q1-7 일반 회원 관리자 접근 불가·비로그인 글쓰기 로그인 안내', async ({ page, context }) => {
  await page.goto('/community/write');
  await expect(page.getByText('글을 쓰려면', { exact: false })).toBeVisible();
  await expect(page.getByLabel('제목', { exact: true })).toBeDisabled();
  const loginLink = page.getByRole('link', { name: '로그인', exact: true }).last();
  await expect(loginLink).toHaveAttribute('href', '/login');
  await loginLink.click();
  await expect(page).toHaveURL(/\/login$/);
  await login(page);
  await page.goto('/admin');
  await expect(page.getByText('접근할 수 없습니다. 관리자 전용 화면입니다.', { exact: true })).toBeVisible();
  const response = await context.request.get(`${apiURL}/api/admin/reports`);
  expect(response.status()).toBe(403);
});
