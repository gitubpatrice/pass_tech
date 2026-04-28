# Politique de sécurité — Pass Tech

> Pass Tech est un gestionnaire de mots de passe. La sécurité est la priorité absolue de ce projet. Tout signalement responsable est traité avec la plus haute priorité.

## Versions supportées

Seule la dernière version publiée sur GitHub Releases est activement maintenue côté sécurité.

| Version       | Supportée  |
| ------------- | ---------- |
| 1.8.x         | ✅          |
| < 1.8.0       | ❌          |

## Signaler une vulnérabilité

**Merci de ne PAS ouvrir d'issue publique sur GitHub** — un gestionnaire de mots de passe demande une divulgation strictement coordonnée.

📧 **Envoyez un email chiffré (si possible) à : contact@files-tech.com**

Indiquez dans le sujet : `[SECURITY] Pass Tech — <description courte>`.

Merci d'inclure :

- Une description claire de la vulnérabilité
- Les étapes pour la reproduire (PoC bienvenue mais non requise)
- L'impact potentiel (compromission du vault ? vol clé ? bypass biométrie ?)
- La version affectée
- Une suggestion de correctif si possible

## Délai de réponse renforcé (gestionnaire de mots de passe)

- Accusé de réception : sous 48 heures
- Évaluation initiale : sous 7 jours
- Correctif : selon la criticité
  - Critique (compromission du vault, fuite clé) → patch sous 7 jours
  - Majeure → patch sous 30 jours
  - Mineure → version suivante

## Divulgation responsable

Merci de ne pas divulguer publiquement la vulnérabilité avant qu'un correctif ne soit publié et qu'un délai raisonnable de mise à jour (90 jours minimum) ait été laissé aux utilisateurs.

## Vérification de l'intégrité d'un APK

Chaque release publiée sur GitHub contient un hash SHA-256 attendu pour l'APK arm64-v8a dans les notes. Avant install, vous pouvez vérifier :

```bash
sha256sum app-arm64-v8a-release.apk
```

Le résultat doit correspondre exactement à la valeur publiée. Sinon, **ne pas installer l'APK**.

## Modèle de menace

Pass Tech protège contre :

- Vol physique de l'appareil (verrouillage écran + biométrique hardware-bound + lockout progressif)
- Lecture du fichier vault sans le mot de passe maître (AES-256-CBC + HMAC-SHA256, PBKDF2-SHA256 600 000 iter)
- Attaque hors-ligne sur le vault exfiltré (PBKDF2 600k force le coût)
- Capture d'écran et aperçu du sélecteur récent (FLAG_SECURE)
- Backup cloud Android (allowBackup=false + dataExtractionRules)
- Brute-force du master password (lockout progressif 5 fails → 30s à 30min)
- Réutilisation après timeout (auto-lock configurable + wipe clé RAM)
- Trafic réseau MITM partiel (HIBP en k-anonymity, network_security_config)

Pass Tech NE protège PAS contre :

- Appareil rooté + Frida actif (un avertissement RASP est affiché mais l'utilisateur peut continuer à ses risques)
- Keylogger système / clavier compromis
- Compromission complète du Keystore Android matériel
- Attaquant ayant le mot de passe maître

## Périmètre

Vulnérabilités acceptées :

- Faiblesse cryptographique (PBKDF2, AES, HMAC, IV)
- Bypass de la biométrie hardware-bound
- Bypass du lockout / brute-force facilité
- Fuite du vault chiffré ou de la clé maître hors processus
- Side-channels timing exploitables

Hors périmètre :

- Bugs UX sans impact sécurité
- Vulnérabilités dans `flutter_secure_storage`, `biometric_storage`, `encrypt`, `crypto` déjà reportées en amont
- Attaques nécessitant un appareil rooté/compromis (déjà couvert par RASP)
- Attaques physiques sur l'appareil déverrouillé avec le vault ouvert
