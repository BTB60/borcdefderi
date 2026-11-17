"use client";

import { useEffect, useState } from "react";
import { desktop, type ScaleReading } from "@/lib/desktop";

/** The live weight from the cable scale in the Windows kassa (null in the browser or with no scale set up). */
export function useScale(): ScaleReading | null {
  const [reading, setReading] = useState<ScaleReading | null>(null);
  useEffect(() => {
    const app = desktop();
    if (!app?.onScale) return;
    app.scaleWeight?.().then((r) => { if (r) setReading(r); }).catch(() => undefined);
    return app.onScale(setReading);
  }, []);
  return reading;
}
