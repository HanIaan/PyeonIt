import { Suspense } from "react";
import CalendarView from "@/components/calendar/calendar-view";

export default function Page() {
  return (
    <Suspense fallback={<p role="status">캘린더를 불러오는 중…</p>}>
      <CalendarView />
    </Suspense>
  );
}
