import { Link, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { errorMessage } from '../api/client'
import { meApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { AppNotification, Page, UnreadCount } from '../api/types'
import { ErrorState } from '../components/Feedback'
import { useNotify } from '../components/Notices'
import { Button, EmptyState, IconButton, LoadingRegion, PageHeader, Pagination, Skeleton } from '../design/components'
import { Check, Icon, Inbox } from '../design/icons'
import { formatDate, relativeTime } from '../design/lib/format'
import { notificationTarget } from '../lib/notificationLink'
import { recentApplicationsQuery, workSamplesQuery } from '../lib/queries'
import './common.css'
import './inbox/inbox.css'

const PAGE_SIZE = 20

function isToday(iso: string): boolean {
  const d = new Date(iso)
  const n = new Date()
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate()
}

/**
 * Inbox (UX X-11): every row links to its subject (application, work sample, interview, results to review);
 * opening it marks it read at once (optimistic). Grouped Today / Earlier.
 */
export function NotificationsPage() {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0)
  const key = queryKeys.notifications(page)
  const notifications = useQuery({ queryKey: key, queryFn: () => meApi.notifications(page, PAGE_SIZE), placeholderData: keepPreviousData })
  const unread = useQuery({ queryKey: queryKeys.unreadCount, queryFn: meApi.unreadCount })
  const applications = useQuery(recentApplicationsQuery)
  const samples = useQuery(workSamplesQuery)

  /** Optimistic read state on this page and the unread badge; rolled back on error. */
  function markLocally(ids: number[] | 'all') {
    const at = new Date().toISOString()
    const prevPage = queryClient.getQueryData<Page<AppNotification>>(key)
    const prevCount = queryClient.getQueryData<UnreadCount>(queryKeys.unreadCount)
    queryClient.setQueryData<Page<AppNotification>>(key, current =>
      current ? { ...current, content: current.content.map(n => (ids === 'all' || ids.includes(n.id) ? { ...n, readAt: n.readAt ?? at } : n)) } : current,
    )
    queryClient.setQueryData<UnreadCount>(queryKeys.unreadCount, current =>
      current ? { count: ids === 'all' ? 0 : Math.max(0, current.count - ids.length) } : current,
    )
    return { prevPage, prevCount }
  }
  function rollback(ctx?: { prevPage?: Page<AppNotification>; prevCount?: UnreadCount }) {
    if (ctx?.prevPage) queryClient.setQueryData(key, ctx.prevPage)
    if (ctx?.prevCount) queryClient.setQueryData(queryKeys.unreadCount, ctx.prevCount)
  }
  const settle = () => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.notificationsAll })
    void queryClient.invalidateQueries({ queryKey: queryKeys.unreadCount })
  }

  const markRead = useMutation({
    mutationFn: (id: number) => meApi.markRead(id),
    onMutate: id => markLocally([id]),
    onError: (error, _id, ctx) => {
      rollback(ctx)
      notify('error', errorMessage(error))
    },
    onSettled: settle,
  })
  const markAll = useMutation({
    mutationFn: meApi.markAllRead,
    onMutate: () => markLocally('all'),
    onError: (error, _v, ctx) => {
      rollback(ctx)
      notify('error', errorMessage(error))
    },
    onSuccess: () => notify('success', 'All caught up'),
    onSettled: settle,
  })

  const unreadCount = unread.data?.count ?? 0
  const items = notifications.data?.content ?? []
  const today = items.filter(n => isToday(n.createdAt))
  const earlier = items.filter(n => !isToday(n.createdAt))

  const row = (n: AppNotification) => {
    const target = notificationTarget(n, applications.data?.content, samples.data)
    const isUnread = n.readAt == null
    return (
      <li key={n.id} className={`in-row${target.to ? ' c-linkrow' : ''}`} data-unread={isUnread || undefined}>
        <span className="in-icon">
          <Icon icon={target.icon} size={16} />
        </span>
        <div className="in-main">
          <p className="in-subject">
            {isUnread && <span className="in-dot" aria-hidden="true" />}
            {isUnread && <span className="sr-only">Unread: </span>}
            {target.to ? (
              <Link
                to={target.to}
                className="c-linkrow-link"
                onClick={() => {
                  if (isUnread) markRead.mutate(n.id)
                }}
              >
                {n.subject}
              </Link>
            ) : (
              n.subject
            )}
          </p>
          <p className="in-body">{n.body}</p>
        </div>
        <div className="in-side">
          <time dateTime={n.createdAt} title={formatDate(n.createdAt, { dateStyle: 'medium', timeStyle: 'short' })} className="c-meta numeric">
            {relativeTime(n.createdAt)}
          </time>
          {isUnread && (
            <IconButton className="c-above" label={`Mark as read: ${n.subject}`} icon={Check} size="sm" onClick={() => markRead.mutate(n.id)} />
          )}
        </div>
      </li>
    )
  }

  return (
    <>
      <PageHeader
        title="Inbox"
        meta={[unreadCount > 0 ? `${unreadCount} unread` : 'You are up to date']}
        actions={
          <Button icon={Check} onClick={() => markAll.mutate()} disabled={unreadCount === 0 || markAll.isPending}>
            Mark all as read
          </Button>
        }
      />
      {notifications.isPending ? (
        <LoadingRegion label="Loading notifications">
          {[0, 1, 2].map(i => (
            <div key={i} className="c-skel-row">
              <Skeleton width={320} height={12} />
              <Skeleton width={240} height={10} />
            </div>
          ))}
        </LoadingRegion>
      ) : notifications.isError ? (
        <ErrorState error={notifications.error} onRetry={() => void notifications.refetch()} title="Notifications could not be loaded" />
      ) : items.length === 0 ? (
        <EmptyState icon={Inbox} title="Nothing here yet" description="Updates about your applications, work samples, interviews and checks arrive here, each linked to the thing it is about." />
      ) : (
        <div className={notifications.isPlaceholderData ? 'c-dim' : undefined}>
          {today.length > 0 && (
            <section aria-labelledby="in-today" className="in-group">
              <h2 id="in-today" className="in-group-title">
                Today
              </h2>
              <ul className="c-rows" data-closed="">
                {today.map(row)}
              </ul>
            </section>
          )}
          {earlier.length > 0 && (
            <section aria-labelledby="in-earlier" className="in-group">
              <h2 id="in-earlier" className="in-group-title">
                Earlier
              </h2>
              <ul className="c-rows" data-closed="">
                {earlier.map(row)}
              </ul>
            </section>
          )}
        </div>
      )}
      {notifications.data && notifications.data.totalElements > PAGE_SIZE && (
        <Pagination
          className="j-pagination"
          page={page + 1}
          pageSize={PAGE_SIZE}
          total={notifications.data.totalElements}
          onPageChange={next => setParams(next > 1 ? { page: String(next - 1) } : {})}
        />
      )}
    </>
  )
}
