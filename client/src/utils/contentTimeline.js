const PARAGRAPH_TARGET_MS = 60_000
const PARAGRAPH_MAX_CHARACTERS = 400
const SILENCE_BREAK_MS = 15_000
const FALLBACK_SECTION_MS = 5 * 60_000

const paragraphFrom = (segments) => ({
  id: `paragraph-${segments[0].id}-${segments.at(-1).id}`,
  startMs: segments[0].startMs,
  endMs: segments.at(-1).endMs,
  text: segments.map(segment => segment.text).join(' '),
  segmentIds: segments.map(segment => segment.id),
  segments: [...segments]
})

const groupParagraphs = (segments) => {
  const paragraphs = []
  let current = []
  let characterCount = 0

  const flush = () => {
    if (!current.length) return
    paragraphs.push(paragraphFrom(current))
    current = []
    characterCount = 0
  }

  for (const segment of segments) {
    const previous = current.at(-1)
    const gapMs = previous ? segment.startMs - previous.endMs : 0
    const nextCharacterCount = characterCount + (current.length ? 1 : 0) + segment.text.length

    if (current.length && (gapMs >= SILENCE_BREAK_MS || nextCharacterCount > PARAGRAPH_MAX_CHARACTERS)) {
      flush()
    }

    current.push(segment)
    characterCount += (current.length > 1 ? 1 : 0) + segment.text.length

    if (segment.endMs - current[0].startMs >= PARAGRAPH_TARGET_MS) {
      flush()
    }
  }

  flush()
  return paragraphs
}

const chapterSections = (chapters, segments, keyframes) => {
  const sections = chapters.map((chapter, index) => ({
    id: `chapter-${chapter.id}`,
    index,
    title: chapter.title,
    startMs: chapter.startMs,
    endMs: chapter.endMs,
    synthetic: false,
    segments: [],
    keyframes: []
  }))

  let sectionIndex = 0
  for (const segment of segments) {
    while (
      sectionIndex < sections.length - 1
      && segment.startMs >= sections[sectionIndex + 1].startMs
    ) {
      sectionIndex++
    }
    sections[sectionIndex].segments.push(segment)
  }
  sectionIndex = 0
  for (const keyframe of keyframes) {
    while (
      sectionIndex < sections.length - 1
      && keyframe.timestampMs >= sections[sectionIndex + 1].startMs
    ) {
      sectionIndex++
    }
    sections[sectionIndex].keyframes.push(keyframe)
  }
  return sections
}

const fallbackSections = (segments, keyframes) => {
  const sectionsByStart = new Map()
  const sectionFor = (timestampMs) => {
    const bucketStart = Math.floor(timestampMs / FALLBACK_SECTION_MS) * FALLBACK_SECTION_MS
    if (!sectionsByStart.has(bucketStart)) {
      sectionsByStart.set(bucketStart, {
        id: `time-${bucketStart}`,
        index: 0,
        title: '',
        startMs: bucketStart,
        endMs: bucketStart + FALLBACK_SECTION_MS,
        synthetic: true,
        segments: [],
        keyframes: []
      })
    }
    return sectionsByStart.get(bucketStart)
  }

  for (const segment of segments) sectionFor(segment.startMs).segments.push(segment)
  for (const keyframe of keyframes) sectionFor(keyframe.timestampMs).keyframes.push(keyframe)

  return [...sectionsByStart.values()]
    .sort((left, right) => left.startMs - right.startMs)
    .map((section, index) => ({ ...section, index }))
}

export const buildContentTimeline = (chapters = [], transcriptSegments = [], videoKeyframes = []) => {
  const segments = [...transcriptSegments].sort((left, right) => left.startMs - right.startMs)
  const sortedChapters = [...chapters].sort((left, right) => left.startMs - right.startMs)
  const keyframes = [...videoKeyframes].sort((left, right) => left.timestampMs - right.timestampMs)
  if (!segments.length && !sortedChapters.length && !keyframes.length) return []

  const sections = sortedChapters.length
    ? chapterSections(sortedChapters, segments, keyframes)
    : fallbackSections(segments, keyframes)

  return sections.map(({ segments: sectionSegments, ...section }) => ({
    ...section,
    paragraphs: groupParagraphs(sectionSegments)
  }))
}

export const timelineSegmentSections = (timeline) => {
  const sectionBySegmentId = new Map()
  for (const section of timeline) {
    for (const paragraph of section.paragraphs) {
      for (const segmentId of paragraph.segmentIds) {
        sectionBySegmentId.set(segmentId, section)
      }
    }
  }
  return sectionBySegmentId
}
