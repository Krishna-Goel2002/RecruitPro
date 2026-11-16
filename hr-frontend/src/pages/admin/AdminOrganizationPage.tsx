import { useEffect, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import type { Organization, OrganizationUpdate } from '../../api/types'
import { ActionDialog } from '../../components/ActionDialog'
import { DomainListInput } from '../../components/DomainListInput'
import { InlineError, QueryError } from '../../components/QueryError'
import { SaveBar } from '../../components/SaveBar'
import { Field, Input, LoadingRegion, PageHeader, Section, SkeletonText, Switch, useToast } from '../../design/components'
import './admin.css'

type Form = { name: string; blindReview: boolean; shareVerificationStatus: boolean; requireStaffMfa: boolean; ssoAllowedDomains: string[] }
const toForm = (o: Organization): Form => ({ name: o.name, blindReview: o.blindReview, shareVerificationStatus: o.shareVerificationStatus, requireStaffMfa: o.requireStaffMfa ?? false, ssoAllowedDomains: o.ssoAllowedDomains ?? [] })

/** /admin/organization (FLOWS-HR-B §8): one form in sections; a sticky save bar appears only when something changed. */
export function AdminOrganizationPage() {
  const q = useQuery({ queryKey: qk.adminOrganization, queryFn: ({ signal }) => adminApi.organization(signal) })
  return (
    <div className="adm" data-width="form">
      <PageHeader title="Organisation" meta={q.data ? [q.data.name, <span key="s" className="mono">{q.data.slug}</span>] : undefined} />
      {q.isPending ? <LoadingRegion label="Loading organisation settings"><SkeletonText lines={8} /></LoadingRegion>
        : q.isError ? <QueryError error={q.error} title="We couldn’t load the organisation settings" onRetry={() => void q.refetch()} retrying={q.isFetching} />
          : <OrgForm org={q.data} />}
    </div>
  )
}

function OrgForm({ org }: { org: Organization }) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const saved = toForm(org)
  const [form, setForm] = useState<Form>(saved)
  const [nameError, setNameError] = useState<string | null>(null)
  const [confirmBlindOff, setConfirmBlindOff] = useState(false)
  useEffect(() => { setForm(toForm(org)) }, [org])

  const diff: OrganizationUpdate = {}
  if (form.name.trim() !== saved.name) diff.name = form.name.trim()
  if (form.blindReview !== saved.blindReview) diff.blindReview = form.blindReview
  if (form.shareVerificationStatus !== saved.shareVerificationStatus) diff.shareVerificationStatus = form.shareVerificationStatus
  if (form.requireStaffMfa !== saved.requireStaffMfa) diff.requireStaffMfa = form.requireStaffMfa
  if (JSON.stringify(form.ssoAllowedDomains) !== JSON.stringify(saved.ssoAllowedDomains)) diff.ssoAllowedDomains = form.ssoAllowedDomains
  const changes = Object.keys(diff).length

  const save = useMutation({
    mutationFn: () => adminApi.updateOrganization(diff),
    onSuccess: updated => {
      queryClient.setQueryData(qk.adminOrganization, updated)
      toast({ tone: 'success', title: 'Organisation settings saved', body: 'Every change is recorded in the audit log.' })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!form.name.trim()) return setNameError('The organisation needs a name.')
    if (diff.blindReview === false && !confirmBlindOff) return setConfirmBlindOff(true)
    setConfirmBlindOff(false)
    save.mutate()
  }

  return (
    <>
    <form id="org-form" onSubmit={submit} noValidate className="adm-form">
      <Section title="Name">
        <Field label="Name" hint={<>Shown to candidates. Identifier: <span className="mono">{org.slug}</span></>} error={nameError}>
          <Input value={form.name} maxLength={200} onChange={e => { setForm(f => ({ ...f, name: e.target.value })); setNameError(null) }} />
        </Field>
      </Section>
      <Section title="Review">
        <Switch
          label="Blind review"
          description="Names, contact details, resume files and cover letters stay hidden until an applicant is shortlisted or a manager reveals them (audited). Turning it off exposes identity from the first look, which is when bias does the most harm."
          checked={form.blindReview}
          onChange={e => setForm(f => ({ ...f, blindReview: e.target.checked }))}
        />
        <Switch
          label="Share verification status with candidates"
          description="Candidates see which of their requirements are verified on their application page. They never see scores, ranks, reviewer notes or other applicants."
          checked={form.shareVerificationStatus}
          onChange={e => setForm(f => ({ ...f, shareVerificationStatus: e.target.checked }))}
        />
      </Section>
      <Section title="Security">
        <Switch
          label="Require two-step verification for all staff"
          description="Staff without it must set it up at their next sign-in. Open sessions are not ended. Needs the server’s encryption key."
          checked={form.requireStaffMfa}
          onChange={e => setForm(f => ({ ...f, requireStaffMfa: e.target.checked }))}
        />
      </Section>
      <Section title="Single sign-on" description="Staff whose email is at one of these domains can sign in with your identity provider. No accounts are created automatically. Leave empty to turn single sign-on off.">
        <DomainListInput label="Allowed email domains" hint="Exact domains, no wildcards. At most 20." value={form.ssoAllowedDomains} max={20} onChange={v => setForm(f => ({ ...f, ssoAllowedDomains: v }))} placeholder="northwind.example" />
      </Section>
      <InlineError error={save.error} />
      <SaveBar changes={changes} busy={save.isPending} form="org-form" onDiscard={() => { setForm(saved); save.reset() }} note="changes are audited" />
    </form>
      <ActionDialog
        open={confirmBlindOff}
        title="Turn off blind review?"
        description="Reviewers will see names, contact details and resume files from the first look at every application. Research on hiring shows this is where bias does the most harm."
        confirmLabel="Turn off blind review"
        tone="danger"
        busy={save.isPending}
        error={save.error}
        onCancel={() => setConfirmBlindOff(false)}
        onConfirm={() => { setConfirmBlindOff(false); save.mutate() }}
      />
    </>
  )
}
