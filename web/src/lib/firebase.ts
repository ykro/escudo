"use client";
import { getApps, initializeApp } from "firebase/app";
import { getAuth } from "firebase/auth";
import { config } from "./config";

// Auth only: the portal reads everything through the Escudo API, never Firestore directly.
export function firebaseAuth() {
  const app = getApps()[0] ?? initializeApp(config.firebase);
  return getAuth(app);
}
