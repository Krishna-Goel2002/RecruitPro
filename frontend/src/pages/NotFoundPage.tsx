import { Link } from 'react-router-dom'
import brand from '../design/brand/active'
import { EmptyState, PageHeader, buttonProps } from '../design/components'
import { Search } from '../design/icons'
import { appLinks } from '../design/lib/links'

export function NotFoundPage() {
  return (
    <>
      <PageHeader title="Page not found" />
      <EmptyState
        icon={Search}
        title="There is nothing at this address"
        description="The link may be old or mistyped. Your overview has everything that needs you."
        action={
          <>
            <Link {...buttonProps('primary')} to="/home">
              Go to your overview
            </Link>
            <a {...buttonProps('ghost')} href={appLinks().site}>
              {brand.name} home
            </a>
          </>
        }
      />
    </>
  )
}
