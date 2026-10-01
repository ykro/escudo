"use client";
import { slotLabel, usd } from "@/lib/labels";
import type { Claim, RepairOption } from "@/lib/types";
import { useApi } from "@/lib/useApi";
import { EmptyBlock, ErrorBlock, LoadingBlock } from "@/components/portal/States";
import styles from "@/components/portal/portal.module.css";

export default function ShopsPage() {
  const claim = useApi<Claim>("/claims/latest");
  const ready = claim.status === "ready" && claim.data.assessment;
  const shops = useApi<RepairOption[]>(ready ? `/claims/${claim.data.claim_id}/repair-options` : null);

  if (claim.status === "loading") return <LoadingBlock lines={3} label="Buscando talleres…" />;
  if (claim.status === "error") {
    if (claim.error.status === 404)
      return (
        <EmptyBlock title="Los talleres aparecen con tu reclamo">
          <p>Cotizamos la reparación con las fotos del daño. Cuando abras un reclamo, aquí verás talleres de la red con cupo y precio.</p>
        </EmptyBlock>
      );
    return <ErrorBlock error={claim.error} onRetry={claim.reload} />;
  }
  if (!claim.data.assessment)
    return (
      <EmptyBlock title="Falta evaluar el daño">
        <p>Sube las fotos del choque desde la app de Escudo para que la red de talleres pueda cotizar.</p>
      </EmptyBlock>
    );
  const booked = claim.data.repair;

  return (
    <section aria-labelledby="talleres-titulo">
      <p className={styles.eyebrow}>Reclamo {claim.data.claim_id}</p>
      <h1 id="talleres-titulo" className={styles.display}>
        Talleres con cupo
      </h1>
      <p className={styles.meta}>Cotizados por la Red de Talleres para tu daño. Reservar requiere tu aprobación en el teléfono.</p>
      {shops.status === "loading" && <LoadingBlock lines={3} label="Consultando a la red de talleres…" />}
      {shops.status === "error" && <ErrorBlock error={shops.error} onRetry={shops.reload} />}
      {shops.status === "ready" && (
        <ul className={styles.shops}>
          {shops.data.map((s) => (
            <li key={s.shop_id} className={styles.shop}>
              <div className={styles.shopHead}>
                <h2>{s.name}</h2>
                <p className={styles.shopQuote}>{usd(s.quote_usd)}</p>
              </div>
              <p className={styles.coverageNote}>
                {s.address} · <span aria-label={`Calificación ${s.rating} de 5`}>★ {s.rating.toFixed(1)}</span>
                {booked?.shop_id === s.shop_id && <strong className={styles.bookedTag}> · Reservado para {slotLabel(booked.slot)}</strong>}
              </p>
              <ul className={styles.slots} aria-label="Horarios disponibles">
                {s.slots.slice(0, 4).map((slot) => (
                  <li key={slot}>{slotLabel(slot)}</li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
