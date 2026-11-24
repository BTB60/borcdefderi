import 'package:flutter/material.dart';
import 'package:intl/date_symbol_data_local.dart';

import 'api.dart';
import 'screens/cards_screen.dart';
import 'screens/login_screen.dart';

const navy = Color(0xFF0F1F3D);
const gold = Color(0xFFD4A537);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initializeDateFormatting('az');
  await Api.instance.load();
  runApp(const KartApp());
}

/// HesabPro Kart: the customer's bonus cards from every store that uses HesabPro.
class KartApp extends StatelessWidget {
  const KartApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'HesabPro Kart',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          useMaterial3: true,
          colorScheme: ColorScheme.fromSeed(seedColor: navy, primary: navy, secondary: gold),
          scaffoldBackgroundColor: const Color(0xFFF5F6FA),
          inputDecorationTheme: InputDecorationTheme(
            filled: true,
            fillColor: Colors.white,
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
          ),
          filledButtonTheme: FilledButtonThemeData(
            style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(54), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14))),
          ),
        ),
        home: Api.instance.token == null ? const LoginScreen() : const CardsScreen(welcome: true),
      );
}
