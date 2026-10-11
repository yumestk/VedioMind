const timestampSeconds = (milliseconds) => {
  const value = Number(milliseconds)
  return Number.isFinite(value) && value > 0 ? Math.floor(value / 1000) : 0
}

const youtubeVideoId = (url) => {
  const id = url.hostname === 'youtu.be'
    ? url.pathname.split('/').filter(Boolean)[0]
    : url.searchParams.get('v')
  return /^[A-Za-z0-9_-]{11}$/.test(id || '') ? id : null
}

const bilibiliVideoId = (url) => url.pathname.match(/\/video\/(BV[0-9A-Za-z]{10})(?:\/|$)/)?.[1] || null

export const buildExternalEmbedUrl = (platform, sourceUrl, milliseconds = null) => {
  let source
  try {
    source = new URL(sourceUrl)
  } catch {
    return null
  }

  const shouldAutoplay = milliseconds != null
  const seconds = timestampSeconds(milliseconds)

  if (platform === 'YOUTUBE') {
    const videoId = youtubeVideoId(source)
    if (!videoId) return null
    const embed = new URL(`https://www.youtube-nocookie.com/embed/${videoId}`)
    embed.searchParams.set('autoplay', shouldAutoplay ? '1' : '0')
    embed.searchParams.set('playsinline', '1')
    if (shouldAutoplay) embed.searchParams.set('start', String(seconds))
    return embed.toString()
  }

  if (platform === 'BILIBILI') {
    const videoId = bilibiliVideoId(source)
    if (!videoId) return null
    const embed = new URL('https://player.bilibili.com/player.html')
    embed.searchParams.set('bvid', videoId)
    embed.searchParams.set('autoplay', shouldAutoplay ? '1' : '0')
    embed.searchParams.set('poster', '1')
    embed.searchParams.set('danmaku', '0')
    if (shouldAutoplay) embed.searchParams.set('t', String(seconds))
    return embed.toString()
  }

  return null
}
