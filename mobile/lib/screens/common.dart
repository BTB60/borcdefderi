import 'package:flutter/material.dart';

import '../api/api_client.dart';
import '../format.dart';
import '../theme.dart';

String errorMessage(Object error) {
  if (error is ApiException) return friendlyError(error.code, error.message);
  return 'Xəta baş verdi';
}

void showError(BuildContext context, Object error) {
  if (!context.mounted) return;
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(errorMessage(error))));
}

class ErrorBanner extends StatelessWidget {
  const ErrorBanner(this.message, {super.key});

  final String message;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        margin: const EdgeInsets.only(bottom: 12),
        decoration: BoxDecoration(color: Colors.red.shade50, borderRadius: BorderRadius.circular(12)),
        child: Text(message, style: TextStyle(color: Colors.red.shade800)),
      );
}

class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.title, this.text});

  final String title;
  final String? text;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(title, textAlign: TextAlign.center, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: navy)),
              if (text != null) ...[
                const SizedBox(height: 6),
                Text(text!, textAlign: TextAlign.center, style: TextStyle(color: Colors.grey.shade600)),
              ],
            ],
          ),
        ),
      );
}

/// Dark navy stat card with a gold figure, used on the dashboard and debt headers.
class StatTile extends StatelessWidget {
  const StatTile({super.key, required this.label, required this.value, this.hint});

  final String label;
  final String value;
  final String? hint;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(color: navy, borderRadius: BorderRadius.circular(16)),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: const TextStyle(color: Colors.white70, fontSize: 12)),
            const SizedBox(height: 6),
            FittedBox(
              fit: BoxFit.scaleDown,
              alignment: Alignment.centerLeft,
              child: Text(value, style: const TextStyle(color: gold, fontSize: 20, fontWeight: FontWeight.w700)),
            ),
            if (hint != null) Text(hint!, style: const TextStyle(color: Colors.white54, fontSize: 11)),
          ],
        ),
      );
}

class StatusChip extends StatelessWidget {
  const StatusChip(this.status, {super.key});

  final String status;

  @override
  Widget build(BuildContext context) {
    final color = switch (status) {
      'PAID' => Colors.green,
      'PARTIAL' => Colors.orange,
      _ => Colors.blueGrey,
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(999)),
      child: Text(debtStatusLabels[status] ?? status, style: TextStyle(color: color.shade800, fontSize: 12, fontWeight: FontWeight.w600)),
    );
  }
}
