import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../api/api_client.dart';
import '../models/models.dart';

/// Where the bearer token is kept between launches. Secure storage on device; memory in tests.
abstract class TokenStore {
  Future<String?> read();
  Future<void> write(String? token);
}

class SecureTokenStore implements TokenStore {
  static const _key = 'bdf_token';
  final FlutterSecureStorage _storage = const FlutterSecureStorage();

  @override
  Future<String?> read() => _storage.read(key: _key);

  @override
  Future<void> write(String? token) =>
      token == null ? _storage.delete(key: _key) : _storage.write(key: _key, value: token);
}

class MemoryTokenStore implements TokenStore {
  String? _token;

  @override
  Future<String?> read() async => _token;

  @override
  Future<void> write(String? token) async => _token = token;
}

/// Holds the signed-in user and performs auth calls against the shared REST API.
class Session extends ChangeNotifier {
  Session({required this.api, required this.tokens}) {
    api.onUnauthorized = () {
      if (me != null) {
        _clear().then((_) => notifyListeners());
      }
    };
  }

  final ApiClient api;
  final TokenStore tokens;

  Me? me;
  bool restoring = true;

  /// Set when the saved session could not be checked (no internet / server down). The token is kept.
  String? restoreError;

  bool get isSignedIn => me != null;

  Future<void> restore() async {
    restoring = true;
    restoreError = null;
    final saved = await tokens.read();
    if (saved == null) {
      restoring = false;
      notifyListeners();
      return;
    }
    api.setToken(saved);
    try {
      me = Me.fromJson(await api.get('/api/auth/me') as Map<String, dynamic>);
    } on ApiException catch (e) {
      if (e.status == 401) {
        await _clear(); // expired or revoked: sign in again
      } else {
        restoreError = e.message; // offline / server error: do NOT sign the user out
      }
    } finally {
      restoring = false;
      notifyListeners();
    }
  }

  Future<void> login(String email, String password) async {
    final res = await api.post('/api/auth/login', {'email': email, 'password': password}) as Map<String, dynamic>;
    await _apply(res);
  }

  Future<void> register({
    required String email,
    required String password,
    required String fullName,
    required String storeName,
    String? storePhone,
  }) async {
    final res = await api.post('/api/auth/register', {
      'email': email,
      'password': password,
      'fullName': fullName,
      'storeName': storeName,
      if (storePhone != null && storePhone.isNotEmpty) 'storePhone': storePhone,
    }) as Map<String, dynamic>;
    await _apply(res);
  }

  /// Re-reads profile and subscription (e.g. after the admin approves a subscription).
  Future<void> refresh() async {
    me = Me.fromJson(await api.get('/api/auth/me') as Map<String, dynamic>);
    notifyListeners();
  }

  Future<void> logout() async {
    await _clear();
    notifyListeners();
  }

  Future<void> _apply(Map<String, dynamic> res) async {
    final token = res['token'] as String;
    api.setToken(token);
    await tokens.write(token);
    me = Me.fromJson(res['me'] as Map<String, dynamic>);
    notifyListeners();
  }

  Future<void> _clear() async {
    api.setToken(null);
    await tokens.write(null);
    me = null;
  }
}
