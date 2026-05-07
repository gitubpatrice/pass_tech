# Politique de confidentialité — Pass Tech

**Version du document** : 7 mai 2026 (Pass Tech v2.0.2)
**App** : Pass Tech
**Site officiel** : https://www.files-tech.com
**Contact** : contact@files-tech.com
**Code source** : https://github.com/gitubpatrice/pass_tech
**Licence du code** : Apache License 2.0

---

## 1. Objet

La présente Politique de confidentialité explique comment l'application **Pass Tech** — un gestionnaire de mots de passe 100 % local — traite les données et permissions de l'utilisateur.

## 2. Résumé pour l'utilisateur

- ✅ **Aucune publicité** dans l'application.
- ✅ **Aucun traceur**, mesure d'audience, analyse comportementale ou profilage.
- ✅ **Aucun compte** propre à l'application.
- ✅ **Aucune synchronisation cloud** — votre coffre-fort reste sur votre appareil, chiffré.
- ✅ **Aucune télémétrie** — pas de données d'usage, pas de rapports d'erreur envoyés au développeur.

**Principe général** : Pass Tech est un coffre-fort de mots de passe 100 % local. Toutes les données sensibles (mots de passe, secrets TOTP, cartes bancaires, notes sécurisées) restent chiffrées sur l'appareil. Aucun serveur distant n'est opéré par le développeur.

## 3. Responsable / développeur

- **Développeur** : Files Tech / Patrice
- **Site internet** : https://www.files-tech.com
- **Contact confidentialité** : contact@files-tech.com
- **Dépôt source** : https://github.com/gitubpatrice/pass_tech
- **Licence du code source** : Apache License 2.0

## 4. Données accessibles ou stockées

| Type de donnée                       | Utilisation                                              | Lieu de traitement                                |
| ------------------------------------ | -------------------------------------------------------- | ------------------------------------------------- |
| Mots de passe, secrets TOTP, cartes bancaires, notes sécurisées | Entrées du coffre créées par l'utilisateur | Chiffré au repos sur l'appareil (`pt_vault.enc`)  |
| Mot de passe maître                  | Dérive la clé de chiffrement (Argon2id m=19 MiB, t=2)    | Jamais persisté ; effacé de la RAM au verrouillage |
| Clé biométrique                      | Déverrouillage optionnel par empreinte / face            | Android Keystore (lié au matériel), `setUserAuthenticationRequired(true)` |
| KEK liée au matériel                 | Clé AES-256-GCM enveloppant le secret du coffre          | Android Keystore alias `pt_vault_kek_v1` (StrongBox/TEE) — non extractible |
| Coffre leurre (optionnel)            | Coffre secondaire pour déni plausible                    | Chiffré avec un alias KEK distinct (`pt_vault_kek_decoy_v1`) sur l'appareil |
| Sauvegardes chiffrées (`.ptbak`)     | Export optionnel déclenché par l'utilisateur             | Emplacement choisi par l'utilisateur              |
| Préférences locales                  | Thème, durée auto-lock, timeout presse-papier            | Stockage local sur l'appareil                     |

## 5. Chiffrement & dérivation de clé (vault v4)

- **AES-256-GCM** (NIST SP 800-38D) avec nonce aléatoire 96 bits et tag d'authentification 128 bits. L'AAD GCM lie `version | alias KEK | paramètres KDF` pour empêcher tout downgrade silencieux.
- **Argon2id** (RFC 9106) — m = 19 MiB, t = 2, p = 1, L = 32 octets (recommandation OWASP 2024 pour gestionnaires de mots de passe sur mobile). Remplace le PBKDF2 utilisé dans les coffres v3 hérités.
- **KEK liée au matériel** — un `hwSecret` de 32 octets est enveloppé par une KEK AES-256-GCM stockée dans l'Android Keystore (alias `pt_vault_kek_v1`, StrongBox si disponible, fallback TEE software). La KEK ne quitte jamais le secure element.
- **Dérivation finale** — `finalKey = HKDF-SHA256(salt, pwHash || hwSecret, "pt:v4", 32)`. Un attaquant qui exfiltre le seul fichier coffre ne peut pas le brute-forcer sans la KEK liée à l'appareil.
- **Déni plausible** — 2 alias KEK (`pt_vault_kek_v1` + `pt_vault_kek_decoy_v1`) sont créés systématiquement à la 1ʳᵉ install, qu'un coffre leurre soit configuré ou non ; un sel dummy de 32 octets est généré et le timing de la vérification du leurre est aligné sur celui du coffre principal — l'inspection du Keystore et l'analyse temporelle ne révèlent rien sur l'usage du décoy.
- **Sauvegardes `.ptbak`** — chiffrées avec une passphrase choisie par l'utilisateur, même pack Argon2id + AES-GCM (pas de liaison Keystore pour rester portable entre appareils).
- **Déverrouillage biométrique** — optionnel ; clé biométrique liée à l'Android Keystore, non extractible sans authentification biométrique.

## 6. Réseau

- L'app utilise le réseau pour **deux fonctions strictement à impact local** :
  1. **Vérification de mises à jour** : interroge `api.github.com/repos/gitubpatrice/pass_tech/releases/latest` (HTTPS, sans auth, sans cookie).
  2. **Vérification HIBP** (Have I Been Pwned, opt-in) : envoie uniquement les **5 premiers caractères du SHA-1** d'un mot de passe (modèle k-anonymity). Le mot de passe ne quitte jamais l'appareil.
- Network Security Config refuse le HTTP en clair et les autorités utilisateur en release.
- Aucune télémétrie, rapport de crash ou analytics.

## 7. Partage et transmission de données

L'application ne transmet aucune donnée à un serveur opéré par le développeur. Le partage hors de l'appareil nécessite :

- un export `.ptbak` explicitement déclenché par l'utilisateur (chiffré avec une passphrase choisie par l'utilisateur) ;
- l'utilisation volontaire d'une fonction de partage / email Android.

## 8. Conservation et suppression

- Les données du coffre sont stockées localement et restent sous le contrôle de l'utilisateur.
- La désinstallation de l'app efface toutes les données (le fichier coffre est dans le répertoire privé de l'app, exclu du backup cloud via `dataExtractionRules`).
- L'utilisateur peut aussi supprimer le coffre depuis l'app (`Réglages → Supprimer le coffre`).

## 9. Sécurité

- Isolation sandbox, `FLAG_SECURE` (bloque captures et aperçu Recents).
- `allowBackup=false` et `dataExtractionRules` excluent le coffre de tout backup Android cloud ou device-transfer.
- Verrouillage progressif après 5 échecs (30s → 30min).
- Auto-lock après inactivité configurable (5 min par défaut).
- Clé du mot de passe maître effacée de la RAM au verrouillage.
- Détection RASP (root, émulateur, debugger) avec décharge utilisateur explicite.
- Flag clipboard sensible (Android 13+) et effacement immédiat du presse-papier au pause.
- **Coffre leurre** — un mot de passe maître secondaire ouvre un faux coffre crédible (timing aligné avec le coffre réel).
- **Mode panique** — verrouillage instantané, effacement du presse-papier, camouflage optionnel de l'icône en « Calculatrice ».
- **Héritage / dead-man switch** — flux optionnel 100 % local permettant à un proche d'accéder au coffre après une longue période d'inactivité. Aucun cloud, aucun tiers.
- **Anti-phishing par domaine** — l'app vérifie le domaine du navigateur en avant-plan avant de copier des identifiants ; alerte en cas de typosquatting.
- Signature APK v2+ uniquement (`enableV1Signing = false`) — neutralise CVE-2017-13156 (Janus).

Voir [SECURITY.md](./SECURITY.md).

## 10. Permissions Android

| Permission / accès                   | Raison                                                                                            |
| ------------------------------------ | ------------------------------------------------------------------------------------------------- |
| `USE_BIOMETRIC` / `USE_FINGERPRINT`  | Déverrouillage biométrique optionnel via Android BiometricPrompt.                                 |
| `INTERNET`                           | Vérification de mises à jour (GitHub Releases) et HIBP (k-anonymity, opt-in).                     |
| `CAMERA`                             | Scanner un QR code 2FA pour ajouter un secret TOTP. Flux caméra traité localement, jamais enregistré. |

## 11. Enfants

L'application n'est pas spécifiquement destinée aux enfants et ne contient aucun mécanisme de publicité comportementale ou de profilage.

## 12. Modifications

Cette politique peut être mise à jour lors de l'évolution de l'application.

## 13. Contact

📧 **contact@files-tech.com**
