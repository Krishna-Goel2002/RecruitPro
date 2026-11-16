import { describe, expect, it } from 'vitest'
import { file } from '../test/utils'
import { extensionOf, mergeWorkSampleFiles, validateDocumentFile, validateWorkSampleFile, validateWorkSampleFiles } from './files'

const MB = 1024 * 1024

describe('extensionOf', () => {
  it('reads the last extension in lower case', () => {
    expect(extensionOf('Report.Final.PDF')).toBe('pdf')
    expect(extensionOf('.bashrc')).toBe('')
    expect(extensionOf('noext')).toBe('')
  })
})

describe('validateDocumentFile (verification documents: pdf/docx/pptx, 15 MB)', () => {
  it('accepts the allowed types up to the limit', () => {
    expect(validateDocumentFile(file('cert.pdf', 15 * MB))).toBeNull()
    expect(validateDocumentFile(file('slides.pptx', 10))).toBeNull()
  })
  it('explains each refusal', () => {
    expect(validateDocumentFile(null)).toMatch(/Choose a PDF/)
    expect(validateDocumentFile(file('cv.doc', 10))).toMatch(/Only PDF, DOCX and PPTX/)
    expect(validateDocumentFile(file('empty.pdf', 0))).toBe('This file is empty.')
    expect(validateDocumentFile(file('big.pdf', 15 * MB + 1))).toMatch(/limit is 15 MB/)
  })
})

describe('work-sample attachments (pdf, zip, docx, pptx, png, jpg/jpeg; 20 MB; at most 3)', () => {
  it('checks type and size per file', () => {
    expect(validateWorkSampleFile(file('a.JPEG', 10))).toBeNull()
    expect(validateWorkSampleFile(file('a.exe', 10))).toMatch(/only PDF, ZIP/)
    expect(validateWorkSampleFile(file('a.zip', 20 * MB + 1))).toMatch(/limit is 20 MB per file/)
    expect(validateWorkSampleFile(file('a.zip', 0))).toBe('a.zip is empty.')
  })

  it('checks the set', () => {
    expect(validateWorkSampleFiles([file('1.pdf'), file('2.pdf'), file('3.pdf'), file('4.pdf')])).toContain('You can attach at most 3 files.')
    expect(validateWorkSampleFiles([file('1.pdf')])).toEqual([])
  })

  it('merges new files, reports the ones over the limit and skips duplicates', () => {
    const a = file('a.pdf', 10)
    const merged = mergeWorkSampleFiles([a], [file('b.pdf', 20), file('c.pdf', 30), file('d.pdf', 40)])
    expect(merged.files.map(f => f.name)).toEqual(['a.pdf', 'b.pdf', 'c.pdf'])
    expect(merged.problems[0]).toBe('You can attach at most 3 files, so 1 file was not added.')

    const again = mergeWorkSampleFiles([a], [file('a.pdf', 10)])
    expect(again.files).toHaveLength(1)
    expect(again.problems).toEqual(['One file is already attached.'])

    const bad = mergeWorkSampleFiles([], [file('virus.exe', 10)])
    expect(bad.files).toHaveLength(1)
    expect(bad.problems[0]).toMatch(/virus.exe: only PDF/)
  })
})
