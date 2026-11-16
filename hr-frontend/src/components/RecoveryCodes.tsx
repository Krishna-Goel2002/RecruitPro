import { useState } from 'react'
import brand from '../design/brand/active'
import { Button, Checkbox } from '../design/components'
import { Check, Copy, Download } from '../design/icons'
import './staff-ui.css'

/** Saves the codes as a small text file (client-side Blob; nothing is uploaded). */
function downloadCodes(codes: string[], email: string) {
  const text = [
    `${brand.name} recovery codes for ${email}`,
    `Created ${new Date().toLocaleString('en-IN')}`,
    'Each code signs you in once if you lose access to your authenticator app.',
    '',
    ...codes,
    '',
  ].join('\n')
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url
  a.download = `${brand.name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}-recovery-codes.txt`
  document.body.appendChild(a)
  a.click()
  a.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 2000)
}

/**
 * Recovery codes shown once: a mono grid, Download .txt, Copy all, and a required "I have saved these codes" tick
 * before Done. Generic: the sign-in enrolment can reuse it.
 */
export function RecoveryCodes({ codes, email, onDone, doneLabel = 'Done' }: { codes: string[]; email: string; onDone: () => void; doneLabel?: string }) {
  const [saved, setSaved] = useState(false)
  const [copied, setCopied] = useState(false)
  const [downloaded, setDownloaded] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(codes.join('\n'))
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1600)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="sx-codes">
      <ol className="sx-codes-grid" aria-label="Recovery codes">
        {codes.map((code, index) => (
          <li key={code} className="mono" style={{ ['--i' as string]: index }}>
            {code}
          </li>
        ))}
      </ol>
      <div className="sx-codes-actions">
        <Button icon={downloaded ? Check : Download} onClick={() => { downloadCodes(codes, email); setDownloaded(true) }}>
          {downloaded ? 'Downloaded' : 'Download .txt'}
        </Button>
        <Button variant="ghost" icon={copied ? Check : Copy} onClick={() => void copy()}>
          {copied ? 'Copied' : 'Copy all'}
        </Button>
        <span className="sr-only" role="status">{copied ? 'Recovery codes copied' : ''}</span>
      </div>
      <Checkbox label="I have saved these codes somewhere safe" description="They will not be shown again. Earlier codes no longer work." checked={saved} onChange={event => setSaved(event.target.checked)} />
      <div>
        <Button variant="primary" onClick={onDone} disabled={!saved}>{doneLabel}</Button>
      </div>
    </div>
  )
}
