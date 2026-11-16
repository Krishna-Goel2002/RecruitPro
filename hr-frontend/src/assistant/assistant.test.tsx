import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ActionBlock, AssistantMessage, Conversation } from '../api/assistantTypes'
import { ApiError } from '../api/client'
import { renderApp } from '../test/render'
import { AssistantProvider } from './AssistantProvider'
import { AskButton, AssistantPanel } from './AssistantPanel'
import { Composer, ConversationView } from './Conversation'
import { pageContext, suggestionsFor } from './pageContext'

const api = vi.hoisted(() => ({
  create: vi.fn(),
  list: vi.fn(),
  get: vi.fn(),
  send: vi.fn(),
  confirm: vi.fn(),
  cancel: vi.fn(),
  remove: vi.fn(),
}))
vi.mock('../api/assistant', async importOriginal => ({ ...(await importOriginal<typeof import('../api/assistant')>()), assistantApi: api }))

const NOW = new Date().toISOString()
const later = () => new Date(Date.now() + 20 * 60_000).toISOString()
const msg = (id: number, role: 'user' | 'assistant', text: string, blocks: AssistantMessage['blocks'] = []): AssistantMessage => ({ id, role, text, blocks, planner: role === 'assistant' ? 'offline' : null, createdAt: NOW })
const shortlist = (): ActionBlock => ({ kind: 'action', actionId: 31, action: 'decide_application', summary: 'Shortlist A-3014', preview: 'Moves A-3014 from Submitted to Shortlisted.', status: 'PENDING', expiresAt: later() })

function seed(conversation: Conversation) {
  window.sessionStorage.setItem('rp-assistant-conversation', String(conversation.id))
  api.get.mockResolvedValue(conversation)
}

beforeEach(() => {
  Object.values(api).forEach(fn => fn.mockReset())
  window.sessionStorage.clear()
})

describe('Composer', () => {
  it('Enter sends and clears; Shift + Enter does not send', () => {
    const onSend = vi.fn()
    renderApp(<Composer onSend={onSend} />)
    const box = screen.getByLabelText('Message the assistant')
    fireEvent.change(box, { target: { value: 'What needs my attention?' } })
    fireEvent.keyDown(box, { key: 'Enter', shiftKey: true })
    expect(onSend).not.toHaveBeenCalled()
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(onSend).toHaveBeenCalledWith('What needs my attention?')
    expect(box).toHaveValue('')
  })

  it('does not send empty text, while busy, or during IME composition', () => {
    const onSend = vi.fn()
    const { rerender } = renderApp(<Composer onSend={onSend} />)
    const box = screen.getByLabelText('Message the assistant')
    fireEvent.keyDown(box, { key: 'Enter' })
    fireEvent.change(box, { target: { value: '   ' } })
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(onSend).not.toHaveBeenCalled()
    fireEvent.change(box, { target: { value: 'こんにちは' } })
    fireEvent.keyDown(box, { key: 'Enter', isComposing: true })
    expect(onSend).not.toHaveBeenCalled()
    rerender(<Composer onSend={onSend} busy />)
    fireEvent.keyDown(screen.getByLabelText('Message the assistant'), { key: 'Enter' })
    expect(onSend).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()
  })

  it('the send button submits too', () => {
    const onSend = vi.fn()
    renderApp(<Composer onSend={onSend} />)
    fireEvent.change(screen.getByLabelText('Message the assistant'), { target: { value: 'Post a new job' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(onSend).toHaveBeenCalledWith('Post a new job')
  })
})

describe('page context and suggestions', () => {
  it('derives the context from the route', () => {
    expect(pageContext('/jobs/101/applicants/3014')).toEqual({ page: 'applicant', jobId: 101, applicationId: 3014 })
    expect(pageContext('/jobs/101')).toEqual({ page: 'job', jobId: 101 })
    expect(pageContext('/jobs')).toEqual({ page: 'jobs' })
    expect(pageContext('/admin/users')).toEqual({ page: 'admin' })
    expect(pageContext('/interviews/3')).toEqual({ page: 'interviews' })
    expect(pageContext('/interviews')).toEqual({ page: 'interviews' })
    expect(pageContext('/notifications')).toEqual({ page: 'other' })
  })

  it('suggests per page and role; interviewers only get interview prompts', () => {
    const manager = (c: string) => c !== 'admin'
    expect(suggestionsFor({ page: 'job', jobId: 1 }, manager as never).map(s => s.label)).toContain("Summarise this job's applicants")
    expect(suggestionsFor({ page: 'jobs' }, manager as never).map(s => s.label)).toContain('Post a new job')
    const interviewer = (c: string) => c === 'interviews.view' || c === 'account'
    const labels = suggestionsFor({ page: 'other' }, interviewer as never).map(s => s.label)
    expect(labels).not.toContain('Post a new job')
    expect(labels).toContain('My interviews this week')
  })
})

describe('conversation flow (mocked API)', () => {
  it('first message creates a conversation and sends the page context', async () => {
    api.create.mockResolvedValue({ id: 5, createdAt: NOW })
    api.send.mockResolvedValue({ messages: [msg(1, 'user', 'Summarise'), msg(2, 'assistant', '**38 applicants**, 5 ranked.')] })
    api.get.mockResolvedValue({ id: 5, title: null, messages: [] })
    renderApp(<AssistantProvider><ConversationView /></AssistantProvider>, { route: '/jobs/101', path: '/jobs/:jobId' })
    fireEvent.click(screen.getByRole('button', { name: "Summarise this job's applicants" }))
    await waitFor(() => expect(api.send).toHaveBeenCalledWith(5, { text: "Summarise this job's applicants", context: { page: 'job', jobId: 101 } }))
    expect(await screen.findByText('38 applicants')).toBeInTheDocument()
    expect(screen.getByRole('log', { name: 'Conversation' })).toBeInTheDocument()
  })

  it('a failed send shows the error with Try again', async () => {
    api.create.mockResolvedValue({ id: 6, createdAt: NOW })
    api.send.mockRejectedValueOnce(new ApiError(503, 'Unavailable')).mockResolvedValueOnce({ messages: [msg(1, 'user', 'hi'), msg(2, 'assistant', 'Hello.')] })
    api.get.mockResolvedValue({ id: 6, title: null, messages: [] })
    renderApp(<AssistantProvider><ConversationView /></AssistantProvider>, { route: '/jobs' })
    const box = screen.getByLabelText('Message the assistant')
    fireEvent.change(box, { target: { value: 'hi' } })
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(await screen.findByText('Unavailable')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Hello.')).toBeInTheDocument()
  })

  it('Confirm calls the confirm endpoint once and shows the action as done', async () => {
    seed({ id: 9, title: 'Shortlist', messages: [msg(1, 'user', 'Shortlist A-3014'), msg(2, 'assistant', 'Here is what will change.', [shortlist()])] })
    api.confirm.mockResolvedValue({ action: { ...shortlist(), status: 'CONFIRMED' }, result: { kind: 'application', id: 3014, link: '/jobs/101/applicants/3014' }, message: msg(3, 'assistant', 'Done: A-3014 is shortlisted.') })
    renderApp(<AssistantProvider><ConversationView /></AssistantProvider>, { route: '/jobs/101', path: '/jobs/:jobId' })
    fireEvent.click(await screen.findByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(api.confirm).toHaveBeenCalledWith(31, {}))
    expect(await screen.findByText('Done: A-3014 is shortlisted.')).toBeInTheDocument()
    expect(screen.getByText('Done')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirm' })).toBeNull()
    expect(api.confirm).toHaveBeenCalledTimes(1)
  })

  it('Cancel calls the cancel endpoint and nothing runs', async () => {
    seed({ id: 9, title: null, messages: [msg(2, 'assistant', 'Proposed.', [shortlist()])] })
    api.cancel.mockResolvedValue({ action: { ...shortlist(), status: 'CANCELLED' } })
    renderApp(<AssistantProvider><ConversationView /></AssistantProvider>, { route: '/jobs' })
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(api.cancel).toHaveBeenCalledWith(31))
    expect(await screen.findByText('Cancelled')).toBeInTheDocument()
    expect(api.confirm).not.toHaveBeenCalled()
  })

  it('409 on confirm (already done or expired) refreshes the conversation and explains', async () => {
    seed({ id: 9, title: null, messages: [msg(2, 'assistant', 'Proposed.', [shortlist()])] })
    api.confirm.mockRejectedValue(new ApiError(409, 'Expired'))
    renderApp(<AssistantProvider><ConversationView /></AssistantProvider>, { route: '/jobs' })
    fireEvent.click(await screen.findByRole('button', { name: 'Confirm' }))
    expect(await screen.findByText('This action is no longer pending')).toBeInTheDocument()
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2))
  })

  it('job preview: Save as draft sends mode DRAFT, Publish sends mode PUBLISH', async () => {
    const create: ActionBlock = { kind: 'action', actionId: 'c1', action: 'create_job', summary: 'Create “Data Analyst”', status: 'PENDING', expiresAt: later() }
    const conversation: Conversation = { id: 9, title: null, messages: [msg(2, 'assistant', 'Here is the posting.', [{ kind: 'job_preview', draft: { title: 'Data Analyst', requirements: [] } }, create])] }
    seed(conversation)
    api.confirm.mockResolvedValue({ action: { ...create, status: 'CONFIRMED' }, result: { kind: 'job', id: 55, link: '/jobs/55' } })
    const first = renderApp(<AssistantProvider><ConversationView /></AssistantProvider>, { route: '/jobs' })
    fireEvent.click(await screen.findByRole('button', { name: 'Save as draft' }))
    await waitFor(() => expect(api.confirm).toHaveBeenCalledWith('c1', { mode: 'DRAFT' }))
    expect(await screen.findByText('Saved as a draft')).toBeInTheDocument()
    first.unmount()

    api.confirm.mockClear()
    seed(conversation)
    renderApp(<AssistantProvider><ConversationView /></AssistantProvider>, { route: '/jobs' })
    fireEvent.click(await screen.findByRole('button', { name: 'Publish' }))
    fireEvent.click(screen.getByRole('button', { name: 'Publish now' }))
    await waitFor(() => expect(api.confirm).toHaveBeenCalledWith('c1', { mode: 'PUBLISH' }))
    expect(await screen.findByText('Published')).toBeInTheDocument()
  })
})

describe('panel', () => {
  it('Ask opens the panel and focuses the composer; Escape closes it and returns focus', async () => {
    renderApp(<AssistantProvider><AskButton /><AssistantPanel /></AssistantProvider>, { route: '/jobs' })
    const ask = screen.getByRole('button', { name: /Ask/ })
    ask.focus()
    fireEvent.click(ask)
    const panel = await screen.findByRole('complementary', { name: 'Assistant' })
    await waitFor(() => expect(screen.getByLabelText('Message the assistant')).toHaveFocus())
    expect(ask).toHaveAttribute('aria-expanded', 'true')
    fireEvent.keyDown(panel, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('complementary', { name: 'Assistant' })).toBeNull())
    expect(ask).toHaveFocus()
  })

  it('Ctrl + K toggles the panel', async () => {
    renderApp(<AssistantProvider><AssistantPanel /></AssistantProvider>, { route: '/jobs' })
    act(() => { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true })) })
    expect(await screen.findByRole('complementary', { name: 'Assistant' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Message the assistant')).toHaveFocus())
    act(() => { window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true })) })
    await waitFor(() => expect(screen.queryByRole('complementary', { name: 'Assistant' })).toBeNull())
  })

  it('the resize handle is keyboard operable', async () => {
    renderApp(<AssistantProvider><AskButton /><AssistantPanel /></AssistantProvider>, { route: '/jobs' })
    fireEvent.click(screen.getByRole('button', { name: /Ask/ }))
    const handle = await screen.findByRole('separator', { name: 'Resize the assistant panel' })
    const before = Number(handle.getAttribute('aria-valuenow'))
    fireEvent.keyDown(handle, { key: 'ArrowLeft' })
    expect(Number(handle.getAttribute('aria-valuenow'))).toBe(before + 24)
  })
})
