import { useEffect, useState } from 'react'
import { DESKS, me, type DeskId, type Me } from './api'
import DeskHome from './DeskHome'
import LoginForm from './LoginForm'

const STORAGE_KEY = 'trade-desk.desk'

function savedDesk(): DeskId | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return DESKS.some((desk) => desk.id === value) ? (value as DeskId) : null
  } catch {
    return null
  }
}

function remember(desk: DeskId | null) {
  try {
    if (desk) localStorage.setItem(STORAGE_KEY, desk)
    else localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Private windows can block storage; the app works without it.
  }
}

export default function App() {
  const [desk, setDesk] = useState<DeskId | null>(savedDesk)
  const [user, setUser] = useState<Me | null>(null)
  const [checking, setChecking] = useState(desk !== null)

  // After a reload, pick up an existing session for the last desk instead of asking to log in again.
  useEffect(() => {
    if (!desk || user) return
    let cancelled = false
    me(desk)
      .then((found) => !cancelled && setUser(found))
      .catch(() => !cancelled && setUser(null))
      .finally(() => !cancelled && setChecking(false))
    return () => {
      cancelled = true
    }
  }, [desk, user])

  const choose = (next: DeskId | null) => {
    remember(next)
    setUser(null)
    setChecking(next !== null)
    setDesk(next)
  }

  return (
    <div className="page">
      <header className="topbar">
        <span className="brand">Trade desk</span>
        <span className="tagline">AI agents that trade for small businesses, paid through PayPal</span>
      </header>
      <main>
        {desk && user ? (
          <DeskHome desk={desk} user={user} onLogout={() => choose(null)} />
        ) : desk && checking ? (
          <p className="muted">Connecting…</p>
        ) : desk ? (
          <LoginForm desk={desk} onLoggedIn={setUser} onBack={() => choose(null)} />
        ) : (
          <section>
            <h1>Log in as</h1>
            <div className="choices">
              {DESKS.map((option) => (
                <button key={option.id} className="choice" onClick={() => choose(option.id)}>
                  <span className="choice-label">{option.label}</span>
                  <span className="muted">{option.blurb}</span>
                </button>
              ))}
            </div>
          </section>
        )}
      </main>
    </div>
  )
}
