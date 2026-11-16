import { useQueries } from '@tanstack/react-query'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type { ApplicantDetail, ApplicantSummary, JobStaff } from '../../api/types'
import { ApplicationStatusBadge } from '../../components/StatusBadges'
import { Button, CoverageMeter, Dialog, EvidenceLadder, ReqMarker, Skeleton } from '../../design/components'
import { tierOrdinal } from '../../design/lib/labels'
import { ArrowRight } from '../../design/icons'
import { stripRequirements } from './ranking'

/**
 * Side-by-side compare of 2–3 applicants (`?compare=a,b,c`), built client-side from the cached details: one column per
 * applicant, one row per requirement (gates first). Rows where everyone has the same evidence are drawn quieter, so
 * the differences stand out.
 */
export function ApplicantCompare({ job, ids, summaries, onClose, onOpen }: {
  job: JobStaff
  ids: number[]
  summaries: ApplicantSummary[]
  onClose: () => void
  onOpen: (id: number) => void
}) {
  const open = ids.length >= 2
  const results = useQueries({
    queries: ids.map(id => ({
      queryKey: qk.applicant(job.id, id),
      queryFn: ({ signal }: { signal: AbortSignal }) => reviewApi.detail(job.id, id, signal),
      staleTime: 60_000,
      enabled: open,
    })),
  })
  const details = results.map(r => r.data)
  const requirements = stripRequirements(job.requirements)
  const name = (id: number, d?: ApplicantDetail) => d?.displayName ?? summaries.find(s => s.applicationId === id)?.displayName ?? `Application #${id}`
  const tierOf = (d: ApplicantDetail | undefined, reqId: number) => {
    const e = d?.requirements.find(x => x.requirementId === reqId)
    return e && e.status !== 'NOT_OBSERVED' ? e.tier : null
  }

  return (
    <Dialog open={open} onClose={onClose} size="lg" className="hra-compare" title={`Compare ${ids.length} applicants`} description="Evidence per requirement, gates first. The strongest evidence in each row is highlighted; rows where everyone is level are greyed.">
      <div className="hra-compare-scroll" tabIndex={0} role="region" aria-label="Comparison table">
        <table className="hra-compare-table">
          <caption className="sr-only">Evidence tier per requirement for each applicant</caption>
          <thead>
            <tr>
              <th scope="col" className="hra-compare-req">Requirement</th>
              {ids.map((id, i) => (
                <th scope="col" key={id}>
                  <span className="hra-compare-name">{name(id, details[i])}</span>
                  {details[i] && <ApplicationStatusBadge status={details[i]!.applicationStatus} />}
                </th>
              ))}
            </tr>
            <tr className="hra-compare-cov">
              <th scope="row">Coverage</th>
              {ids.map((id, i) => {
                const d = details[i]
                return (
                  <td key={id}>
                    {!d ? <Skeleton width="80%" height={24} /> : d.verifiedCoverage != null ? <CoverageMeter compact verified={d.verifiedCoverage} claimed={d.claimedCoverage ?? d.verifiedCoverage} note={null} /> : <span className="subtle">No result yet</span>}
                  </td>
                )
              })}
            </tr>
          </thead>
          <tbody>
            {requirements.map(r => {
              const tiers = ids.map((_, i) => tierOrdinal(tierOf(details[i], r.id)))
              const same = details.every(Boolean) && tiers.every(t => t === tiers[0])
              const best = Math.max(...tiers)
              return (
                <tr key={r.id} data-same={same || undefined}>
                  <th scope="row" className="hra-compare-req">
                    <span>{r.description}</span>
                    <ReqMarker kind={r.mandatory && r.knockout ? 'gate' : r.mandatory ? 'must' : 'nice'} />
                  </th>
                  {ids.map((id, i) => {
                    const d = details[i]
                    const gateOpen = !!d?.unverifiedKnockouts?.includes(r.id)
                    return (
                      <td key={id} data-best={!same && tiers[i] === best && best > 0 ? true : undefined}>
                        {!same && tiers[i] === best && best > 0 && <span className="sr-only">Strongest: </span>}
                        {d ? <EvidenceLadder tier={tierOf(d, r.id)} variant="chip" gateOpen={gateOpen} animate={false} /> : <Skeleton width={96} height={14} />}
                      </td>
                    )
                  })}
                </tr>
              )
            })}
          </tbody>
          <tfoot>
            <tr>
              <th scope="row"><span className="sr-only">Open</span></th>
              {ids.map((id, i) => (
                <td key={id}>
                  <Button size="sm" variant="ghost" trailingIcon={ArrowRight} onClick={() => onOpen(id)}>
                    Open <span className="sr-only">{name(id, details[i])}</span>
                  </Button>
                </td>
              ))}
            </tr>
          </tfoot>
        </table>
      </div>
    </Dialog>
  )
}
