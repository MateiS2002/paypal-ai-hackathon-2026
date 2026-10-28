import { useState, type FormEvent } from 'react'
import { DESKS, login, me, type DeskId, type Me } from './api'

type Props = { desk: DeskId; onLoggedIn: (user: Me) => void; onBack: () => void }

export default function LoginForm({ desk, onLoggedIn, onBack }: Props) {
  const label = DESKS.find((option) => option.id === desk)?.label ?? desk
  const [username, setUsername] = useState<string>(desk)
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (!(await login(desk, username, password))) {
        setError('Wrong username or password.')
        return
      }
      const user = await me(desk)
      if (user) onLoggedIn(user)
      else setError('Logged in, but the session was not kept. Are cookies blocked?')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="card login" onSubmit={submit}>
      <h1>{label} login</h1>
      <label>
        Username
        <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
      </label>
      <label>
        Password
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          autoFocus
        />
      </label>
      <p className="muted small">Demo login: the username is the desk id, the password is DESK_DEMO_PASSWORD from .env.</p>
      {error && <p className="error">{error}</p>}
      <div className="actions">
        <button type="button" className="secondary" onClick={onBack}>
          Back
        </button>
        <button type="submit" disabled={busy}>
          {busy ? 'Logging in…' : 'Log in'}
        </button>
      </div>
    </form>
  )
}
