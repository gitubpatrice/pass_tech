# Pass Tech — modèle de menace

**S'applique à** Pass Tech 3.0.0 (`com.filestech.pass_tech`), la réécriture en Kotlin. La 2.x, en
Flutter, avait le sien et fonctionnait autrement.

[English](THREAT_MODEL.md) · **Français**

Ce document dit ce que l'application protège, contre qui, et — au moins aussi important — **ce
qu'elle ne protège pas**. Une promesse de sécurité sans limites n'est pas une promesse.

---

## 1. Ce qui est protégé

Le coffre : mots de passe, secrets à deux facteurs, cartes bancaires et notes sécurisées. Son
fichier, l'instantané d'héritage à côté, et les compteurs dont l'app a besoin avant toute ouverture.

### La chaîne cryptographique

```
pwHash   = Argon2id(mot de passe, sel, m = 19 Mio, t = 2, p = 1)     32 octets
hw       = HMAC-SHA256(clé de l'emplacement, domaine || pwHash)      calculé DANS le Keystore
finalKey = HKDF-SHA256(sel, pwHash || hw, info, 32)
fichier  = AES-256-GCM(finalKey, nonce, clair, aad)
```

La clé de l'emplacement ne sort jamais du matériel sécurisé et **participe à chaque essai**. C'est le
choix central de la conception, et il en a remplacé un plus faible : l'app Flutter enveloppait un
secret au hasard, indépendant du mot de passe, si bien qu'un seul déballage permettait d'emporter les
fichiers ailleurs et d'y tester des mots de passe à la vitesse d'un GPU. Ici, un essai ne peut être
vérifié que sur ce téléphone, par son Keystore.

`domaine` et `info` séparent un fichier de coffre d'un instantané d'héritage : la même phrase de
passe sur le même emplacement donne deux clés sans rapport, donc un fichier mis à la place de l'autre
n'ouvre rien.

## 2. Adversaires considérés

| # | Adversaire | Capacité | Couvert |
|---|---|---|---|
| A1 | Qui ramasse le téléphone, verrouillé | Accès physique, sans identifiants | Oui |
| A2 | Qui vous force à l'ouvrir | Contrainte — frontière, agression, contrôle | En partie — §4 |
| A3 | Une autre app du téléphone | Son propre bac à sable, le presse-papiers, l'écran | Oui |
| A4 | Qui observe le réseau | Voit les connexions, pas leur contenu | Oui |
| A5 | Qui emporte les fichiers | Une copie de `/data/data/...`, hors du téléphone | Oui |
| A6 | Nous, l'éditeur | Publions le code | Oui — il n'y a rien à remettre |
| A7 | Le root, ou un téléphone compromis | Lit la mémoire de n'importe quelle app | **Non** — §5 |

## 3. Les protections, et leur portée exacte

### 3.1 Deviner le mot de passe maître

Cinq essais libres, puis 30 s, 1, 5, 15 et 30 minutes. Le compte est dans le fichier d'état chiffré
et se mesure sur une horloge qui compte le sommeil profond : un redémarrage ne l'efface pas. Un
déverrouillage annule son propre essai et aucun autre, donc alterner entre deux coffres ne raccourcit
jamais l'attente.

Hors du téléphone, l'attente ne s'applique pas — mais l'étape matérielle, si : les fichiers ne
peuvent pas être attaqués sur une autre machine, du tout.

### 3.2 Captures d'écran et aperçu des applications récentes

`FLAG_SECURE` sur toutes les fenêtres tant que le réglage est actif, ce qui retire aussi la vignette
du système. Le propriétaire peut l'éteindre (Knox de Samsung bloque le presse-papiers inter-apps tant
qu'il est actif), et l'application demande confirmation avant.

### 3.3 Presse-papiers

Vidé après un délai choisi, depuis un receiver, donc même si l'app a été fermée. Marqué sensible sur
Android 13 et plus, ce qui le tient hors de l'aperçu du presse-papiers.

**Non couvert** : une autre app qui lit le presse-papiers pendant le délai. Android n'offre aucun
moyen de remettre un secret à une seule application.

**Non couvert non plus, et mesuré plutôt que raisonné** : certains claviers de constructeurs gardent
leur propre historique de copie, qui n'est pas le presse-papiers du système. Sur un Galaxy S24 sous
Android 16, la valeur était encore proposée dans la barre de suggestions du clavier Samsung une
minute après que le presse-papiers système ait été vidé — vérifié le 2026-09-24 par un appui long
dans un champ de texte, où il n'y avait plus rien à coller. `EXTRA_IS_SENSITIVE` est posé à chaque
copie et devrait tenir une valeur hors d'un tel historique ; ce clavier ne l'honore pas. Aucune
application ne peut atteindre le magasin d'une autre. Vider l'historique du clavier revient au
propriétaire.

### 3.4 Le contrôle d'adresse (optionnel, éteint par défaut)

Avant qu'un mot de passe soit copié, l'app compare le site ouvert dans le navigateur avec ceux que
l'entrée déclare. Elle lit la barre d'adresse de neuf navigateurs par un service d'accessibilité
désactivé au manifeste tant que le réglage n'est pas activé.

**Sa portée est exactement ce qu'un navigateur affiche.** Un navigateur qu'elle ne connaît pas, ou
une page ouverte dans une autre app, ne peut pas être lu — et l'app dit alors que le contrôle n'a pas
pu être fait, plutôt que de dire que tout va bien. C'est un avertissement, pas une garantie : on peut
toujours copier malgré lui, volontairement.

### 3.5 Sauvegardes

`.ptbak` est chiffré par une phrase de passe qui lui est propre. Où elle est rangée ensuite échappe à
l'application : une sauvegarde sur un cloud est sur ce cloud. L'export en clair n'est pas chiffré, et
l'app le dit avant de l'écrire.

La sauvegarde cloud d'Android et le transfert d'un téléphone à l'autre sont exclus, pour tous les
domaines.

## 4. Déni plausible — portée et limites

### Ce qui est réellement garanti

Trois emplacements existent dès le premier lancement. Tous les trois font la même taille, tous les
trois sont écrits de la même façon, et ceux qui ne servent pas contiennent des données au hasard
chiffrées avec une clé qui n'existe nulle part — ni le propriétaire ni l'éditeur ne pourront jamais
les ouvrir. Rien dans les noms de fichiers, les tailles ou les octets ne dit combien servent.

Le leurre n'écrit jamais dans le vrai coffre. L'ouvrir, le remplir, en supprimer des entrées laisse
les autres emplacements intacts.

### Ce qui n'est PAS garanti

- **Qui sait que cette application existe sait qu'un deuxième coffre peut exister.** C'est public, et
  voulu : la fonction est dans ce fichier, sur la page du site et dans l'app. Le déni protège d'une
  fouille, pas d'une connaissance.
- **Combien d'emplacements sont libres se lit depuis l'application**, par qui tient le téléphone avec
  un coffre ouvert. Configurer un leurre prend un emplacement libre, et l'app le dit quand il n'y en
  a plus : on peut enchaîner deux leurres sur un téléphone à un coffre, un seul sur un téléphone qui
  en cache deux. Le compte n'est pas gratuit — chaque tentative est décomptée sur le même barème
  qu'un mot de passe faux, cinq puis une attente qui grandit — mais il n'est pas fermé, et avec trois
  emplacements il ne peut pas l'être : toutes les façons de masquer le refus disent la même chose
  plus tôt. Assumé, sous le numéro R6.
- **Une sonde de capacité.** L'espace libre du téléphone change à mesure qu'un coffre grossit. Qui le
  mesurerait dans le temps, le téléphone en main entre deux mesures, pourrait en déduire que quelque
  chose a grossi. C'est un résiduel connu (R5 plus bas), assumé plutôt que résolu.
- **Le camouflage ne concerne que le lanceur.** Le mode panique échange les alias de lanceur ;
  `<application android:label>` ne se change pas à l'exécution, donc Réglages › Applications, le
  panneau de partage et le gestionnaire d'autorisations continuent de dire Pass Tech. Cela cache
  l'app d'un coup d'œil sur un écran d'accueil, pas de qui ouvre les réglages.
- **Le déverrouillage par empreinte et le leurre s'excluent**, et l'app l'impose : l'empreinte ouvre
  directement le vrai coffre, ce qui annulerait le leurre dans la situation même où il sert.

## 5. Hors du modèle de menace

### 5.1 Un téléphone rooté ou compromis

Tout ce qui a cet accès lit la mémoire de n'importe quelle application, y compris celle-ci. L'app
remarque ce qu'elle peut — root, débogueur, émulateur — et le dit sur l'écran de déverrouillage,
avant qu'un mot de passe soit saisi. Ces contrôles sont faciles à tromper ; c'est un rappel, jamais
une garantie.

### 5.2 Bibliothèques tierces

Ni Google Play Services, ni ML Kit, ni Firebase, ni statistiques. Les dépendances sont AndroidX,
Jetpack Compose, Hilt, kotlinx et Bouncy Castle, listées dans
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Le jeu d'autorisations qui en résulte est vérifié
sur l'APK construit par le workflow `Promises`, pas lu dans le manifeste source.

### 5.3 Codes TOTP et horloge

Le TOTP dépend de l'horloge du téléphone. Un téléphone mal réglé produit des codes qu'un serveur
refuse. L'application ne corrige pas l'horloge et ne demande pas l'heure au réseau.

### 5.4 Héritage

Un compte à rebours sur le téléphone, pas un service. Si le téléphone est perdu, effacé ou cassé,
l'instantané part avec lui. La phrase de passe de l'héritier est transmise par le propriétaire, par
le moyen qu'il choisit — l'application n'y est pour rien, et ne peut rien si elle est perdue.

## 6. Risques résiduels connus

| # | Risque | Gravité | État |
|---|---|---|---|
| R1 | La clé du coffre est en mémoire tant que le coffre est ouvert | Structurel | Assumé — effacée au verrouillage et après usage |
| R2 | L'enrôlement biométrique n'invalide pas la clé chez certains constructeurs | Moyen | Averti dans Réglages ; le mot de passe maître reste exigé pour réarmer |
| R3 | Le camouflage est partiel (§4) | Moyen | Documenté ; aller plus loin demanderait un second APK |
| R4 | L'écrasement d'un fichier n'est pas garanti sur mémoire flash | Faible | Au mieux — copie sur écriture et nivellement d'usure nous échappent |
| R5 | Une sonde de capacité peut suggérer qu'un coffre a grossi (§4) | Faible | Assumé ; l'alternative serait de rembourrer chaque emplacement à une taille que personne n'accepterait |
| R6 | Le nombre d'emplacements libres se compte depuis l'application (§4) | Moyen | Assumé, et freiné : chaque tentative est décomptée sur le barème anti-force-brute |
| R7 | Le compte à rebours de l'héritage peut être avancé en redémarrant le téléphone la date changée | Faible | Demande le code de déverrouillage du téléphone. Dans une même session, le compte n'avance pas plus vite que l'horloge de fonctionnement, que rien dans les Réglages ne déplace |
| R8 | Le clavier d'un constructeur peut garder une valeur copiée dans son propre historique (§3.3) | Moyen | Hors de portée de toute application. Le presse-papiers système est vidé à l'heure ; cet historique-là revient au propriétaire |

## 7. Signaler une vulnérabilité

**Ne pas ouvrir d'issue publique.** Un gestionnaire de mots de passe exige une divulgation
coordonnée. La procédure est dans [SECURITY.md](SECURITY.md).

---

## Journal

| Date | Révision |
|---|---|
| 2026-09-24 | Après l'audit de sécurité du jour. Deux résiduels réels et non dits le sont désormais (R6, R7), et trois choses que ce document affirmait sont maintenant vraies du code et non de l'intention : les fichiers d'emplacement sont égalisés même quand la session n'en a pas la clé, l'alarme du presse-papiers réveille le téléphone, et un mot de passe qui n'ouvre rien est compté sur chaque écran qui en vérifie un. |
| 2026-09-23 | Réécrit pour l'app Kotlin. L'étape matérielle participe désormais à chaque essai, là où l'app Flutter enveloppait un secret indépendant du mot de passe ; trois emplacements remplacent deux ; le risque ML Kit est parti avec la bibliothèque ; et ce document existe maintenant en anglais, le français à côté. |
| 2026-08-03 | Créé pour l'app Flutter, à l'issue de l'audit de cette date. |
