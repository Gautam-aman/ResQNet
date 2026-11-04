import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { ChevronRight, MapPin, Search, SlidersHorizontal } from 'lucide-react';
import { useMemo, useState } from 'react';

import { StatusChip } from '@/components/primitives/StatusChip';
import {
  clockTime,
  deliveryStateTone,
  relativeTime,
  severityLabel,
} from '@/lib/format';
import type { IncidentStatus, IncidentSummary } from '@/lib/api';

interface IncidentQueueProps {
  incidents: IncidentSummary[];
  selected: string | null;
  loading: boolean;
  unreachable: boolean;
  loadingMore: boolean;
  hasMore: boolean;
  totalElements: number;
  /** The status filter currently applied, so the chip can show itself selected. */
  activeStatus: IncidentStatus | null;
  onSelect: (reference: string) => void;
  onLoadMore: () => void;
  onFilterChange: (patch: { status?: IncidentStatus | null; query?: string }) => void;
}

const STATUS_FILTERS: { label: string; value: IncidentStatus | null }[] = [
  { label: 'All', value: null },
  { label: 'New', value: 'NEW' },
  { label: 'Triaged', value: 'TRIAGED' },
  { label: 'In progress', value: 'IN_PROGRESS' },
  { label: 'Resolved', value: 'RESOLVED' },
  // Dismissed is reachable from the detail panel, so filtering to it has to be
  // reachable here too. Leaving it out meant a dismissed incident could only be
  // found under "All", which is where nobody looks for something already closed.
  { label: 'Dismissed', value: 'DISMISSED' },
];

/**
 * Incident queue.
 *
 * <p>Rows are a list, not a table, because the information density per row is
 * low enough that a grid buys nothing and costs a great deal of horizontal
 * scrolling on a tablet at 3am.
 */
export function IncidentQueue({
  incidents,
  selected,
  loading,
  unreachable,
  loadingMore,
  hasMore,
  totalElements,
  activeStatus,
  onSelect,
  onLoadMore,
  onFilterChange,
}: IncidentQueueProps) {
  const reduced = useReducedMotion();
  const [query, setQuery] = useState('');

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return incidents;
    return incidents.filter(
      (incident) =>
        incident.title.toLowerCase().includes(needle) ||
        incident.reference.toLowerCase().includes(needle) ||
        incident.categoryLabel.toLowerCase().includes(needle),
    );
  }, [incidents, query]);

  return (
    <section className="flex min-h-0 flex-col" aria-label="Incident queue">
      <div className="flex flex-wrap items-center gap-2 pb-3">
        <div className="relative min-w-0 flex-1">
          <Search
            size={14}
            className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-tertiary"
            aria-hidden="true"
          />
          <input
            type="search"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              onFilterChange({ query: event.target.value });
            }}
            placeholder="Search incidents"
            aria-label="Search incidents"
            className="w-full rounded-xl border border-base-600 bg-base-800 py-2.5 pl-9 pr-3 text-sm text-ink-primary placeholder:text-ink-tertiary focus-visible:border-network focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-network"
          />
        </div>

        <div className="flex items-center gap-1 overflow-x-auto" role="group" aria-label="Status filter">
          <SlidersHorizontal size={13} className="mr-1 shrink-0 text-ink-tertiary" aria-hidden="true" />
          {STATUS_FILTERS.map((filter) => {
            const active = filter.value === activeStatus;
            return (
              <button
                key={filter.label}
                type="button"
                onClick={() => onFilterChange({ status: filter.value })}
                // The active filter, not the selected incident. These were the
                // same expression before, which meant selecting an incident
                // cleared every chip's pressed state and the applied filter was
                // never exposed to assistive technology at all.
                aria-pressed={active}
                className={
                  'shrink-0 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-colors ' +
                  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-network ' +
                  // Previously both branches of this conditional produced the
                  // same class, so clicking a filter gave no visual confirmation
                  // that anything had happened.
                  (active
                    ? 'bg-network-soft text-network border border-network/40'
                    : 'border border-transparent text-ink-tertiary hover:text-ink-primary')
                }
              >
                <FilterChip label={filter.label} />
              </button>
            );
          })}
        </div>
      </div>

      <div className="mb-2 flex items-center justify-between text-[11px] text-ink-tertiary" aria-live="polite">
        <span>Loaded {incidents.length} incident{incidents.length === 1 ? '' : 's'}</span>
        <span>{totalElements} total</span>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto pr-1">
        {unreachable && incidents.length === 0 ? (
          <EmptyState
            title="Gateway unreachable"
            body="Incidents could not be loaded. This is not an empty queue."
          />
        ) : loading && incidents.length === 0 ? (
          <SkeletonRows />
        ) : visible.length === 0 ? (
          <EmptyState
            title={query ? 'No matches' : 'Queue is empty'}
            body={
              query
                ? 'Nothing matches that search.'
                : 'No incidents have been reported. Simulation runs are shown in the topology panel, clearly labelled.'
            }
          />
        ) : (
          <ul className="space-y-2">
            <AnimatePresence initial={false}>
              {visible.map((incident) => (
                <motion.li
                  key={incident.reference}
                  {...(reduced
                    ? { initial: false as const }
                    : {
                        layout: 'position' as const,
                        initial: { opacity: 0, y: 8 },
                        exit: { opacity: 0, y: -8 },
                      })}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ duration: 0.26, ease: [0.22, 1, 0.36, 1] }}
                >
                  <IncidentRow
                    incident={incident}
                    active={incident.reference === selected}
                    onSelect={() => onSelect(incident.reference)}
                  />
                </motion.li>
              ))}
            </AnimatePresence>
          </ul>
        )}

        {hasMore && incidents.length > 0 && !unreachable ? (
          <div className="flex justify-center py-3">
            <button
              type="button"
              onClick={onLoadMore}
              disabled={loadingMore || loading}
              className="rounded-lg border border-base-600 px-4 py-2 text-xs font-medium text-ink-secondary transition-colors hover:border-base-500 hover:text-ink-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-network disabled:cursor-not-allowed disabled:opacity-50"
            >
              {loadingMore ? 'Loading more…' : 'Load more incidents'}
            </button>
          </div>
        ) : null}
      </div>
    </section>
  );
}

function FilterChip({ label }: { label: string }) {
  return <span className="block px-2.5 py-1.5 text-xs font-medium">{label}</span>;
}

function IncidentRow({
  incident,
  active,
  onSelect,
}: {
  incident: IncidentSummary;
  active: boolean;
  onSelect: () => void;
}) {
  const tone = incident.severity >= 3 ? 'emergency' : incident.status === 'RESOLVED' ? 'delivered' : 'network';

  return (
    <button
      type="button"
      onClick={onSelect}
      aria-current={active ? 'true' : undefined}
      className={
        'group w-full rounded-xl border p-4 text-left transition-colors duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-network ' +
        (active
          ? 'border-network/50 bg-network-soft'
          : 'border-base-600 bg-base-800 hover:border-base-500 hover:bg-base-750')
      }
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="flex items-center gap-2">
            <span className="tabular text-[11px] font-semibold tracking-wider text-ink-tertiary">
              {incident.reference}
            </span>
            {incident.advisory ? (
              <span
                className="rounded bg-base-700 px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-widest text-ink-tertiary"
                title={
                  // The handset does not send a confidence, and the backend
                  // omits the field entirely rather than sending null, so this
                  // is usually absent rather than zero. Saying "classified
                  // on-device" is honest; interpolating undefined is not.
                  incident.advisoryConfidence != null
                    ? `Classified on-device at ${Math.round(incident.advisoryConfidence * 100)}% confidence`
                    : 'Classified on-device'
                }
              >
                Advisory
              </span>
            ) : null}
          </p>
          <h3 className="mt-1 truncate text-sm font-medium text-ink-primary">{incident.title}</h3>
        </div>
        <ChevronRight
          size={15}
          className="mt-1 shrink-0 text-ink-tertiary transition-transform duration-200 group-hover:translate-x-0.5"
          aria-hidden="true"
        />
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <StatusChip tone={tone}>{severityLabel(incident.severity)}</StatusChip>
        <StatusChip tone={deliveryStateTone(incident.status)}>{incident.statusLabel}</StatusChip>
        {incident.locationShared ? (
          <span
            className="inline-flex items-center gap-1 rounded-full bg-pending-soft px-2 py-1 text-[10px] font-semibold uppercase tracking-widest text-pending"
            title="The sender attached a location to this message"
          >
            <MapPin size={10} aria-hidden="true" />
            Located
          </span>
        ) : null}
      </div>

      <p className="tabular mt-3 text-[11px] text-ink-tertiary">
        {incident.categoryLabel} · {relativeTime(incident.createdAt)} · {clockTime(incident.createdAt)} ·{' '}
        {incident.messageCount} message{incident.messageCount === 1 ? '' : 's'}
      </p>
    </button>
  );
}

function EmptyState({ title, body }: { title: string; body: string }) {
  return (
    <div className="rounded-xl border border-dashed border-base-600 px-5 py-10 text-center">
      <p className="text-sm font-medium text-ink-primary">{title}</p>
      <p className="mx-auto mt-2 max-w-sm text-xs leading-relaxed text-ink-tertiary">{body}</p>
    </div>
  );
}

/** Skeletons match the row shape so the layout does not jump when data lands. */
function SkeletonRows() {
  return (
    <ul className="space-y-2" aria-hidden="true">
      {[0, 1, 2, 3].map((index) => (
        <li key={index} className="rounded-xl border border-base-700 bg-base-800/60 p-4">
          <div className="h-2.5 w-16 rounded bg-base-700" />
          <div className="mt-2.5 h-3 w-3/4 rounded bg-base-700" />
          <div className="mt-3 flex gap-2">
            <div className="h-5 w-16 rounded-full bg-base-700" />
            <div className="h-5 w-20 rounded-full bg-base-700" />
          </div>
        </li>
      ))}
    </ul>
  );
}
