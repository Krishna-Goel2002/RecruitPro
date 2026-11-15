import { ApiError, errorMessage } from '../../api/client'

/**
 * Plain-language messages for the verification endpoints' conflict and limit answers (WP-VBE notes 2.1).
 * `context` picks the wording for the call that failed.
 */
export function verificationErrorMessage(error: unknown, context: 'run' | 'ownership' | 'connect' | 'reference' | 'respond' = 'run'): string {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      if (context === 'connect') return 'This source is already connected. Revoke the existing one first if you want to connect it again.'
      if (context === 'run' || context === 'respond') return 'A verification of this source is already running. Please wait for it to finish.'
    }
    if (error.status === 429) {
      if (context === 'ownership') return 'You have checked ownership many times in the last hour. Please wait a while and try again.'
      if (context === 'reference') return 'You already have 5 open reference requests. Wait for an answer or cancel one first.'
      if (context === 'run' || context === 'respond') return 'You have reached the limit of 10 verifications in 24 hours. Please try again tomorrow.'
      return 'Too many attempts. Please wait a few minutes and try again.'
    }
    if (error.status === 503 && context === 'ownership') {
      return 'The checking service is not available right now. Please try again later.'
    }
  }
  return errorMessage(error)
}
