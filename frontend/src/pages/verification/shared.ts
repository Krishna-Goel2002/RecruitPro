import { ApiError, errorMessage } from '../../api/client'
import { MAX_ACTIVE_SOURCES, MAX_OPEN_REFERENCES, MAX_REFERENCES_PER_DAY } from '../../lib/verification'

/** "about N minutes" (or hours) from a 429's Retry-After, or null when the server did not say. */
export function waitFor(error: ApiError): string | null {
  const seconds = error.retryAfterSeconds
  if (seconds == null || seconds <= 0) return null
  if (seconds < 90) return 'about a minute'
  if (seconds < 90 * 60) return `about ${Math.ceil(seconds / 60)} minutes`
  return `about ${Math.ceil(seconds / 3600)} hours`
}

/**
 * Plain-language messages for the verification endpoints' conflict and limit answers (WP-VBE notes 2.1; integration
 * pass: business caps are 409 with a code, rate limits 429 with Retry-After). `context` picks the wording for the call
 * that failed.
 */
export function verificationErrorMessage(error: unknown, context: 'run' | 'ownership' | 'connect' | 'reference' | 'respond' = 'run'): string {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      if (error.code === 'REFERENCE_LIMIT') return `You already have ${MAX_OPEN_REFERENCES} open reference requests. Wait for an answer or cancel one first.`
      if (error.code === 'SOURCE_LIMIT') return `You can connect at most ${MAX_ACTIVE_SOURCES} sources. Remove one you no longer need first.`
      if (context === 'connect') return 'This source is already connected. Revoke the existing one first if you want to connect it again.'
      if (context === 'run' || context === 'respond') return 'A verification of this source is already running. Please wait for it to finish.'
    }
    if (error.status === 429) {
      const wait = waitFor(error)
      if (context === 'ownership') return `You have checked ownership many times in the last hour. Please wait ${wait ?? 'a while'} and try again.`
      if (context === 'reference') return `You can send at most ${MAX_REFERENCES_PER_DAY} reference requests in 24 hours. Please try again ${wait ? `in ${wait}` : 'tomorrow'}.`
      if (context === 'run' || context === 'respond') return `You have reached the limit of 10 verifications in 24 hours. Please try again ${wait ? `in ${wait}` : 'tomorrow'}.`
      if (context === 'connect') return `You started connecting GitHub too often. Please wait ${wait ?? 'a while'} and try again.`
      return 'Too many attempts. Please wait a few minutes and try again.'
    }
    if (error.status === 503 && context === 'ownership') {
      return 'The checking service is not available right now. Please try again later.'
    }
  }
  return errorMessage(error)
}
