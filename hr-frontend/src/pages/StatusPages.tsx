import { Link } from 'react-router-dom'
import { EmptyState, PageHeader, buttonProps } from '../design/components'
import brand from '../design/brand/active'
import { LockKeyhole, Search as Compass } from '../design/icons'
import { appLinks } from '../design/lib/links'

/** 403 inside the app: the role does not include this area. The server stays the authority. */
export function ForbiddenPage() {
  return (
    <div className="hra-status-page" data-width="read">
      <PageHeader title="Not available for your role" documentTitle="Not available" />
      <EmptyState
        icon={LockKeyhole}
        title="Your role does not include this area"
        description="Ask an administrator if you need access. The server checks every action, whatever this page shows."
        action={<Link {...buttonProps('secondary')} to="/">Go to your home screen</Link>}
      />
    </div>
  )
}

/** 404 inside the app. */
export function NotFoundPage() {
  return (
    <div className="hra-status-page" data-width="read">
      <PageHeader title="Page not found" documentTitle="Not found" />
      <EmptyState
        icon={Compass}
        title="This page does not exist"
        description="The link may be out of date, or the item belongs to another organisation."
        action={
          <>
            <Link {...buttonProps('secondary')} to="/">Go to your home screen</Link>
            <a {...buttonProps('ghost')} href={appLinks().site}>{brand.name} home</a>
          </>
        }
      />
    </div>
  )
}
