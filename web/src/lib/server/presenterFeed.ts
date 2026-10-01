import "server-only";
import { applicationDefault, getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, type Firestore } from "firebase-admin/firestore";
import type { Approval, Claim } from "../types";
import type { EscudoEvent } from "../timeline";

/**
 * Live presenter data straight from Firestore (db `escudo`), read on the server with the
 * escudo-web service account (read-only). The browser never gets Firestore access, so the
 * presenter needs no login and the database stays closed.
 */
export type PresenterSnapshot = { events: EscudoEvent[]; claim: Claim | null; approvals: Approval[]; at: string };

const MAX_EVENTS = 200;

function db(): Firestore {
  const app =
    getApps()[0] ??
    initializeApp({ credential: applicationDefault(), projectId: process.env.GOOGLE_CLOUD_PROJECT ?? "ai-experiments-487722" });
  return getFirestore(app, process.env.FIRESTORE_DATABASE ?? "escudo");
}

/** Calls `onChange` with a full snapshot whenever events, claims or approvals change. Returns unsubscribe. */
export function watchPresenter(onChange: (s: PresenterSnapshot) => void, onError: (e: Error) => void): () => void {
  const firestore = db();
  let events: EscudoEvent[] = [];
  let claims: Claim[] = [];
  let approvals: Approval[] = [];
  const ready = { events: false, claims: false, approvals: false };

  const emit = () => {
    if (!ready.events || !ready.claims || !ready.approvals) return;
    const claim = [...claims].sort((a, b) => b.created_at.localeCompare(a.created_at))[0] ?? null;
    onChange({ events, claim, approvals, at: new Date().toISOString() });
  };

  const unsubscribers = [
    firestore
      .collection("events")
      .orderBy("ts", "desc")
      .limit(MAX_EVENTS)
      .onSnapshot((snap) => {
        events = snap.docs.map((d) => d.data() as EscudoEvent).reverse();
        ready.events = true;
        emit();
      }, onError),
    firestore.collection("claims").onSnapshot((snap) => {
      claims = snap.docs.map((d) => {
        const { customer_id: _omit, ...claim } = d.data();
        void _omit;
        return claim as Claim;
      });
      ready.claims = true;
      emit();
    }, onError),
    firestore.collection("approvals").onSnapshot((snap) => {
      approvals = snap.docs.map((d) => {
        const a = d.data();
        return {
          approval_id: a.approval_id, claim_id: a.claim_id ?? null, tool: a.tool, reason: a.reason, summary: a.summary,
          amount_usd: a.amount_usd ?? null, status: a.status, created_at: a.created_at,
        } as Approval;
      });
      ready.approvals = true;
      emit();
    }, onError),
  ];
  return () => unsubscribers.forEach((u) => u());
}
