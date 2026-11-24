import 'dart:math';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import 'api.dart';
import 'effects.dart';
import 'models.dart';
import 'themes.dart';

final _money = NumberFormat('#,##0.00', 'az');
String money(double v) => '${_money.format(v)} ₼';

/// Bank-card proportions (85.6 × 54 mm).
const cardAspect = 85.6 / 54;

/// The front of a card in its store's design: title, store, holder, logo and the balance.
class CardFront extends StatelessWidget {
  const CardFront({super.key, required this.card, this.animateBalance = true, this.theme});

  final LoyaltyCard card;
  final bool animateBalance;
  /// The customer's chosen look; null = the store's own design.
  final KartTheme? theme;

  @override
  Widget build(BuildContext context) {
    final d = card.design;
    final t = theme;
    final c1 = t?.color1 ?? d.color1, c2 = t?.color2 ?? d.color2, fg = t?.text ?? d.textColor;
    final text = TextStyle(color: fg);
    return AspectRatio(
      aspectRatio: cardAspect,
      child: Container(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(22),
          gradient: LinearGradient(colors: [c1, c2], begin: Alignment.topLeft, end: Alignment.bottomRight),
          image: d.background == null || t != null
              ? null
              : DecorationImage(
                  image: MemoryImage(d.background!),
                  fit: BoxFit.cover,
                  colorFilter: ColorFilter.mode(Colors.black.withValues(alpha: d.shade), BlendMode.darken),
                ),
          boxShadow: [BoxShadow(color: c1.withValues(alpha: 0.45), blurRadius: 24, offset: const Offset(0, 12))],
        ),
        child: Stack(children: [
          if (t != null && t.pattern != CardPattern.none)
            Positioned.fill(
              child: ClipRRect(
                borderRadius: BorderRadius.circular(22),
                child: CustomPaint(painter: PatternPainter(t.pattern, (t.accent ?? fg).withValues(alpha: 0.16))),
              ),
            ),
          Padding(
        padding: const EdgeInsets.all(20),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${d.title.toUpperCase()}${d.showPercent ? ' · ${_pct(card.percent)}%' : ''}',
                    style: text.copyWith(fontSize: 11, letterSpacing: 1.6, fontWeight: FontWeight.w600).apply(color: fg.withValues(alpha: 0.8))),
                if (d.showStore) ...[
                  const SizedBox(height: 4),
                  Text(card.storeName, maxLines: 1, overflow: TextOverflow.ellipsis,
                      style: text.copyWith(fontSize: 20, fontWeight: FontWeight.w800)),
                ],
              ]),
            ),
            if (d.logo != null) Image.memory(d.logo!, height: 40, width: 90, fit: BoxFit.contain, alignment: Alignment.topRight),
          ]),
          const Spacer(),
          Text('BONUS', style: text.copyWith(fontSize: 10, letterSpacing: 2).apply(color: fg.withValues(alpha: 0.7))),
          animateBalance
              ? TweenAnimationBuilder<double>(
                  tween: Tween(begin: 0, end: card.balance),
                  duration: const Duration(milliseconds: 1400),
                  curve: Curves.easeOutCubic,
                  builder: (_, v, _) => Text(money(v), style: text.copyWith(fontSize: 30, fontWeight: FontWeight.w800)),
                )
              : Text(money(card.balance), style: text.copyWith(fontSize: 30, fontWeight: FontWeight.w800)),
          const SizedBox(height: 6),
          Row(children: [
            Expanded(child: Text(card.fullName, maxLines: 1, overflow: TextOverflow.ellipsis, style: text.copyWith(fontSize: 13))),
            Text(card.number, style: text.copyWith(fontSize: 13, letterSpacing: 1.5, fontWeight: FontWeight.w600)),
          ]),
        ]),
          ),
        ]),
      ),
    );
  }
}

String _pct(double p) => p == p.roundToDouble() ? p.toStringAsFixed(0) : p.toStringAsFixed(1);

/// The back: a big white barcode for the till, and how to use it.
class CardBack extends StatelessWidget {
  const CardBack({super.key, required this.card});

  final LoyaltyCard card;

  @override
  Widget build(BuildContext context) => AspectRatio(
        aspectRatio: cardAspect,
        child: Container(
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(22),
            border: Border.all(color: card.design.color1, width: 3),
            boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 24, offset: Offset(0, 12))],
          ),
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 12),
          child: Column(children: [
            Text(card.storeName, style: const TextStyle(fontWeight: FontWeight.w700, color: Colors.black87)),
            const SizedBox(height: 8),
            Expanded(
              child: Image.network(barcodeUrl(card.number), fit: BoxFit.contain, filterQuality: FilterQuality.none,
                  errorBuilder: (_, _, _) => const Center(child: Text('Barkod yüklənmədi — interneti yoxlayın'))),
            ),
            const SizedBox(height: 6),
            Text(card.number, style: const TextStyle(fontSize: 18, letterSpacing: 4, fontWeight: FontWeight.w800, color: Colors.black)),
            Text('Kassada göstərin — bonus yığılır və xərclənir', style: TextStyle(fontSize: 11, color: Colors.grey.shade600)),
          ]),
        ),
      );
}

/// A card that turns over (3D) when tapped: front ⇄ barcode.
class FlipCard extends StatefulWidget {
  const FlipCard({super.key, required this.card, this.theme});

  final LoyaltyCard card;
  final KartTheme? theme;

  @override
  State<FlipCard> createState() => _FlipCardState();
}

class _FlipCardState extends State<FlipCard> with TickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 650));
  // the card leans after the finger and springs back when let go
  late final AnimationController _back = AnimationController(vsync: this, duration: const Duration(milliseconds: 500));
  Offset _tilt = Offset.zero, _from = Offset.zero;

  @override
  void initState() {
    super.initState();
    _back.addListener(() => setState(() => _tilt = Offset.lerp(_from, Offset.zero, Curves.elasticOut.transform(_back.value))!));
  }

  @override
  void dispose() {
    _c.dispose();
    _back.dispose();
    super.dispose();
  }

  void _lean(PointerEvent d, Size size) {
    _back.stop();
    final x = (d.localPosition.dx / size.width - 0.5) * 2, y = (d.localPosition.dy / size.height - 0.5) * 2;
    setState(() => _tilt = Offset(x.clamp(-1.0, 1.0), y.clamp(-1.0, 1.0)));
  }

  void _release() {
    _from = _tilt;
    _back.forward(from: 0);
  }

  void _flip() => _c.status == AnimationStatus.completed || _c.status == AnimationStatus.forward ? _c.reverse() : _c.forward();

  @override
  Widget build(BuildContext context) => LayoutBuilder(
        // raw pointer events: the card leans without taking the swipe away from the page view
        builder: (_, box) => Listener(
          onPointerMove: (d) => _lean(d, box.biggest),
          onPointerUp: (_) => _release(),
          onPointerCancel: (_) => _release(),
          child: GestureDetector(
          onTap: _flip,
          child: AnimatedBuilder(
            animation: _c,
            builder: (_, _) {
              final t = Curves.easeInOutBack.transform(_c.value).clamp(0.0, 1.0);
              final angle = t * pi;
              final back = angle > pi / 2;
              return Transform(
                alignment: Alignment.center,
                transform: Matrix4.identity()
                  ..setEntry(3, 2, 0.0012)
                  ..rotateX(-_tilt.dy * 0.18)
                  ..rotateY(angle + _tilt.dx * 0.22),
                child: back
                    ? Transform(alignment: Alignment.center, transform: Matrix4.identity()..rotateY(pi), child: CardBack(card: widget.card))
                    // a new look fades in over the old one
                    : Shine(
                        child: AnimatedSwitcher(
                          duration: const Duration(milliseconds: 500),
                          transitionBuilder: (child, a) => FadeTransition(
                              opacity: a, child: ScaleTransition(scale: Tween(begin: 0.96, end: 1.0).animate(a), child: child)),
                          child: CardFront(key: ValueKey(widget.theme?.id ?? storeThemeId), card: widget.card, theme: widget.theme),
                        ),
                      ),
              );
            },
          ),
          ),
        ),
      );
}
