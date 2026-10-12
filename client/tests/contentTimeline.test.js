import test from 'node:test'
import assert from 'node:assert/strict'
import { buildContentTimeline, timelineSegmentSections } from '../src/utils/contentTimeline.js'

const segment = (id, startMs, endMs, text = `字幕${id}`) => ({ id, startMs, endMs, text })

test('groups sentence evidence into chapter paragraphs without losing segment ids', () => {
  const chapters = [
    { id: 10, title: '第一章', startMs: 0, endMs: 120_000 },
    { id: 11, title: '第二章', startMs: 120_000, endMs: 240_000 }
  ]
  const segments = [
    segment(1, 0, 30_000),
    segment(2, 30_000, 61_000),
    segment(3, 70_000, 100_000),
    segment(4, 120_000, 150_000),
    segment(5, 150_000, 181_000)
  ]

  const timeline = buildContentTimeline(chapters, segments)

  assert.equal(timeline.length, 2)
  assert.deepEqual(timeline[0].paragraphs.map(item => item.segmentIds), [[1, 2], [3]])
  assert.deepEqual(timeline[1].paragraphs.map(item => item.segmentIds), [[4, 5]])
  assert.deepEqual(
    timeline.flatMap(section => section.paragraphs.flatMap(item => item.segmentIds)),
    [1, 2, 3, 4, 5]
  )
})

test('starts a new paragraph after long silence or before the text becomes too large', () => {
  const timeline = buildContentTimeline([], [
    segment(1, 0, 5_000, 'a'.repeat(250)),
    segment(2, 6_000, 10_000, 'b'.repeat(200)),
    segment(3, 30_000, 35_000, '短句')
  ])

  assert.deepEqual(timeline[0].paragraphs.map(item => item.segmentIds), [[1], [2], [3]])
})

test('builds five-minute fallback sections when semantic chapters are unavailable', () => {
  const timeline = buildContentTimeline([], [
    segment(1, 10_000, 20_000),
    segment(2, 301_000, 310_000),
    segment(3, 620_000, 630_000)
  ])

  assert.deepEqual(timeline.map(section => [section.startMs, section.endMs]), [
    [0, 300_000],
    [300_000, 600_000],
    [600_000, 900_000]
  ])
  assert.ok(timeline.every(section => section.synthetic))
})

test('maps search results back to their timeline sections', () => {
  const timeline = buildContentTimeline(
    [{ id: 10, title: '主题', startMs: 0, endMs: 60_000 }],
    [segment(1, 0, 10_000), segment(2, 10_000, 20_000)]
  )

  const sections = timelineSegmentSections(timeline)

  assert.equal(sections.get(1).title, '主题')
  assert.equal(sections.get(2).id, 'chapter-10')
})

test('keeps empty chapters available for navigation', () => {
  const timeline = buildContentTimeline(
    [{ id: 10, title: '空章节', startMs: 0, endMs: 60_000 }],
    []
  )

  assert.equal(timeline.length, 1)
  assert.deepEqual(timeline[0].paragraphs, [])
})
