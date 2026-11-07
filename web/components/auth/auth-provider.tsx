"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
} from "react";
import { usePathname, useRouter } from "next/navigation";
import { ApiError, apiErrorMessage } from "@/lib/api";
import { getMe, logout as logoutApi, type Me } from "@/lib/auth-api";

type AuthState =
  | "loading"
  | "anonymous"
  | "pending"
  | "authenticated"
  | "error";
type AuthContextValue = {
  me: Me | null;
  state: AuthState;
  error: string;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
};
const AuthContext = createContext<AuthContextValue | null>(null);
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [state, setState] = useState<AuthState>("loading");
  const [error, setError] = useState("");
  const router = useRouter();
  const pathname = usePathname();
  const refresh = useCallback(async () => {
    try {
      setMe(await getMe());
      setState("authenticated");
      setError("");
    } catch (e) {
      setMe(null);
      if (e instanceof ApiError && e.code === "SIGNUP_REQUIRED")
        setState("pending");
      else if (e instanceof ApiError && e.status === 401) setState("anonymous");
      else {
        setState("error");
        setError(apiErrorMessage(e));
      }
    }
  }, []);
  useEffect(() => {
    let active = true;
    getMe().then(
      (value) => {
        if (active) {
          setMe(value);
          setState("authenticated");
        }
      },
      (e: unknown) => {
        if (!active) return;
        if (e instanceof ApiError && e.code === "SIGNUP_REQUIRED")
          setState("pending");
        else if (e instanceof ApiError && e.status === 401)
          setState("anonymous");
        else {
          setState("error");
          setError(apiErrorMessage(e));
        }
      },
    );
    return () => {
      active = false;
    };
  }, []);
  useEffect(() => {
    if (state === "pending" && pathname !== "/signup")
      router.replace("/signup");
  }, [state, pathname, router]);
  // 탭 복귀 시 서버의 로그아웃·정지 등 상태를 다시 확인한다.
  useEffect(() => {
    const onFocus = () => {
      void refresh();
    };
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, [refresh]);
  const logout = async () => {
    await logoutApi();
    setMe(null);
    setState("anonymous");
    router.push("/login");
  };
  return (
    <AuthContext.Provider value={{ me, state, error, refresh, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
export function useMe() {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useMe는 AuthProvider 안에서 사용해야 합니다.");
  return value;
}
