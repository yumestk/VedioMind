import test from 'node:test'
import assert from 'node:assert/strict'
import { buildExternalVideoLink } from '../src/utils/externalVideoLink.js'

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
