import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { accountApi } from '../../api/account'
import { qk } from '../../api/queryKeys'
import type { AppNotification, Page } from '../../api/types'
import { QueryError } from '../../components/QueryError'
import { Button, EmptyState, LoadingRegion, PageHeader, Pagination, SegmentedControl, Skeleton, SkeletonText, useToast } from '../../design/components'
import { BriefcaseBusiness, CalendarClock, Check, ChevronRight, Icon, Inbox, ListChecks, type IconComponent } from '../../design/icons'
import { relativeTime } from '../../design/lib/format'
import { errorMessage } from '../../api/client'
import { useSearchState } from '../../lib/useSearchState'
import { dayLabel } from '../../lib/when'
import './account.css'

const PAGE_SIZE = 20

const TEMPLATE: Record<string, { label: string; icon: IconComponent; fallback: string }> = {
  INTERVIEW_ASSIGNED: { label: 'Interview assigned', icon: CalendarClock, fallback: '/interviews' },
  INTERVIEW_SCHEDULED: { label: 'Interview scheduled', icon: CalendarClock, fallback: '/interviews' },
  INTERVIEW_CANCELLED: { label: 'Interview cancelled', icon: CalendarClock, fallback: '/interviews' },
  APPLICATION_RECEIVED: { label: 'New application', icon: BriefcaseBusiness, fallback: '/jobs' },
  APPLICATION_STATUS_CHANGED: { label: 'Application update', icon: BriefcaseBusiness, fallback: '/jobs' },
  APPLICATION_UPDATE: { label: 'Application update', icon: BriefcaseBusiness, fallback: '/jobs' },
  WORK_SAMPLE_ASSIGNED: { label: 'Work sample assigned', icon: ListChecks, fallback: '/jobs' },
  WORK_SAMPLE_SUBMITTED: { label: 'Work sample submitted', icon: ListChecks, fallback: '/jobs' },
}

/** In-app path for a notification: the server's `link` when it is a safe internal path, else a route by template. */
export function notificationTarget(n: AppNotification): string | null {
  if (n.link && n.link.startsWith('/') && !n.link.startsWith('//')) return n.link
  return TEMPLATE[n.template]?.fallback ?? null
}

/** /notifications (FLOWS-HR-B §9): grouped by day, unread first in weight, every row opens its subject. */
export function NotificationsPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const { toast } = useToast()
  const search = useSearchState()
  const page = search.getPage()
  const filter = search.get('filter', 'all') === 'unread' ? 'unread' : 'all'
  const list = useQuery({ queryKey: qk.notificationPage(page), queryFn: ({ signal }) => accountApi.notifications(page, PAGE_SIZE, signal), placeholderData: keepPreviousData })
  const unread = useQuery({ queryKey: qk.unreadCount, queryFn: ({ signal }) => accountApi.unreadCount(signal) })
  const unreadCount = unread.data?.count ?? 0

  const setRead = (ids: number[] | 'all') => {
    const at = new Date().toISOString()
    queryClient.setQueryData<Page<AppNotification>>(qk.notificationPage(page), cur => cur && { ...cur, content: cur.content.map(n => (ids === 'all' || ids.includes(n.id) ? { ...n, readAt: n.readAt ?? at } : n)) })
    queryClient.setQueryData<{ count: number }>(qk.unreadCount, cur => ({ count: ids === 'all' ? 0 : Math.max(0, (cur?.count ?? 0) - ids.length) }))
  }
  const markRead = useMutation({
    mutationFn: (id: number) => accountApi.markRead(id),
    onMutate: id => setRead([id]),
    onError: () => void queryClient.invalidateQueries({ queryKey: qk.notifications }),
  })
  const markAll = useMutation({
    mutationFn: () => accountApi.markAllRead(),
    onMutate: () => setRead('all'),
    onSuccess: () => toast({ tone: 'success', title: 'All caught up', body: 'Every notification is marked as read.' }),
    onError: cause => { toast({ tone: 'danger', title: 'Could not mark all as read', body: errorMessage(cause) }); void queryClient.invalidateQueries({ queryKey: qk.notifications }) },
  })

  function open(n: AppNotification) {
    if (!n.readAt) markRead.mutate(n.id)
    const target = notificationTarget(n)
    if (target) navigate(target)
  }

  const items = (list.data?.content ?? []).filter(n => filter === 'all' || !n.readAt)
  const groups = new Map<string, AppNotification[]>()
  for (const n of items) {
    const key = dayLabel(n.createdAt)
    groups.set(key, [...(groups.get(key) ?? []), n])
  }

  return (
    <div className="acc" data-width="read">
      <PageHeader
        title="Inbox"
        documentTitle="Notifications"
        meta={[unreadCount > 0 ? <span key="u"><strong className="numeric">{unreadCount}</strong> unread</span> : <span key="u">You are up to date</span>]}
        actions={<Button icon={Check} variant="secondary" onClick={() => markAll.mutate()} disabled={unreadCount === 0} loading={markAll.isPending}>Mark all as read</Button>}
      />
      <div className="acc-toolbar">
        <SegmentedControl label="Show" size="sm" value={filter} onChange={v => search.set({ filter: v === 'all' ? null : v }, { replace: true })} options={[{ value: 'all', label: 'All' }, { value: 'unread', label: `Unread · ${unreadCount}` }]} />
      </div>
      {list.isPending ? (
        <LoadingRegion label="Loading notifications">
          {Array.from({ length: 5 }, (_, i) => <div key={i} className="nt-row"><Skeleton width={28} height={28} radius={14} /><SkeletonText lines={2} /><Skeleton width={48} height={10} /></div>)}
        </LoadingRegion>
      ) : list.isError ? (
        <QueryError error={list.error} title="We couldn’t load your notifications" onRetry={() => void list.refetch()} retrying={list.isFetching} />
      ) : items.length === 0 ? (
        <EmptyState
          icon={Inbox}
          title={filter === 'unread' ? 'No unread notifications' : 'No notifications yet'}
          description={filter === 'unread' ? 'You have read everything on this page.' : 'Interview assignments, new applications and work-sample submissions appear here.'}
          action={filter === 'unread' ? <Button size="sm" variant="secondary" onClick={() => search.set({ filter: null })}>Show all</Button> : undefined}
        />
      ) : (
        <div className="nt" data-stale={list.isPlaceholderData || undefined}>
          {[...groups.entries()].map(([day, rows]) => (
            <section key={day} className="nt-day" aria-label={day}>
              <h2 className="nt-day-label">{day}</h2>
              <ul className="nt-list">
                {rows.map((n, i) => {
                  const t = TEMPLATE[n.template]
                  const target = notificationTarget(n)
                  const unreadRow = !n.readAt
                  return (
                    <li key={n.id} className="nt-row" data-unread={unreadRow || undefined} style={{ ['--i' as string]: Math.min(i, 7) }}>
                      <span className="nt-icon" aria-hidden="true"><Icon icon={t?.icon ?? Inbox} size={14} /></span>
                      <div className="nt-text">
                        <button type="button" className="nt-subject" onClick={() => open(n)}>
                          {unreadRow && <span className="sr-only">Unread: </span>}
                          {n.subject}
                          {target && <span className="sr-only"> (opens {target.startsWith('/interviews') ? 'the interview' : 'the related page'})</span>}
                        </button>
                        <p className="nt-body">{n.body}</p>
                        <p className="nt-meta">{t?.label ?? 'Notice'} · <time dateTime={n.createdAt}>{relativeTime(n.createdAt)}</time></p>
                      </div>
                      <span className="nt-end" aria-hidden="true">{unreadRow ? <span className="nt-dot" /> : null}{target && <Icon icon={ChevronRight} size={16} />}</span>
                    </li>
                  )
                })}
              </ul>
            </section>
          ))}
          {list.data.totalElements > PAGE_SIZE && (
            <Pagination page={list.data.page + 1} pageSize={PAGE_SIZE} total={list.data.totalElements} onPageChange={p => search.set({ page: p - 1, filter: filter === 'all' ? null : filter })} />
          )}
        </div>
      )}
    </div>
  )
}
