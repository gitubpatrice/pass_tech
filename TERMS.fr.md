# Conditions d'utilisation — Pass Tech

**Version du document** : 28 avril 2026
**App** : Pass Tech
**Site officiel** : https://www.files-tech.com
**Contact** : contact@files-tech.com
**Code source** : https://github.com/gitubpatrice/pass_tech
**Licence du code** : Apache License 2.0

---

## 1. Objet

Les présentes Conditions d'utilisation définissent les règles applicables à l'utilisation de l'application **Pass Tech** — un gestionnaire de mots de passe 100 % local.

## 2. Acceptation

L'utilisation de l'application vaut acceptation des présentes conditions.

## 3. Fonctionnement général

Pass Tech est un coffre-fort de mots de passe 100 % local. Il stocke les mots de passe, secrets TOTP 2FA, cartes bancaires et notes sécurisées dans un fichier chiffré sur l'appareil, protégé par un mot de passe maître et, optionnellement, une clé biométrique Android Keystore.

## 4. Absence de publicité, traceurs et télémétrie

Le développeur déclare que l'application ne contient pas de publicité, de traceur, de mesure d'audience, d'analyse comportementale, de système de profilage, de télémétrie ou de rapport de crash distant. Le coffre n'est jamais transmis à un serveur opéré par le développeur.

## 5. Licence du code source

Code source publié sous **Apache License 2.0**.

- Dépôt : https://github.com/gitubpatrice/pass_tech
- Site officiel : https://www.files-tech.com
- Contact : contact@files-tech.com

## 6. Responsabilités de l'utilisateur

- **Choisir un mot de passe maître fort** (12+ caractères recommandé) et le conserver en sûreté. **Il ne peut pas être récupéré** par le développeur.
- Effectuer ses propres sauvegardes chiffrées (export `.ptbak` avec une passphrase forte).
- Protéger son appareil par un verrouillage adapté, des mises à jour de sécurité et des sauvegardes utiles.
- Respecter les droits d'auteur, la confidentialité, les secrets professionnels et les lois applicables.

## 7. Points spécifiques à l'application

- **Le mot de passe maître ne peut pas être récupéré.** Oublié, le coffre est définitivement inaccessible.
- Le déverrouillage biométrique est une couche de commodité ; le mot de passe maître reste la racine de confiance.
- Pass Tech est best-effort contre la compromission de l'appareil : sur appareil rooté, debuggable ou émulé, l'utilisateur est averti et utilise l'app à ses propres risques.
- La vérification HIBP est opt-in et utilise un protocole k-anonymity (seuls les 5 premiers caractères du SHA-1 du mot de passe sont envoyés).

## 8. Limites de garantie

L'application est fournie en l'état. Le développeur fait ses meilleurs efforts pour proposer un outil sécurisé mais ne garantit pas la sécurité absolue. L'utilisateur reste responsable du choix du mot de passe maître et de la protection de son appareil.

## 9. Limitation de responsabilité

Dans les limites autorisées par la loi, le développeur ne peut être tenu responsable des pertes de données (notamment d'un mot de passe maître oublié rendant le coffre irrécupérable), erreurs de manipulation, problèmes liés à des services tiers ou conséquences d'une utilisation non conforme.

## 10. Services tiers

- **API GitHub Releases** pour les vérifications de mises à jour (HTTPS, sans authentification, sans cookie).
- **API Have I Been Pwned** pour les vérifications de fuites (HTTPS, k-anonymity, opt-in).

## 11. Propriété intellectuelle

Le nom, les contenus, textes, icônes, visuels et ressources propres au projet restent protégés. Le code source principal est placé sous Apache License 2.0.

## 12. Sécurité

L'utilisateur doit protéger son mot de passe maître, son appareil et éviter d'utiliser Pass Tech sur des appareils rootés/compromis. Voir [SECURITY.md](./SECURITY.md).

## 13. Modification des conditions

Ces conditions peuvent être mises à jour. La date du document indique la version en vigueur.

## 14. Droit applicable

Sauf obligation légale contraire, ces conditions sont rédigées dans une logique de droit français et européen.
