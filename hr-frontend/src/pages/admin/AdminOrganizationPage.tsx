import { FormEvent, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import type { Organization, OrganizationUpdate } from '../../api/types'
import { ChipsInput } from '../../components/ChipsInput'
import { Field, Toggle } from '../../components/Field'
import { SaveIcon } from '../../components/Icons'
import { PageHeader } from '../../components/Layout'
import { useNotices } from '../../components/Notices'
import { ErrorPanel, FormError, LoadingPanel } from '../../components/States'
import { usePageTitle } from '../../lib/hooks'

/** /admin/organization — name, blind review, share-verification-status, staff MFA requirement and SSO domains, each explained. */
export function AdminOrganizationPage() {
  usePageTitle('Organisation settings')
  const organization = useQuery({ queryKey: qk.adminOrganization, queryFn: ({ signal }) => adminApi.organization(signal) })
  return (
    <>
      <PageHeader eyebrow="ADMINISTRATION" title="Organisation settings" subtitle="Settings apply to every job in your organisation. Changes are recorded in the audit log." />
      {organization.isPending ? <LoadingPanel label="Loading settings…" /> : organization.isError ? (
        <ErrorPanel error={organization.error} onRetry={() => void organization.refetch()} />
      ) : <OrganizationForm key={organization.dataUpdatedAt} organization={organization.data} />}
    </>
  )
}

function OrganizationForm({ organization }: { organization: Organization }) {
  const queryClient = useQueryClient()
  const notices = useNotices()
  const [name, setName] = useState(organization.name)
  const [blindReview, setBlindReview] = useState(organization.blindReview)
  const [share, setShare] = useState(organization.shareVerificationStatus)
  const [requireMfa, setRequireMfa] = useState(organization.requireStaffMfa ?? false)
  const [ssoDomains, setSsoDomains] = useState<string[]>(organization.ssoAllowedDomains ?? [])
  const [nameError, setNameError] = useState<string | null>(null)

  const changes: OrganizationUpdate = {}
  if (name.trim() !== organization.name) changes.name = name.trim()
  if (blindReview !== organization.blindReview) changes.blindReview = blindReview
  if (share !== organization.shareVerificationStatus) changes.shareVerificationStatus = share
  if (requireMfa !== (organization.requireStaffMfa ?? false)) changes.requireStaffMfa = requireMfa
  const savedDomains = organization.ssoAllowedDomains ?? []
  if (ssoDomains.length !== savedDomains.length || ssoDomains.some((domain, index) => domain !== savedDomains[index])) {
    changes.ssoAllowedDomains = ssoDomains.map(domain => domain.trim().toLowerCase().replace(/^@/, '')).filter(Boolean)
  }
  const dirty = Object.keys(changes).length > 0

  const save = useMutation({
    mutationFn: () => adminApi.updateOrganization(changes),
    onSuccess: updated => {
      queryClient.setQueryData(qk.adminOrganization, updated)
      notices.success('Organisation settings saved.')
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!name.trim()) {
      setNameError('Enter the organisation’s name.')
      return
    }
    setNameError(null)
    if (dirty) save.mutate()
  }

  return (
    <form className="detail-card form-card settings-form" onSubmit={submit} noValidate>
      <Field label="Organisation name" required error={nameError} hint={`Shown to candidates on your job posts. Identifier: ${organization.slug}`}>
        <input value={name} maxLength={200} onChange={event => setName(event.target.value)} />
      </Field>

      <Toggle
        checked={blindReview}
        onChange={setBlindReview}
        label="Blind review"
        description={<>
          Reviewers first see “Candidate #A-…”, the evidence and the claims — not the name, contact details, resume file or cover letter.
          Identity is revealed automatically when an applicant is shortlisted, or by an HR manager after that; every reveal is audited.
          Turning this off shows identity from the start, which makes name-, gender- and age-related bias easier to creep in.
        </>}
      />

      <Toggle
        checked={share}
        onChange={setShare}
        label="Share verification status with candidates"
        description={<>
          Candidates see, for each requirement of a job they applied to, whether it is verified, only claimed, or not verified yet — so they
          can add evidence. They never see scores, ranks, reviewer notes or other applicants.
        </>}
      />

      <Toggle
        checked={requireMfa}
        onChange={setRequireMfa}
        label="Require two-step verification for all staff"
        description={<>
          Every staff account (admins included) must use an authenticator-app code at sign-in. Staff who have not set it up are asked to
          enrol at their next sign-in; sessions that are already open are not ended. Needs the server’s encryption key.
        </>}
      />

      <ChipsInput
        label="Single sign-on: allowed e-mail domains"
        values={ssoDomains}
        onChange={setSsoDomains}
        placeholder="example.com, then Enter"
        maxItems={20}
        maxLength={253}
      />
      <p className="item-meta">
        Staff can sign in with a configured identity provider only when their e-mail domain is listed here (exact domains, no wildcards).
        No accounts are created automatically. Leave empty to turn single sign-on off.
      </p>

      <FormError error={save.error} />
      <div className="editor-actions">
        <span className="item-meta">{dirty ? 'Unsaved changes.' : 'No changes.'}</span>
        <button type="submit" className="primary-button workspace-primary" disabled={!dirty || save.isPending}><SaveIcon /> {save.isPending ? 'Saving…' : 'Save settings'}</button>
      </div>
    </form>
  )
}
