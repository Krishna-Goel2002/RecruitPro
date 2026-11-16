import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { skipToken, useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import { useLocation } from 'react-router-dom'
import { assistantApi } from '../api/assistant'
import type {
  ActionBlock, AssistantContext, AssistantId, AssistantMessage, Block, ConfirmRequest, Conversation,
} from '../api/assistantTypes'
import { errorMessage, isApiError, waitText } from '../api/client'
import { qk } from '../api/queryKeys'
import type { JobStaff } from '../api/types'
import { useToast } from '../design/components'
import { pageContext } from './pageContext'

const STORE_KEY = 'rp-assistant-conversation'

function readStored(): AssistantId | null {
  try {
    return window.sessionStorage.getItem(STORE_KEY)
  } catch {
    return null
  }
}
function writeStored(id: AssistantId | null) {
  try {
    if (id == null) window.sessionStorage.removeItem(STORE_KEY)
    else window.sessionStorage.setItem(STORE_KEY, String(id))
  } catch {
    /* ignore */
  }
}

/** Replace one action block (by actionId) in every message of a conversation. */
export function patchAction(conversation: Conversation, actionId: AssistantId, patch: Partial<ActionBlock>): Conversation {
  return {
    ...conversation,
    messages: conversation.messages.map(m => ({
      ...m,
      blocks: m.blocks.map((b: Block) => (b.kind === 'action' && String(b.actionId) === String(actionId) ? { ...b, ...patch } : b)),
    })),
  }
}

export interface PendingSend {
  text: string
  startedAt: number
}

export interface AssistantApi {
  /** Panel visibility (the full page at /assistant shows the same conversation). */
  open: boolean
  openPanel: () => void
  closePanel: () => void
  togglePanel: () => void
  /** Open the panel (unless on /assistant) and send a prompt. */
  ask: (prompt: string) => void

  conversationId: AssistantId | null
  selectConversation: (id: AssistantId | null) => void
  newConversation: () => void

  /** Context sent with the next message, and a readable label for it. */
  context: AssistantContext
  contextLabel: string | null
  /** On /assistant: the context of the page the user came from, until cleared. */
  clearContext: () => void

  conversation: UseQueryResult<Conversation>
  messages: AssistantMessage[]
  pending: PendingSend | null
  sendError: string | null
  send: (text: string) => void
  retry: () => void
  dismissError: () => void

  confirmAction: (block: ActionBlock, body?: ConfirmRequest) => Promise<void>
  cancelAction: (block: ActionBlock) => Promise<void>
  busyAction: string | null

  /** The composer registers itself so Ctrl/⌘ + K and "Ask" can focus it. */
  composerRef: React.MutableRefObject<HTMLTextAreaElement | null>
  focusComposer: () => void
}

const Ctx = createContext<AssistantApi | null>(null)

export function useAssistant(): AssistantApi {
  const value = useContext(Ctx)
  if (!value) throw new Error('useAssistant outside AssistantProvider')
  return value
}

/** Optional variant for components that may render outside the provider (tests, gallery). */
export function useOptionalAssistant(): AssistantApi | null {
  return useContext(Ctx)
}

function modalOpen(): boolean {
  try {
    return document.querySelector('dialog[open]:modal') != null
  } catch {
    return document.querySelector('dialog[open]') != null
  }
}

export const isMac = () => typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent)

/**
 * Assistant state shared by the top-bar button, the side panel and the /assistant page: which conversation is open,
 * the page context, the in-flight message, and the confirm / cancel calls for proposed actions. Also owns the global
 * Ctrl/⌘ + K shortcut.
 */
export function AssistantProvider({ children }: { children: ReactNode }) {
  const location = useLocation()
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const onPage = location.pathname === '/assistant'

  const [open, setOpen] = useState(false)
  const [conversationId, setConversationId] = useState<AssistantId | null>(() => readStored())
  const [pending, setPending] = useState<PendingSend | null>(null)
  const [sendError, setSendError] = useState<string | null>(null)
  const [failedText, setFailedText] = useState<string | null>(null)
  const [busyAction, setBusyAction] = useState<string | null>(null)
  const composerRef = useRef<HTMLTextAreaElement | null>(null)

  // Context: the current page, or (on /assistant) the last page before it until cleared.
  const routeContext = useMemo(() => pageContext(location.pathname), [location.pathname])
  const [carried, setCarried] = useState<AssistantContext>({ page: 'other' })
  useEffect(() => {
    if (!onPage) setCarried(routeContext)
  }, [onPage, routeContext])
  const context = onPage ? carried : routeContext

  // Subscribe to the page's job (never fetches here: the page loads it), so the label switches from "Job #12" to the
  // title as soon as the job arrives.
  const contextJob = useQuery<JobStaff>({ queryKey: qk.job(context.jobId ?? 0), queryFn: skipToken })
  const jobTitle = context.jobId ? contextJob.data?.title : undefined
  const contextLabel = useMemo(() => {
    if (context.page === 'applicant' && context.applicationId) {
      return [`A-${context.applicationId}`, jobTitle].filter(Boolean).join(' · ')
    }
    if (context.page === 'job' && context.jobId) return jobTitle ?? `Job #${context.jobId}`
    if (context.page === 'jobs') return 'Jobs'
    if (context.page === 'admin') return 'Administration'
    if (context.page === 'interviews') return 'My interviews'
    return null
  }, [context.page, context.jobId, context.applicationId, jobTitle])

  useEffect(() => writeStored(conversationId), [conversationId])

  const conversation = useQuery({
    queryKey: qk.assistantConversation(conversationId ?? 'none'),
    queryFn: ({ signal }) => assistantApi.get(conversationId!, signal),
    enabled: conversationId != null,
    staleTime: 30_000,
    retry: (count, cause) => !isApiError(cause, 404) && count < 1,
  })
  // A stored conversation that was deleted elsewhere: start fresh instead of showing an error.
  useEffect(() => {
    if (conversation.isError && isApiError(conversation.error, 404)) setConversationId(null)
  }, [conversation.isError, conversation.error])

  const focusComposer = useCallback(() => {
    window.setTimeout(() => composerRef.current?.focus(), 0)
  }, [])

  const sendMutation = useMutation({
    mutationFn: async (text: string) => {
      let id = conversationId
      if (id == null) {
        const created = await assistantApi.create()
        id = created.id
        queryClient.setQueryData<Conversation>(qk.assistantConversation(id), { id, title: null, messages: [] })
        setConversationId(id)
      }
      const response = await assistantApi.send(id, { text, context })
      return { id, response }
    },
    onMutate: text => {
      setSendError(null)
      setFailedText(null)
      setPending({ text, startedAt: Date.now() })
    },
    onSuccess: ({ id, response }) => {
      queryClient.setQueryData<Conversation>(qk.assistantConversation(id), old => ({
        id,
        title: old?.title ?? null,
        messages: [...(old?.messages ?? []), ...(response?.messages ?? [])],
      }))
      void queryClient.invalidateQueries({ queryKey: qk.assistantConversations })
    },
    onError: (cause, text) => {
      const wait = waitText(cause)
      setSendError(
        isApiError(cause, 429)
          ? `You have sent a lot of messages in the last hour.${wait ? ` Try again ${wait}.` : ' Try again later.'}`
          : errorMessage(cause, 'The assistant could not answer. Try again.'),
      )
      setFailedText(text)
    },
    onSettled: () => setPending(null),
  })

  const send = useCallback(
    (raw: string) => {
      const text = raw.trim()
      if (!text || sendMutation.isPending) return
      sendMutation.mutate(text)
    },
    [sendMutation],
  )

  const retry = useCallback(() => {
    if (failedText) sendMutation.mutate(failedText)
  }, [failedText, sendMutation])

  const openPanel = useCallback(() => {
    if (onPage) focusComposer()
    else setOpen(true)
  }, [onPage, focusComposer])
  const closePanel = useCallback(() => setOpen(false), [])
  const togglePanel = useCallback(() => (open ? setOpen(false) : openPanel()), [open, openPanel])
  const ask = useCallback(
    (prompt: string) => {
      if (!onPage) setOpen(true)
      send(prompt)
    },
    [onPage, send],
  )

  const selectConversation = useCallback((id: AssistantId | null) => {
    setSendError(null)
    setFailedText(null)
    setConversationId(id)
  }, [])
  const newConversation = useCallback(() => {
    selectConversation(null)
    focusComposer()
  }, [selectConversation, focusComposer])

  // ------------------------------------------------------------------ proposed actions
  const touchAfterAction = useCallback(() => {
    for (const key of [qk.jobs, ['applicants'], qk.interviews, ['workSamples'], ['interviewKit'], qk.adminDashboard]) {
      void queryClient.invalidateQueries({ queryKey: key as readonly unknown[] })
    }
  }, [queryClient])

  const settleAction = useCallback(
    async (block: ActionBlock, run: () => Promise<{ action?: ActionBlock | null; result?: ActionBlock['result']; message?: AssistantMessage | null }>, verb: 'confirm' | 'cancel') => {
      const cid = conversationId
      setBusyAction(String(block.actionId))
      try {
        const res = await run()
        if (cid != null) {
          queryClient.setQueryData<Conversation>(qk.assistantConversation(cid), old => {
            if (!old) return old
            const patched = patchAction(old, block.actionId, {
              ...(res.action ?? {}),
              status: res.action?.status ?? (verb === 'confirm' ? 'CONFIRMED' : 'CANCELLED'),
              result: res.result ?? null,
            })
            return res.message ? { ...patched, messages: [...patched.messages, res.message] } : patched
          })
        }
        if (verb === 'confirm') {
          touchAfterAction()
          toast({ tone: 'success', title: 'Confirmed', body: block.summary })
        }
      } catch (cause) {
        if (isApiError(cause, 409)) {
          toast({ tone: 'info', title: 'This action is no longer pending', body: 'It was already done, cancelled or has expired. The conversation has been refreshed.' })
          if (cid != null) void queryClient.invalidateQueries({ queryKey: qk.assistantConversation(cid) })
        } else {
          toast({ tone: 'danger', title: verb === 'confirm' ? 'The action did not run' : 'Could not cancel', body: errorMessage(cause) })
        }
      } finally {
        setBusyAction(null)
      }
    },
    [conversationId, queryClient, toast, touchAfterAction],
  )

  const confirmAction = useCallback(
    (block: ActionBlock, body: ConfirmRequest = {}) => settleAction(block, () => assistantApi.confirm(block.actionId, body), 'confirm'),
    [settleAction],
  )
  const cancelAction = useCallback(
    (block: ActionBlock) => settleAction(block, () => assistantApi.cancel(block.actionId), 'cancel'),
    [settleAction],
  )

  // ------------------------------------------------------------------ Ctrl/⌘ + K
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key.toLowerCase() !== 'k' || !(e.ctrlKey || e.metaKey) || e.altKey || e.shiftKey) return
      // A modal dialog owns the keyboard.
      if (modalOpen()) return
      e.preventDefault()
      if (onPage) {
        focusComposer()
        return
      }
      const inPanel = composerRef.current && document.activeElement && composerRef.current.closest('.as-panel')?.contains(document.activeElement)
      if (open && inPanel) setOpen(false)
      else {
        setOpen(true)
        focusComposer()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onPage, open, focusComposer])

  // Leaving for the full page closes the panel; the page shows the same conversation.
  useEffect(() => {
    if (onPage) setOpen(false)
  }, [onPage])

  const messages = conversationId != null ? conversation.data?.messages ?? [] : []

  const value: AssistantApi = {
    open,
    openPanel,
    closePanel,
    togglePanel,
    ask,
    conversationId,
    selectConversation,
    newConversation,
    context,
    contextLabel,
    clearContext: () => setCarried({ page: 'other' }),
    conversation,
    messages,
    pending,
    sendError,
    send,
    retry,
    dismissError: () => setSendError(null),
    confirmAction,
    cancelAction,
    busyAction,
    composerRef,
    focusComposer,
  }
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}
