import {
  abortMediaUpload,
  completeMediaUpload,
  getMediaUpload,
  getMediaUploadPartUrl,
  initiateMediaUpload,
  putMediaUploadPart
} from '../api/media'

const CONCURRENCY = 3
const MAX_ATTEMPTS = 3
const STORAGE_KEY = 'pending-media-upload'

export const fileFingerprint = file => `${file.name}:${file.size}:${file.lastModified}`

const wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

export class MultipartUploader {
  constructor(file, userId, onProgress) {
    this.file = file
    this.userId = userId
    this.onProgress = onProgress
    this.sessionId = null
    this.partSize = 0
    this.parts = new Map()
    this.activeBytes = new Map()
    this.controllers = new Set()
    this.paused = false
    this.cancelled = false
    this.startedAt = 0
    this.baseUploadedBytes = 0
    this.initialUploadedBytes = 0
  }

  async start() {
    this.paused = false
    this.cancelled = false
    const completedMedia = await this.restoreOrInitiate()
    if (completedMedia) return completedMedia
    this.startedAt = Date.now()
    this.baseUploadedBytes = [...this.parts.values()].reduce((total, part) => total + part.size, 0)
    this.initialUploadedBytes = this.baseUploadedBytes
    this.emitProgress()

    const totalParts = Math.ceil(this.file.size / this.partSize)
    const pending = Array.from({ length: totalParts }, (_, index) => index + 1)
      .filter(partNumber => !this.parts.has(partNumber))
    let cursor = 0
    const worker = async () => {
      while (cursor < pending.length && !this.paused && !this.cancelled) {
        const partNumber = pending[cursor++]
        await this.uploadPart(partNumber)
      }
    }
    try {
      await Promise.all(Array.from({ length: Math.min(CONCURRENCY, pending.length) }, worker))
    } catch (error) {
      this.paused = true
      this.abortActiveRequests()
      throw error
    }

    if (this.paused || this.cancelled) return null
    const parts = [...this.parts.values()]
      .sort((left, right) => left.partNumber - right.partNumber)
      .map(({ partNumber, etag }) => ({ partNumber, etag }))
    const media = await completeMediaUpload(this.sessionId, this.userId, parts)
    this.clearStoredSession()
    this.onProgress?.({ percent: 100, speed: 0, eta: 0 })
    return media
  }

  pause() {
    this.paused = true
    this.abortActiveRequests()
  }

  async cancel() {
    this.cancelled = true
    this.abortActiveRequests()
    if (this.sessionId) await abortMediaUpload(this.sessionId, this.userId)
    this.clearStoredSession()
  }

  async restoreOrInitiate() {
    const stored = this.readStoredSession()
    if (stored?.userId === this.userId && stored.fingerprint === fileFingerprint(this.file)) {
      try {
        const session = await getMediaUpload(stored.sessionId, this.userId)
        if (session.status === 'UPLOADING') {
          this.applySession(session)
          return
        }
        if (session.status === 'COMPLETED' && session.mediaId) {
          this.clearStoredSession()
          return { id: session.mediaId }
        }
      } catch {
        this.clearStoredSession()
      }
    }

    const created = await initiateMediaUpload(this.file, this.userId)
    this.sessionId = created.sessionId
    this.partSize = created.partSize
    this.storeSession()
    return null
  }

  applySession(session) {
    this.sessionId = session.sessionId
    this.partSize = session.partSize
    this.parts = new Map(session.uploadedParts.map(part => [part.partNumber, part]))
  }

  async uploadPart(partNumber) {
    const start = (partNumber - 1) * this.partSize
    const end = Math.min(start + this.partSize, this.file.size)
    const blob = this.file.slice(start, end)

    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      if (this.paused || this.cancelled) return
      const controller = new AbortController()
      this.controllers.add(controller)
      this.activeBytes.set(partNumber, 0)
      try {
        const { url } = await getMediaUploadPartUrl(this.sessionId, partNumber, this.userId)
        const etag = await putMediaUploadPart(url, blob, controller.signal, loaded => {
          this.activeBytes.set(partNumber, loaded)
          this.emitProgress()
        })
        this.parts.set(partNumber, { partNumber, etag, size: blob.size })
        this.baseUploadedBytes += blob.size
        this.activeBytes.delete(partNumber)
        this.emitProgress()
        return
      } catch (error) {
        this.activeBytes.delete(partNumber)
        if (this.paused || this.cancelled) return
        if (attempt === MAX_ATTEMPTS) throw error
        await wait(500 * 2 ** (attempt - 1))
      } finally {
        this.controllers.delete(controller)
      }
    }
  }

  emitProgress() {
    const active = [...this.activeBytes.values()].reduce((total, bytes) => total + bytes, 0)
    const uploaded = Math.min(this.file.size, this.baseUploadedBytes + active)
    const elapsedSeconds = Math.max((Date.now() - this.startedAt) / 1000, 0.001)
    const speed = Math.max(0, uploaded - this.initialUploadedBytes) / elapsedSeconds
    const remaining = this.file.size - uploaded
    this.onProgress?.({
      percent: Math.round((uploaded / this.file.size) * 100),
      speed,
      eta: speed > 0 ? Math.ceil(remaining / speed) : null
    })
  }

  abortActiveRequests() {
    this.controllers.forEach(controller => controller.abort())
    this.controllers.clear()
    this.activeBytes.clear()
  }

  storeSession() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      userId: this.userId,
      fingerprint: fileFingerprint(this.file),
      sessionId: this.sessionId
    }))
  }

  readStoredSession() {
    try {
      return JSON.parse(localStorage.getItem(STORAGE_KEY))
    } catch {
      return null
    }
  }

  clearStoredSession() {
    localStorage.removeItem(STORAGE_KEY)
  }
}
