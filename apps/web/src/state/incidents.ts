
import { create } from 'zustand';
import {
  api,
  ApiError,
  type IncidentStatus,
  type IncidentSummary,
  type Overview,
} from '@/lib/api';

interface IncidentFilters {
  status: IncidentStatus | null;
  query: string;
  severity: number | null;
}

interface IncidentState {
  items: IncidentSummary[];
  overview: Overview | null;
  filters: IncidentFilters;
  loading: boolean;
  loadingMore: boolean;
  page: number;
  totalPages: number;
  totalElements: number;
  unreachable: boolean;
  error: string | null;
  setStatusFilter: (status: IncidentStatus | null) => void;
  setQuery: (query: string) => void;
  setSeverityFilter: (severity: number | null) => void;
  refresh: () => Promise<void>;
  loadMore: () => Promise<void>;
  updateStatus: (
    reference: string,
    status: IncidentStatus,
    note?: string,
  ) => Promise<void>;
}

/**
 * Incremented whenever a new request supersedes an older one.
 * Only the latest request may update the store.
 */
let requestVersion = 0;

export const useIncidentStore = create<IncidentState>((set, get) => ({
  items: [],
  overview: null,
  filters: { status: null, query: '', severity: null },
  loading: false,
  loadingMore: false,
  page: 0,
  totalPages: 0,
  totalElements: 0,
  unreachable: false,
  error: null,

  setStatusFilter: (status) => {
    if (get().filters.status === status) return;

    requestVersion += 1;

    set({
      filters: { ...get().filters, status },
      items: [],
      page: 0,
      totalPages: 0,
      totalElements: 0,
      loading: false,
      loadingMore: false,
      unreachable: false,
      error: null,
    });

    void get().refresh();
  },

  setQuery: (query) => {
    if (get().filters.query === query) return;

    requestVersion += 1;

    set({
      filters: { ...get().filters, query },
      items: [],
      page: 0,
      totalPages: 0,
      totalElements: 0,
      loading: false,
      loadingMore: false,
      unreachable: false,
      error: null,
    });

    void get().refresh();
  },

  setSeverityFilter: (severity) => {
    if (get().filters.severity === severity) return;

    requestVersion += 1;

    set({
      filters: { ...get().filters, severity },
      items: [],
      page: 0,
      totalPages: 0,
      totalElements: 0,
      loading: false,
      loadingMore: false,
      unreachable: false,
      error: null,
    });

    void get().refresh();
  },

  async refresh() {
    const version = ++requestVersion;
    const { filters, page: previousPage, items } = get();

    set({
      loading: items.length === 0,
      loadingMore: false,
      error: null,
      unreachable: false,
    });

    const fetchPage = (page: number) =>
      filters.query.trim()
        ? api.search(filters.query.trim(), page)
        : api.incidents(filters.status ?? undefined, page);

    try {
      const firstPage = await fetchPage(0);

      // When severity is selected, inspect every server page so that
      // matching incidents on later pages are not hidden.
      const lastPage =
        filters.severity !== null
          ? Math.max(0, firstPage.totalPages - 1)
          : Math.min(
              previousPage,
              Math.max(0, firstPage.totalPages - 1),
            );

      const remainingPages = await Promise.all(
        Array.from({ length: lastPage }, (_, index) =>
          fetchPage(index + 1),
        ),
      );

      if (version !== requestVersion) return;

      const uniqueItems = new Map<string, IncidentSummary>();

      for (const result of [firstPage, ...remainingPages]) {
        for (const incident of result.content) {
          uniqueItems.set(incident.reference, incident);
        }
      }

      const items = [...uniqueItems.values()].filter(
        (incident) =>
          filters.severity === null ||
          incident.severity === filters.severity,
      );

      const overview = await api.overview();

      if (version !== requestVersion) return;

      set({
        items,
        overview,
        page: lastPage,
        totalPages: firstPage.totalPages,
        totalElements: firstPage.totalElements,
        loading: false,
        loadingMore: false,
        unreachable: false,
        error: null,
      });
    } catch (cause) {
      if (version !== requestVersion) return;

      if (cause instanceof ApiError && cause.status === 0) {
        set({
          unreachable: true,
          loading: false,
          loadingMore: false,
          error: cause.message,
        });
        return;
      }

      if (cause instanceof ApiError && cause.isAuthFailure) {
        set({
          loading: false,
          loadingMore: false,
          error: 'Session expired. Sign in again.',
        });
        return;
      }

      set({
        loading: false,
        loadingMore: false,
        error:
          cause instanceof Error
            ? cause.message
            : 'Could not load incidents',
      });
    }
  },

  async loadMore() {
    const state = get();

    if (
      state.loading ||
      state.loadingMore ||
      state.page + 1 >= state.totalPages
    ) {
      return;
    }

    const version = requestVersion;
    const nextPage = state.page + 1;
    const { filters } = state;

    set({ loadingMore: true, error: null });

    try {
      const result = filters.query.trim()
        ? await api.search(filters.query.trim(), nextPage)
        : await api.incidents(filters.status ?? undefined, nextPage);

      if (version !== requestVersion) return;

      const current = get();
      const uniqueItems = new Map(
        current.items.map((incident) => [
          incident.reference,
          incident,
        ]),
      );

      for (const incident of result.content) {
        if (
          filters.severity === null ||
          incident.severity === filters.severity
        ) {
          uniqueItems.set(incident.reference, incident);
        }
      }

      set({
        items: [...uniqueItems.values()],
        page: nextPage,
        totalPages: result.totalPages,
        totalElements: result.totalElements,
        loadingMore: false,
      });
    } catch (cause) {
      if (version !== requestVersion) return;

      set({
        loadingMore: false,
        error:
          cause instanceof Error
            ? cause.message
            : 'Could not load more incidents',
      });
    }
  },

  async updateStatus(reference, status, note) {
    await api.updateStatus(reference, status, note);
    await get().refresh();
  },
}));
