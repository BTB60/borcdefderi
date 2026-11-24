import 'dart:async';

import 'package:flutter/material.dart';

import 'api.dart';
import 'card_widget.dart';
import 'effects.dart';
import 'models.dart';

/// "Endirimlər": the stores' promo posts as a swiping, auto-playing banner strip under the card.
class PromoCarousel extends StatefulWidget {
  const PromoCarousel({super.key, required this.promos});

  final List<Promo> promos;

  @override
  State<PromoCarousel> createState() => _PromoCarouselState();
}

class _PromoCarouselState extends State<PromoCarousel> {
  final _pages = PageController(viewportFraction: 0.86);
  Timer? _auto;
  double _page = 0;

  @override
  void initState() {
    super.initState();
    _pages.addListener(() => setState(() => _page = _pages.page ?? 0));
    _auto = Timer.periodic(const Duration(seconds: 5), (_) {
      if (!_pages.hasClients || widget.promos.length < 2) return;
      final next = (_page.round() + 1) % widget.promos.length;
      _pages.animateToPage(next, duration: const Duration(milliseconds: 700), curve: Curves.easeInOutCubic);
    });
  }

  @override
  void dispose() {
    _auto?.cancel();
    _pages.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final list = widget.promos;
    final width = MediaQuery.of(context).size.width * 0.86;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      SizedBox(
        height: width * 9 / 16 + 16,
        child: PageView.builder(
          controller: _pages,
          clipBehavior: Clip.none, // keep the shadows
          itemCount: list.length,
          itemBuilder: (_, i) {
            // the banner in front is full size, the neighbours sink back a little (parallax inside)
            final d = (_page - i).clamp(-1.0, 1.0);
            return Transform.scale(
              scale: 1 - d.abs() * 0.07,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
                child: PromoBanner(promo: list[i], parallax: d, onTap: () => showPromo(context, list[i])),
              ),
            );
          },
        ),
      ),
      if (list.length > 1)
        Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          for (var i = 0; i < list.length; i++)
            AnimatedContainer(
              duration: const Duration(milliseconds: 300),
              margin: const EdgeInsets.symmetric(horizontal: 3),
              width: i == _page.round() ? 18 : 6,
              height: 6,
              decoration: BoxDecoration(
                color: i == _page.round() ? list[i].color : Colors.grey.shade300,
                borderRadius: BorderRadius.circular(3),
              ),
            ),
        ]),
    ]);
  }
}

/// One banner: picture, the store's colour washing over it, a light sweep, a pulsing badge and the prices.
class PromoBanner extends StatelessWidget {
  const PromoBanner({super.key, required this.promo, this.parallax = 0, this.onTap});

  final Promo promo;
  final double parallax;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final p = promo;
    return GestureDetector(
      onTap: onTap,
      child: Hero(
        tag: 'promo-${p.id}',
        child: Shine(
          radius: 24,
          period: const Duration(milliseconds: 4200),
          child: Container(
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(24),
              gradient: LinearGradient(colors: [p.color, const Color(0xFF0F1F3D)], begin: Alignment.topLeft, end: Alignment.bottomRight),
              boxShadow: [BoxShadow(color: p.color.withValues(alpha: 0.4), blurRadius: 22, offset: const Offset(0, 10))],
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(24),
              child: Stack(fit: StackFit.expand, children: [
                if (p.hasImage)
                  Transform.translate(
                    offset: Offset(parallax * 40, 0),
                    child: Transform.scale(
                      scale: 1.15,
                      child: Image.network(promoImageUrl(p.id, p.imageVersion), fit: BoxFit.cover,
                          errorBuilder: (_, _, _) => const SizedBox()),
                    ),
                  ),
                DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(colors: [
                      p.color.withValues(alpha: 0.95),
                      p.color.withValues(alpha: 0.55),
                      Colors.transparent,
                    ], stops: const [0, 0.5, 1]),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(18, 14, 18, 16),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Row(children: [
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                        decoration: BoxDecoration(color: Colors.black.withValues(alpha: 0.25), borderRadius: BorderRadius.circular(20)),
                        child: Text(p.storeName, style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w600)),
                      ),
                      const Spacer(),
                      if (p.label != null)
                        Pulse(
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 5),
                            decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(40)),
                            child: Text(p.label!, style: TextStyle(color: p.color, fontWeight: FontWeight.w900, fontSize: 14)),
                          ),
                        ),
                    ]),
                    const Spacer(),
                    SizedBox(
                      width: MediaQuery.of(context).size.width * 0.5,
                      child: Text(p.title, maxLines: 2, overflow: TextOverflow.ellipsis,
                          style: const TextStyle(color: Colors.white, fontSize: 19, fontWeight: FontWeight.w800, height: 1.15,
                              shadows: [Shadow(color: Colors.black38, blurRadius: 8)])),
                    ),
                    if (p.newPrice != null || p.oldPrice != null) ...[
                      const SizedBox(height: 6),
                      _Prices(promo: p, big: false),
                    ],
                  ]),
                ),
                if (p.daysLeft != null && p.daysLeft! <= 7)
                  Positioned(
                    right: 14,
                    bottom: 12,
                    child: Text(p.daysLeft! <= 0 ? 'Bu gün son gün!' : '${p.daysLeft} gün qaldı',
                        style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w700)),
                  ),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}

class _Prices extends StatelessWidget {
  const _Prices({required this.promo, required this.big});

  final Promo promo;
  final bool big;

  @override
  Widget build(BuildContext context) => Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
        if (promo.newPrice != null)
          TweenAnimationBuilder<double>(
            tween: Tween(begin: promo.oldPrice ?? promo.newPrice!, end: promo.newPrice!),
            duration: const Duration(milliseconds: 1200),
            curve: Curves.easeOutCubic,
            builder: (_, v, _) => Text(money(v),
                style: TextStyle(color: big ? promo.color : Colors.white, fontSize: big ? 34 : 22, fontWeight: FontWeight.w900)),
          ),
        if (promo.oldPrice != null) ...[
          const SizedBox(width: 8),
          Padding(
            padding: const EdgeInsets.only(bottom: 3),
            child: Text(money(promo.oldPrice!),
                style: TextStyle(
                    color: big ? Colors.grey.shade500 : Colors.white70,
                    fontSize: big ? 16 : 13,
                    decoration: TextDecoration.lineThrough,
                    decorationColor: big ? Colors.grey.shade500 : Colors.white70)),
          ),
        ],
      ]);
}

/// The post opened full: the picture flies up (hero), then the text and prices slide in.
void showPromo(BuildContext context, Promo p) {
  Navigator.of(context).push(PageRouteBuilder(
    opaque: false,
    barrierColor: Colors.black54,
    transitionDuration: const Duration(milliseconds: 550),
    reverseTransitionDuration: const Duration(milliseconds: 400),
    pageBuilder: (_, _, _) => _PromoPage(promo: p),
    transitionsBuilder: (_, a, _, child) => FadeTransition(opacity: a, child: child),
  ));
}

class _PromoPage extends StatelessWidget {
  const _PromoPage({required this.promo});

  final Promo promo;

  @override
  Widget build(BuildContext context) {
    final p = promo;
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: GestureDetector(
        onTap: () => Navigator.of(context).pop(),
        child: SafeArea(
          child: Center(
            child: GestureDetector(
              onTap: () {},
              child: Container(
                margin: const EdgeInsets.all(20),
                decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(28)),
                clipBehavior: Clip.antiAlias,
                child: SingleChildScrollView(
                  child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
                    AspectRatio(aspectRatio: 16 / 9, child: PromoBanner(promo: p)),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(22, 18, 22, 22),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Reveal(
                          delay: const Duration(milliseconds: 150),
                          child: Text(p.title, style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w900, color: Color(0xFF0F1F3D))),
                        ),
                        if (p.subtitle != null) ...[
                          const SizedBox(height: 8),
                          Reveal(
                            delay: const Duration(milliseconds: 260),
                            child: Text(p.subtitle!, style: TextStyle(fontSize: 15, color: Colors.grey.shade700, height: 1.35)),
                          ),
                        ],
                        if (p.newPrice != null || p.oldPrice != null) ...[
                          const SizedBox(height: 14),
                          Reveal(delay: const Duration(milliseconds: 360), child: _Prices(promo: p, big: true)),
                        ],
                        const SizedBox(height: 14),
                        Reveal(
                          delay: const Duration(milliseconds: 460),
                          child: Row(children: [
                            Icon(Icons.storefront_rounded, color: p.color),
                            const SizedBox(width: 6),
                            Expanded(child: Text(p.storeName, style: const TextStyle(fontWeight: FontWeight.w700))),
                            if (p.daysLeft != null)
                              Text(p.daysLeft! <= 0 ? 'Bu gün son gün' : '${p.daysLeft} gün qaldı',
                                  style: TextStyle(color: p.color, fontWeight: FontWeight.w700)),
                          ]),
                        ),
                        const SizedBox(height: 18),
                        Reveal(
                          delay: const Duration(milliseconds: 560),
                          child: SizedBox(
                            width: double.infinity,
                            child: FilledButton(
                              style: FilledButton.styleFrom(backgroundColor: p.color),
                              onPressed: () => Navigator.of(context).pop(),
                              child: const Text('Kassada bonus kartınızı göstərin'),
                            ),
                          ),
                        ),
                      ]),
                    ),
                  ]),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
