# Condiciones de uso — Pass Tech

**Versión del documento**: 20 de septiembre de 2026 (Pass Tech v2.7.0)
**App**: Pass Tech
**Sitio oficial**: https://www.files-tech.com
**Contacto**: contact@files-tech.com
**Código fuente**: https://github.com/gitubpatrice/pass_tech
**Licencia del código**: Apache License 2.0

---

## 1. Objeto

Estas condiciones de uso definen las reglas aplicables al uso de la aplicación **Pass Tech** — un gestor de contraseñas 100 % local.

## 2. Aceptación

El uso de la aplicación implica la aceptación de estas condiciones.

## 3. Funcionamiento general

Pass Tech es una caja fuerte de contraseñas 100 % local. Guarda contraseñas, secretos 2FA TOTP, tarjetas bancarias y notas seguras en un archivo cifrado en el dispositivo, protegido por una contraseña maestra y, opcionalmente, por una clave biométrica del Android Keystore.

## 4. Sin publicidad, sin rastreadores, sin telemetría

El desarrollador declara que la aplicación no contiene publicidad, ni rastreadores, ni analítica, ni análisis del comportamiento, ni sistemas de elaboración de perfiles, ni telemetría, ni informes remotos de fallos. La caja fuerte nunca se transmite a ningún servidor operado por el desarrollador.

## 5. Licencia del código fuente

Código fuente publicado bajo la **Apache License 2.0**.

- Repositorio: https://github.com/gitubpatrice/pass_tech
- Sitio oficial: https://www.files-tech.com
- Contacto: contact@files-tech.com

## 6. Responsabilidades del usuario

- **Elige una contraseña maestra robusta** (se recomiendan 12 caracteres o más) y guárdala en lugar seguro. **El desarrollador no puede recuperarla.**
- Haz tus propias copias cifradas (exportación `.ptbak` con una frase de contraseña robusta).
- Protege tu dispositivo con un bloqueo adecuado, con actualizaciones de seguridad y con copias útiles.
- Respeta los derechos de autor, la privacidad, el secreto profesional y la legislación aplicable.

## 7. Puntos propios de esta aplicación

- **La contraseña maestra no se puede recuperar.** Si la olvidas, la caja fuerte queda inaccesible de forma definitiva.
- El desbloqueo biométrico es una capa de comodidad; la contraseña maestra sigue siendo la raíz de la confianza.
- Frente a un dispositivo comprometido, Pass Tech actúa según el principio del mejor esfuerzo: en un dispositivo con root, depurable o emulado, se avisa al usuario y este usa la app bajo su responsabilidad.
- La comprobación HIBP es opcional y usa un protocolo de k-anonimato (solo se envían los 5 primeros caracteres del SHA-1 de la contraseña).

## 8. Exclusión de garantía

La aplicación se proporciona tal cual. El desarrollador hace todo lo posible por ofrecer una herramienta segura, pero no garantiza una seguridad absoluta. El usuario sigue siendo responsable de la elección de la contraseña maestra y de la protección de su dispositivo.

## 9. Limitación de responsabilidad

En la medida en que lo permita la ley, el desarrollador no puede ser considerado responsable de pérdidas de datos (en particular de una contraseña maestra olvidada que haga irrecuperable la caja fuerte), de errores de manejo, de incidencias de servicios de terceros ni de las consecuencias de un uso no conforme.

## 10. Servicios de terceros

- **API de GitHub Releases** para la comprobación de actualizaciones (HTTPS, sin autenticación, sin cookies).
- **API de Have I Been Pwned** para la comprobación de filtraciones (HTTPS, k-anonimato, opcional).

## 11. Propiedad intelectual

El nombre, los contenidos, los textos, los iconos, los elementos gráficos y los recursos propios del proyecto siguen estando protegidos. El código fuente principal se publica bajo la Apache License 2.0.

## 12. Seguridad

El usuario debe proteger su contraseña maestra y su dispositivo, y evitar usar Pass Tech en dispositivos con root o comprometidos. Consulta [SECURITY.md](./SECURITY.md).

## 13. Modificación de las condiciones

Estas condiciones pueden actualizarse. La fecha del documento indica la versión en vigor.

## 14. Legislación aplicable

Salvo disposiciones legales imperativas en contrario, estas condiciones se redactan en el marco del derecho francés y europeo.
