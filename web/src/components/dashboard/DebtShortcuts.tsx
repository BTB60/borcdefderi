"use client";

import Link from "next/link";
import { useState } from "react";
import { createPortal } from "react-dom";
import { AccountModal, PickPartyModal } from "@/components/debts/AccountModals";
import { useAuth } from "@/lib/auth";

export type ShortcutSection = "customer" | "receivable" | "payable";

const DEBT_TYPE = { customer: "CUSTOMER_DEBT", receivable: "COMPANY_RECEIVABLE", payable: "COMPANY_PAYABLE" } as const;
const PERMISSION = { customer: "CUSTOMER_DEBTS", receivable: "COMPANY_RECEIVABLES", payable: "COMPANY_PAYABLES" } as const;

/** Whether the signed-in user may add parties / debts in this section. */
export function useCanShortcut(section: ShortcutSection) {
  const { can, isOwner } = useAuth();
  return isOwner || can(PERMISSION[section]);
}

/**
 * Shortcuts shown on a dashboard card and on the list behind it:
 * "+ Müştəri" / "+ Şirkət" (opens the add form on Kontragentlar) and "+ Borc" (pick the party, then write the debt).
 */
export function DebtShortcuts({ section, onSaved, tone = "light" }: { section: ShortcutSection; onSaved: (msg: string) => void; tone?: "light" | "onColor" }) {
  const allowed = useCanShortcut(section);
  const [picking, setPicking] = useState(false);
  const [partyId, setPartyId] = useState<string | null>(null);
  if (!allowed) return null;

  const isCustomer = section === "customer";
  const addHref = isCustomer ? "/kontragentlar?yeni=1" : "/kontragentlar?tip=sirket&yeni=1";
  const cls =
    tone === "onColor"
      ? "rounded-xl bg-white/20 px-3 py-1.5 text-xs font-semibold tracking-wide text-white backdrop-blur transition hover:bg-white/30"
      : "rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-navy-800 shadow-sm transition hover:border-gold-500";

  return (
    <>
      <div className="flex flex-wrap gap-1.5">
        <Link href={addHref} className={cls}>+ {isCustomer ? "Müştəri" : "Şirkət"}</Link>
        <button type="button" className={cls} onClick={() => setPicking(true)}>+ Borc</button>
      </div>
      {/* Modals go to <body>: the cards use a hover transform, which would otherwise trap fixed-position overlays. */}
      {picking && createPortal(
        <PickPartyModal
          isCustomer={isCustomer}
          title={isCustomer ? "Borc yaz — müştərini seçin" : "Borc yaz — şirkəti seçin"}
          onClose={() => setPicking(false)}
          onPick={(id) => {
            setPartyId(id);
            setPicking(false);
          }}
        />,
        document.body,
      )}
      {partyId && createPortal(
        <AccountModal
          mode="charge"
          isCustomer={isCustomer}
          partyId={partyId}
          initialType={DEBT_TYPE[section]}
          onClose={() => setPartyId(null)}
          onSaved={(msg) => {
            setPartyId(null);
            onSaved(msg);
          }}
        />,
        document.body,
      )}
    </>
  );
}
