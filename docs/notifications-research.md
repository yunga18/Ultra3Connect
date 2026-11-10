# HryFine: investigación de notificaciones (10 noviembre 2026)

Basado en `base.apk` proporcionado para la interoperabilidad con el smartwatch Ultra 3. No se ha incluido el APK en el repositorio.

## Referencias observadas en `classes5.dex`
- `aider.NPNotificationService.onNotificationPosted` obtiene `android.title`, `android.text` y el nombre de paquete de Android.
- `aider.MsgType.pck2MsgType` categoriza aplicaciones (WhatsApp, Telegram, SMS, etc.).
- `ble.MsgPushHelper.onAppMsgReceive` consulta `NotifyBean` y `SharePreferenceNotify` para comprobar preferencias, luego llama a `SettingIssuedUtils.messagePush`.
- `SettingIssuedUtils.messagePush` convierte a UTF-8 y construye CMD 02 / SUB 12 para texto; una ruta alternativa genera SUB 11.
- Las llamadas se gestionan por separado: `MsgPushHelper.onPhoneInComing` y `SettingIssuedUtils.androidCall`.

## Formato experimental
Trama: `DF | longitud16 | checksum | 02 | 10 | 12 | tamaño_de_payload16 | tipo | 00 | 00 | texto_UTF8`.
Checksum: suma de todos los bytes menos byte 3 (mod 256).
Límite del cuerpo de texto: 196 bytes UTF-8. Dividir el paquete en fragmentos ATT de hasta 20 bytes con MTU 23.
Códigos verificados al examinar `MsgPushHelper.getType` y `getNewType` de `classes5.dex` del APK proporcionado:
- `WHATSAPP` devuelve **8**
- `TWITTER` devuelve **5**
- `TELEGRAM` devuelve **21**
- `Other` devuelve **13**; SMS usa 0.
La prueba de campo del propietario confirmó que enviar tipo **5** muestra el logo de **X/Twitter** en su Ultra 3. Corregimos el tipo de WhatsApp a **8**, pendiente de comprobar su icono visual en el reloj.

Referencia complementaria: https://github.com/InsertX2k/hryfine_ultra3_com_protocol/blob/main/make_generic_cmd_pattern.py

## Seguridad y limitaciones
El propietario confirmó que el reloj **sí mostró** la notificación de prueba enviada con CMD 02 SUB 12, inicialmente con el icono X/Twitter por el error de asignación de tipo 5. Falta verificar que con el tipo 8 aparezca el icono WhatsApp.
Primero: botón manual para mandar solamente texto ficticio.
Más adelante: servicio `NotificationListenerService` con autorización expresa del usuario, lista de apps permitidas, sin guardar mensajes sensibles y con flujo BLE en segundo plano adecuadamente gestionado.
