"use client";

import type { CSSProperties, ReactNode } from "react";
import { formatMoney } from "@/lib/format";
import { contrastText, optionLabel, savingPercent } from "@/lib/plans";
import type { PeriodCode, Plan } from "@/lib/types";

/**
 * A subscription package card drawn with the package's own colours. Used on the store's subscription page
 * and as the live preview in the admin package editor, so the admin sees exactly what stores will see.
 */
export function PlanCard({
  plan,
  period,
  onPeriod,
  selected = false,
  action,
  footer,
}: {
  plan: Pick<Plan, "name" | "description" | "features" | "options" | "recommended" | "primaryColor" | "secondaryColor" | "backgroundColor" | "textColor" | "buttonColor" | "maxEmployees" | "maxCustomers">;
  period: PeriodCode | null;
  onPeriod?: (p: PeriodCode) => void;
  selected?: boolean;
  /** Button label + handler; omitted in previews that only show the card. */
  action?: { label: string; onClick?: () => void; disabled?: boolean; title?: string };
  footer?: ReactNode;
}) {
  const option = plan.options.find((o) => o.period === period) ?? plan.options[0];
  const saving = option ? savingPercent(plan as Plan, option) : 0;
  const style: CSSProperties = {
    backgroundColor: plan.backgroundColor,
    color: plan.textColor,
    borderColor: selected || plan.recommended ? plan.primaryColor : `${plan.textColor}22`,
    boxShadow: selected ? `0 0 0 3px ${plan.primaryColor}55, 0 20px 40px -20px ${plan.primaryColor}80` : undefined,
  };

  return (
    <div
      className={`relative flex h-full flex-col overflow-hidden rounded-2xl border-2 p-6 transition-all duration-300 hover:-translate-y-1 ${plan.recommended ? "shadow-2xl" : "shadow-card"}`}
      style={style}
    >
      <div className="absolute inset-x-0 top-0 h-1.5" style={{ background: `linear-gradient(90deg, ${plan.primaryColor}, ${plan.secondaryColor})` }} />
      {plan.recommended && (
        <span
          className="absolute right-3 top-4 rounded-full px-2.5 py-0.5 text-[10px] font-bold uppercase tracking-wider"
          style={{ backgroundColor: plan.secondaryColor, color: contrastText(plan.secondaryColor) }}
        >
          Tövsiyə olunur
        </span>
      )}
      <p className="pr-24 font-serif text-xl font-semibold">{plan.name || "Paket adı"}</p>
      {plan.description && <p className="mt-1 text-sm opacity-70">{plan.description}</p>}

      {plan.options.length > 1 && (
        <div className="mt-4 flex flex-wrap gap-1.5" role="radiogroup" aria-label="Müddət">
          {plan.options.map((o) => {
            const on = o.period === option?.period;
            return (
              <button
                key={o.period}
                type="button"
                role="radio"
                aria-checked={on}
                onClick={() => onPeriod?.(o.period)}
                className="rounded-full border px-3 py-1 text-xs font-medium transition"
                style={on
                  ? { backgroundColor: plan.primaryColor, borderColor: plan.primaryColor, color: contrastText(plan.primaryColor) }
                  : { borderColor: `${plan.textColor}33`, color: plan.textColor }}
              >
                {optionLabel(o)}
              </button>
            );
          })}
        </div>
      )}

      {option ? (
        <>
          <p className="mt-5 font-serif text-4xl font-semibold" style={{ color: plan.primaryColor === plan.backgroundColor ? plan.textColor : undefined }}>
            {formatMoney(option.price)}
          </p>
          <p className="mt-1 text-sm opacity-70">
            {option.days ? `${option.days} gün` : `${option.months} ay`}
            {(option.months ?? 0) > 1 && ` · ayda ${formatMoney(option.price / (option.months ?? 1))}`}
          </p>
          {saving > 0 && <p className="mt-1 text-xs font-semibold" style={{ color: plan.secondaryColor }}>{saving}% qənaət</p>}
        </>
      ) : (
        <p className="mt-5 text-sm opacity-60">Qiymət əlavə edilməyib</p>
      )}

      {(plan.features.length > 0 || plan.maxEmployees != null || plan.maxCustomers != null) && (
        <ul className="mt-5 space-y-2 text-sm">
          {plan.features.map((f, i) => (
            <li key={i} className="flex gap-2">
              <span aria-hidden="true" className="mt-0.5 font-bold" style={{ color: plan.secondaryColor }}>✓</span>
              <span>{f}</span>
            </li>
          ))}
          {plan.maxEmployees != null && (
            <li className="flex gap-2 opacity-80"><span aria-hidden="true">•</span>{plan.maxEmployees === 0 ? "İşçi əlavə etmək olmur" : `${plan.maxEmployees} işçiyə qədər`}</li>
          )}
          {plan.maxCustomers != null && (
            <li className="flex gap-2 opacity-80"><span aria-hidden="true">•</span>{plan.maxCustomers} müştəriyə qədər</li>
          )}
        </ul>
      )}

      <div className="mt-6 flex-1" />
      {action && (
        <button
          type="button"
          onClick={action.onClick}
          disabled={action.disabled}
          title={action.title}
          className="w-full rounded-xl px-4 py-3 text-sm font-semibold transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
          style={{ backgroundColor: plan.buttonColor, color: contrastText(plan.buttonColor) }}
        >
          {action.label}
        </button>
      )}
      {footer}
    </div>
  );
}
