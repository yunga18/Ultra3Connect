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
Tipo WhatsApp 5, SMS 0, Telegram 21 son equivalencias de un protocolo relacionado, que aún requieren comprobarse en el Ultra 3.

Referencia complementaria: https://github.com/InsertX2k/hryfine_ultra3_com_protocol/blob/main/make_generic_cmd_pattern.py

## Seguridad y limitaciones
No se ha capturado todavía una notificación real CMD 02 SUB 12 en este Ultra 3, así que la compatibilidad visual no está confirmada.
Primero: botón manual para mandar solamente texto ficticio.
Más adelante: servicio `NotificationListenerService` con autorización expresa del usuario, lista de apps permitidas, sin guardar mensajes sensibles y con flujo BLE en segundo plano adecuadamente gestionado.
