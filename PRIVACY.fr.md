# Politique de confidentialité — Pass Tech

**Version du document** : 28 avril 2026
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
| Mot de passe maître                  | Dérive la clé de chiffrement (PBKDF2-SHA256 600 000 itérations) | Jamais persisté ; effacé de la RAM au verrouillage |
| Clé biométrique                      | Déverrouillage optionnel par empreinte / face            | Android Keystore (lié au matériel), `setUserAuthenticationRequired(true)` |
| Sauvegardes chiffrées (`.ptbak`)     | Export optionnel déclenché par l'utilisateur             | Emplacement choisi par l'utilisateur              |
| Préférences locales                  | Thème, durée auto-lock, timeout presse-papier            | Stockage local sur l'appareil                     |

## 5. Chiffrement & dérivation de clé

- **AES-256-CBC + HMAC-SHA256** (encrypt-then-MAC), comparaison MAC en temps constant avant déchiffrement.
- **PBKDF2-HMAC-SHA256, 600 000 itérations** pour le coffre et les `.ptbak` (recommandation OWASP 2023).
- **HMAC sur les métadonnées** (version, itérations, sel) empêche les attaques par downgrade du fichier.
- **Clé biométrique liée au matériel** via Android Keystore ; non extractible sans authentification biométrique.

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
