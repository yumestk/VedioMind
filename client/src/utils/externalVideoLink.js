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

let externalVideoWindow = null

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
  if (externalVideoWindow && !externalVideoWindow.closed) {
    externalVideoWindow.location.href = url
    externalVideoWindow.focus()
    return true
  }

  externalVideoWindow = window.open(url, '_blank')
  if (!externalVideoWindow) return false
  externalVideoWindow.opener = null
  return true
}
