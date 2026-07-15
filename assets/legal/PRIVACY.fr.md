# Politique de confidentialité — Pass Tech

**Version du document** : 15 juillet 2026 (Pass Tech v2.5.1)
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
| Mots de passe, secrets TOTP, cartes bancaires, notes sécurisées | Entrées du coffre créées par l'utilisateur | Chiffré au repos sur l'appareil (`pt_vault_a.enc`) |
| Second emplacement de coffre (`pt_vault_b.enc`) | Déni plausible — **toujours présent**, que vous ayez configuré un leurre ou non | Chiffré sur l'appareil avec sa propre clé Keystore |
| Mot de passe maître                  | Dérive la clé de chiffrement (Argon2id, référence OWASP 2024) | Jamais persisté ; effacé de la RAM au verrouillage |
| Clé biométrique                      | Déverrouillage optionnel par empreinte / face            | Android Keystore (lié au matériel), `setUserAuthenticationRequired(true)` |
| Sauvegardes chiffrées (`.ptbak`)     | Export optionnel déclenché par l'utilisateur             | Emplacement choisi par l'utilisateur              |
| Préférences locales                  | Thème, durée auto-lock, timeout presse-papier            | Stockage local sur l'appareil                     |

## 5. Chiffrement & dérivation de clé

- **AES-256-GCM** (AEAD), avec AAD liée pour résister au downgrade du fichier.
- **Argon2id** (m = 19 MiB, t = 2, p = 1, référence OWASP 2024) pour dériver la clé maître du coffre.
- **KEK liée au matériel** dans l'Android Keystore (StrongBox si disponible), qui enveloppe un secret matériel propre au coffre : celui qui exfiltre le seul fichier ne peut pas le brute-forcer sans votre appareil.
- **Sauvegardes `.ptbak`** — même pack Argon2id + AES-GCM, avec une passphrase que vous choisissez (sans liaison Keystore, pour rester portables d'un appareil à l'autre).
- **Clé biométrique liée au matériel** via Android Keystore ; non extractible sans authentification biométrique.
- **Déni plausible** — personne, en inspectant l'appareil, ne peut savoir si vous gardez un second coffre caché. Les deux emplacements portent des noms neutres et indistinguables (`pt_vault_a.enc` / `pt_vault_b.enc`) et **les deux existent toujours** : sans leurre configuré, l'app en écrit quand même un factice — une liste vide, chiffrée sous un mot de passe aléatoire stocké nulle part, donc jamais ouvrable, ni par vous ni par nous. Alias Keystore, sels et temps de déverrouillage sont alignés entre les deux chemins.

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
- **Aucune copie résiduelle d'un ancien coffre** — la migration d'un coffre v3 laissait auparavant une copie `.bak`, chiffrée avec l'ancien schéma plus faible et attaquable hors ligne. Depuis la v2.5.1, elle est supprimée dès que la migration réussit.

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
