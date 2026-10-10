const timestampSeconds = (milliseconds) => {
  const value = Number(milliseconds)
  return Number.isFinite(value) && value > 0 ? Math.floor(value / 1000) : 0
}

const bilibiliTimestamp = (seconds) => {
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const remainingSeconds = seconds % 60

  return [
    hours ? `${hours}h` : '',
    minutes ? `${minutes}m` : '',
    `${remainingSeconds}s`
  ].join('')
}

const EXTERNAL_VIDEO_WINDOW = 'vediomind-external-video'

export const buildExternalVideoLink = (platform, sourceUrl, milliseconds) => {
  let url
  try {
    url = new URL(sourceUrl)
  } catch {
    return null
  }

  const seconds = timestampSeconds(milliseconds)
  switch (platform) {
    case 'YOUTUBE':
      url.searchParams.set('t', `${seconds}s`)
      break
    case 'BILIBILI':
      url.searchParams.set('t', bilibiliTimestamp(seconds))
      break
    default:
      return null
  }
  return url.toString()
}

export const openExternalVideoLink = (url) => {
  const externalVideoWindow = window.open(url, EXTERNAL_VIDEO_WINDOW)
  if (!externalVideoWindow) return false
  try {
    externalVideoWindow.opener = null
  } catch {
    // A reused cross-origin window already had its opener removed when created.
  }
  externalVideoWindow.focus()
  return true
}
