import DocumentsPage from "@/components/admin/documents";

// 권한 확인 전에는 하위 화면을 렌더링하지 않으므로 즉시 탐색 검증을 제외한다.
export const instant = false;

export default function Page() {
  return <DocumentsPage />;
}
