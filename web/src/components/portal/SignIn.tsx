"use client";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { EscudoLogo } from "@/brand/EscudoMark";
import { AuthProvider, signInError, useAuth } from "@/lib/auth";
import styles from "./portal.module.css";

export function SignIn() {
  return (
    <AuthProvider>
      <SignInForm />
    </AuthProvider>
  );
}

function SignInForm() {
  const auth = useAuth();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (auth.status === "signedIn") router.replace("/");
  }, [auth.status, router]);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(signInError(e));
      setBusy(false);
    }
  }

  return (
    <main className={styles.signin}>
      <div className={styles.signinCard}>
        <EscudoLogo size={40} />
        <h1 className={styles.signinTitle}>Entra a tu póliza</h1>
        <p className={styles.coverageNote}>Consulta coberturas, deducible, talleres y el avance de tu reclamo.</p>
        <form
          className={styles.form}
          onSubmit={(e) => {
            e.preventDefault();
            run(() => auth.signInWithEmail(email, password));
          }}
        >
          <label className={styles.field}>
            <span>Correo</span>
            <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <label className={styles.field}>
            <span>Contraseña</span>
            <input type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
          </label>
          {error && (
            <p className={styles.inlineError} role="alert">
              {error}
            </p>
          )}
          <button className="btn btn-primary" type="submit" disabled={busy}>
            {busy ? "Entrando…" : "Entrar"}
          </button>
        </form>
        <div className={styles.divider}>
          <span>o</span>
        </div>
        <button type="button" className="btn btn-quiet" disabled={busy} onClick={() => run(auth.signInDemo)}>
          Entrar con la cuenta de demostración
        </button>
      </div>
    </main>
  );
}
