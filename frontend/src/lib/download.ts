/** Hands a file to the browser's download. */
export function saveFile(content: Blob, fileName: string): void {
  const link = document.createElement('a')
  link.href = URL.createObjectURL(content)
  link.download = fileName
  link.click()
  setTimeout(() => URL.revokeObjectURL(link.href), 10_000)
}

/** A file name made from an app's title: characters Windows and macOS refuse become '-'. */
export function safeFileName(title: string, extension: string): string {
  const base = title
    .replace(/[\\/:*?"<>|\u0000-\u001f]+/g, '-')
    .replace(/\s+/g, ' ')
    .replace(/^[\s.-]+|[\s.-]+$/g, '')
  return `${base || 'app'}.${extension}`
}

/** The file name from a Content-Disposition header (plain or RFC 5987 `filename*=`), or null. */
export function fileNameFromDisposition(header: string | null): string | null {
  if (!header) return null
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1].trim())
    } catch {
      // Malformed encoding: fall through to the plain name.
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)
  return plain ? plain[1].trim() : null
}
