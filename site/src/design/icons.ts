/*
 * The curated icon set (DESIGN_SYSTEM §7). This is the ONLY file that imports lucide-react, so both portals use the
 * same glyphs and the bundle only carries what is listed here. Add an icon by exporting it below.
 *
 * Use through <Icon icon={X} size={16|20} />, which applies the stroke rules (16 px → 1.75, 20 px → 1.5,
 * absoluteStrokeWidth) and hides the icon from assistive tech unless it is given a label.
 */
import { createElement, forwardRef } from 'react'
import type { LucideIcon, LucideProps } from 'lucide-react'
import { LockOpen } from 'lucide-react'

export type { LucideIcon as IconComponent } from 'lucide-react'

export {
  Archive, ArrowDown, ArrowLeft, ArrowRight, ArrowUp, ArrowUpDown, ArrowUpRight, Award, BadgeCheck, Bell, BriefcaseBusiness,
  Building2, Calendar, CalendarClock, Check, ChevronDown, ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight,
  ChevronUp, Circle, CircleAlert, CircleCheck, CircleMinus, Clock, Copy, Download, Ellipsis, ExternalLink, Eye, EyeOff,
  FileText, Filter, Github, Gitlab, Globe, Inbox, Info, KeyRound, KeySquare, LayoutGrid, LifeBuoy, Link2, ListChecks,
  History, LoaderCircle, LockKeyhole, LockOpen, LogOut, Mail, MailPlus, Maximize2, Menu, MessagesSquare, Minimize2, Monitor, Moon,
  PanelLeftClose, PanelLeftOpen, PanelRightClose, SendHorizontal, SquarePen,
  Pencil, Plus, RefreshCw, Rows3, Rows4, Scale, ScrollText, Search, Settings2, ShieldCheck, SlidersHorizontal, Sun,
  Trash2, TriangleAlert, Undo2, Upload, User, UserRoundCheck, Users, X,
} from 'lucide-react'

/** Ownership-open ranking gate (alias, §7). */
export const GateOpen = LockOpen

type CustomDraw = (stroke: number) => ReturnType<typeof createElement>[]

function custom(name: string, draw: CustomDraw): LucideIcon {
  const Comp = forwardRef<SVGSVGElement, LucideProps>(function CustomIcon(
    { size = 24, strokeWidth = 2, absoluteStrokeWidth, color = 'currentColor', className, ...rest },
    ref,
  ) {
    const px = Number(size)
    const stroke = absoluteStrokeWidth ? (Number(strokeWidth) * 24) / px : Number(strokeWidth)
    return createElement(
      'svg',
      {
        ref,
        xmlns: 'http://www.w3.org/2000/svg',
        width: size,
        height: size,
        viewBox: '0 0 24 24',
        fill: 'none',
        stroke: color,
        strokeWidth: stroke,
        strokeLinecap: 'round',
        strokeLinejoin: 'round',
        className: ['lucide', className].filter(Boolean).join(' '),
        ...rest,
      },
      ...draw(stroke),
    )
  })
  Comp.displayName = name
  return Comp as unknown as LucideIcon
}

/** Partly verified: a circle with the left half filled (§3.6). */
export const CircleHalf = custom('CircleHalf', () => [
  createElement('circle', { key: 'c', cx: 12, cy: 12, r: 9 }),
  createElement('path', { key: 'h', d: 'M12 3a9 9 0 0 0 0 18Z', fill: 'currentColor' }),
])

/** Three rising rungs: the neutral brand idea as a 24-grid icon (§7). */
export const LadderMark = custom('LadderMark', () => [
  createElement('path', { key: 'a', d: 'M5 19v-4' }),
  createElement('path', { key: 'b', d: 'M12 19v-8' }),
  createElement('path', { key: 'c', d: 'M19 19V5' }),
])

/** Checkbox tick, drawn with stroke-dashoffset by components.css. */
export const TickPath = 'M3.5 8.5 6.5 11.5 12.5 4.5'

export interface IconProps extends Omit<LucideProps, 'ref'> {
  icon: LucideIcon
  /** 12 · 14 · 16 (default) · 20 */
  size?: 12 | 14 | 16 | 20 | 24
  /** Accessible name; when omitted the icon is decorative (aria-hidden). */
  label?: string
}

/** Applies the stroke rules from §7 to any icon in this file. */
export function Icon({ icon, size = 16, label, strokeWidth, ...rest }: IconProps) {
  const sw = strokeWidth ?? (size >= 20 ? 1.5 : 1.75)
  return createElement(icon, {
    size,
    strokeWidth: sw,
    absoluteStrokeWidth: true,
    'aria-hidden': label ? undefined : true,
    'aria-label': label,
    role: label ? 'img' : undefined,
    focusable: 'false',
    ...rest,
  } as LucideProps)
}
