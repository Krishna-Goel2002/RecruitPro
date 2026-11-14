import { Link } from 'react-router-dom'
import { usePageTitle } from '../lib/hooks'

export function ForbiddenPage() {
  usePageTitle('Not available')
  return (
    <section className="jobs-card">
      <div className="state-panel">
        <div className="state-icon" aria-hidden="true">!</div>
        <h1 className="state-title">This area is not available for your role</h1>
        <p>Ask an administrator if you need access. The server checks every action, whatever this page shows.</p>
        <Link className="outline-button" to="/">Go to your home screen</Link>
      </div>
    </section>
  )
}

export function NotFoundPage() {
  usePageTitle('Not found')
  return (
    <section className="jobs-card">
      <div className="state-panel">
        <div className="state-icon" aria-hidden="true">?</div>
        <h1 className="state-title">Page not found</h1>
        <p>The link may be out of date, or the item belongs to another organisation.</p>
        <Link className="outline-button" to="/">Go to your home screen</Link>
      </div>
    </section>
  )
}
