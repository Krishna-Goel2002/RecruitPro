import { QRCodeSVG } from 'qrcode.react'
import './staff-ui.css'

/**
 * A QR code rendered locally as inline SVG (qrcode.react 4.2.0, pinned): the otpauth:// URI never leaves the browser
 * and no image is fetched. Always dark modules on a white tile, in both themes, because phone cameras need the
 * contrast. Import lazily (`lazy(() => import('…/QrCode'))`) so the encoder only loads where it is shown.
 */
export default function QrCode({ value, label, size = 168 }: { value: string; label: string; size?: number }) {
  return (
    <figure className="sx-qr" style={{ ['--qr-size' as string]: `${size}px` }}>
      <QRCodeSVG value={value} size={size} level="M" marginSize={0} fgColor="currentColor" bgColor="transparent" role="img" aria-label={label} />
    </figure>
  )
}
