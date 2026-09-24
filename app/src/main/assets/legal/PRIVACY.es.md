# Política de privacidad — Pass Tech

- **Se aplica a**: Pass Tech 3.0.0 (`com.filestech.pass_tech`)
- **Última modificación**: 23 de septiembre de 2026
- **Editor**: Files Tech — Patrice Haltaya
- **Contacto**: contact@files-tech.com
- **Código fuente**: https://github.com/gitubpatrice/pass_tech — Apache License 2.0

> Este documento describe la versión **Kotlin** de Pass Tech, la 3.0.0. No es la política de las
> versiones Flutter anteriores (2.x, `com.passtech.pass_tech`), que funcionaban de otra manera.

---

## 1. En resumen

Pass Tech guarda contraseñas, tarjetas bancarias y notas en tu teléfono, cifradas. No hay ninguna
cuenta, ningún servidor nuestro ni ninguna copia de tus datos en otro sitio.

- **No recogemos nada.** Ninguna publicidad, ningún rastreador, ninguna estadística, ningún informe
  de fallos, ningún identificador.
- **No recibimos nada.** Ni un nombre, ni una dirección, ni una contraseña, ni una cifra.
- **No hay nada que borrar de nuestro lado**, porque de nuestro lado no hay nada.

La app usa la red para **dos cosas únicamente**, ambas descritas en el §5. Ninguna de las dos envía
lo que has escrito.

## 2. Qué hay en tu teléfono, y dónde

Todo vive en la carpeta privada de la app, que ninguna otra app puede leer.

| Archivo | Qué contiene |
| --- | --- |
| `pt_vault_a.enc`, `pt_vault_b.enc`, `pt_vault_c.enc` | Tres plazas de caja fuerte. **Las tres existen siempre y tienen el mismo tamaño**, uses una, dos o tres. |
| `pt_heir_a.enc`, `pt_heir_b.enc`, `pt_heir_c.enc` | Tres instantáneas para el heredero, si el acceso de heredero está configurado. **Las tres existen siempre y tienen el mismo tamaño**, por la misma razón. |
| `pt_state.enc` | Los contadores que la app necesita antes de que ninguna caja fuerte esté abierta: intentos fallidos, último arranque, fecha de la última comprobación de actualizaciones. |
| Preferencias de Android | Tema, retardo de bloqueo, retardo del portapapeles, si las capturas están bloqueadas, si la comprobación del dominio está activa. Ningún secreto. |

**Por qué tres de todo.** Una segunda contraseña abre una segunda caja fuerte, y nada en el teléfono
dice si la usas. Eso solo se sostiene si una plaza usada y una sin usar se parecen exactamente —
mismo nombre, mismo tamaño, mismo contenido para quien lea los bytes. Las plazas que no usas
contienen datos al azar, cifrados con una clave que no existe en ninguna parte: ni tú ni nosotros
podremos abrirlas jamás.

## 3. Cómo está cifrado

- **AES-256-GCM** para la caja fuerte, las instantáneas del heredero y el archivo de estado.
- La clave se deriva de tu contraseña maestra con **Argon2id** (19 MiB de memoria, 2 pasadas, 1 hilo
  — la referencia OWASP 2024 para el móvil) **y** con una clave guardada en el chip seguro del
  teléfono, que participa en cada intento, sin excepción. Una copia de tu caja fuerte llevada a otra
  máquina no puede atacarse allí: ese chip no está.
- **Tu contraseña maestra no se guarda nunca**, de ninguna forma, en ningún sitio. No puede
  recuperarse — ni por nosotros, ni por nadie. Olvidarla es perder la caja fuerte.
- El desbloqueo biométrico, si lo activas, guarda su clave en el Keystore de Android: ilegible sin tu
  huella y destruida si las huellas del teléfono cambian.
- Tras **5 intentos fallidos**, la app espera: 30 segundos, luego 1, 5, 15 y 30 minutos. La espera se
  cuenta en el archivo de estado y sobrevive a un reinicio.

## 4. Qué puedes exportar

- **Copia `.ptbak`**: eliges tú cuándo, y una frase de contraseña tuya la cifra. Nosotros no la vemos
  nunca. Dónde la guardes después es cosa tuya — una copia en una nube está en esa nube.
- **Exportación en claro**: se ofrece para mudarse a otro gestor. **No está cifrada**, y la app lo
  dice antes de escribirla.

Nada sale solo. No hay ninguna copia automática: la copia de seguridad en la nube de Android y la
transferencia de un teléfono a otro están desactivadas para esta app.

## 5. Las dos veces que la app usa la red

1. **Comprobación de actualizaciones.** Una vez abierta tu caja fuerte, la app pregunta a GitHub si
   ha salido una versión más reciente — **dos veces al día como máximo**. Consulta
   `api.github.com` sobre la última publicación de este proyecto. Ninguna cuenta, ninguna cookie,
   nada tuyo se envía. No descarga ni instala nada: muestra lo que ha encontrado y un enlace.
2. **Comprobación de filtraciones**, en la pantalla de auditoría, **que inicias tú**. La contraseña
   no se envía nunca. La app calcula su huella SHA-1 y envía **solo los cinco primeros caracteres**
   a Have I Been Pwned, que responde con todas las huellas que empiezan así — decenas de miles. La
   comparación ocurre en tu teléfono. Es el modelo de k-anonimato que ese servicio publica.

**Lo que aun así revelan estas dos llamadas**, y conviene decirlo claro: quien vea tu conexión — tu
operador, GitHub, Have I Been Pwned — se entera de que alguien en tu dirección usa esta app. No se
entera de nada de tu caja fuerte. Si eso te importa: las dos se detienen por completo cuando la app
está camuflada de calculadora (§7), y la comprobación de filtraciones no parte nunca mientras no la
pulses.

La app rechaza el HTTP sin cifrar y rechaza las autoridades de certificación que otra persona haya
añadido al teléfono.

## 6. La comprobación del dominio y el permiso de Android que necesita

Si activas **«Comprobar el dominio antes de copiar»**, la app usa un servicio de accesibilidad de
Android. Es lo más intrusivo que pide nunca, así que aquí está exactamente lo que hace con él.

- Está **apagado al instalar** y ni siquiera aparece en la lista de accesibilidad de Android mientras
  no lo pidas.
- Recibe eventos **solo de los navegadores que conoce** — Chrome, Firefox y sus versiones beta,
  Brave, Edge, Opera, Vivaldi, Samsung Internet, DuckDuckGo. Android no le manda nada de ninguna otra
  app: ni de tu app bancaria, ni de tus mensajes, ni de tu teclado.
- De esos navegadores lee **la barra de direcciones y nada más** — el nombre del sitio, no la ruta,
  no los parámetros, ni una palabra de la página.
- Ese nombre se guarda **solo en memoria**, uno cada vez, sustituido en cada lectura y olvidado a los
  quince segundos. No se escribe nunca en el disco y no se envía nunca a ninguna parte.
- Apagar el ajuste **retira el permiso**: el servicio sale de la lista de Android, y volver a
  encenderlo te lo pedirá de nuevo.

## 7. Modo pánico

Si lo usas, la app se bloquea, vacía el portapapeles, desarma la huella, retira el servicio de
accesibilidad y sustituye su propio nombre y su icono en tu pantalla de inicio por una calculadora
que funciona. **No se borra nada**: tu contraseña maestra sigue abriendo la caja fuerte. Mientras
está camuflada, la app no hace ninguna llamada de red.

## 8. Acceso de heredero

Si lo configuras, una persona que elijas podrá abrir una **instantánea de solo lectura** de tu caja
fuerte tras un silencio suficientemente largo por tu parte — 90 días de forma predeterminada, más 7
días de gracia, y cada apertura reinicia la cuenta. La instantánea está cifrada con una frase de
contraseña propia que entregas tú mismo. No sale nunca del teléfono, no hay nube ni terceros, y
nosotros no intervenimos.

## 9. Permisos de Android

Medidos sobre el APK publicado, no sobre el código fuente:

| Permiso | Para qué |
| --- | --- |
| `INTERNET` | Las dos llamadas del §5. |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | El desbloqueo con la huella, si lo activas. |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Añadido por una biblioteca de Android; permite a la app hablar consigo misma, y nada más. |

El servicio de accesibilidad del §6 **no** es un permiso de esta lista: Android lo concede aparte,
desde sus propios ajustes, y puedes retirarlo allí cuando quieras.

No hay permiso de cámara, ni de contactos, ni de ubicación, ni de almacenamiento: Pass Tech pide un
archivo al sistema cuando exportas o importas, y el sistema le entrega ese archivo.

## 10. Menores

La app no está dirigida a menores y no contiene publicidad, ni elaboración de perfiles, ni mecanismo
conductual de ningún tipo.

## 11. Cambios en este documento

Se publica con la app y con su código fuente. Un cambio sale en una versión; la fecha de arriba dice
en cuál.

## 12. Contacto

contact@files-tech.com
