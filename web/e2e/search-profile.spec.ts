import { test, expect, type Page, type BrowserContext } from "@playwright/test";
import type { components } from "../lib/api-types";

type S = components["schemas"];
const apiURL = process.env.E2E_API_URL ?? "http://localhost:8081";
// B5 병합 후 조율자 지시에 따라 새 DB와 별도 포트에서만 실행한다. 네트워크 목은 사용하지 않는다.
async function request<T>(
  context: BrowserContext,
  path: string,
  data?: unknown,
): Promise<T> {
  const headers: Record<string, string> = {};
  if (data !== undefined) {
    const csrf = await context.request.get(`${apiURL}/api/csrf`);
    expect(csrf.ok()).toBeTruthy();
    headers["X-XSRF-TOKEN"] = (await csrf.json()).token;
  }
  const response = await context.request.fetch(`${apiURL}${path}`, {
    method: data === undefined ? "GET" : "POST",
    data,
    headers,
  });
  expect(response.ok(), `${path}: ${response.status()}`).toBeTruthy();
  return response.json();
}
async function fixture(page: Page, context: BrowserContext) {
  const suffix =
    Date.now().toString(36) + Math.random().toString(36).slice(2, 5);
  const nickname = `f${suffix}`.slice(0, 12);
  await page.goto("/login");
  await page.getByLabel("닉네임", { exact: true }).fill(nickname);
  await page
    .getByRole("combobox", { name: "권한", exact: true })
    .selectOption("USER");
  const login = page.waitForResponse(
    (r) =>
      r.url() === `${apiURL}/api/dev/login` && r.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "테스트 로그인", exact: true })
    .click();
  const response = await login;
  expect(response.ok()).toBeTruthy();
  const user: S["Me"] = await response.json();
  await expect(page).not.toHaveURL(/\/login/);
  const boards = await request<S["Board"][]>(context, "/api/boards");
  expect(boards.length).toBeGreaterThan(0);
  const title = `F5 검색 ${suffix}`;
  const post = await request<S["PostDetail"]>(context, "/api/posts", {
    boardId: boards[0].id,
    title,
    body: title,
    universityIds: [],
    imageIds: [],
  });
  const comment = await request<S["Comment"]>(
    context,
    `/api/posts/${post.id}/comments`,
    { body: `F5 댓글 ${suffix}`, parentId: null },
  );
  return { user, post, comment };
}

test("F5 게시물 검색 → 결과 → 상세 → 뒤로 가기에서 q 유지", async ({
  page,
  context,
}) => {
  const { post, user } = await fixture(page, context);
  await page.goto("/community");
  await page.getByRole("radio", { name: "게시물 검색", exact: true }).check();
  await page
    .getByRole("textbox", { name: "검색어", exact: true })
    .fill(post.title);
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await expect(page).toHaveURL(
    (url) =>
      url.pathname === "/community/search" &&
      url.searchParams.get("q") === post.title,
  );
  await page.getByRole("link", { name: post.title, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/community/${post.id}$`));
  await expect(
    page.getByRole("heading", { name: post.title, exact: true }),
  ).toBeVisible();
  await page.goBack();
  await expect(page).toHaveURL(
    (url) =>
      url.pathname === "/community/search" &&
      url.searchParams.get("q") === post.title,
  );
  await expect(
    page.getByRole("textbox", { name: "검색어", exact: true }),
  ).toHaveValue(post.title);
  await expect(
    page.getByRole("link", { name: post.title, exact: true }),
  ).toBeVisible();
  await test.step("불일치 검색과 작성자 닉네임 부분 일치 검색", async () => {
    await page
      .getByRole("textbox", { name: "검색어", exact: true })
      .fill(`${post.title}없음`);
    await page.getByRole("button", { name: "검색", exact: true }).click();
    await expect(
      page.getByText("검색 결과가 없습니다.", { exact: true }),
    ).toBeVisible();
    await page.getByRole("radio", { name: "작성자 검색", exact: true }).check();
    await page
      .getByRole("textbox", { name: "검색어", exact: true })
      .fill(user.nickname.slice(0, -1));
    await page.getByRole("button", { name: "검색", exact: true }).click();
    await expect(page).toHaveURL(
      (url) => url.searchParams.get("scope") === "users",
    );
    await expect(
      page
        .getByRole("listitem")
        .getByRole("link", { name: user.nickname, exact: true }),
    ).toHaveAttribute("href", `/users/${user.id}`);
  });
  await test.step("성대 별칭 학교 검색과 결과 없음", async () => {
    await page.goto("/universities");
    await page
      .getByRole("textbox", { name: "학교명 검색", exact: true })
      .fill("성대");
    await page.getByRole("button", { name: "검색", exact: true }).click();
    await expect(page).toHaveURL((url) => url.searchParams.get("q") === "성대");
    await expect(
      page.getByRole("link", { name: "성균관대학교 →", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("link", { name: "서강대학교 →", exact: true }),
    ).toHaveCount(0);
    await page
      .getByRole("textbox", { name: "학교명 검색", exact: true })
      .fill(post.title);
    await page.getByRole("button", { name: "검색", exact: true }).click();
    await expect(
      page.getByText("검색 결과가 없습니다.", { exact: true }),
    ).toBeVisible();
  });
});

test("F5 상세 작성자 → 공개 프로필 → 글·댓글 탭 → 댓글 원문", async ({
  page,
  context,
  browser,
}, testInfo) => {
  const { user, post, comment } = await fixture(page, context);
  const anonymous = await browser.newContext({
    baseURL: testInfo.project.use.baseURL,
  });
  try {
    const reader = await anonymous.newPage();
    await reader.goto(`/community/${post.id}`);
    await reader.locator(`a[href="/users/${user.id}"]`).first().click();
    await expect(reader).toHaveURL(new RegExp(`/users/${user.id}$`));
    await expect(
      reader.getByRole("heading", { name: user.nickname, exact: true }),
    ).toBeVisible();
    const tabs = reader.getByRole("navigation", {
      name: "프로필 활동",
      exact: true,
    });
    await tabs.getByRole("link", { name: "글", exact: true }).click();
    await expect(reader).toHaveURL(
      (url) => url.searchParams.get("tab") === "posts",
    );
    await expect(
      reader.getByRole("link", { name: post.title, exact: true }),
    ).toBeVisible();
    await tabs.getByRole("link", { name: "댓글", exact: true }).click();
    await expect(reader).toHaveURL(
      (url) => url.searchParams.get("tab") === "comments",
    );
    const original = reader
      .getByRole("link")
      .filter({ hasText: comment.body! });
    await expect(original).toContainText(post.title);
    await reader.reload();
    await expect(
      tabs.getByRole("link", { name: "댓글", exact: true }),
    ).toHaveAttribute("aria-current", "page");
    await original.click();
    await expect(reader).toHaveURL(new RegExp(`/community/${post.id}$`));
    await expect(
      reader.getByRole("heading", { name: post.title, exact: true }),
    ).toBeVisible();
    await test.step("실제 탈퇴 후 프로필 404·작성자 링크 제거", async () => {
      const csrf = await context.request.get(`${apiURL}/api/csrf`);
      expect(csrf.ok()).toBeTruthy();
      const withdrawn = await context.request.delete(`${apiURL}/api/me`, {
        headers: { "X-XSRF-TOKEN": (await csrf.json()).token },
      });
      expect(withdrawn.status()).toBe(204);
      expect(
        (
          await anonymous.request.get(`${apiURL}/api/users/${user.id}`)
        ).status(),
      ).toBe(404);
      await reader.reload();
      await expect(
        reader.getByText("탈퇴한 사용자", { exact: true }).first(),
      ).toBeVisible();
      await expect(reader.locator(`a[href="/users/${user.id}"]`)).toHaveCount(
        0,
      );
      await reader.goto(`/users/${user.id}`);
      await expect(
        reader.getByRole("heading", {
          name: "탈퇴한 사용자입니다",
          exact: true,
        }),
      ).toBeVisible();
      await expect(reader.locator("section a")).toHaveCount(0);
    });
  } finally {
    await anonymous.close();
  }
});
