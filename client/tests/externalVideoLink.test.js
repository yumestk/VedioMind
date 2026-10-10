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
  let focusCount = 0
  const videoWindow = {
    closed: false,
    location: { href: '' },
    focus: () => { focusCount += 1 },
    opener: {}
  }
  globalThis.window = {
    open: (url) => {
      openCount += 1
      videoWindow.location.href = url
      return videoWindow
    }
  }

  assert.equal(openExternalVideoLink('https://example.com/video?t=10s'), true)
  assert.equal(openExternalVideoLink('https://example.com/video?t=20s'), true)
  assert.equal(openCount, 1)
  assert.equal(focusCount, 1)
  assert.equal(videoWindow.location.href, 'https://example.com/video?t=20s')
  assert.equal(videoWindow.opener, null)

  delete globalThis.window
})
