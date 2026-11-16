import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { assistantApi, conversationItems } from '../api/assistant'
import type { AssistantId, ConversationSummary } from '../api/assistantTypes'
import { errorMessage } from '../api/client'
import { qk } from '../api/queryKeys'
import { Button, Dialog, IconButton, PageHeader, Skeleton, useToast } from '../design/components'
import { History, SquarePen, Trash2, X } from '../design/icons'
import { relativeTime } from '../design/lib/format'
import { useAssistant } from './AssistantProvider'
import { ConversationView, shortcutLabel } from './Conversation'
import './assistant.css'

/**
 * /assistant: the full-page assistant. Conversation history on the left (new, open, delete), the open conversation on
 * the right; `?c=` keeps the open conversation in the URL. The context of the page the user came from is carried over
 * (shown above the composer) until cleared.
 */
export function AssistantPage() {
  const a = useAssistant()
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [params, setParams] = useSearchParams()
  const [toDelete, setToDelete] = useState<ConversationSummary | null>(null)
  const [historyOpen, setHistoryOpen] = useState(false)

  // URL → selection on arrival; selection → URL afterwards.
  const fromUrl = params.get('c')
  useEffect(() => {
    if (fromUrl && fromUrl !== String(a.conversationId ?? '')) a.selectConversation(fromUrl)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  useEffect(() => {
    const want = a.conversationId != null ? String(a.conversationId) : null
    if (want !== fromUrl) {
      setParams(cur => {
        const next = new URLSearchParams(cur)
        if (want) next.set('c', want)
        else next.delete('c')
        return next
      }, { replace: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [a.conversationId])

  const list = useQuery({ queryKey: qk.assistantConversations, queryFn: ({ signal }) => assistantApi.list(0, signal) })
  const items = conversationItems(list.data)

  const remove = useMutation({
    mutationFn: (id: AssistantId) => assistantApi.remove(id),
    onSuccess: (_r, id) => {
      setToDelete(null)
      if (String(id) === String(a.conversationId ?? '')) a.selectConversation(null)
      queryClient.removeQueries({ queryKey: qk.assistantConversation(id) })
      void queryClient.invalidateQueries({ queryKey: qk.assistantConversations })
      toast({ tone: 'success', title: 'Conversation deleted' })
    },
  })

  const current = items.find(c => String(c.id) === String(a.conversationId ?? ''))
  const title = current?.title || (a.conversationId != null ? 'Conversation' : 'New conversation')

  const history = (
    <nav className="as-history" aria-label="Conversation history">
      <div className="as-history-head">
        <h2 className="as-history-title">History</h2>
        <Button size="sm" icon={SquarePen} onClick={() => { a.newConversation(); setHistoryOpen(false) }}>New</Button>
      </div>
      {list.isPending ? (
        <div className="as-history-skel">{Array.from({ length: 5 }, (_, i) => <Skeleton key={i} height={36} />)}</div>
      ) : list.isError ? (
        <p className="as-muted">History could not be loaded. <button type="button" className="as-textbtn" onClick={() => void list.refetch()}>Try again</button></p>
      ) : items.length === 0 ? (
        <p className="as-muted">Your conversations appear here. They are kept for 180 days and only you can see them.</p>
      ) : (
        <ul className="as-history-list ui-stagger" role="list">
          {items.map(c => {
            const active = String(c.id) === String(a.conversationId ?? '')
            return (
              <li key={String(c.id)} className="as-history-item" data-active={active || undefined}>
                <button
                  type="button"
                  className="as-history-open"
                  aria-current={active ? 'true' : undefined}
                  onClick={() => { a.selectConversation(c.id); setHistoryOpen(false) }}
                >
                  <span className="as-history-name">{c.title || 'Untitled conversation'}</span>
                  <span className="as-history-when">{relativeTime(c.updatedAt)}</span>
                </button>
                <IconButton size="sm" label={`Delete “${c.title || 'Untitled conversation'}”`} icon={Trash2} className="as-history-del" onClick={() => { remove.reset(); setToDelete(c) }} noTooltip />
              </li>
            )
          })}
        </ul>
      )}
    </nav>
  )

  return (
    <div className="as-page" data-width="full">
      <PageHeader
        title="Assistant"
        meta={[<span key="k">Open it from any page with <kbd className="as-kbd">{shortcutLabel()}</kbd></span>, <span key="c">Always asks before changing anything</span>]}
        actions={
          <span className="as-page-actions">
            <Button className="as-history-toggle" icon={History} onClick={() => setHistoryOpen(o => !o)} aria-expanded={historyOpen} aria-controls="as-history-pane">
              History
            </Button>
            <Button icon={SquarePen} onClick={a.newConversation}>New conversation</Button>
          </span>
        }
      />
      <div className="as-page-grid">
        <aside id="as-history-pane" className="as-page-history" data-open={historyOpen || undefined}>{history}</aside>
        <section className="as-page-main" aria-label={title}>
          {a.context.page !== 'other' && a.contextLabel && (
            <p className="as-carried">
              Answers use the context of <strong>{a.contextLabel}</strong>.
              <IconButton size="sm" label="Clear the context" icon={X} onClick={a.clearContext} noTooltip />
            </p>
          )}
          <ConversationView />
        </section>
      </div>

      <Dialog
        open={toDelete != null}
        onClose={() => setToDelete(null)}
        title="Delete this conversation?"
        description="The messages are removed for good. Anything the assistant already did stays done and stays in the audit log."
        size="sm"
        alert
        busy={remove.isPending}
        footer={
          <>
            <Button onClick={() => setToDelete(null)} autoFocus disabled={remove.isPending}>Cancel</Button>
            <Button variant="danger" loading={remove.isPending} onClick={() => toDelete && remove.mutate(toDelete.id)}>Delete</Button>
          </>
        }
      >
        {remove.isError && <p className="as-action-error">{errorMessage(remove.error)}</p>}
        <p className="as-muted">“{toDelete?.title || 'Untitled conversation'}”</p>
      </Dialog>
    </div>
  )
}
