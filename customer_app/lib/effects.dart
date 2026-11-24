import 'dart:math';

import 'package:flutter/material.dart';

/// A slow "aurora": soft coloured light drifting behind the wallet.
class AuroraBackground extends StatefulWidget {
  const AuroraBackground({super.key, required this.child, this.colors = const [Color(0xFF7C3AED), Color(0xFF0EA5E9), Color(0xFFD4A537)]});

  final Widget child;
  final List<Color> colors;

  @override
  State<AuroraBackground> createState() => _AuroraBackgroundState();
}

class _AuroraBackgroundState extends State<AuroraBackground> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(seconds: 18))..repeat();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final reduce = MediaQuery.of(context).disableAnimations;
    return Stack(children: [
      Positioned.fill(child: Container(color: const Color(0xFFF4F3FA))),
      Positioned.fill(
        child: AnimatedBuilder(
          animation: _c,
          builder: (_, _) {
            final t = reduce ? 0.0 : _c.value * 2 * pi;
            final size = MediaQuery.of(context).size;
            return Stack(children: [
              for (var i = 0; i < widget.colors.length; i++)
                Positioned(
                  left: size.width * (0.5 + 0.45 * cos(t + i * 2.1)) - 160,
                  top: size.height * (0.28 + 0.22 * sin(t * 0.8 + i * 1.7)) - 160,
                  // a radial fade instead of a blur filter: soft edges without banding, and cheap to draw
                  child: Container(
                    width: 320,
                    height: 320,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      gradient: RadialGradient(colors: [widget.colors[i].withValues(alpha: 0.30), widget.colors[i].withValues(alpha: 0)]),
                    ),
                  ),
                ),
            ]);
          },
        ),
      ),
      widget.child,
    ]);
  }
}

/// A light sweep that passes over its child every few seconds (the "premium card" sheen).
class Shine extends StatefulWidget {
  const Shine({super.key, required this.child, this.radius = 22, this.period = const Duration(milliseconds: 3600)});

  final Widget child;
  final double radius;
  final Duration period;

  @override
  State<Shine> createState() => _ShineState();
}

class _ShineState extends State<Shine> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: widget.period)..repeat();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (MediaQuery.of(context).disableAnimations) return widget.child;
    return Stack(children: [
      widget.child,
      Positioned.fill(
        child: IgnorePointer(
          child: ClipRRect(
            borderRadius: BorderRadius.circular(widget.radius),
            child: AnimatedBuilder(
              animation: _c,
              builder: (_, _) {
                // sweep during the first 40% of the period, then rest
                final p = (_c.value / 0.4).clamp(0.0, 1.0);
                return FractionalTranslation(
                  translation: Offset(-1.2 + 2.4 * Curves.easeInOut.transform(p), 0),
                  child: Container(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: const Alignment(-1, -0.4),
                        end: const Alignment(1, 0.4),
                        colors: [Colors.white.withValues(alpha: 0), Colors.white.withValues(alpha: 0.32), Colors.white.withValues(alpha: 0)],
                        stops: const [0.35, 0.5, 0.65],
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ),
      ),
    ]);
  }
}

/// Gently pulses (scale + glow) — for badges like "-30%".
class Pulse extends StatefulWidget {
  const Pulse({super.key, required this.child, this.glow = Colors.white});

  final Widget child;
  final Color glow;

  @override
  State<Pulse> createState() => _PulseState();
}

class _PulseState extends State<Pulse> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 1400))..repeat(reverse: true);

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _c,
        builder: (_, child) {
          final t = Curves.easeInOut.transform(_c.value);
          return Transform.scale(
            scale: 1 + 0.08 * t,
            child: DecoratedBox(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(40),
                boxShadow: [BoxShadow(color: widget.glow.withValues(alpha: 0.55 * t), blurRadius: 18 * t, spreadRadius: 2 * t)],
              ),
              child: child,
            ),
          );
        },
        child: widget.child,
      );
}

/// Slides and fades its child in after [delay] — for staggered sections.
class Reveal extends StatelessWidget {
  const Reveal({super.key, required this.child, this.delay = Duration.zero, this.offset = 30});

  final Widget child;
  final Duration delay;
  final double offset;

  @override
  Widget build(BuildContext context) {
    final total = delay + const Duration(milliseconds: 700);
    final start = delay.inMilliseconds / total.inMilliseconds;
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: 1),
      duration: total,
      builder: (_, v, child) {
        final t = Curves.easeOutCubic.transform(((v - start) / (1 - start)).clamp(0.0, 1.0));
        return Opacity(opacity: t, child: Transform.translate(offset: Offset(0, (1 - t) * offset), child: child));
      },
      child: child,
    );
  }
}
