"use client";

import { useCallback, useEffect, useRef, useState, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes } from "react";

export function cx(...parts: Array<string | false | null | undefined>) {
  return parts.filter(Boolean).join(" ");
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <h1 className="font-serif text-2xl font-semibold text-navy-800 sm:text-3xl">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-slate-500">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function Card({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cx("rounded-2xl border border-slate-200/70 bg-white p-5 shadow-card transition-shadow hover:shadow-lg", className)}>
      {children}
    </div>
  );
}

type Tone = "navy" | "gold" | "emerald" | "rose" | "amber" | "sky";

const TONE_BORDER: Record<Tone, string> = {
  navy: "border-l-navy-800",
  gold: "border-l-gold-500",
  emerald: "border-l-emerald-500",
  rose: "border-l-rose-500",
  amber: "border-l-amber-500",
  sky: "border-l-sky-500",
};

export function StatCard({ label, value, hint, tone = "navy" }: { label: string; value: string; hint?: string; tone?: Tone }) {
  return (
    <div className={cx("rounded-2xl border border-slate-200/70 border-l-4 bg-white p-5 shadow-card transition-all hover:-translate-y-0.5 hover:shadow-lg", TONE_BORDER[tone])}>
      <p className="text-xs font-medium uppercase tracking-wider text-slate-500">{label}</p>
      <p className="mt-2 font-serif text-2xl font-semibold text-navy-800 tabular-nums">{value}</p>
      {hint && <p className="mt-1 text-xs text-slate-400">{hint}</p>}
    </div>
  );
}

export function Button({
  variant = "primary",
  className,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "gold" | "ghost" | "danger" | "outline" }) {
  const styles = {
    primary: "bg-navy-800 text-white hover:bg-navy-700 focus-visible:ring-navy-800",
    gold: "bg-gold-500 text-navy-900 hover:bg-gold-600 hover:text-white focus-visible:ring-gold-500",
    ghost: "text-slate-600 hover:bg-slate-100",
    danger: "bg-rose-600 text-white hover:bg-rose-700 focus-visible:ring-rose-600",
    outline: "border border-slate-300 bg-white text-navy-800 hover:border-gold-500 hover:text-gold-600",
  }[variant];
  return (
    <button
      {...props}
      className={cx(
        "inline-flex items-center justify-center gap-2 rounded-xl px-4 py-2 text-sm font-medium transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 active:scale-[0.98]",
        styles,
        className,
      )}
    />
  );
}

export function Field({ label, error, children, hint }: { label: string; error?: string; children: ReactNode; hint?: string }) {
  return (
    <label className="block space-y-1.5">
      <span className="text-sm font-medium text-slate-700">{label}</span>
      {children}
      {hint && !error && <span className="block text-xs text-slate-400">{hint}</span>}
      {error && <span className="block text-xs text-rose-600">{error}</span>}
    </label>
  );
}

const inputBase =
  "w-full rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-slate-900 shadow-sm transition placeholder:text-slate-400 focus:border-gold-500 focus:outline-none focus:ring-4 focus:ring-gold-500/15";

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={cx(inputBase, props.className)} />;
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={cx(inputBase, "pr-8", props.className)} />;
}

export function Badge({ children, tone = "slate" }: { children: ReactNode; tone?: "slate" | "emerald" | "amber" | "rose" | "navy" | "sky" | "gold" }) {
  const styles = {
    slate: "bg-slate-100 text-slate-700",
    emerald: "bg-emerald-50 text-emerald-700 ring-1 ring-emerald-600/15",
    amber: "bg-amber-50 text-amber-700 ring-1 ring-amber-600/15",
    rose: "bg-rose-50 text-rose-700 ring-1 ring-rose-600/15",
    navy: "bg-navy-50 text-navy-800 ring-1 ring-navy-800/10",
    sky: "bg-sky-50 text-sky-700 ring-1 ring-sky-600/15",
    gold: "bg-gold-500/15 text-gold-600 ring-1 ring-gold-500/30",
  }[tone];
  return <span className={cx("inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium", styles)}>{children}</span>;
}

export function Alert({ kind = "error", children }: { kind?: "error" | "success" | "info"; children: ReactNode }) {
  const styles = {
    error: "border-rose-200 bg-rose-50 text-rose-700",
    success: "border-emerald-200 bg-emerald-50 text-emerald-700",
    info: "border-sky-200 bg-sky-50 text-sky-700",
  }[kind];
  return <div role={kind === "error" ? "alert" : "status"} className={cx("rounded-xl border px-4 py-3 text-sm", styles)}>{children}</div>;
}

export function Spinner({ label = "Yüklənir…" }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-3 py-10 text-sm text-slate-500">
      <span className="h-4 w-4 animate-spin rounded-full border-2 border-gold-500 border-t-transparent" />
      {label}
    </div>
  );
}

export function EmptyState({ title, text }: { title: string; text?: string }) {
  return (
    <div className="px-6 py-12 text-center">
      <p className="font-serif text-lg text-navy-800">{title}</p>
      {text && <p className="mt-1 text-sm text-slate-500">{text}</p>}
    </div>
  );
}

export function Modal({ open, title, onClose, children, footer }: { open: boolean; title: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-navy-900/50 p-0 backdrop-blur-sm sm:items-center sm:p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="animate-[fadeUp_.25s_ease-out] flex max-h-[92vh] w-full flex-col overflow-hidden rounded-t-3xl bg-white shadow-2xl sm:max-w-lg sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-slate-100 px-6 py-4">
          <h2 className="font-serif text-lg font-semibold text-navy-800">{title}</h2>
          <button onClick={onClose} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700" aria-label="Bağla">
            ✕
          </button>
        </div>
        <div className="overflow-y-auto px-6 py-5">{children}</div>
        {footer && <div className="flex justify-end gap-2 border-t border-slate-100 bg-slate-50/60 px-6 py-4">{footer}</div>}
      </div>
    </div>
  );
}

export function Table({ children }: { children: ReactNode }) {
  return (
    <div className="-mx-5 overflow-x-auto sm:mx-0">
      <table className="min-w-full divide-y divide-slate-100 text-sm">{children}</table>
    </div>
  );
}

export function Th({ children, className }: { children?: ReactNode; className?: string }) {
  return <th className={cx("whitespace-nowrap px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-slate-500", className)}>{children}</th>;
}

export function Td({ children, className }: { children?: ReactNode; className?: string }) {
  return <td className={cx("whitespace-nowrap px-4 py-3 text-slate-700", className)}>{children}</td>;
}

type ConfirmOptions = { title?: string; confirmLabel?: string; danger?: boolean };

/**
 * In-app confirmation dialog. Use this instead of window.confirm: some browsers and embedded webviews block
 * native dialogs and window.confirm then returns false immediately, so the action silently never runs.
 * Usage: const [confirm, confirmDialog] = useConfirm(); … if (!(await confirm("…"))) return; … render {confirmDialog}.
 */
export function useConfirm(): [(message: ReactNode, options?: ConfirmOptions) => Promise<boolean>, ReactNode] {
  const [state, setState] = useState<{ message: ReactNode; options: ConfirmOptions } | null>(null);
  const resolver = useRef<((ok: boolean) => void) | null>(null);

  const confirm = useCallback((message: ReactNode, options: ConfirmOptions = {}) => {
    resolver.current?.(false);
    setState({ message, options });
    return new Promise<boolean>((resolve) => {
      resolver.current = resolve;
    });
  }, []);

  const close = (ok: boolean) => {
    resolver.current?.(ok);
    resolver.current = null;
    setState(null);
  };

  const dialog = state ? (
    <Modal
      open
      title={state.options.title ?? "Təsdiq"}
      onClose={() => close(false)}
      footer={
        <>
          <Button variant="ghost" onClick={() => close(false)}>Ləğv et</Button>
          <Button variant={state.options.danger ? "danger" : "gold"} onClick={() => close(true)} autoFocus>
            {state.options.confirmLabel ?? "Bəli, təsdiqlə"}
          </Button>
        </>
      }
    >
      <p className="text-sm text-slate-700">{state.message}</p>
    </Modal>
  ) : null;

  return [confirm, dialog];
}
