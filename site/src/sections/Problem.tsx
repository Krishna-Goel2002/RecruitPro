import { Icon, ScrollText, Search, TriangleAlert, type IconComponent } from '../design/icons'
import { Reveal } from '../site/reveal'

const POINTS: { icon: IconComponent; title: string; body: string }[] = [
  {
    icon: Search,
    title: 'Keyword matches are not evidence',
    body: 'A screen that counts words rewards whoever repeats the job ad. It cannot tell a skill someone used last month from one they once read about.',
  },
  {
    icon: ScrollText,
    title: 'Checking by hand is slow and uneven',
    body: 'Recruiters open profiles and search for certificates one by one. Each check is different, little is recorded, and the candidate’s identity is in view before anyone has looked at the work.',
  },
  {
    icon: TriangleAlert,
    title: 'One opaque score explains nothing',
    body: 'A single “match %” cannot be questioned. Recruiters cannot see why it is high, and candidates cannot see what would change it.',
  },
]

export function Problem() {
  return (
    <section id="problem" className="band" aria-labelledby="problem-title">
      <div className="wrap">
        <Reveal className="sec-head">
          <p className="overline">The problem</p>
          <h2 id="problem-title" className="sec-title">
            A resume is a list of claims. Most hiring tools treat it as a list of keywords.
          </h2>
        </Reveal>
        <ul className="cols-3">
          {POINTS.map((p, i) => (
            <Reveal as="li" key={p.title} index={i} className="point">
              <span className="point-icon">
                <Icon icon={p.icon} size={20} />
              </span>
              <h3 className="point-title">{p.title}</h3>
              <p className="point-body">{p.body}</p>
            </Reveal>
          ))}
        </ul>
      </div>
    </section>
  )
}
