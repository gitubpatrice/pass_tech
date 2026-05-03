import 'package:flutter/material.dart';

const categories = [
  'Web',
  'Email',
  'Banque',
  'Réseaux sociaux',
  'Apps',
  'Cartes',
  'Autres',
];

IconData categoryIcon(String cat) {
  switch (cat) {
    case 'Web':
      return Icons.language;
    case 'Email':
      return Icons.email_outlined;
    case 'Banque':
      return Icons.account_balance_outlined;
    case 'Réseaux sociaux':
      return Icons.people_outline;
    case 'Apps':
      return Icons.apps;
    case 'Cartes':
      return Icons.credit_card_outlined;
    default:
      return Icons.folder_outlined;
  }
}

Color categoryColor(String cat) {
  switch (cat) {
    case 'Web':
      return const Color(0xFF58A6FF);
    case 'Email':
      return const Color(0xFFFF7043);
    case 'Banque':
      return const Color(0xFF43A047);
    case 'Réseaux sociaux':
      return const Color(0xFF7B1FA2);
    case 'Apps':
      return const Color(0xFF00897B);
    case 'Cartes':
      return const Color(0xFFFDD835);
    default:
      return const Color(0xFF8B949E);
  }
}
