# Pass Tech — modèle de menace

**Version du document** : 1.0 — 2026-08-03
**Version de l'application** : 2.5.4
**Licence** : Apache 2.0 — [code source](https://github.com/gitubpatrice/pass_tech)

> Ce document dit ce que Pass Tech protège, contre qui, **et ce qu'elle ne
> protège pas**. La seconde partie est la plus importante : un gestionnaire de
> mots de passe qui laisse croire à une protection qu'il n'offre pas est plus
> dangereux qu'un gestionnaire modeste.
>
> Tout ce qui suit est vérifiable dans le code. Quand une limite est
> structurelle, elle est signalée comme telle plutôt que minimisée.

---

## 1. Ce qui est protégé

| Bien | Où il vit | Comment |
|---|---|---|
| Mots de passe, notes, cartes bancaires | `pt_vault_a.enc` | AES-GCM-256, clé jamais écrite sur disque |
| Mot de passe maître | nulle part | Jamais stocké ; seul son dérivé Argon2id sert à la clé |
| Clé de coffre | RAM uniquement | Effacée au verrouillage, à la mise en arrière-plan, à la panique |
| Secret matériel (`hwSecret`) | AndroidKeyStore (TEE/StrongBox) | Enveloppé par une clé qui ne quitte jamais le composant sécurisé |
| Existence d'un second coffre | — | Déni plausible : voir §4 |

### Chaîne cryptographique

```
mot de passe maître ──Argon2id(m=19 MiB, t=2, p=1, sel 32 o)──> pwHash
                                                                   │
AndroidKeyStore (KEK, non extractible) ──unwrap──> hwSecret ───────┤
                                                                   ▼
                          HKDF-SHA256(pwHash ‖ hwSecret, info="pt:v4") ──> clé finale
                                                                   │
                                            AES-GCM-256 + AAD ─────┘
```

**Ce que cette construction implique concrètement** : une copie du fichier de
coffre, prise sur un appareil et emportée ailleurs, est **inexploitable**. Le
`hwSecret` est scellé dans le composant sécurisé de CE téléphone. Un attaquant
qui vole le fichier doit aussi disposer du matériel, et même alors il lui faut
le mot de passe maître, à raison d'un essai par seconde environ.

---

## 2. Adversaires considérés

| # | Adversaire | Capacités | Couverture |
|---|---|---|---|
| A1 | **Personne qui trouve le téléphone verrouillé** | Accès physique, pas de déverrouillage | ✅ Complète |
| A2 | **Personne qui trouve le téléphone déverrouillé** | Accès à l'écran d'accueil | ✅ Verrouillage auto, FLAG_SECURE, presse-papier purgé |
| A3 | **Application malveillante installée** | Même appareil, bac à sable Android | ✅ Bonne — voir limites §5 |
| A4 | **Analyse post-mortem (forensic) sans root** | Copie de la partition de données | ✅ Complète |
| A5 | **Contrainte physique** (« donnez le mot de passe ») | Vous êtes présent et sous pression | 🟡 Partielle — voir §4 |
| A6 | **Attaquant avec root / Magisk** | Contrôle total de l'OS | ❌ Hors modèle — voir §5.1 |
| A7 | **Attaquant étatique ciblé** | 0-day, exploitation du TEE | ❌ Hors modèle |

**La ligne est en A6.** C'est une limite honnête, partagée par tous les
gestionnaires de mots de passe : sur un appareil rooté, un attaquant lit la
mémoire du processus et intercepte la frappe. Aucun logiciel ne s'en protège.
L'application détecte le root et vous avertit, mais cette détection se contourne
(Magisk Hide) — elle vaut comme signal, pas comme barrière.

---

## 3. Protections et leur portée exacte

### 3.1 Force brute du mot de passe maître

- **En ligne** (sur l'appareil) : verrouillage progressif après 5 échecs —
  30 s, 60 s, 5 min, 15 min, 30 min.
- Le compte à rebours est ancré sur `SystemClock.elapsedRealtime()`, **pas sur
  l'horloge murale**. Avancer la date dans les Réglages ne le raccourcit pas ;
  redémarrer non plus, et un redémarrage l'allonge même en pratique.
- **Hors ligne** (à partir d'une copie du fichier) : impossible sans l'appareil,
  puisque le `hwSecret` manque. C'est l'apport principal du format v4.

### 3.2 Captures d'écran et aperçu des applications récentes

`FLAG_SECURE` est posé sur la fenêtre. Il est **temporairement relâché** sur un
seul écran : l'éditeur de note libre, pour permettre le collage depuis une autre
application. Ce relâchement est réarmé dès le passage en arrière-plan — c'est ce
qui évite qu'une note contenant des codes de récupération reste lisible dans la
vignette des applications récentes.

### 3.3 Presse-papier

Effacement automatique (30 s par défaut), immédiat à la mise en arrière-plan et
à la panique. Sur Android 13+, la valeur est marquée `EXTRA_IS_SENSITIVE` : elle
n'apparaît pas dans l'aperçu système.

### 3.4 Anti-hameçonnage (optionnel, désactivé par défaut)

Compare le domaine affiché par le navigateur au domaine de l'entrée, avant la
copie. **Ne lit que le domaine racine**, en mémoire volatile, pendant 15 s, et
uniquement sur 9 navigateurs reconnus. Aucune sortie réseau, aucun journal.

**Limite** : repose sur un service d'accessibilité, que vous activez vous-même.
Il ne couvre pas les applications natives, ni un navigateur non reconnu — dans
ce cas le verdict est « inconnu » et l'application vous le dit, plutôt que de
laisser croire à une vérification.

### 3.5 Sauvegardes

- `allowBackup="false"` et `dataExtractionRules` excluent le coffre de toute
  sauvegarde Android, cloud ou transfert d'appareil.
- Les sauvegardes `.ptbak` que vous créez sont chiffrées avec Argon2id +
  AES-GCM sous une phrase secrète que vous choisissez. **Elles ne sont pas liées
  au matériel** — c'est délibéré, pour rester restaurables sur un autre
  téléphone, et cela signifie qu'une phrase faible est attaquable hors ligne.
  Minimum imposé : 12 caractères.

---

## 4. Déni plausible — portée et limites

C'est la fonction la plus délicate de l'application, et celle dont les limites
doivent être les plus claires.

### Ce qui est réellement garanti

| Canal | Traitement |
|---|---|
| **Noms de fichiers** | `pt_vault_a.enc` / `pt_vault_b.enc` — aucun ne dit « leurre » |
| **Nombre de fichiers** | Toujours deux, dès la création du coffre : un leurre factice existe même si vous n'en avez pas configuré |
| **Taille des fichiers** | Rembourrage sur une échelle commune, réaligné automatiquement des deux côtés |
| **Contenu des fichiers** | Aucune étiquette distinctive ; le mot « decoy » n'apparaît plus sur le disque |
| **Temps de réponse** | Deux passes Argon2id systématiques, y compris quand le premier emplacement a déjà répondu |
| **Clés matérielles** | Les deux alias Keystore existent toujours, utilisés ou non |
| **Fonctionnalités** | Aucune différence de comportement entre les deux emplacements |

### Ce qui n'est PAS garanti

1. **Le camouflage ne couvre que le lanceur.** Le mode panique bascule l'icône
   et le nom vers une calculatrice fonctionnelle. Mais `android:label` et
   `android:icon` de l'application ne sont pas modifiables à l'exécution :
   **Réglages → Applications continue d'afficher « Pass Tech »**, de même que le
   sélecteur de partage et le gestionnaire de permissions. Le camouflage est
   conçu pour le regard porté sur un écran d'accueil. Face à quelqu'un qui ouvre
   les réglages, il ne tient pas.

2. **La sortie du camouflage est publique.** Appui long de 2 s sur l'affichage
   de la calculatrice. Le dépôt étant sous Apache 2.0, cette information est
   accessible à tous. Elle protège d'une inspection distraite, pas d'un
   adversaire qui sait déjà que l'appareil porte Pass Tech.

3. **Le déni plausible ne résiste pas à un adversaire qui connaît
   l'application.** Quelqu'un qui sait que Pass Tech crée systématiquement deux
   emplacements sait aussi qu'il peut en exiger deux mots de passe. Le déni
   fonctionne contre un examen générique, pas contre un examen informé.

4. **Vous restez le maillon.** Si vous ouvrez le coffre principal devant
   l'adversaire, aucune propriété cryptographique ne vous sauve.

---

## 5. Hors du modèle de menace

### 5.1 Appareil rooté ou compromis

Lecture de la mémoire du processus, interception de la frappe, remplacement de
l'application. La clé de coffre est en clair en RAM tant que le coffre est
ouvert — **c'est inévitable**, il faut bien déchiffrer pour afficher.

Mesures de réduction, sans prétention d'étanchéité : effacement actif des
tampons après usage, verrouillage automatique, détection du root avec
avertissement explicite.

### 5.2 Bibliothèques tierces — plus aucun composant Google *(résolu)*

Jusqu'au 2026-08-03, le scan de QR code reposait sur `mobile_scanner`, bâti sur
**Google ML Kit**, qui entraînait Play Services et le composant de télémétrie
`com.google.android.datatransport`. Ce composant n'a jamais eu accès au coffre —
il ne voyait que l'image de la caméra — mais il constituait une pile réseau non
maîtrisée dans une application dont c'est précisément l'argument, et il ajoutait
une permission `ACCESS_NETWORK_STATE` que le dépôt ne déclarait nulle part.

**La dépendance a été retirée.** Le manifeste fusionné de la release ne déclare
plus que `INTERNET`, `USE_BIOMETRIC` et `USE_FINGERPRINT` ; `CAMERA` et
`ACCESS_NETWORK_STATE` ont disparu. La liste est figée dans
`android/expected-permissions.txt` et vérifiée **sur l'APK** à chaque commit
(`.github/workflows/promesses.yml`), avec un contrôle Exodus Privacy en prime :
toute permission ou tout traceur qui réapparaîtrait ferait échouer le build.

Contrepartie assumée : l'ajout d'un secret 2FA se fait en collant l'URI
`otpauth://…` dans le champ prévu — l'application en extrait le secret — ou à la
main. La plupart des services affichent cette URI sous le QR code.

### 5.3 Codes TOTP et horloge

La RFC 6238 impose l'horloge murale — le serveur qui valide le code emploie la
même. Un attaquant root peut donc décaler la date pour rejouer un code dans la
fenêtre de ±30 s. **Toutes les autres décisions de sécurité** (verrouillage,
inactivité de l'héritage) utilisent une horloge monotone insensible à cela.

### 5.4 Héritage

`pt_heir.enc` est une copie complète du coffre chiffrée sous le **seul** mot de
passe héritier, sans liaison au matériel — l'héritier doit pouvoir la
déchiffrer sur un autre appareil. C'est donc le fichier le plus exposé à une
attaque hors ligne. Minimum 12 caractères imposé ; choisissez-le long.

Le compte à rebours d'inactivité peut être **accéléré** par un attaquant root
qui avance l'horloge. Il lui faudra toujours le mot de passe héritier.

---

## 6. Risques résiduels connus

| # | Risque | Gravité | État |
|---|---|---|---|
| R1 | Clé de coffre en RAM pendant la session | Structurel | Accepté — effacements actifs |
| R2 | Mot de passe maître en `String` Dart non effaçable | Faible | Limite du langage |
| R3 | Enrôlement biométrique non invalidant sur certains constructeurs | Moyen | Avertissement dans Réglages ; le mot de passe maître reste requis pour réactiver |
| R4 | Pile Google via ML Kit | Moyen | Documenté, contournable en n'utilisant pas le scan |
| R5 | Camouflage partiel (§4.1) | Moyen | Documenté ; nécessiterait un second APK |
| R6 | Écrasement de fichier non garanti sur mémoire flash | Faible | Best-effort (copie sur écriture, nivellement d'usure) |

---

## 7. Signaler une vulnérabilité

**Ne pas ouvrir d'issue publique.** Un gestionnaire de mots de passe exige une
divulgation coordonnée. Procédure dans [`SECURITY.md`](./SECURITY.md).

---

## Journal

| Date | Révision |
|---|---|
| 2026-08-03 | Création. Rédigé à l'issue de l'audit du 2026-08-03 (16 correctifs), qui a rendu explicites les limites des §4.1, §5.2 et §5.4 — jusque-là dispersées dans des commentaires de code. |
