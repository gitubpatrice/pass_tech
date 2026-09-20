# Política de privacidad — Pass Tech

**Versión del documento**: 20 de septiembre de 2026 (Pass Tech v2.7.0)
**App**: Pass Tech
**Sitio oficial**: https://www.files-tech.com
**Contacto**: contact@files-tech.com
**Código fuente**: https://github.com/gitubpatrice/pass_tech
**Licencia del código**: Apache License 2.0

---

## 1. Objeto

Esta política de privacidad explica cómo la aplicación **Pass Tech** — un gestor de contraseñas 100 % local — trata los datos y los permisos.

## 2. Resumen

- ✅ **Sin publicidad** en la aplicación.
- ✅ **Sin rastreadores**, sin medición de audiencia, sin análisis del comportamiento, sin elaboración de perfiles.
- ✅ **Sin cuenta** propia de la aplicación.
- ✅ **Sin sincronización en la nube** — tu caja fuerte se queda cifrada en tu dispositivo.
- ✅ **Sin telemetría** — no se envían al desarrollador datos de uso ni informes de error.

**Principio general**: Pass Tech es una caja fuerte de contraseñas 100 % local. Todos los datos sensibles (contraseñas, secretos TOTP, tarjetas bancarias, notas seguras) permanecen cifrados en el dispositivo. El desarrollador no opera ningún servidor remoto.

## 3. Responsable / desarrollador

- **Desarrollador**: Files Tech / Patrice
- **Sitio web**: https://www.files-tech.com
- **Contacto de privacidad**: contact@files-tech.com
- **Repositorio del código**: https://github.com/gitubpatrice/pass_tech
- **Licencia del código fuente**: Apache License 2.0

## 4. Datos consultados o almacenados

| Tipo de dato                           | Uso                                                          | Lugar del tratamiento                            |
| -------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------ |
| Contraseñas, secretos TOTP, tarjetas bancarias, notas seguras | Entradas de la caja fuerte creadas por ti | Cifradas en el dispositivo (`pt_vault_a.enc`)    |
| Segundo espacio de la caja fuerte (`pt_vault_b.enc`) | Negación plausible — **siempre presente**, hayas configurado o no un señuelo | Cifrado en el dispositivo con su propia clave del Keystore |
| Contraseña maestra                     | Deriva la clave de cifrado (Argon2id, referencia OWASP 2024) | Nunca se almacena; se borra de la RAM al bloquear |
| Clave biométrica                       | Desbloqueo opcional con huella o rostro                      | Android Keystore (ligada al hardware), `setUserAuthenticationRequired(true)` |
| Copias cifradas (`.ptbak`)             | Exportación opcional, iniciada por ti                         | Ubicación que tú elijas                           |
| Preferencias locales                   | Tema, tiempo hasta el bloqueo automático, tiempo de borrado del portapapeles | Almacenamiento local del dispositivo              |

## 5. Cifrado y derivación de claves

- **AES-256-GCM** (AEAD) con AAD vinculada, resistente a la degradación de versión.
- **Argon2id** (m = 19 MiB, t = 2, p = 1, referencia OWASP 2024) para derivar la clave maestra de la caja fuerte.
- **KEK ligada al hardware** en el Android Keystore (StrongBox cuando está disponible) que envuelve un secreto de hardware propio de cada caja fuerte.
- **Clave biométrica ligada al hardware** mediante el Android Keystore; no extraíble sin autenticación biométrica.
- **Negación plausible** — quien inspeccione el dispositivo no puede saber si guardas una segunda caja fuerte oculta. Los dos espacios llevan nombres neutros e indistinguibles (`pt_vault_a.enc` / `pt_vault_b.enc`) y **ambos existen siempre**: si no has configurado ningún señuelo, la app escribe igualmente uno ficticio — una lista vacía, cifrada con una contraseña aleatoria que no se guarda en ningún sitio y que, por tanto, nadie puede abrir, ni tú ni nosotros. Los alias del Keystore, las sales y los tiempos de desbloqueo están alineados entre ambos caminos.

## 6. Red

- La app usa la red para **dos funciones de efecto estrictamente local**:
  1. **Comprobación de actualizaciones**: consulta `api.github.com/repos/gitubpatrice/pass_tech/releases/latest` (HTTPS, sin autenticación, sin cookies).
  2. **Comprobación HIBP** (Have I Been Pwned, opcional): envía solo los **5 primeros caracteres del SHA-1** de una contraseña (modelo de k-anonimato). La contraseña nunca sale del dispositivo.
- La Network Security Config rechaza el HTTP en claro y las autoridades instaladas por el usuario en la versión de publicación.
- Sin telemetría, sin informes de fallos, sin analítica.

## 7. Cesión y transmisión de datos

La aplicación no transmite ningún dato a un servidor operado por el desarrollador. Compartir fuera del dispositivo requiere:

- una exportación `.ptbak` que inicies expresamente (cifrada con una frase de contraseña que tú eliges);
- el uso voluntario de una función de Android para compartir o enviar por correo.

## 8. Conservación y eliminación

- Los datos de la caja fuerte se quedan en local y bajo tu control.
- Desinstalar la app elimina todos los datos (el archivo de la caja fuerte está en el directorio privado de la app y queda excluido de las copias en la nube mediante `dataExtractionRules`).
- También puedes eliminar la caja fuerte desde la app (`Ajustes → Eliminar todos los datos`).
- **Ninguna copia residual de una caja fuerte anterior** — al actualizar una caja fuerte v3 antigua quedaba una copia `.bak`, cifrada con el esquema anterior, más débil, y atacable sin conexión. Desde la v2.5.1 se elimina en cuanto la actualización tiene éxito.

## 9. Seguridad

- Aislamiento en la sandbox, `FLAG_SECURE` (bloquea capturas y la vista previa de las apps recientes).
- `allowBackup=false` y `dataExtractionRules` excluyen la caja fuerte de cualquier copia en la nube de Android o transferencia entre dispositivos.
- Bloqueo progresivo tras 5 intentos fallidos (30 s → 30 min).
- Bloqueo automático configurable tras la inactividad (5 minutos por defecto).
- La clave derivada de la contraseña maestra se borra de la RAM al bloquear.
- Detección RASP (root, emulador, depurador) con aviso explícito.
- Marcado del portapapeles como sensible (Android 13+) y borrado inmediato al pasar a segundo plano.

Consulta [SECURITY.md](./SECURITY.md).

## 10. Permisos de Android

| Permiso / acceso                     | Motivo                                                                                              |
| ------------------------------------ | --------------------------------------------------------------------------------------------------- |
| `USE_BIOMETRIC` / `USE_FINGERPRINT`  | Desbloqueo biométrico opcional mediante Android BiometricPrompt.                                     |
| `INTERNET`                           | Comprobación de actualizaciones (GitHub Releases) y comprobación HIBP (k-anonimato, opcional).       |

`CAMERA` y `ACCESS_NETWORK_STATE` se **retiraron el 2026-08-03** junto con la
lectura de códigos QR, que se apoyaba en Google ML Kit. Un secreto 2FA se añade
ahora pegando la URI `otpauth://` que los servicios muestran bajo su código QR.

## 11. Menores

La aplicación no está dirigida específicamente a menores y no contiene publicidad comportamental ni mecanismos de elaboración de perfiles.

## 12. Cambios

Esta política puede actualizarse a medida que la aplicación evoluciona.

## 13. Contacto

📧 **contact@files-tech.com**
