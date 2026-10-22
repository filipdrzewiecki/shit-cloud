import { describe, expect, it } from 'vitest'
import { toolLine } from './toolLabels'

describe('toolLine', () => {
  it('describes save_draft without its input (a whole app)', () => {
    expect(toolLine({ type: 'tool-save_draft', state: 'input-available', input: { html: '<html>…' } }))
      .toEqual({ text: 'Saving the app…', state: 'busy' })
    expect(toolLine({ type: 'tool-save_draft', state: 'output-available', output: { saved: true, bytes: 6048 } }))
      .toEqual({ text: 'Saved the app (6 KB)', state: 'done' })
    expect(toolLine({ type: 'tool-save_draft', state: 'output-error', errorText: 'too large' }))
      .toEqual({ text: 'Could not save the app: too large', state: 'error' })
  })

  it('names the app get_app read', () => {
    expect(toolLine({ type: 'tool-get_app', state: 'output-available', input: { slug: 'lunch-poll' } }).text)
      .toBe('Read lunch-poll')
    expect(toolLine({ type: 'tool-get_app', state: 'output-error', input: { slug: 'x' }, errorText: 'There is no app x' }).text)
      .toBe('Could not read x: There is no app x')
  })

  it('counts the changes edit_draft made', () => {
    expect(toolLine({ type: 'tool-edit_draft', state: 'input-available', input: { edits: [] } }))
      .toEqual({ text: 'Editing the app…', state: 'busy' })
    expect(toolLine({ type: 'tool-edit_draft', state: 'output-available', output: { edited: 2, bytes: 49152 } }).text)
      .toBe('Edited the app (2 changes, 48 KB)')
    expect(toolLine({ type: 'tool-edit_draft', state: 'output-available', output: { edited: 1, bytes: 100 } }).text)
      .toBe('Edited the app (1 change, 1 KB)')
    expect(toolLine({ type: 'tool-edit_draft', state: 'output-error', errorText: 'edit 2: old_text found 3 times' }).text)
      .toBe('Could not edit the app: edit 2: old_text found 3 times')
  })

  it('says what list_spaces and sample_docs looked at, never the data', () => {
    expect(toolLine({ type: 'tool-list_spaces', state: 'output-available', output: { spaces: 7 } }).text)
      .toBe('Listed 7 apps and their data')
    expect(toolLine({ type: 'tool-list_spaces', state: 'output-available', output: { spaces: 1 } }).text)
      .toBe('Listed 1 app and their data')
    const input = { space: 'team-calendar', collection: 'marks' }
    expect(toolLine({ type: 'tool-sample_docs', state: 'input-available', input }).text)
      .toBe('Looking at documents of team-calendar / marks…')
    expect(toolLine({ type: 'tool-sample_docs', state: 'output-available', input, output: { docs: 3 } }).text)
      .toBe('Looked at 3 documents of team-calendar / marks')
    expect(toolLine({ type: 'tool-sample_docs', state: 'output-error', input, errorText: 'No app x' }).text)
      .toBe('Could not read documents of team-calendar / marks: No app x')
  })

  it('says whose data get_deps checked and how many other apps use it', () => {
    const input = { space: 'team-calendar' }
    expect(toolLine({ type: 'tool-get_deps', state: 'input-available', input }).text)
      .toBe("Checking who uses team-calendar's data…")
    expect(toolLine({ type: 'tool-get_deps', state: 'output-available', input, output: { otherApps: 2 } }).text)
      .toBe("Checked who uses team-calendar's data (2 other apps)")
    expect(toolLine({ type: 'tool-get_deps', state: 'output-available', input, output: { otherApps: 1 } }).text)
      .toBe("Checked who uses team-calendar's data (1 other app)")
    expect(toolLine({ type: 'tool-get_deps', state: 'output-available', input, output: { otherApps: 0 } }).text)
      .toBe("Checked who uses team-calendar's data (no other apps)")
  })

  it('describes propose_migration by the documents it checked', () => {
    expect(toolLine({ type: 'tool-propose_migration', state: 'input-available', input: { code: 'function migrate…' } }))
      .toEqual({ text: 'Checking a change of the stored data…', state: 'busy' })
    const done = (output: unknown) => toolLine({ type: 'tool-propose_migration', state: 'output-available', output }).text
    expect(done({ migration: 7, documents: 412, changed: 412, removed: 0, failed: 0 }))
      .toBe('Checked a change of the stored data (412 documents)')
    expect(done({ migration: 7, documents: 1, changed: 0, removed: 0, failed: 1 }))
      .toBe('Checked a change of the stored data (1 document, 1 failed)')
    expect(toolLine({ type: 'tool-propose_migration', state: 'output-error', errorText: 'no data' }).text)
      .toBe('Could not check the change of the stored data: no data')
  })

  it('describes the import tools by file and number of changes', () => {
    const line = (type: string, output?: unknown) => toolLine({ type, state: 'output-available', output }).text
    expect(toolLine({ type: 'tool-get_upload', state: 'input-available', input: {} }).text).toBe('Reading the uploaded file…')
    expect(line('tool-get_upload', { fileName: 'planner.html', bytes: 84000 })).toBe('Read planner.html (82 KB)')
    expect(line('tool-report_changes', { changes: 1 })).toBe('Listed 1 change')
    expect(line('tool-report_changes', { changes: 4 })).toBe('Listed 4 changes')
  })

  it('has a plain line for tools it does not know', () => {
    expect(toolLine({ type: 'tool-restore_doc', state: 'output-available' }).text).toBe('restore_doc: done')
  })
})
