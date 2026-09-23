# Politique de confidentialité — Pass Tech

- **S'applique à** : Pass Tech 3.0.0 (`com.filestech.pass_tech`)
- **Dernière modification** : 23 septembre 2026
- **Éditeur** : Files Tech — Patrice Haltaya
- **Contact** : contact@files-tech.com
- **Code source** : https://github.com/gitubpatrice/pass_tech — Apache License 2.0

> Ce document décrit la version **Kotlin** de Pass Tech, la 3.0.0. Ce n'est pas la politique des
> versions Flutter précédentes (2.x, `com.passtech.pass_tech`), qui fonctionnaient autrement.

---

## 1. En bref

Pass Tech garde vos mots de passe, cartes bancaires et notes sur votre téléphone, chiffrés. Il n'y a
ni compte, ni serveur à nous, ni copie de vos données ailleurs.

- **Nous ne collectons rien.** Aucune publicité, aucun traceur, aucune statistique, aucun rapport de
  plantage, aucun identifiant.
- **Nous ne recevons rien.** Ni un nom, ni une adresse, ni un mot de passe, ni un chiffre.
- **Il n'y a rien à supprimer chez nous**, parce qu'il n'y a rien chez nous.

L'application se sert du réseau pour **deux choses seulement**, décrites au §5. Ni l'une ni l'autre
n'envoie ce que vous avez saisi.

## 2. Ce qui est sur votre téléphone, et où

Tout vit dans le dossier privé de l'application, qu'aucune autre application ne peut lire.

| Fichier | Ce qu'il contient |
| --- | --- |
| `pt_vault_a.enc`, `pt_vault_b.enc`, `pt_vault_c.enc` | Trois emplacements de coffre. **Les trois existent toujours et font la même taille**, que vous en utilisiez un, deux ou trois. |
| `pt_heir_a.enc`, `pt_heir_b.enc`, `pt_heir_c.enc` | Trois instantanés d'héritage, si l'héritage est configuré. **Les trois existent toujours et font la même taille**, pour la même raison. |
| `pt_state.enc` | Les compteurs dont l'app a besoin avant qu'un coffre soit ouvert : essais ratés, dernière exécution, date de la dernière vérification de mise à jour. |
| Préférences Android | Thème, délai de verrouillage, délai du presse-papiers, blocage des captures d'écran, contrôle du domaine. Aucun secret. |

**Pourquoi trois de tout.** Un deuxième mot de passe ouvre un deuxième coffre, et rien sur le
téléphone ne dit si vous vous en servez. Cela ne tient que si un emplacement utilisé et un
emplacement inutilisé se ressemblent exactement — même nom, même taille, même contenu pour qui lit
les octets. Les emplacements dont vous ne vous servez pas contiennent des données au hasard,
chiffrées avec une clé qui n'existe nulle part : ni vous ni nous ne pourrons jamais les ouvrir.

## 3. Comment c'est chiffré

- **AES-256-GCM** pour le coffre, les instantanés d'héritage et le fichier d'état.
- La clé est dérivée de votre mot de passe maître par **Argon2id** (19 Mio de mémoire, 2 passes, 1
  fil — la référence OWASP 2024 pour le mobile) **et** par une clé gardée dans la puce sécurisée du
  téléphone, qui participe à chaque essai, sans exception. Une copie de votre coffre emportée sur
  une autre machine ne peut pas y être attaquée : cette puce n'y est pas.
- **Votre mot de passe maître n'est jamais enregistré**, sous aucune forme, nulle part. Il ne peut
  pas être récupéré — ni par nous, ni par personne. L'oublier, c'est perdre le coffre.
- Le déverrouillage par empreinte, si vous l'activez, garde sa clé dans le Keystore d'Android :
  illisible sans votre empreinte, et détruite si les empreintes du téléphone changent.
- Après **5 essais faux**, l'application attend : 30 secondes, puis 1, 5, 15 et 30 minutes. L'attente
  est comptée dans le fichier d'état et survit à un redémarrage.

## 4. Ce que vous pouvez exporter

- **Sauvegarde `.ptbak`** : vous choisissez le moment, et une phrase de passe à vous la chiffre. Nous
  ne la voyons jamais. Où vous la rangez ensuite vous appartient — une sauvegarde sur un cloud est
  sur ce cloud.
- **Export en clair** : proposé pour partir vers un autre gestionnaire. Il n'est **pas chiffré**, et
  l'application le dit avant de l'écrire.

Rien ne part tout seul. Il n'y a aucune sauvegarde automatique : la sauvegarde dans le cloud
d'Android et le transfert d'un téléphone à l'autre sont désactivés pour cette application.

## 5. Les deux moments où l'application se sert du réseau

1. **Vérification de mise à jour.** Une fois votre coffre ouvert, l'application demande à GitHub si
   une version plus récente est parue — **deux fois par jour au maximum**. Elle interroge
   `api.github.com` sur la dernière version de ce projet. Aucun compte, aucun cookie, rien de vous
   n'est envoyé. Elle ne télécharge et n'installe rien : elle montre ce qu'elle a trouvé, et un lien.
2. **Contrôle des fuites**, sur l'écran d'audit, **que vous lancez vous-même**. Le mot de passe n'est
   jamais envoyé. L'application calcule son empreinte SHA-1 et en envoie **les cinq premiers
   caractères seulement** à Have I Been Pwned, qui répond avec toutes les empreintes commençant
   ainsi — des dizaines de milliers. La comparaison se fait sur votre téléphone. C'est le modèle de
   k-anonymat que ce service publie.

**Ce que ces deux appels montrent quand même**, et il vaut mieux le dire : qui voit votre connexion —
votre opérateur, GitHub, Have I Been Pwned — apprend que quelqu'un, à votre adresse, utilise cette
application. Il n'apprend rien de votre coffre. Si cela compte pour vous : les deux s'arrêtent
complètement quand l'application est camouflée en calculatrice (§7), et le contrôle des fuites ne
part jamais tant que vous n'appuyez pas.

L'application refuse le HTTP en clair et refuse les autorités de certification ajoutées au téléphone
par quelqu'un d'autre.

## 6. Le contrôle du domaine, et l'autorisation Android qu'il demande

Si vous activez **« Vérifier le domaine avant de copier »**, l'application utilise un service
d'accessibilité Android. C'est la chose la plus intrusive qu'elle demande, alors voici exactement ce
qu'elle en fait.

- Il est **éteint à l'installation**, et il n'apparaît même pas dans la liste d'accessibilité
  d'Android tant que vous ne le demandez pas.
- Il ne reçoit d'événements que **des navigateurs qu'il connaît** — Chrome, Firefox et ses versions
  bêta, Brave, Edge, Opera, Vivaldi, Samsung Internet, DuckDuckGo. Android ne lui envoie rien des
  autres applications : ni votre application bancaire, ni vos messages, ni votre clavier.
- De ces navigateurs, il lit **la barre d'adresse et rien d'autre** — le nom du site, pas le chemin,
  pas les paramètres, pas un mot de la page.
- Ce nom est gardé **en mémoire seulement**, un seul à la fois, remplacé à chaque lecture et oublié
  au bout de quinze secondes. Il n'est jamais écrit sur le disque et jamais envoyé nulle part.
- Éteindre le réglage **reprend l'autorisation** : le service quitte la liste d'Android, et le
  rallumer vous la redemandera.

## 7. Mode panique

Si vous vous en servez, l'application se verrouille, vide le presse-papiers, désarme l'empreinte,
retire le service d'accessibilité et remplace son propre nom et son icône sur votre écran d'accueil
par une calculatrice qui fonctionne. **Rien n'est supprimé** : votre mot de passe maître ouvre
toujours le coffre. Tant qu'elle est camouflée, l'application ne fait aucun appel réseau.

## 8. Héritage

Si vous le configurez, une personne que vous choisissez pourra ouvrir un **instantané en lecture
seule** de votre coffre après un silence assez long de votre côté — 90 jours par défaut, plus 7 jours
de grâce, et toute ouverture relance le compte. L'instantané est chiffré par une phrase de passe qui
lui est propre et que vous transmettez vous-même. Il ne quitte jamais le téléphone, il n'y a ni cloud
ni tiers, et nous n'y sommes pour rien.

## 9. Autorisations Android

Mesurées sur l'APK publié, pas sur le code source :

| Autorisation | Pourquoi |
| --- | --- |
| `INTERNET` | Les deux appels du §5. |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | Le déverrouillage par empreinte, si vous l'activez. |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Ajoutée par une bibliothèque Android ; elle permet à l'application de se parler à elle-même, et rien d'autre. |

Le service d'accessibilité du §6 n'est **pas** une autorisation de cette liste : Android l'accorde à
part, depuis ses propres réglages, et vous pouvez la reprendre là-bas quand vous voulez.

Il n'y a pas d'autorisation caméra, ni contacts, ni position, ni stockage : Pass Tech demande un
fichier au système quand vous exportez ou importez, et le système lui donne ce fichier-là.

## 10. Enfants

L'application ne vise pas les enfants et ne contient aucune publicité, aucun profilage et aucun
mécanisme comportemental d'aucune sorte.

## 11. Modifications de ce document

Il est publié avec l'application et avec son code source. Une modification part dans une version ; la
date en haut dit laquelle.

## 12. Contact

contact@files-tech.com
