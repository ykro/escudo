"use client";
import { createContext, useContext, useEffect, useMemo, useState } from "react";
import {
  onAuthStateChanged,
  signInAnonymously,
  signInWithEmailAndPassword,
  signOut as firebaseSignOut,
  type User,
} from "firebase/auth";
import { firebaseAuth } from "./firebase";

type AuthState =
  | { status: "loading"; user: null }
  | { status: "signedOut"; user: null }
  | { status: "signedIn"; user: User };

type AuthApi = AuthState & {
  signInWithEmail: (email: string, password: string) => Promise<void>;
  signInDemo: () => Promise<void>;
  signOut: () => Promise<void>;
};

const AuthContext = createContext<AuthApi | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: "loading", user: null });

  useEffect(
    () =>
      onAuthStateChanged(firebaseAuth(), (user) =>
        setState(user ? { status: "signedIn", user } : { status: "signedOut", user: null }),
      ),
    [],
  );

  const value = useMemo<AuthApi>(
    () => ({
      ...state,
      signInWithEmail: async (email, password) => {
        await signInWithEmailAndPassword(firebaseAuth(), email, password);
      },
      // A one-tap demo session: the backend treats any Firebase user without a
      // customer_id claim as the seeded policy holder (see backend/escudo/auth.py).
      signInDemo: async () => {
        await signInAnonymously(firebaseAuth());
      },
      signOut: () => firebaseSignOut(firebaseAuth()),
    }),
    [state],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthApi {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth outside AuthProvider");
  return ctx;
}

/** Firebase error codes -> Spanish copy for the sign-in form. */
export function signInError(error: unknown): string {
  const code = (error as { code?: string })?.code ?? "";
  if (code.includes("invalid-credential") || code.includes("wrong-password") || code.includes("user-not-found"))
    return "El correo o la contraseña no coinciden.";
  if (code.includes("too-many-requests")) return "Demasiados intentos. Espera un momento e intenta de nuevo.";
  if (code.includes("network")) return "Sin conexión. Revisa tu internet e intenta de nuevo.";
  return "No pudimos iniciar sesión. Intenta de nuevo.";
}
