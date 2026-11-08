/// API base URL. Override at build time:
///   flutter run --dart-define=API_URL=https://borcdefteri-api.onrender.com
const String apiBaseUrl = String.fromEnvironment('API_URL', defaultValue: 'http://10.0.2.2:8080');
