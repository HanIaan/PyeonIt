// 백엔드 API 주소. NEXT_PUBLIC_ 접두사라 빌드 시 클라이언트 번들에 박힌다.
export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
