import test from 'node:test'
import assert from 'node:assert/strict'
import { buildExternalVideoLink, openExternalVideoLink } from '../src/utils/externalVideoLink.js'

test('builds platform timestamp links and rejects unsupported input', () => {
  const youtube = new URL(buildExternalVideoLink(
    'YOUTUBE',
    'https://www.youtube.com/watch?v=kNareBFFWtQ&t=10s',
    90_999
  ))
  assert.equal(youtube.searchParams.get('v'), 'kNareBFFWtQ')
  assert.equal(youtube.searchParams.get('t'), '90s')

  const bilibili = new URL(buildExternalVideoLink(
    'BILIBILI',
    'https://www.bilibili.com/video/BV1jKat6REbW',
    3_710_000
  ))
  assert.equal(bilibili.searchParams.get('t'), '1h1m50s')

  assert.equal(new URL(buildExternalVideoLink('YOUTUBE', 'https://youtu.be/example', -1)).searchParams.get('t'), '0s')
  assert.equal(buildExternalVideoLink('UNKNOWN', 'https://example.com/video', 1_000), null)
  assert.equal(buildExternalVideoLink('YOUTUBE', 'not a url', 1_000), null)
})

test('reuses the external video window for later timestamps', () => {
  let openCount = 0
  let createdWindowCount = 0
  let focusCount = 0
  let opener = {}
  const videoWindow = {
    closed: false,
    location: { href: '' },
    focus: () => { focusCount += 1 }
  }
  Object.defineProperty(videoWindow, 'opener', {
    get: () => opener,
    set: (value) => {
      if (openCount > 1) throw new DOMException('cross-origin window', 'SecurityError')
      opener = value
    }
  })
  const namedWindows = new Map()
  globalThis.window = {
    open: (url, name) => {
      openCount += 1
      if (!namedWindows.has(name)) {
        namedWindows.set(name, videoWindow)
        createdWindowCount += 1
      }
      const opened = namedWindows.get(name)
      opened.location.href = url
      return opened
    }
  }

  assert.equal(openExternalVideoLink('https://example.com/video?t=10s'), true)
  assert.equal(openExternalVideoLink('https://example.com/video?t=20s'), true)
  assert.equal(openCount, 2)
  assert.equal(createdWindowCount, 1)
  assert.equal(focusCount, 2)
  assert.equal(videoWindow.location.href, 'https://example.com/video?t=20s')
  assert.equal(opener, null)

  delete globalThis.window
})
