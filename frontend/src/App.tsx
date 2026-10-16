import { useEffect, useState } from 'react'

export function App() {
  const [message, setMessage] = useState('Loading…')

  useEffect(() => {
    fetch(`${import.meta.env.VITE_API_BASE_URL}/api/hello`)
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`)
        return response.json() as Promise<{ message: string }>
      })
      .then((body) => setMessage(body.message))
      .catch((error: Error) => setMessage(`API unreachable: ${error.message}`))
  }, [])

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', padding: 32 }}>
      <h1>Hello from ShitCloud GUI</h1>
      <p>{message}</p>
    </main>
  )
}
