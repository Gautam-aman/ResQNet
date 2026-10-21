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
    set({
      filters: { ...get().filters, status },
      items: [],
      page: 0,
      totalPages: 0,
      totalElements: 0,
    });
  },

  setQuery: (query) => {
    set({
      filters: { ...get().filters, query },
      items: [],
      page: 0,
      totalPages: 0,
      totalElements: 0,
    });
  },

  setSeverityFilter: (severity) => {
    set({
      filters: { ...get().filters, severity },
      items: [],
      page: 0,
      totalPages: 0,
      totalElements: 0,
    });
  },

  async refresh() {
    const { filters } = get();
    const previousPage = get().page;

    if (get().items.length === 0) {
      set({ loading: true });
    }
    set({ error: null });

    const fetchPage = (page: number) =>
      filters.query
        ? api.search(filters.query, page)
        : api.incidents(filters.status ?? undefined, page);

    try {
      const firstPage = await fetchPage(0);
      const lastPage = Math.min(
        previousPage,
        Math.max(0, firstPage.totalPages - 1),
      );

      const remainingPages = await Promise.all(
        Array.from({ length: lastPage }, (_, index) =>
          fetchPage(index + 1),
        ),
      );

      const pages = [firstPage, ...remainingPages];
      const uniqueItems = new Map<string, IncidentSummary>();

      for (const result of pages) {
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

      set({
        items,
        overview,
        page: lastPage,
        totalPages: firstPage.totalPages,
        totalElements: firstPage.totalElements,
        loading: false,
        loadingMore: false,
        unreachable: false,
      });
    } catch (cause) {
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
        error: cause instanceof Error
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

    const nextPage = state.page + 1;
    const { filters } = state;

    set({ loadingMore: true, error: null });

    try {
      const result = filters.query
        ? await api.search(filters.query, nextPage)
        : await api.incidents(filters.status ?? undefined, nextPage);

      const current = get();
      const uniqueItems = new Map(
        current.items.map((incident) => [incident.reference, incident]),
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
      set({
        loadingMore: false,
        error: cause instanceof Error
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