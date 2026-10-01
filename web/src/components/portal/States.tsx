"use client";
import type { ApiError } from "@/lib/api";
import styles from "./portal.module.css";

export function LoadingBlock({ lines = 3, label }: { lines?: number; label: string }) {
  return (
    <div className={styles.loading} role="status">
      <span className="visually-hidden">{label}</span>
      {Array.from({ length: lines }, (_, i) => (
        <div key={i} className="skeleton" style={{ height: i === 0 ? 40 : 20, width: `${[70, 90, 60, 80][i % 4]}%` }} />
      ))}
    </div>
  );
}

export function ErrorBlock({ error, onRetry }: { error: ApiError; onRetry: () => void }) {
  return (
    <div className={styles.error} role="alert">
      <p className={styles.errorTitle}>No pudimos cargar esta información</p>
      <p>{error.message}</p>
      <button type="button" className="btn btn-quiet" onClick={onRetry}>
        Reintentar
      </button>
    </div>
  );
}

export function EmptyBlock({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className={styles.empty}>
      <p className={styles.emptyTitle}>{title}</p>
      <div className={styles.emptyText}>{children}</div>
    </div>
  );
}
