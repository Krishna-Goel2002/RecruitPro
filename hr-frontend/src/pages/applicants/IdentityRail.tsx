import { useEffect, useRef, useState, type FormEvent, type RefObject } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { errorMessage } from '../../api/client'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type { ApplicantDetail } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { SaveIndicator, type SaveState } from '../../components/SaveIndicator'
import { Avatar, Button, Textarea, Timeline, useToast } from '../../design/components'
import { ArrowUpRight, Download, Eye, EyeOff, Icon } from '../../design/icons'
import { humanize, safeHttpUrl } from '../../lib/format'
import { clearDraft, readDraft, writeDraft } from '../../lib/localDraft'
import { applicationStatusLabel } from '../../components/StatusBadges'
import { HISTORY_WORD } from './model'

/** Right rail (≥ 1200 px) or the "Identity & notes" tab: identity, review note, decision history. */
export function IdentityRail({ jobId, detail, canReveal, onReveal, noteRef }: {
  jobId: number
  detail: ApplicantDetail
  canReveal: boolean
  onReveal: () => void
  noteRef: RefObject<HTMLTextAreaElement>
}) {
  return (
    <div className="ap-rail-stack">
      <IdentityCard jobId={jobId} detail={detail} canReveal={canReveal} onReveal={onReveal} />
      <ReviewNote jobId={jobId} detail={detail} noteRef={noteRef} />
      <History detail={detail} />
    </div>
  )
}

function IdentityCard({ jobId, detail, canReveal, onReveal }: { jobId: number; detail: ApplicantDetail; canReveal: boolean; onReveal: () => void }) {
  const { can } = useAuth()
  const { toast } = useToast()
  const wasBlind = useRef(!detail.identityRevealed)
  const [revealing, setRevealing] = useState(false)
  const [letterOpen, setLetterOpen] = useState(false)
  useEffect(() => {
    if (wasBlind.current && detail.identityRevealed) {
      setRevealing(true)
      const t = window.setTimeout(() => setRevealing(false), 900)
      wasBlind.current = false
      return () => window.clearTimeout(t)
    }
  }, [detail.identityRevealed])

  const download = useMutation({
    mutationFn: () => reviewApi.downloadResume(jobId, detail.applicationId, detail.resumeFilename ?? `application-${detail.applicationId}-resume`),
    onSuccess: filename => toast({ tone: 'success', title: `Downloaded ${filename}`, body: 'The download is recorded in the audit log.' }),
    onError: cause => toast({ tone: 'danger', title: 'The resume could not be downloaded', body: errorMessage(cause) }),
  })

  if (!detail.identityRevealed || !detail.candidate) {
    return (
      <section className="ap-card ap-identity" data-blind="" aria-labelledby="ap-id-h">
        <div className="ap-identity-head">
          <Avatar blind size={32} />
          <div>
            <h2 id="ap-id-h" className="t-h3">Blind review</h2>
            <p className="ap-muted">{detail.displayName}</p>
          </div>
        </div>
        <p className="ap-identity-text">
          Name, contact details, resume file and cover letter stay hidden so the first review rests on evidence alone.
          {detail.applicationStatus === 'SUBMITTED' ? ' Shortlisting reveals them.' : ''}
        </p>
        <ul className="ap-redacted" aria-hidden="true">
          <li><span>Name</span><i style={{ width: '62%' }} /></li>
          <li><span>Email</span><i style={{ width: '78%' }} /></li>
          <li><span>Resume</span><i style={{ width: '48%' }} /></li>
        </ul>
        {canReveal && <Button icon={Eye} onClick={onReveal} block>Reveal identity</Button>}
      </section>
    )
  }

  const c = detail.candidate
  const linkedin = safeHttpUrl(c.linkedinUrl)
  const portfolio = safeHttpUrl(c.portfolioUrl)
  const place = [c.city, c.country].filter(Boolean).join(', ')
  const role = c.currentDesignation ? `${c.currentDesignation}${c.currentCompany ? ` at ${c.currentCompany}` : ''}` : c.currentCompany
  const long = (detail.coverLetter?.length ?? 0) > 280

  return (
    <section className="ap-card ap-identity" data-revealing={revealing || undefined} aria-labelledby="ap-id-h">
      <div className="ap-identity-head ap-stagger" style={{ ['--i' as string]: 0 }}>
        <Avatar name={c.fullName} size={32} />
        <div>
          <h2 id="ap-id-h" className="t-h3">{c.fullName}</h2>
          {role && <p className="ap-muted">{role}</p>}
        </div>
      </div>
      <dl className="ap-facts">
        <div className="ap-stagger" style={{ ['--i' as string]: 1 }}><dt>Email</dt><dd><a href={`mailto:${c.email}`}>{c.email}</a></dd></div>
        {place && <div className="ap-stagger" style={{ ['--i' as string]: 2 }}><dt>Location</dt><dd>{place}</dd></div>}
        {c.yearsOfExperience != null && <div className="ap-stagger" style={{ ['--i' as string]: 3 }}><dt>Experience</dt><dd><span className="numeric">{c.yearsOfExperience}</span> years <span className="ap-muted">(self-reported)</span></dd></div>}
        {(linkedin || portfolio) && (
          <div className="ap-stagger" style={{ ['--i' as string]: 4 }}>
            <dt>Links</dt>
            <dd className="ap-links">
              {linkedin && <a href={linkedin} target="_blank" rel="noopener noreferrer nofollow">LinkedIn <Icon icon={ArrowUpRight} size={12} /><span className="sr-only"> (opens in a new tab)</span></a>}
              {portfolio && <a href={portfolio} target="_blank" rel="noopener noreferrer nofollow">Portfolio <Icon icon={ArrowUpRight} size={12} /><span className="sr-only"> (opens in a new tab)</span></a>}
            </dd>
          </div>
        )}
      </dl>
      {detail.resumeAvailable && can('applicants.downloadResume') && (
        <div className="ap-stagger ap-resume" style={{ ['--i' as string]: 5 }}>
          <Button icon={Download} loading={download.isPending} onClick={() => download.mutate()} block>Download resume</Button>
          {detail.resumeFilename && <p className="ap-resume-name"><span className="mono">{detail.resumeFilename}</span> · the download is audited</p>}
        </div>
      )}
      <div className="ap-letter ap-stagger" style={{ ['--i' as string]: 6 }}>
        <h3 className="t-label">Cover letter</h3>
        {detail.coverLetter ? (
          <>
            <p className="ap-letter-text" data-clamped={long && !letterOpen ? '' : undefined}>{detail.coverLetter}</p>
            {long && <button type="button" className="ap-textlink" aria-expanded={letterOpen} onClick={() => setLetterOpen(o => !o)}>{letterOpen ? 'Show less' : 'Read all'}</button>}
          </>
        ) : <p className="ap-muted">No cover letter.</p>}
      </div>
    </section>
  )
}

/**
 * Review note: a draft is kept on this device as you type (survives a reload), and Save (or Ctrl/⌘ Enter) writes
 * one history entry. Not autosaved to the server, because every save adds an entry to the decision history.
 */
function ReviewNote({ jobId, detail, noteRef }: { jobId: number; detail: ApplicantDetail; noteRef: RefObject<HTMLTextAreaElement> }) {
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const draftKey = `review-note:${detail.applicationId}`
  const latest = [...detail.history].sort((a, b) => a.timestamp.localeCompare(b.timestamp)).reverse()
    .find(e => e.actionType === 'COMMENT_UPDATED' || e.actionType === 'REVIEW_COMMENT')?.comment ?? ''
  const [note, setNote] = useState(() => readDraft<string>(draftKey)?.value ?? latest)
  const [state, setState] = useState<SaveState>(() => (readDraft<string>(draftKey) ? { kind: 'saved', at: readDraft<string>(draftKey)!.savedAt, where: 'device' } : { kind: 'idle' }))

  useEffect(() => {
    if (note.trim() === latest.trim()) return
    const t = window.setTimeout(() => {
      const at = writeDraft(draftKey, note)
      if (at) setState({ kind: 'saved', at, where: 'device' })
    }, 700)
    return () => window.clearTimeout(t)
  }, [note, latest, draftKey])

  const save = useMutation({
    mutationFn: () => reviewApi.comment(jobId, detail.applicationId, note.trim()),
    onMutate: () => setState({ kind: 'saving' }),
    onSuccess: () => {
      clearDraft(draftKey)
      setState({ kind: 'saved', at: Date.now(), where: 'server' })
      toast({ tone: 'success', title: 'Review note saved', body: 'Visible to your team in the history.' })
      void queryClient.invalidateQueries({ queryKey: qk.applicant(jobId, detail.applicationId) })
    },
    onError: cause => setState({ kind: 'error', message: errorMessage(cause), retry: () => save.mutate() }),
  })

  if (!can('applicants.comment')) return null
  const dirty = note.trim() !== latest.trim()

  function submit(event: FormEvent) {
    event.preventDefault()
    if (dirty) save.mutate()
  }

  return (
    <section className="ap-card ap-note" aria-labelledby="ap-note-h">
      <form onSubmit={submit}>
        <div className="ap-note-head">
          <h2 id="ap-note-h" className="t-h3"><label htmlFor="ap-note">Review note</label></h2>
          <SaveIndicator state={dirty && state.kind === 'idle' ? { kind: 'dirty' } : state} />
        </div>
        <Textarea
          ref={noteRef}
          id="ap-note"
          rows={3}
          maxRows={10}
          maxLength={2000}
          value={note}
          placeholder="What stood out, what to probe next…"
          aria-describedby="ap-note-hint"
          onChange={event => { setNote(event.target.value); if (state.kind === 'saved' && state.where === 'server') setState({ kind: 'dirty' }) }}
          onKeyDown={event => {
            if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
              event.preventDefault()
              event.currentTarget.form?.requestSubmit()
            }
          }}
        />
        <div className="ap-note-foot">
          <p id="ap-note-hint" className="ap-muted">Visible to your team; each save is kept in the history.</p>
          <Button type="submit" size="sm" variant={dirty ? 'primary' : 'secondary'} loading={save.isPending}>Save note</Button>
        </div>
      </form>
    </section>
  )
}

function History({ detail }: { detail: ApplicantDetail }) {
  const sorted = [...detail.history].sort((a, b) => b.timestamp.localeCompare(a.timestamp))
  const count = useRef(sorted.length)
  const [grew, setGrew] = useState(false)
  useEffect(() => {
    if (sorted.length > count.current) {
      setGrew(true)
      const t = window.setTimeout(() => setGrew(false), 1200)
      count.current = sorted.length
      return () => window.clearTimeout(t)
    }
    count.current = sorted.length
  }, [sorted.length])

  return (
    <section className="ap-card ap-history" aria-labelledby="ap-hist-h" data-grew={grew || undefined}>
      <h2 id="ap-hist-h" className="t-h3">History</h2>
      {sorted.length === 0 ? (
        <p className="ap-muted">No decisions yet. Every decision, reveal and note is recorded here and in the audit log.</p>
      ) : (
        <Timeline
          label="Decision history, newest first"
          items={sorted.map((e, i) => ({
            id: e.id,
            state: i === 0 ? 'current' : 'done',
            title: (
              <>
                {HISTORY_WORD[e.actionType] ?? humanize(e.actionType)}
                {e.previousStatus !== e.newStatus && (
                  <span className="ap-hist-move"> · {applicationStatusLabel(e.previousStatus)} → {applicationStatusLabel(e.newStatus)}</span>
                )}
              </>
            ),
            meta: <>{e.actorName}{e.matchRunId ? <> · run #{e.matchRunId}</> : null}</>,
            at: e.timestamp,
            children: e.comment ? <p>“{e.comment}”</p> : undefined,
          }))}
        />
      )}
      {!detail.identityRevealed && <p className="ap-hist-foot"><EyeOffNote /></p>}
    </section>
  )
}

function EyeOffNote() {
  return <><Icon icon={EyeOff} size={12} /> Identity hidden: names in this history are your team’s, never the candidate’s.</>
}
