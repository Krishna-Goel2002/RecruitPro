import { useEffect, useMemo } from 'react'
import brand from './design/brand/active'
import { portalLinks } from './site/config'
import { FaqSection } from './sections/FaqSection'
import { Footer } from './sections/Footer'
import { ForCandidates } from './sections/ForCandidates'
import { ForTeams } from './sections/ForTeams'
import { Header } from './sections/Header'
import { Hero } from './sections/Hero'
import { HowItWorks } from './sections/HowItWorks'
import { Problem } from './sections/Problem'
import { Trust } from './sections/Trust'

/** The public landing page: one document, eight sections (ASSISTANT_PLAN §5). */
export default function App() {
  const links = useMemo(() => portalLinks(), [])
  useEffect(() => {
    document.title = `${brand.name} · ${brand.tagline}`
  }, [])
  return (
    <div className="site" id="top">
      <a className="skip" href="#main">
        Skip to content
      </a>
      <Header links={links} />
      <main id="main" tabIndex={-1}>
        <Hero links={links} />
        <Problem />
        <HowItWorks />
        <ForTeams links={links} />
        <ForCandidates links={links} />
        <Trust />
        <FaqSection />
      </main>
      <Footer links={links} />
    </div>
  )
}
