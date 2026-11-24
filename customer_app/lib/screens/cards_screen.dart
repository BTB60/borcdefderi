import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../api.dart';
import '../card_widget.dart';
import '../effects.dart';
import '../main.dart';
import '../models.dart';
import '../promo_widgets.dart';
import 'login_screen.dart';

const _kind = {'EARN': 'Yığıldı', 'USE': 'Xərcləndi', 'ADJUST': 'Mağaza tərəfindən', 'REVERSE': 'Qaytarma'};

/// The wallet: an animated "Salam, Aynur!" when the customer comes in, then their cards (swipe between stores,
/// tap to turn a card over to its barcode) and the history of the card in front.
class CardsScreen extends StatefulWidget {
  const CardsScreen({super.key, this.welcome = false});

  final bool welcome;

  @override
  State<CardsScreen> createState() => _CardsScreenState();
}

class _CardsScreenState extends State<CardsScreen> with TickerProviderStateMixin {
  late final AnimationController _hello = AnimationController(vsync: this, duration: const Duration(milliseconds: 2600));
  late final AnimationController _deal = AnimationController(vsync: this, duration: const Duration(milliseconds: 1100));
  final _pages = PageController(viewportFraction: 0.88);
  String? _name;
  List<LoyaltyCard>? _cards;
  List<Promo> _promos = const [];
  String? _error;
  int _page = 0;
  bool _showHello = true;
  final Map<String, List<BonusEntry>?> _history = {};

  @override
  void initState() {
    super.initState();
    _showHello = widget.welcome;
    _load();
    if (_showHello) {
      _hello.forward().whenComplete(() {
        if (!mounted) return;
        setState(() => _showHello = false);
        _deal.forward();
      });
    } else {
      _deal.forward();
    }
    _pages.addListener(() {
      final p = _pages.page?.round() ?? 0;
      if (p != _page) {
        setState(() => _page = p);
        _loadHistory();
      }
    });
  }

  @override
  void dispose() {
    _hello.dispose();
    _deal.dispose();
    _pages.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final me = await Api.instance.get('/me') as Map<String, dynamic>;
      if (!mounted) return;
      setState(() {
        _name = me['firstName'] as String?;
        _cards = (me['cards'] as List).cast<Map<String, dynamic>>().map(LoyaltyCard.new).toList();
        _error = null;
        _history.clear();
      });
      _loadHistory();
      _loadPromos();
    } on ApiException catch (e) {
      if (e.status == 401) return _logout();
      if (mounted) setState(() => _error = e.message);
    }
  }

  Future<void> _loadPromos() async {
    try {
      final list = (await Api.instance.get('/promos') as List).cast<Map<String, dynamic>>().map(Promo.new).toList();
      if (mounted) setState(() => _promos = list);
    } catch (_) {
      // the offers are a bonus: the wallet works without them
    }
  }

  Future<void> _loadHistory() async {
    final cards = _cards;
    if (cards == null || cards.isEmpty) return;
    final card = cards[_page.clamp(0, cards.length - 1)];
    if (_history.containsKey(card.id)) return;
    _history[card.id] = null;
    try {
      final list = (await Api.instance.get('/cards/${card.id}/history') as List).cast<Map<String, dynamic>>().map(BonusEntry.new).toList();
      if (mounted) setState(() => _history[card.id] = list);
    } catch (_) {
      _history.remove(card.id);
    }
  }

  Future<void> _logout() async {
    await Api.instance.save(null);
    if (!mounted) return;
    Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => const LoginScreen()));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: AnimatedSwitcher(
        duration: const Duration(milliseconds: 600),
        child: _showHello
            ? _Hello(key: const ValueKey('hello'), controller: _hello, name: _name)
            : AuroraBackground(key: const ValueKey('wallet'), child: _wallet()),
      ),
    );
  }

  Widget _wallet() {
    final cards = _cards;
    return SafeArea(
      child: RefreshIndicator(
        onRefresh: _load,
        child: ListView(padding: const EdgeInsets.only(bottom: 32), children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 8, 8),
            child: Row(children: [
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  ShaderMask(
                    shaderCallback: (r) => const LinearGradient(colors: [navy, Color(0xFF7C3AED)]).createShader(r),
                    child: Text(_name == null ? 'Kartlarım' : 'Salam, $_name!',
                        style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w900, color: Colors.white)),
                  ),
                  Text(cards == null ? '' : '${cards.length} kart', style: TextStyle(color: Colors.grey.shade600)),
                ]),
              ),
              IconButton(tooltip: 'Çıxış', onPressed: _logout, icon: const Icon(Icons.logout)),
            ]),
          ),
          if (_error != null) Padding(padding: const EdgeInsets.all(20), child: Text(_error!, style: TextStyle(color: Colors.red.shade700))),
          if (cards == null && _error == null) const Padding(padding: EdgeInsets.all(48), child: Center(child: CircularProgressIndicator())),
          if (cards != null && cards.isEmpty)
            const Padding(
              padding: EdgeInsets.all(32),
              child: Text('Hələ bonus kartınız yoxdur. HesabPro işlədən mağaza sizə kart verən kimi burada görünəcək.',
                  textAlign: TextAlign.center),
            ),
          if (cards != null && cards.isNotEmpty) ...[
            SizedBox(
              height: (MediaQuery.of(context).size.width * 0.88 - 24) / cardAspect + 40,
              child: PageView.builder(
                controller: _pages,
                clipBehavior: Clip.none, // keep the shadows
                itemCount: cards.length,
                itemBuilder: (_, i) => AnimatedBuilder(
                  animation: Listenable.merge([_pages, _deal]),
                  builder: (_, child) {
                    // cards are dealt in one after another, then grow a little when in front
                    final start = (i * 0.15).clamp(0.0, 0.6);
                    final dealt = Curves.easeOutBack.transform(((_deal.value - start) / (1 - start)).clamp(0.0, 1.0));
                    double front = 1;
                    if (_pages.hasClients && _pages.position.haveDimensions) {
                      front = (1 - ((_pages.page ?? 0) - i).abs() * 0.12).clamp(0.85, 1.0);
                    } else if (i != _page) {
                      front = 0.88;
                    }
                    return Opacity(
                      opacity: dealt.clamp(0.0, 1.0),
                      child: Transform.translate(
                        offset: Offset(0, (1 - dealt) * 120),
                        child: Transform.scale(scale: front, child: child),
                      ),
                    );
                  },
                  child: Padding(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 16), child: FlipCard(card: cards[i])),
                ),
              ),
            ),
            if (cards.length > 1)
              Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                for (var i = 0; i < cards.length; i++)
                  AnimatedContainer(
                    duration: const Duration(milliseconds: 250),
                    margin: const EdgeInsets.symmetric(horizontal: 3),
                    width: i == _page ? 22 : 8,
                    height: 8,
                    decoration: BoxDecoration(color: i == _page ? navy : Colors.grey.shade300, borderRadius: BorderRadius.circular(4)),
                  ),
              ]),
            const SizedBox(height: 6),
            Center(child: Text('Barkodu görmək üçün karta toxunun', style: TextStyle(color: Colors.grey.shade600, fontSize: 12))),
            if (_promos.isNotEmpty) ...[
              Reveal(
                delay: const Duration(milliseconds: 300),
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 22, 20, 4),
                  child: Row(children: [
                    const Pulse(glow: Color(0xFFF97316), child: Text('🔥', style: TextStyle(fontSize: 20))),
                    const SizedBox(width: 8),
                    const Text('Endirimlər və yeniliklər', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 18, color: navy)),
                    const Spacer(),
                    Text('${_promos.length}', style: TextStyle(color: Colors.grey.shade600, fontWeight: FontWeight.w600)),
                  ]),
                ),
              ),
              Reveal(delay: const Duration(milliseconds: 420), child: PromoCarousel(promos: _promos)),
            ],
            Reveal(delay: const Duration(milliseconds: 520), child: _details(cards[_page.clamp(0, cards.length - 1)])),
          ],
        ]),
      ),
    );
  }

  Widget _details(LoyaltyCard c) {
    final list = _history[c.id];
    final fmt = DateFormat('dd.MM.yyyy HH:mm', 'az');
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 0),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          _stat('Balans', money(c.balance)),
          const SizedBox(width: 10),
          _stat('Hər alışdan', '${c.percent == c.percent.roundToDouble() ? c.percent.toStringAsFixed(0) : c.percent}%'),
        ]),
        if (c.storePhone != null) Padding(padding: const EdgeInsets.only(top: 10), child: Text('${c.storeName} · ${c.storePhone}', style: TextStyle(color: Colors.grey.shade700))),
        const SizedBox(height: 16),
        const Text('Bonus tarixçəsi', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16, color: navy)),
        const SizedBox(height: 6),
        if (list == null)
          const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator()))
        else if (list.isEmpty)
          Padding(padding: const EdgeInsets.all(12), child: Text('Hələ bonus hərəkəti yoxdur', style: TextStyle(color: Colors.grey.shade600)))
        else
          for (final e in list)
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: CircleAvatar(
                backgroundColor: (e.amount >= 0 ? Colors.green : Colors.red).withValues(alpha: 0.12),
                child: Icon(e.amount >= 0 ? Icons.add : Icons.remove, color: e.amount >= 0 ? Colors.green.shade700 : Colors.red.shade700),
              ),
              title: Text(_kind[e.kind] ?? e.kind),
              subtitle: Text([if (e.createdAt != null) fmt.format(e.createdAt!), if (e.saleNumber != null) e.saleNumber!].join(' · ')),
              trailing: Text('${e.amount > 0 ? '+' : ''}${money(e.amount)}',
                  style: TextStyle(fontWeight: FontWeight.w700, color: e.amount >= 0 ? Colors.green.shade700 : Colors.red.shade700)),
            ),
      ]),
    );
  }

  Widget _stat(String label, String value) => Expanded(
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: 0.72),
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: Colors.white),
            boxShadow: [BoxShadow(color: navy.withValues(alpha: 0.06), blurRadius: 16, offset: const Offset(0, 6))],
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
            const SizedBox(height: 4),
            Text(value, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: navy)),
          ]),
        ),
      );
}

/// "Salam, Aynur!" — the greeting that opens the wallet: a glowing card icon, then the name letter by letter.
class _Hello extends StatelessWidget {
  const _Hello({super.key, required this.controller, required this.name});

  final AnimationController controller;
  final String? name;

  @override
  Widget build(BuildContext context) {
    final who = name ?? '';
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(colors: [navy, Color(0xFF3B1F6B)], begin: Alignment.topLeft, end: Alignment.bottomRight),
      ),
      child: Center(
        child: AnimatedBuilder(
          animation: controller,
          builder: (_, _) {
            final t = controller.value;
            final icon = Curves.elasticOut.transform((t / 0.4).clamp(0.0, 1.0));
            final hello = Curves.easeOut.transform(((t - 0.25) / 0.25).clamp(0.0, 1.0));
            final letters = (((t - 0.45) / 0.35).clamp(0.0, 1.0) * who.length).round();
            final fadeOut = 1 - Curves.easeIn.transform(((t - 0.88) / 0.12).clamp(0.0, 1.0));
            return Opacity(
              opacity: fadeOut,
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                Transform.scale(
                  scale: icon,
                  child: Container(
                    width: 96,
                    height: 96,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: gold.withValues(alpha: 0.18),
                      boxShadow: [BoxShadow(color: gold.withValues(alpha: 0.5 * icon.clamp(0, 1)), blurRadius: 40, spreadRadius: 4)],
                    ),
                    child: const Icon(Icons.credit_card_rounded, color: gold, size: 52),
                  ),
                ),
                const SizedBox(height: 28),
                Opacity(
                  opacity: hello,
                  child: Transform.translate(
                    offset: Offset(0, (1 - hello) * 20),
                    child: Text('Salam${who.isEmpty ? '!' : ','}', style: const TextStyle(color: Colors.white70, fontSize: 22)),
                  ),
                ),
                const SizedBox(height: 6),
                Text(who.substring(0, letters.clamp(0, who.length)) + (letters > 0 && letters >= who.length ? '!' : ''),
                    style: const TextStyle(color: Colors.white, fontSize: 40, fontWeight: FontWeight.w800)),
              ]),
            );
          },
        ),
      ),
    );
  }
}
