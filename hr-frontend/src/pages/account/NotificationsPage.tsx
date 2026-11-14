import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { accountApi } from '../../api/account'
import { qk } from '../../api/queryKeys'
import { PageHeader } from '../../components/Layout'
import { useNotices } from '../../components/Notices'
import { Pagination } from '../../components/Pagination'
import { EmptyPanel, ErrorPanel, FormError, LoadingPanel } from '../../components/States'
import { formatDateTime } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'

const PAGE_SIZE = 20

/** Readable name per notification template (WP-BE-A templates plus WP-BE-C's INTERVIEW_CANCELLED). */
const TEMPLATE_LABELS: Record<string, string> = {
  INTERVIEW_ASSIGNED: 'Interview assigned',
  INTERVIEW_SCHEDULED: 'Interview scheduled',
  INTERVIEW_CANCELLED: 'Interview cancelled',
  APPLICATION_RECEIVED: 'Application received',
  APPLICATION_STATUS_CHANGED: 'Application update',
  WORK_SAMPLE_ASSIGNED: 'Work sample assigned',
}

/** /notifications — the signed-in staff member's in-app inbox (`/me/notifications`). Every staff role. */
export function NotificationsPage() {
  usePageTitle('Notifications')
  const queryClient = useQueryClient()
  const notices = useNotices()
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0)
  const list = useQuery({
    queryKey: qk.notificationPage(page),
    queryFn: ({ signal }) => accountApi.notifications(page, PAGE_SIZE, signal),
    placeholderData: keepPreviousData,
  })
  const unread = useQuery({ queryKey: qk.unreadCount, queryFn: ({ signal }) => accountApi.unreadCount(signal) })
  const unreadCount = unread.data?.count ?? 0

  const refresh = () => { void queryClient.invalidateQueries({ queryKey: qk.notifications }) }
  const markRead = useMutation({ mutationFn: (id: number) => accountApi.markRead(id), onSuccess: refresh })
  const markAll = useMutation({
    mutationFn: () => accountApi.markAllRead(),
    onSuccess: () => { notices.success('All notifications marked as read.'); refresh() },
  })

  return (
    <>
      <PageHeader
        eyebrow="ACCOUNT"
        title="Notifications"
        subtitle={unreadCount > 0 ? `${unreadCount} unread` : 'You are up to date.'}
        actions={
          <button type="button" className="outline-button" onClick={() => markAll.mutate()} disabled={markAll.isPending || unreadCount === 0}>
            {markAll.isPending ? 'Marking…' : 'Mark all as read'}
          </button>
        }
      />
      <FormError error={markAll.error ?? markRead.error} />
      {list.isPending ? <LoadingPanel label="Loading notifications…" /> : list.isError ? (
        <ErrorPanel error={list.error} title="We couldn’t load your notifications" onRetry={() => void list.refetch()} />
      ) : list.data.content.length === 0 ? (
        <section className="jobs-card"><EmptyPanel title="No notifications yet">Interview assignments, reschedules and cancellations appear here.</EmptyPanel></section>
      ) : (
        <section className="jobs-card" aria-label="Notifications">
          <ul className={`card-list notification-list${list.isPlaceholderData ? ' is-stale' : ''}`}>
            {list.data.content.map(item => {
              const isUnread = item.readAt == null
              return (
                <li key={item.id} className={`item-card notification-item${isUnread ? ' unread' : ''}`}>
                  <div className="item-card-head">
                    <div>
                      <h2 className="notification-subject">
                        {isUnread && <span className="unread-dot" aria-hidden="true" />}
                        {isUnread && <span className="sr-only">Unread: </span>}
                        {item.subject}
                      </h2>
                      <p className="item-meta">
                        {TEMPLATE_LABELS[item.template] ?? 'Notice'} · <time dateTime={item.createdAt}>{formatDateTime(item.createdAt)}</time>
                      </p>
                    </div>
                    {isUnread && (
                      <button
                        type="button"
                        className="outline-button"
                        onClick={() => markRead.mutate(item.id)}
                        disabled={markRead.isPending && markRead.variables === item.id}
                      >
                        Mark as read<span className="sr-only">: {item.subject}</span>
                      </button>
                    )}
                  </div>
                  <p className="notification-body">{item.body}</p>
                </li>
              )
            })}
          </ul>
          <Pagination page={list.data} label="notifications" onPage={next => setParams(next > 0 ? { page: String(next) } : {})} />
        </section>
      )}
    </>
  )
}
