import { describe, expect, it } from 'vitest'
import { withBaseHref } from './draftHtml'

const BASE = 'https://apps.example/'

describe('withBaseHref', () => {
  it('puts <base> first in <head>, before anything that loads', () => {
    expect(withBaseHref('<!doctype html><html><head lang="pl"><script src="/_lib/tailwind.js"></script>', BASE)).toBe(
      '<!doctype html><html><head lang="pl"><base href="https://apps.example/"><script src="/_lib/tailwind.js"></script>',
    )
  })

  it('without a <head>, goes right after the doctype or at the start', () => {
    expect(withBaseHref('<!DOCTYPE html><p>x</p>', BASE)).toBe('<!DOCTYPE html><base href="https://apps.example/"><p>x</p>')
    expect(withBaseHref('<p>x</p>', BASE)).toBe('<base href="https://apps.example/"><p>x</p>')
  })

  it('does not take <header> for <head>', () => {
    expect(withBaseHref('<header>x</header>', BASE)).toBe('<base href="https://apps.example/"><header>x</header>')
  })

  it('leaves the HTML alone while the base is not known yet', () => {
    expect(withBaseHref('<html></html>', undefined)).toBe('<html></html>')
  })

  it('cannot break out of the attribute', () => {
    expect(withBaseHref('<p>', 'https://a/"><script>')).toBe('<base href="https://a/&quot;><script>"><p>')
  })
})
