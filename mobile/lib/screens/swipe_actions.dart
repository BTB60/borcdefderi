import 'package:flutter/material.dart';

/// One button revealed behind a row when it is swiped to the left.
class SwipeAction {
  const SwipeAction({required this.icon, required this.label, required this.color, required this.onTap});

  final IconData icon;
  final String label;
  final Color color;
  final VoidCallback onTap;

  static SwipeAction edit(VoidCallback onTap) =>
      SwipeAction(icon: Icons.edit_outlined, label: 'Redaktə', color: const Color(0xFF2563EB), onTap: onTap);

  static SwipeAction delete(VoidCallback onTap, {String label = 'Sil'}) =>
      SwipeAction(icon: Icons.delete_outline, label: label, color: const Color(0xFFDC2626), onTap: onTap);
}

/// Swipe a row to the left to reveal its action buttons (Redaktə / Sil); tap one, or swipe back / tap the row to close.
/// With no actions the row is shown as it is.
class SwipeActions extends StatefulWidget {
  const SwipeActions({super.key, required this.child, required this.actions});

  final Widget child;
  final List<SwipeAction> actions;

  @override
  State<SwipeActions> createState() => _SwipeActionsState();
}

class _SwipeActionsState extends State<SwipeActions> with SingleTickerProviderStateMixin {
  static const _buttonWidth = 76.0;
  late final _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 180));

  double get _max => _buttonWidth * widget.actions.length;

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  void _close() => _c.animateTo(0, curve: Curves.easeOut);

  @override
  Widget build(BuildContext context) {
    if (widget.actions.isEmpty) return widget.child;
    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onHorizontalDragUpdate: (d) => _c.value = (_c.value - d.primaryDelta! / _max).clamp(0.0, 1.0),
      onHorizontalDragEnd: (d) {
        final v = d.primaryVelocity ?? 0;
        if (v < -300 || (v <= 300 && _c.value > 0.4)) {
          _c.animateTo(1, curve: Curves.easeOut);
        } else {
          _close();
        }
      },
      child: ClipRect(
        child: Stack(children: [
          Positioned.fill(
            child: Row(mainAxisAlignment: MainAxisAlignment.end, children: [
              for (final a in widget.actions)
                SizedBox(
                  width: _buttonWidth,
                  child: Material(
                    color: a.color,
                    child: InkWell(
                      onTap: () {
                        _close();
                        a.onTap();
                      },
                      child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                        Icon(a.icon, color: Colors.white),
                        const SizedBox(height: 4),
                        Text(a.label, style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w600)),
                      ]),
                    ),
                  ),
                ),
            ]),
          ),
          AnimatedBuilder(
            animation: _c,
            builder: (context, child) => Transform.translate(offset: Offset(-_max * _c.value, 0), child: child),
            child: Material(
              color: Theme.of(context).cardColor,
              child: NotificationListener<ScrollNotification>(onNotification: (_) => false, child: widget.child),
            ),
          ),
        ]),
      ),
    );
  }
}
