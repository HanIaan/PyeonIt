// 다른 라우트에서 공통 로그인 상태를 읽는 진입점. RootLayout의 AuthProvider를 사용한다.
export { useMe } from "@/components/auth/auth-provider";
export type { Me, Provider } from "./auth-api";
