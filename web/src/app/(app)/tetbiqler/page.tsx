"use client";

import { Card, PageHeader } from "@/components/ui";
import { StoreBadges } from "@/components/StoreBadges";

/** "Tətbiqi yüklə" inside the app: the same always-newest downloads as the home page, for staff who are already in. */
export default function AppsPage() {
  return (
    <>
      <PageHeader title="Tətbiqi yüklə" subtitle="Telefon və kompüter üçün HesabPro — həmişə ən son versiya" />
      <Card>
        <StoreBadges />
      </Card>
      <div className="mt-6 grid gap-4 md:grid-cols-3">
        <Card>
          <h2 className="mb-1 font-semibold text-navy-800">Android</h2>
          <p className="text-sm text-slate-600">
            Yeni versiya hər yenilənmədən sonra avtomatik hazırlanır. Yükləyib köhnənin üstündən quraşdırın — hesabdan çıxmaq lazım deyil.
            Telefon “naməlum mənbə” soruşsa, bu dəfəlik icazə verin.
          </p>
        </Card>
        <Card>
          <h2 className="mb-1 font-semibold text-navy-800">iPhone</h2>
          <p className="text-sm text-slate-600">
            Safari-də hesabpro.cloud açın → Paylaş → “Ana ekrana əlavə et”. Proqram kimi açılır və həmişə yenidir.
          </p>
        </Card>
        <Card>
          <h2 className="mb-1 font-semibold text-navy-800">Windows (Kassa)</h2>
          <p className="text-sm text-slate-600">
            Bir dəfə quraşdırın: yeni funksiyalar dərhal görünür, proqramın özü də yenilənəndə avtomatik yenilənir.
          </p>
        </Card>
      </div>
    </>
  );
}
