import { useSearchParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { errorMessage } from '../api/client'
import { meApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import { EmptyState, ErrorBlock, LoadingBlock, Pagination } from '../components/Feedback'
import { useNotify } from '../components/Notices'
import { PageHeader } from '../components/PageHeader'
import { formatDateTime, formatRelative } from '../lib/format'

const PAGE_SIZE = 20

export function NotificationsPage() {
  const queryClient = useQueryClient()
  const notify = useNotify()
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0)
  const notifications = useQuery({
    queryKey: queryKeys.notifications(page),
    queryFn: () => meApi.notifications(page, PAGE_SIZE),
    placeholderData: keepPreviousData,
  })
  const unread = useQuery({ queryKey: queryKeys.unreadCount, queryFn: meApi.unreadCount })

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.notificationsAll })
    void queryClient.invalidateQueries({ queryKey: queryKeys.unreadCount })
  }

  const markRead = useMutation({
    mutationFn: (id: number) => meApi.markRead(id),
    onSuccess: refresh,
    onError: error => notify('error', errorMessage(error)),
  })
  const markAll = useMutation({
    mutationFn: meApi.markAllRead,
    onSuccess: () => {
      notify('success', 'All notifications marked as read.')
      refresh()
    },
    onError: error => notify('error', errorMessage(error)),
  })

  const unreadCount = unread.data?.count ?? 0

  return (
    <>
      <PageHeader
        eyebrow="CANDIDATE PORTAL"
        title="Notifications"
        description={unreadCount > 0 ? `${unreadCount} unread` : 'You are up to date.'}
        actions={
          <button type="button" className="quiet-button" onClick={() => markAll.mutate()} disabled={markAll.isPending || unreadCount === 0}>
            {markAll.isPending ? 'Marking…' : 'Mark all as read'}
          </button>
        }
      />
      {notifications.isPending ? (
        <LoadingBlock label="Loading notifications…" />
      ) : notifications.isError ? (
        <ErrorBlock error={notifications.error} onRetry={() => void notifications.refetch()} />
      ) : notifications.data.content.length === 0 ? (
        <EmptyState icon="◔" title="No notifications yet">
          Updates about your applications, work samples and interviews appear here.
        </EmptyState>
      ) : (
        <ul className={`plain-list notification-list${notifications.isPlaceholderData ? ' is-stale' : ''}`}>
          {notifications.data.content.map(item => {
            const isUnread = item.readAt == null
            return (
              <li key={item.id} className={`notification${isUnread ? ' unread' : ''}`}>
                <div className="notification-head">
                  <h2 className="card-title">
                    {isUnread && <span className="unread-dot" aria-hidden="true" />}
                    {isUnread && <span className="visually-hidden">Unread: </span>}
                    {item.subject}
                  </h2>
                  <time dateTime={item.createdAt} title={formatDateTime(item.createdAt)} className="muted">
                    {formatRelative(item.createdAt)}
                  </time>
                </div>
                <p className="notification-body">{item.body}</p>
                {isUnread && (
                  <button
                    type="button"
                    className="text-button inline"
                    onClick={() => markRead.mutate(item.id)}
                    disabled={markRead.isPending && markRead.variables === item.id}
                  >
                    Mark as read<span className="visually-hidden">: {item.subject}</span>
                  </button>
                )}
              </li>
            )
          })}
        </ul>
      )}
      {notifications.data && (
        <Pagination
          label="Notification pages"
          page={page}
          totalPages={notifications.data.totalPages}
          totalElements={notifications.data.totalElements}
          disabled={notifications.isFetching}
          onPageChange={next => setParams(next > 0 ? { page: String(next) } : {})}
        />
      )}
    </>
  )
}
