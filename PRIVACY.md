# Privacy policy — Pass Tech

The privacy policy is the one displayed in the app (About → Privacy policy):

- English: [assets/legal/PRIVACY.en.md](assets/legal/PRIVACY.en.md)
- Français : [assets/legal/PRIVACY.fr.md](assets/legal/PRIVACY.fr.md)
- Deutsch: [assets/legal/PRIVACY.de.md](assets/legal/PRIVACY.de.md)
- Italiano: [assets/legal/PRIVACY.it.md](assets/legal/PRIVACY.it.md)
- Español: [assets/legal/PRIVACY.es.md](assets/legal/PRIVACY.es.md)

Any other language falls back to English.

Only those five files are maintained, so the repository and the app cannot say
different things. They had diverged: until v2.7.0 this page was a separate copy,
and the one shipped to users declared a `CAMERA` permission the app does not
have, for a QR scanner removed in v2.6.0.

The permission table is section 10 of each file. It is the one to update when
`.github/workflows/promesses.yml` reports that the APK permission surface has
changed.
