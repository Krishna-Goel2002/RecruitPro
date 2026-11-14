import type { ReactNode } from 'react'

/** Inline stroke icons (decorative; always aria-hidden). */
type IconProps = { className?: string }

function Svg({ className, children }: IconProps & { children: ReactNode }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {children}
    </svg>
  )
}

export const BriefcaseIcon = (p: IconProps) => <Svg {...p}><rect x="3" y="7" width="18" height="13" rx="2.5" /><path d="M8 7V5.5A1.5 1.5 0 0 1 9.5 4h5A1.5 1.5 0 0 1 16 5.5V7M3 12h18m-11 0v2h4v-2" /></Svg>
export const LockIcon = (p: IconProps) => <Svg {...p}><rect x="4.5" y="10" width="15" height="11" rx="2.5" /><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 4v3" /></Svg>
export const MailIcon = (p: IconProps) => <Svg {...p}><rect x="3.5" y="5" width="17" height="14" rx="3" /><path d="m5 7 7 5.5L19 7" /></Svg>
export const UserIcon = (p: IconProps) => <Svg {...p}><circle cx="12" cy="8" r="4" /><path d="M4 21v-1a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v1" /></Svg>
export const BuildingIcon = (p: IconProps) => <Svg {...p}><rect x="4" y="3" width="16" height="18" rx="2" /><path d="M9 7h1m4 0h1M9 11h1m4 0h1M9 15h1m4 0h1M10 21v-3h4v3" /></Svg>
export const GridIcon = (p: IconProps) => <Svg {...p}><rect x="3" y="3" width="8" height="8" rx="2" /><rect x="13" y="3" width="8" height="5" rx="2" /><rect x="13" y="10" width="8" height="11" rx="2" /><rect x="3" y="13" width="8" height="8" rx="2" /></Svg>
export const SearchIcon = (p: IconProps) => <Svg {...p}><circle cx="10.8" cy="10.8" r="6.8" /><path d="m16 16 4.5 4.5" /></Svg>
export const PlusIcon = (p: IconProps) => <Svg {...p}><path d="M12 5v14m-7-7h14" /></Svg>
export const ArrowIcon = (p: IconProps) => <Svg {...p}><path d="M5 12h14m-6-6 6 6-6 6" /></Svg>
export const BackIcon = (p: IconProps) => <Svg {...p}><path d="M19 12H5m6 6-6-6 6-6" /></Svg>
export const SaveIcon = (p: IconProps) => <Svg {...p}><path d="M5 4h12l3 3v13H4V4h1zm2 0v6h10V4M8 20v-7h8v7" /></Svg>
export const UsersIcon = (p: IconProps) => <Svg {...p}><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" /></Svg>
export const InviteIcon = (p: IconProps) => <Svg {...p}><path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="8.5" cy="7" r="4" /><path d="M20 8v6m3-3h-6" /></Svg>
export const ShieldIcon = (p: IconProps) => <Svg {...p}><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" /></Svg>
export const DocumentIcon = (p: IconProps) => <Svg {...p}><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><path d="M14 2v6h6M16 13H8m8 4H8" /></Svg>
export const ScaleIcon = (p: IconProps) => <Svg {...p}><path d="M12 3v18M7 21h10M5 7h14M5 7l-3 7a4 4 0 0 0 6 0L5 7zm14 0-3 7a4 4 0 0 0 6 0l-3-7z" /></Svg>
export const CalendarIcon = (p: IconProps) => <Svg {...p}><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M3 10h18M8 3v4m8-4v4" /></Svg>
export const DownloadIcon = (p: IconProps) => <Svg {...p}><path d="M12 4v11m-5-5 5 5 5-5M5 20h14" /></Svg>
export const RefreshIcon = (p: IconProps) => <Svg {...p}><path d="M20 11a8 8 0 1 0-2.3 5.7M20 5v6h-6" /></Svg>
export const SparkIcon = (p: IconProps) => <Svg {...p}><path d="M12 3v4m0 10v4M3 12h4m10 0h4M6 6l2.5 2.5m7 7L18 18M6 18l2.5-2.5m7-7L18 6" /></Svg>
export const AlertIcon = (p: IconProps) => <Svg {...p}><circle cx="12" cy="12" r="9" /><path d="M12 7.5v5m0 4h.01" /></Svg>
export const CheckIcon = (p: IconProps) => <Svg {...p}><path d="m5 12.5 4.5 4.5L19 7.5" /></Svg>
export const CloseIcon = (p: IconProps) => <Svg {...p}><path d="M6 6l12 12M18 6 6 18" /></Svg>
export const EyeIcon = (p: IconProps) => <Svg {...p}><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" /><circle cx="12" cy="12" r="3" /></Svg>
export const CopyIcon = (p: IconProps) => <Svg {...p}><rect x="9" y="9" width="12" height="12" rx="2" /><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1" /></Svg>
export const ClipboardIcon = (p: IconProps) => <Svg {...p}><rect x="5" y="4" width="14" height="17" rx="2" /><path d="M9 4V3h6v1M9 10h6m-6 4h6m-6 4h3" /></Svg>
export const ChatIcon = (p: IconProps) => <Svg {...p}><path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z" /></Svg>
export const BellIcon = (p: IconProps) => <Svg {...p}><path d="M18 16V11a6 6 0 1 0-12 0v5l-2 2h16l-2-2zM10 21h4" /></Svg>
export const KeyIcon = (p: IconProps) => <Svg {...p}><circle cx="8" cy="15" r="4" /><path d="m11 12 9-9m-4 4 3 3m-5-1 2 2" /></Svg>
