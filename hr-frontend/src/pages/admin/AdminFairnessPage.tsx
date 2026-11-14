import { useQuery } from '@tanstack/react-query'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import { PageHeader } from '../../components/Layout'
import { ErrorPanel, LoadingPanel } from '../../components/States'
import { usePageTitle } from '../../lib/hooks'
import { FairnessReportView } from '../jobs/FairnessReportView'

/** /admin/fairness — organisation-wide adverse-impact report. */
export function AdminFairnessPage() {
  usePageTitle('Fairness')
  const report = useQuery({ queryKey: qk.adminFairness, queryFn: ({ signal }) => adminApi.fairness(signal) })
  return (
    <>
      <PageHeader eyebrow="ADMINISTRATION" title="Fairness across the organisation" subtitle="Selection rates by group at each stage, from voluntary equal-opportunity answers. Per-job reports are on each job’s Fairness tab." />
      {report.isPending ? <LoadingPanel label="Calculating selection rates…" /> : report.isError ? (
        <ErrorPanel error={report.error} title="We couldn’t load the fairness report" onRetry={() => void report.refetch()} />
      ) : <FairnessReportView report={report.data} />}
    </>
  )
}
