import test from 'node:test'
import assert from 'node:assert/strict'
import { buildExternalEmbedUrl } from '../src/utils/externalVideoEmbed.js'

test('builds official embeds at the requested timestamp', () => {
  const initialYoutube = new URL(buildExternalEmbedUrl(
    'YOUTUBE',
    'https://www.youtube.com/watch?v=kNareBFFWtQ'
  ))
  assert.equal(initialYoutube.hostname, 'www.youtube-nocookie.com')
  assert.equal(initialYoutube.pathname, '/embed/kNareBFFWtQ')
  assert.equal(initialYoutube.searchParams.get('autoplay'), '0')
  assert.equal(initialYoutube.searchParams.has('start'), false)

  const youtube = new URL(buildExternalEmbedUrl(
    'YOUTUBE',
    'https://youtu.be/kNareBFFWtQ',
    90_999
  ))
  assert.equal(youtube.searchParams.get('start'), '90')
  assert.equal(youtube.searchParams.get('autoplay'), '1')

  const bilibili = new URL(buildExternalEmbedUrl(
    'BILIBILI',
    'https://www.bilibili.com/video/BV1jKat6REbW',
    3_710_000
  ))
  assert.equal(bilibili.hostname, 'player.bilibili.com')
  assert.equal(bilibili.searchParams.get('bvid'), 'BV1jKat6REbW')
  assert.equal(bilibili.searchParams.get('t'), '3710')
  assert.equal(bilibili.searchParams.get('autoplay'), '1')

  assert.equal(buildExternalEmbedUrl('UNKNOWN', 'https://example.com/video', 1_000), null)
  assert.equal(buildExternalEmbedUrl('YOUTUBE', 'not a url', 1_000), null)
  assert.equal(buildExternalEmbedUrl('YOUTUBE', 'https://youtube.com/watch?v=invalid', 1_000), null)
})
