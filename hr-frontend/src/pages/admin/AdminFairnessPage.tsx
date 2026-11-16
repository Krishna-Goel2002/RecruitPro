import { useQuery } from '@tanstack/react-query'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import { QueryError } from '../../components/QueryError'
import { LoadingRegion, PageHeader, SkeletonText } from '../../design/components'
import { FairnessReportView, useFairnessRange } from '../jobs/FairnessReportView'
import './admin.css'

/** /admin/fairness: the organisation-wide adverse-impact report (the report view itself is shared with the job tab). */
export function AdminFairnessPage() {
  const { range, filter } = useFairnessRange()
  const report = useQuery({ queryKey: qk.adminFairness(range), queryFn: ({ signal }) => adminApi.fairness(range, signal) })
  return (
    <div className="adm" data-width="wide">
      <PageHeader
        title="Fairness"
        description="Selection rates by group at each stage, from voluntary equal-opportunity answers, shown only for groups of five or more. A flag is a reason to look, not proof of bias. Per-job reports are on each job’s Fairness tab."
      />
      <div className="adm-fair-filter" aria-label="Report period">{filter}</div>
      {report.isPending ? <LoadingRegion label="Calculating selection rates"><SkeletonText lines={8} /></LoadingRegion>
        : report.isError ? <QueryError error={report.error} title="We couldn’t load the fairness report" onRetry={() => void report.refetch()} retrying={report.isFetching} />
          : <FairnessReportView report={report.data} />}
    </div>
  )
}
