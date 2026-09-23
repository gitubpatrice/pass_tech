# Conditions d'utilisation — Pass Tech

- **S'applique à** : Pass Tech 3.0.0 (`com.filestech.pass_tech`)
- **Dernière modification** : 23 septembre 2026
- **Éditeur** : Files Tech — Patrice Haltaya
- **Contact** : contact@files-tech.com
- **Code source** : https://github.com/gitubpatrice/pass_tech — Apache License 2.0

> Ce document couvre la version **Kotlin** de Pass Tech, la 3.0.0. Ce n'est pas celui des versions
> Flutter précédentes (2.x, `com.passtech.pass_tech`).

---

## 1. Ce que c'est

Pass Tech garde sur votre téléphone des mots de passe, des secrets à deux facteurs, des cartes
bancaires et des notes, chiffrés par un mot de passe maître que vous choisissez. Il n'y a ni compte,
ni serveur à nous. Utiliser l'application, c'est accepter ce qui suit.

## 2. La seule chose à comprendre avant de commencer

**Votre mot de passe maître ne peut pas être récupéré.** Ni par nous, ni par personne, par aucun
moyen. Il n'est jamais enregistré — l'application n'en garde ni copie, ni indice, ni réinitialisation.
Si vous l'oubliez, le coffre est perdu, et tout ce qu'il contient avec.

Ce n'est pas une limite que nous pourrions lever. C'est la raison pour laquelle le coffre ne peut
être ouvert ni par nous, ni sur réquisition qui nous serait adressée, ni par qui prendrait votre
téléphone.

Donc, avant d'y mettre quoi que ce soit d'important :

- choisissez un mot de passe maître d'au moins 12 caractères, et que vous saurez encore dans un an ;
- faites une sauvegarde `.ptbak`, avec une phrase de passe à elle, et gardez-la ailleurs que sur le
  téléphone ;
- souvenez-vous que la sauvegarde ne vaut que ce que vaut l'endroit où vous la rangez.

## 3. Ce que nous promettons

- **Aucune publicité, aucun traceur, aucune statistique, aucune télémétrie, aucun rapport de
  plantage.** Rien sur vous ni sur votre usage de l'application ne nous parvient, jamais.
- **Votre coffre n'est envoyé nulle part.** Les deux appels réseau que fait l'application sont
  énumérés dans la politique de confidentialité, et aucun des deux ne transporte ce que vous avez
  saisi.
- **Le code source est public**, sous licence Apache 2.0, et chaque version publie le SHA-256 de
  chacun de ses fichiers : vous pouvez vérifier que ce que vous avez installé est bien ce qui a été
  publié.

## 4. Ce que nous ne promettons pas

L'application est fournie telle quelle. Nous y travaillons avec soin — le code est public, vous
pouvez en juger — mais personne ne peut promettre qu'un logiciel est sans défaut, et nous ne le
promettons pas.

En particulier :

- **Un téléphone compromis compromet l'application.** Sur un téléphone rooté, avec un débogueur
  attaché, ou sur un émulateur, tout ce qui a cet accès peut lire la mémoire de n'importe quelle
  application, y compris celle-ci. Pass Tech le dit sur son écran de déverrouillage quand elle le
  remarque ; ces contrôles sont faciles à tromper et sont un rappel, jamais une garantie.
- **Le coffre leurre et le mode panique augmentent le coût d'une fouille ; ils ne la rendent pas
  impossible.** Ils sont conçus pour que rien sur le téléphone ne dise si un deuxième coffre existe.
  Qui connaît cette application le sait aussi.
- **L'héritage est un compte à rebours sur votre téléphone**, pas un service. Si le téléphone est
  perdu, effacé ou cassé, l'instantané part avec lui.
- **Le contrôle du domaine ne lit que ce que le navigateur affiche.** Un navigateur qu'il ne connaît
  pas, ou une page ouverte dans une autre application, ne peut pas être lu — et l'application dit
  alors que le contrôle n'a pas pu être fait, plutôt que de dire que tout va bien.

Dans la limite de ce que permet la loi, nous ne pouvons pas être tenus responsables d'une perte de
données due à un mot de passe maître oublié, à une sauvegarde mal gardée, à une suppression
malheureuse, à la défaillance d'un service tiers, ou à un usage de l'application en dehors de ce à
quoi elle sert.

## 5. Ce qui vous revient

- Garder votre mot de passe maître, et le garder pour vous.
- Faire vos sauvegardes, et vérifier de temps en temps que vous savez encore en ouvrir une.
- Protéger le téléphone lui-même : un verrouillage d'écran, ses mises à jour.
- Respecter la loi de là où vous êtes — droit d'auteur, vie privée d'autrui, secret professionnel, et
  toute règle sur le chiffrement qui vous concerne.

## 6. Services tiers

Deux, tous deux décrits dans la politique de confidentialité, tous deux en HTTPS :

- **GitHub** — interrogé sur l'existence d'une version plus récente.
- **Have I Been Pwned** — interrogé pour savoir si un mot de passe figure dans une fuite publique,
  quand vous appuyez sur le bouton, et sans jamais recevoir ce mot de passe.

Leurs propres conditions et politiques s'appliquent à eux, pas à nous.

## 7. Licence et nom

Le code source est publié sous **licence Apache 2.0**. Le nom Pass Tech, les icônes et l'identité
visuelle ne sont pas couverts par cette licence et restent à l'éditeur.

## 8. Modifications

Ces conditions partent avec l'application et avec son code source. Une modification part dans une
version ; la date en haut dit laquelle.

## 9. Droit applicable

Ces conditions sont rédigées dans le cadre du droit français et européen, sauf disposition impérative
contraire.

## 10. Contact

contact@files-tech.com
