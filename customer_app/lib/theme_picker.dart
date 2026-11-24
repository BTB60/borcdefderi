import 'package:flutter/material.dart';

import 'card_widget.dart';
import 'effects.dart';
import 'models.dart';
import 'themes.dart';

/// "Kartın dizaynı": the store's own design and the ready-made looks, each shown on the customer's real card.
/// Returns the chosen theme id ([storeThemeId] for the store's design), or null when closed.
Future<String?> showThemePicker(BuildContext context, LoyaltyCard card, String current) {
  return showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (_) => _ThemeSheet(card: card, current: current),
  );
}

class _ThemeSheet extends StatefulWidget {
  const _ThemeSheet({required this.card, required this.current});

  final LoyaltyCard card;
  final String current;

  @override
  State<_ThemeSheet> createState() => _ThemeSheetState();
}

class _ThemeSheetState extends State<_ThemeSheet> {
  late String _picked = widget.current;

  @override
  Widget build(BuildContext context) {
    final options = <(String, String, KartTheme?)>[
      (storeThemeId, 'Mağazanın dizaynı', null),
      for (final t in cardThemes) (t.id, t.name, t),
    ];
    final chosen = themeById(_picked);
    return DraggableScrollableSheet(
      initialChildSize: 0.86,
      maxChildSize: 0.95,
      minChildSize: 0.5,
      builder: (_, scroll) => Container(
        decoration: const BoxDecoration(color: Color(0xFFF7F6FB), borderRadius: BorderRadius.vertical(top: Radius.circular(28))),
        child: ListView(controller: scroll, padding: const EdgeInsets.fromLTRB(20, 10, 20, 28), children: [
          Center(
            child: Container(width: 44, height: 5, decoration: BoxDecoration(color: Colors.grey.shade300, borderRadius: BorderRadius.circular(3))),
          ),
          const SizedBox(height: 14),
          const Text('Kartın dizaynı', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w900, color: Color(0xFF0F1F3D))),
          Text('${widget.card.storeName} kartı üçün görünüşü seçin', style: TextStyle(color: Colors.grey.shade600)),
          const SizedBox(height: 16),
          // the big preview follows the choice
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 450),
            transitionBuilder: (child, a) => FadeTransition(
                opacity: a, child: SlideTransition(position: Tween(begin: const Offset(0, 0.06), end: Offset.zero).animate(a), child: child)),
            child: Shine(
              key: ValueKey(_picked),
              child: CardFront(card: widget.card, theme: chosen, animateBalance: false),
            ),
          ),
          const SizedBox(height: 18),
          GridView.builder(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            itemCount: options.length,
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: 2, mainAxisSpacing: 14, crossAxisSpacing: 14, childAspectRatio: 1.25),
            itemBuilder: (_, i) {
              final (id, name, theme) = options[i];
              final on = id == _picked;
              return Reveal(
                delay: Duration(milliseconds: 40 * i),
                offset: 20,
                child: GestureDetector(
                  onTap: () => setState(() => _picked = id),
                  child: AnimatedScale(
                    scale: on ? 1.0 : 0.94,
                    duration: const Duration(milliseconds: 250),
                    curve: Curves.easeOutBack,
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Stack(children: [
                        AnimatedContainer(
                          duration: const Duration(milliseconds: 250),
                          padding: const EdgeInsets.all(3),
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(25),
                            border: Border.all(color: on ? const Color(0xFF7C3AED) : Colors.transparent, width: 2.5),
                          ),
                          // a small card: the text is scaled down with it
                          child: FittedBox(
                            child: SizedBox(width: 320, child: CardFront(card: widget.card, theme: theme, animateBalance: false)),
                          ),
                        ),
                        Positioned(
                          right: 8,
                          top: 8,
                          child: AnimatedScale(
                            scale: on ? 1 : 0,
                            duration: const Duration(milliseconds: 300),
                            curve: Curves.elasticOut,
                            child: const CircleAvatar(radius: 12, backgroundColor: Color(0xFF7C3AED), child: Icon(Icons.check, size: 16, color: Colors.white)),
                          ),
                        ),
                      ]),
                      const SizedBox(height: 6),
                      Padding(
                        padding: const EdgeInsets.only(left: 4),
                        child: Text(name, style: TextStyle(fontWeight: on ? FontWeight.w800 : FontWeight.w600, color: const Color(0xFF0F1F3D))),
                      ),
                    ]),
                  ),
                ),
              );
            },
          ),
          const SizedBox(height: 18),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: const Color(0xFF7C3AED)),
            onPressed: () => Navigator.of(context).pop(_picked),
            child: const Text('Bu dizaynı seç'),
          ),
        ]),
      ),
    );
  }
}
