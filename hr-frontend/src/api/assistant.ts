import { api } from './client'
import type {
  AssistantId, CancelResponse, ConfirmRequest, ConfirmResponse, Conversation, ConversationSummary, SendMessageRequest, SendMessageResponse,
} from './assistantTypes'
import type { Page } from './types'

const id = (value: AssistantId) => encodeURIComponent(String(value))

/** Normalise the conversation list: the contract says "?page"; accept a Spring page or a bare array. */
export function conversationItems(data: Page<ConversationSummary> | ConversationSummary[] | null | undefined): ConversationSummary[] {
  if (!data) return []
  return Array.isArray(data) ? data : data.content ?? []
}

/**
 * HR assistant (ASSISTANT_PLAN §3.2). Every write the assistant proposes is an action that runs only through `confirm`.
 * `confirm` sends `{}` except for create_job, where the preview card's choice is sent as `{mode: 'DRAFT' | 'PUBLISH'}`.
 */
export const assistantApi = {
  create: () => api.post<{ id: AssistantId; createdAt: string }>('/assistant/conversations', {}),
  list: (page = 0, signal?: AbortSignal) =>
    api.get<Page<ConversationSummary> | ConversationSummary[]>('/assistant/conversations', { page }, signal),
  get: (conversationId: AssistantId, signal?: AbortSignal) => api.get<Conversation>(`/assistant/conversations/${id(conversationId)}`, undefined, signal),
  send: (conversationId: AssistantId, body: SendMessageRequest) =>
    api.post<SendMessageResponse>(`/assistant/conversations/${id(conversationId)}/messages`, body),
  confirm: (actionId: AssistantId, body: ConfirmRequest = {}) => api.post<ConfirmResponse>(`/assistant/actions/${id(actionId)}/confirm`, body),
  cancel: (actionId: AssistantId) => api.post<CancelResponse>(`/assistant/actions/${id(actionId)}/cancel`, {}),
  remove: (conversationId: AssistantId) => api.delete<null>(`/assistant/conversations/${id(conversationId)}`),
}
