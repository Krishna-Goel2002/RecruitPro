import { useState, type ReactNode } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { authApi } from '../../api/auth'
import { errorMessage } from '../../api/client'
import { useAuth, useCurrentUser } from '../../auth/AuthContext'
import brand from '../../design/brand/active'
import { Button, Meter, PageHeader, Skeleton, buttonProps, useToast } from '../../design/components'
import { ArrowRight, Check, Icon } from '../../design/icons'
import { readFlag, writeFlag } from '../../lib/lastSeen'
import { SETUP_HIDDEN_FLAG, useSetupProgress, type StepState } from './setup'
import './welcome.css'

type Step = { id: string; title: string; why: ReactNode; state: StepState; action?: ReactNode }

/** /welcome: "Get started" for a new organisation's administrator. */
export function WelcomePage() {
  const user = useCurrentUser()
  const { can } = useAuth()
  const navigate = useNavigate()
  const { toast } = useToast()
  const progress = useSetupProgress(true)
  const [hidden, setHidden] = useState(() => readFlag(SETUP_HIDDEN_FLAG))
  const resend = useMutation({
    mutationFn: () => authApi.resendVerification(),
    onSuccess: () => toast({ tone: 'success', title: 'Verification link sent', body: `Check ${user.email}.` }),
    onError: cause => toast({ tone: 'danger', title: 'The link could not be sent', body: errorMessage(cause) }),
  })
  const s = progress.status
  const org = progress.org
  const yes = (v: boolean | undefined) => (v ? 'on' : 'off')

  const steps: Step[] = [
    { id: 'workspace', title: 'Create your workspace', why: `${user.organizationName ?? 'Your organisation'} is set up, with you as its administrator.`, state: 'done' },
    {
      id: 'email',
      title: 'Confirm your email',
      why: <>We sent a link to <strong>{user.email}</strong>. It keeps account recovery safe.</>,
      state: s.email,
      action: <Button size="sm" onClick={() => resend.mutate()} loading={resend.isPending} disabled={resend.isSuccess}>{resend.isSuccess ? 'Link sent' : 'Send the link again'}</Button>,
    },
    {
      id: 'mfa',
      title: 'Turn on two-step verification',
      why: progress.mfaUnavailable ? 'Two-step verification is not available on this server yet (it needs an encryption key).' : 'Administrators can reveal identities and change settings. Protect the account with an authenticator app.',
      state: progress.mfaUnavailable ? 'done' : s.mfa,
      action: <Link {...buttonProps('secondary', 'sm')} to="/account/security">Set up</Link>,
    },
    {
      id: 'org',
      title: 'Check how reviews work',
      why: org
        ? <>Blind review is <strong>{yes(org.blindReview)}</strong> · Verification status shared with candidates: <strong>{yes(org.shareVerificationStatus)}</strong> · Two-step required for all staff: <strong>{yes(org.requireStaffMfa)}</strong></>
        : 'Blind review, what candidates see about verification, and whether staff must use two-step verification.',
      state: s.org,
      action: (
        <span className="cluster">
          <Button size="sm" onClick={progress.markOrgReviewed}>Looks right</Button>
          <Link {...buttonProps('ghost', 'sm')} to="/admin/organization" onClick={progress.markOrgReviewed}>Change</Link>
        </span>
      ),
    },
    {
      id: 'team',
      title: 'Invite your team',
      why: 'HR managers create jobs and decide; recruiters review evidence; interviewers see only their interviews.',
      state: s.team,
      action: <Link {...buttonProps('secondary', 'sm')} to="/admin/invitations">Invite people</Link>,
    },
    {
      id: 'job',
      title: 'Create your first job',
      why: 'Describe the role; we suggest requirements from your description and you choose what counts.',
      state: s.job,
      action: <Link {...buttonProps('secondary', 'sm')} to="/jobs/new">New job</Link>,
    },
    {
      id: 'publish',
      title: 'Publish it',
      why: 'Candidates can apply once it is published. Applicants are ranked by the evidence they show, blind by default.',
      state: s.publish,
      action: <Link {...buttonProps('secondary', 'sm')} to="/jobs?status=DRAFT">Open drafts</Link>,
    },
  ]
  const done = steps.filter(step => step.state === 'done').length
  const next = steps.find(step => step.state === 'open')
  const complete = done === steps.length

  return (
    <div className="hra-welcome" data-width="read">
      <PageHeader
        title={complete ? 'You are set up' : `Welcome to ${brand.name}`}
        documentTitle="Get started"
        description={complete ? 'Everything on the list is done. You can hide this page.' : 'A few steps to get your organisation hiring on evidence. Each one ticks itself when it is done, wherever you do it.'}
      />
      <div className="hra-welcome-progress">
        <Meter label="Set-up progress" value={done} max={steps.length} valueText={`${done} of ${steps.length} done`} tone={complete ? 'success' : 'accent'} />
      </div>
      <ol className="hra-setup">
        {steps.map((step, index) => {
          const isNext = step === next
          return (
            <li key={step.id} className="hra-setup-step" data-state={step.state} data-next={isNext || undefined} style={{ ['--i' as string]: index }}>
              <span className="hra-setup-mark" aria-hidden="true">
                {step.state === 'done' ? <Icon icon={Check} size={12} strokeWidth={2.25} /> : <span className="numeric">{index + 1}</span>}
              </span>
              <div className="hra-setup-text">
                <h2 className="hra-setup-title">
                  {step.title}
                  {isNext && <span className="hra-setup-next">Next</span>}
                  <span className="sr-only">{step.state === 'done' ? ' (done)' : step.state === 'loading' ? ' (checking)' : ''}</span>
                </h2>
                {step.state !== 'done' && <p className="hra-setup-why">{step.why}</p>}
              </div>
              <div className="hra-setup-action">
                {step.state === 'loading' ? <Skeleton width={88} height={28} radius={4} /> : step.state === 'open' ? step.action : null}
              </div>
            </li>
          )
        })}
      </ol>
      <div className="hra-welcome-foot">
        {can('jobs.view') && <Link {...buttonProps(complete ? 'primary' : 'ghost')} to="/jobs">Go to jobs <Icon icon={ArrowRight} size={16} /></Link>}
        {!hidden ? (
          <Button variant="ghost" onClick={() => { writeFlag(SETUP_HIDDEN_FLAG, true); setHidden(true); toast({ tone: 'info', title: 'Checklist hidden', body: 'You can find it again at /welcome.', duration: 5000 }); navigate(can('jobs.view') ? '/jobs' : '/') }}>
            Hide this checklist
          </Button>
        ) : (
          <Button variant="ghost" onClick={() => { writeFlag(SETUP_HIDDEN_FLAG, false); setHidden(false) }}>Show it in the sidebar again</Button>
        )}
      </div>
    </div>
  )
}
