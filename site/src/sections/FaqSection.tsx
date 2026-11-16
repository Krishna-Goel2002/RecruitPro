import { Faq, type FaqItem } from '../site/Faq'
import { Reveal } from '../site/reveal'

export const FAQ: FaqItem[] = [
  {
    q: 'Is coverage a prediction of how well someone will do?',
    a: 'No. Coverage is the share of a job’s requirements that have evidence, with must-haves counting double. It is shown as two numbers, with verified evidence and including the candidate’s own claims, and it is not a probability of success.',
  },
  {
    q: 'Does the software reject applicants?',
    a: 'No. A gate requirement without evidence keeps an applicant out of the ranked group until it is verified. Shortlisting, rejecting and selecting are always done by a person.',
  },
  {
    q: 'What does “Not found in connected sources” mean?',
    a: 'That a source the candidate connected does not mention the claim. It is neutral: it never lowers a ranking and is never shown as a warning.',
  },
  {
    q: 'Which sources can a candidate connect?',
    a: 'GitHub, GitLab, a website, a document, an Open Badge and references. Only public data is read, and popularity signals such as stars and followers are dropped before anything is checked.',
  },
  {
    q: 'Do candidates see what employers will see?',
    a: 'Yes. Every verification result waits for the candidate’s review with a preview of what an employer would see, and it is shared only if they choose.',
  },
  {
    q: 'Can we try verification before it affects rankings?',
    a: 'Yes. Verification starts in shadow mode for each organisation: admins can review results, but they do not change rankings until you switch it on.',
  },
  {
    q: 'Does an AI model see candidate names?',
    a: 'No. Text is de-identified before it reaches any model, and external models are off unless your organisation enables them. The HR assistant refers to candidates by application code.',
  },
  {
    q: 'How do we get started?',
    a: 'Companies sign in to the HR portal and post a job. Candidates create an account in the candidate portal and add a resume; the portal guides the rest.',
  },
]

export function FaqSection() {
  return (
    <section id="faq" className="band" data-tone="sunken" aria-labelledby="faq-title">
      <div className="wrap faq-wrap">
        <Reveal className="sec-head" data-tight="">
          <p className="overline">FAQ</p>
          <h2 id="faq-title" className="sec-title">
            Questions people ask first.
          </h2>
        </Reveal>
        <Reveal>
          <Faq items={FAQ} />
        </Reveal>
      </div>
    </section>
  )
}
