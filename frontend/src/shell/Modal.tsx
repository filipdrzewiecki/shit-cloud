import { type ReactNode, useEffect, useRef } from 'react'

/**
 * A modal on the native <dialog>: focus stays inside, Escape closes it, the page
 * behind is inert. Rendered only while open, so its state starts fresh each time.
 */
export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dialog = ref.current
    dialog?.showModal()
    return () => dialog?.close()
  }, [])

  return (
    <dialog
      ref={ref}
      className="modal"
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
    >
      <div className="modal-head">
        <h2>{title}</h2>
        <button type="button" className="link-button" onClick={onClose} aria-label="Close">
          ✕
        </button>
      </div>
      {children}
    </dialog>
  )
}
