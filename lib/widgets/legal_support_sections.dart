import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show rootBundle, Clipboard, ClipboardData;
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:url_launcher/url_launcher.dart';

/// Sections "Aide & support" + "Mentions légales" partagées par toutes les
/// apps Files Tech. Reçoit la version pour préremplir les emails de support.
class LegalSupportSections extends StatelessWidget {
  final String appName;
  final String version;
  const LegalSupportSections({
    super.key,
    required this.appName,
    required this.version,
  });

  static const _email   = 'contact@files-tech.com';
  static const _website = 'https://files-tech.com';

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _section(context, 'Aide & support'),
        const SizedBox(height: 8),
        Card(
          child: Column(children: [
            ListTile(
              leading: Icon(Icons.email_outlined, color: cs.primary),
              title: const Text('Contacter le support'),
              subtitle: const Text(_email),
              trailing: const Icon(Icons.open_in_new, size: 16),
              onTap: () => _openMail(context, _email,
                  '$appName v$version — support'),
            ),
            const Divider(height: 1),
            ListTile(
              leading: Icon(Icons.public, color: cs.primary),
              title: const Text('Site officiel'),
              subtitle: const Text('files-tech.com'),
              trailing: const Icon(Icons.open_in_new, size: 16),
              onTap: () => _openUrl(context, _website),
            ),
            const Divider(height: 1),
            ListTile(
              leading: Icon(Icons.bug_report_outlined, color: cs.primary),
              title: const Text('Signaler un bug'),
              subtitle: const Text('Email avec version pré-remplie'),
              onTap: () => _openMail(context, _email,
                  '$appName v$version — bug',
                  body: 'Décrivez le problème rencontré :\n\n\n'
                      '— Version : $version\n— Appareil : '),
            ),
          ]),
        ),

        const SizedBox(height: 24),

        _section(context, 'Mentions légales'),
        const SizedBox(height: 8),
        Card(
          child: Column(children: [
            ListTile(
              leading: Icon(Icons.privacy_tip_outlined, color: cs.primary),
              title: const Text('Politique de confidentialité'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _openLegal(context,
                  title: 'Politique de confidentialité',
                  asset: 'assets/legal/PRIVACY.fr.md'),
            ),
            const Divider(height: 1),
            ListTile(
              leading: Icon(Icons.gavel_outlined, color: cs.primary),
              title: const Text('Conditions d\'utilisation'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _openLegal(context,
                  title: 'Conditions d\'utilisation',
                  asset: 'assets/legal/TERMS.fr.md'),
            ),
            const Divider(height: 1),
            ListTile(
              leading: Icon(Icons.copyright_outlined, color: cs.primary),
              title: const Text('Licence'),
              subtitle: const Text('Apache 2.0'),
              onTap: () => _openUrl(context,
                  'https://www.apache.org/licenses/LICENSE-2.0'),
            ),
          ]),
        ),
        const SizedBox(height: 16),
        Center(
          child: Text(
            '© ${DateTime.now().year} Files Tech',
            style: TextStyle(color: Colors.grey.shade600, fontSize: 11),
          ),
        ),
      ],
    );
  }

  Widget _section(BuildContext context, String title) {
    return Padding(
      padding: const EdgeInsets.only(left: 2),
      child: Text(title,
          style: Theme.of(context).textTheme.titleSmall?.copyWith(
              color: Colors.grey.shade600,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.5)),
    );
  }

  Future<void> _openUrl(BuildContext context, String url) async {
    final messenger = ScaffoldMessenger.of(context);
    final uri = Uri.parse(url);
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    } else {
      messenger.showSnackBar(SnackBar(content: Text('Impossible d\'ouvrir : $url')));
    }
  }

  Future<void> _openMail(BuildContext context, String to, String subject,
      {String? body}) async {
    final messenger = ScaffoldMessenger.of(context);
    final params = <String, String>{'subject': subject};
    if (body != null) params['body'] = body;
    final query = params.entries.map((e) =>
        '${Uri.encodeComponent(e.key)}=${Uri.encodeComponent(e.value)}').join('&');
    final uri = Uri.parse('mailto:$to?$query');
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri);
    } else {
      await Clipboard.setData(ClipboardData(text: to));
      messenger.showSnackBar(SnackBar(
        content: Text('Aucune app mail. Adresse copiée : $to'),
      ));
    }
  }

  void _openLegal(BuildContext context,
      {required String title, required String asset}) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => _LegalScreen(title: title, asset: asset),
      ),
    );
  }
}

class _LegalScreen extends StatelessWidget {
  final String title;
  final String asset;
  const _LegalScreen({required this.title, required this.asset});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: FutureBuilder<String>(
        future: rootBundle.loadString(asset),
        builder: (ctx, snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snap.hasError || !snap.hasData) {
            return Center(child: Text('Erreur de chargement : ${snap.error}'));
          }
          return Markdown(
            data: snap.data!,
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
            selectable: true,
            onTapLink: (text, href, title) async {
              if (href == null) return;
              final uri = Uri.parse(href);
              if (await canLaunchUrl(uri)) {
                await launchUrl(uri, mode: LaunchMode.externalApplication);
              }
            },
          );
        },
      ),
    );
  }
}
