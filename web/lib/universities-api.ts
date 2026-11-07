import { api, type Schemas } from "./api";

export type { Schemas } from "./api";
export const isMock = process.env.NEXT_PUBLIC_API_MOCK === "true";
export type NoticeFilters = {
  academicYear?: number;
  region?: Schemas["Region"];
  favoritesOnly?: boolean;
  page: number;
  size: number;
};

function query(values: object): string {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== "" && value !== false)
      params.set(key, String(value));
  });
  return params.toString();
}

export async function listUniversities(
  region?: Schemas["Region"],
): Promise<Schemas["University"][]> {
  if (isMock)
    return (await import("./universities-mock")).listMockUniversities(region);
  return api(`/api/universities?${query({ region })}`);
}

export async function getUniversity(
  id: number,
  academicYear?: number,
): Promise<Schemas["UniversityDetail"]> {
  if (isMock)
    return (await import("./universities-mock")).getMockUniversity(
      id,
      academicYear,
    );
  return api(`/api/universities/${id}?${query({ academicYear })}`);
}

export async function listNotices(
  filters: NoticeFilters,
): Promise<Schemas["NoticePage"]> {
  if (isMock)
    return (await import("./universities-mock")).listMockNotices(filters);
  return api(`/api/notices?${query(filters)}`);
}

export async function listCalendarEvents(
  from: string,
  to: string,
  universityId?: number,
): Promise<Schemas["CalendarEvent"][]> {
  if (isMock)
    return (await import("./universities-mock")).listMockCalendar(
      from,
      to,
      universityId,
    );
  return api(`/api/calendar?${query({ from, to, universityId })}`);
}

export async function setFavoriteUniversity(
  id: number,
  favorite: boolean,
): Promise<void> {
  if (isMock)
    return (await import("./universities-mock")).setMockFavorite(id, favorite);
  return api(`/api/me/favorite-universities/${id}`, {
    method: favorite ? "PUT" : "DELETE",
  });
}
