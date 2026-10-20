import { describe, expect, it } from 'vitest'
import { fileNameFromDisposition, safeFileName } from './download'

describe('safeFileName', () => {
  it('keeps a plain title', () => {
    expect(safeFileName('Team Calendar', 'html')).toBe('Team Calendar.html')
  })

  it('keeps letters with diacritics', () => {
    expect(safeFileName('Głosowanie na lunch', 'html')).toBe('Głosowanie na lunch.html')
  })

  it('replaces characters file systems refuse', () => {
    expect(safeFileName('Q1/Q2: plan? <draft> "v2" | a\\b *', 'html')).toBe('Q1-Q2- plan- -draft- -v2- - a-b.html')
  })

  it('falls back to "app" when nothing usable is left', () => {
    expect(safeFileName('///', 'html')).toBe('app.html')
    expect(safeFileName('   ', 'html')).toBe('app.html')
  })
})

describe('fileNameFromDisposition', () => {
  it('reads a quoted file name', () => {
    expect(fileNameFromDisposition('attachment; filename="kalendarz-data.json"')).toBe('kalendarz-data.json')
  })

  it('prefers the encoded file name', () => {
    expect(fileNameFromDisposition("attachment; filename=\"x.json\"; filename*=UTF-8''g%C5%82os-data.json")).toBe(
      'głos-data.json',
    )
  })

  it('is null without a header or a name', () => {
    expect(fileNameFromDisposition(null)).toBeNull()
    expect(fileNameFromDisposition('attachment')).toBeNull()
  })
})
