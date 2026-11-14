import { Link } from 'react-router-dom'
import { EmptyState } from '../components/Feedback'
import { PageHeader } from '../components/PageHeader'

export function NotFoundPage() {
  return (
    <>
      <PageHeader title="Page not found" />
      <EmptyState
        title="There is nothing at this address"
        action={
          <Link className="quiet-button" to="/jobs">
            Go to jobs
          </Link>
        }
      >
        Check the link, or use the menu to find what you need.
      </EmptyState>
    </>
  )
}
