# Ultra 3 Connect — versión 0.1.0

Aplicación Android experimental para explorar dispositivos Bluetooth Low Energy (BLE) y servicios GATT. Interfaz oscura, sin publicidad.

## Requisitos
- Android Studio reciente.
- JDK 17.
- Android SDK Platform 35.
- Teléfono Android 8.0 o superior con Bluetooth LE.

## Abrir
1. Descomprime el ZIP.
2. En Android Studio elige **Open** y selecciona la carpeta `Ultra3Connect`.
3. Espera a que Gradle sincronice y descargue las dependencias.
4. Conecta un teléfono Android con depuración USB habilitada.
5. Pulsa Run.

## Uso
1. Activa Bluetooth en el teléfono.
2. Abre Ultra 3 Connect y concede los permisos solicitados.
3. Pulsa **Buscar reloj**.
4. Selecciona el dispositivo Ultra 3. Si aparecen dos direcciones parecidas, prueba primero la que corresponda al dispositivo BLE anunciado por el reloj.
5. Abre **Servicios** para explorar UUID y características.
6. Pulsa **Leer** solo en características que indiquen `READ`.

## Importante: límites de esta versión
- Esta es una primera versión de exploración GATT, no un reemplazo completo de HryFine todavía.
- La lectura de batería funciona si el reloj expone el servicio estándar Battery Service (180F) y la característica Battery Level (2A19).
- Aún no se envían comandos propietarios ni datos OTA. No escribir en características desconocidas.
- El soporte de notificaciones GATT se añadirá en una siguiente iteración; los eventos de notificación se registran cuando el sistema los entrega.
- La app no mide salud por sí sola: solo podrá mostrar datos que el reloj transmita mediante un protocolo que logremos identificar.

## Permisos
Android 12+ solicita `BLUETOOTH_SCAN` y `BLUETOOTH_CONNECT`. Android 11 y anteriores usan permiso de ubicación para el escaneo BLE.

## Identidad
- Nombre: Ultra 3 Connect
- Application ID: `com.yunga.ultra3connect`
- Versión: `0.1.0`
- Minimum SDK: 26

## Compilar sin Android Studio usando GitHub Actions
Sube el contenido de esta carpeta a la raíz del repositorio GitHub (no la carpeta contenedora).
Ve a Actions > Build Android APK > Run workflow o haz un push a main.
Cuando el workflow termine correctamente, abre su ejecución y descarga `Ultra3Connect-debug-apk` desde Artifacts. Extrae el ZIP para obtener `app-debug.apk`.
El APK no se ha compilado ni probado todavía; GitHub Actions mostrará cualquier error real.

## Versión 0.2.0 — Diagnóstico BLE
- Escaneo sin filtros en modo LOW_LATENCY durante 25 segundos.
- Resultados priorizados para nombres que contienen `ultra` y ordenados por señal (dBm).
- Mensajes de diagnóstico cuando no se detectan dispositivos o fallan los permisos.
- Entrada manual de dirección LE para conexión directa, sin guardar la dirección en GitHub.
- Lectura automática de Battery Level (2A19) después de descubrir servicios.
- Se impide leer características GATT sin propiedad READ.

### Si Ultra 3 no aparece
1. Enciende Bluetooth del móvil y concede el permiso **Dispositivos cercanos**.
2. Desconecta nRF Connect y HryFine antes de abrir Ultra 3 Connect.
3. Mantén encendido el reloj y la pantalla del móvil mientras buscas.
4. En Android 11 o anterior también activa Ubicación del dispositivo.
5. Si aparece en nRF Connect y no aquí, consulta **Registro** en nuestra app.
6. La opción de dirección manual usa la dirección **LE**, no la de Bluetooth clásico, y puede fallar si la dirección del reloj cambia.

## Versión 0.3.0 — Permisos y estabilidad BLE
- La búsqueda solicita permisos de **Dispositivos cercanos** y **Ubicación precisa** para permitir resultados BLE completos en Android 12+ sin filtrar anuncios de periféricos.
- La app no consulta coordenadas GPS ni envía ubicaciones; el permiso se usa exclusivamente para el descubrimiento BLE.
- El escaneo informa si la opción global Ubicación está desactivada.
- El registro muestra los códigos de estado GATT de conexión/desconexión y si discoverServices pudo solicitarse.
- Se ignoran callbacks antiguos de conexiones anteriores para que no alteren la conexión nueva.
- No se implementa auto-reconexión hasta conocer el motivo exacto de la desconexión.

### Prueba de diagnóstico
1. Cierra HryFine y desconecta nRF Connect; mantén el reloj encendido.
2. Enciende Bluetooth y Ubicación del teléfono.
3. Concede Dispositivos cercanos y Ubicación precisa a Ultra 3 Connect.
4. Pulsa Buscar reloj. Si no aparece, entra en Registro y revisa el motivo.
5. Si no aparece, prueba la dirección LE que termina en 4B.
6. Si se desconecta, anota el dato `GATT status=...` del Registro. El estado no identifica por sí solo el origen del problema.

## Versión 0.4.0 — Monitor UART de solo recepción

- Conectarse al reloj, navegar a **Servicios** y pulsar **Activar recepción UART**.
- La app habilita notificaciones en `6e400003-b5a3-f393-e0a9-e50e24dcca9f` mediante el descriptor CCCD 0x2902; el botón puede detener la recepción.
- Se muestra el estado de suscripción y la cantidad de paquetes UART recibidos, con los últimos cinco mensajes en HEX y ASCII imprimible.
- Hasta 40 notificaciones se mantienen en memoria y se anotan en **Registro**.
- No se envían órdenes al UART TX, ni comandos al servicio de actualización OTA.
- La escritura del CCCD es el procedimiento necesario para activar las notificaciones; **no** es una escritura de comando de control al firmware.
- Que no aparezcan paquetes por sí solo no significa error: el reloj podría no enviar datos espontáneamente sin un intercambio propietario aún desconocido.

## Versión 0.5.0 — Monitor opcional FF14
- Conserva el monitor UART; agrega un segundo monitor **FF14** en el servicio `FF12`.
- Activa las notificaciones `FF14` usando exclusivamente el descriptor CCCD (0x2902), sin enviar comandos del fabricante.
- El contador y los últimos paquetes HEX de FF14 se muestran por separado.
- Evita iniciar dos escrituras de CCCD simultáneas.
- La pestaña **Registro** tiene un botón **Copiar registro** para compartir diagnósticos. Puede contener direcciones BLE y datos recibidos, revísalos antes de compartirlos.
- Para probar, conecta el reloj, activa UART y FF14, e interactúa con la pantalla del reloj; la ausencia de paquetes no implica que BLE esté roto.

## Versión 0.6.0 — Reconstrucción de tramas DF verificadas
- Un nuevo módulo Kotlin `DfFrameAssembler` reconstruye tramas `0xDF` distribuidas en fragmentos GATT (incluidos los casos de 20+1 bytes).
- Reconoce longitud, CMD, SUB, longitud del payload y checksum aditivo (sumatoria de bytes excepto el byte 3, mod 256).
- Cuenta tramas y valida longitud/checksum, mostrando los últimos mensajes y permitiendo copiar sus bytes completos.
- Conserva fragmentos originales y eventos con marcas de hora de milisegundos en el registro.
- Pruebas unitarias incluyen los dos mensajes reales capturados, reconstruidos con bytes finales `61` y `5A`.
- No atribuye significado definitivo a CMD 05/SUB 0E o SUB 04 ni envía comandos a las características TX u OTA.
- Nota: si otro reloj usa un protocolo diferente, el monitor puede no reconocer sus mensajes.

## Versión 0.7.0 — Correlación de eventos y contador experimental
- Muestra etiquetas **hipotéticas** para el protocolo de otros relojes compatibles: CMD 05 SUB 04 (pulso), SUB 05 (presión arterial), SUB 02 (pasos), SUB 0E (SpO2). Estas no son interpretaciones médicas validadas para este modelo.
- Extrae como **campo candidato** los bytes 15–16 de las tramas de al menos 17 bytes e informa el incremento entre tramas válidas y los segundos transcurridos según reloj monotónico de Android.
- Incluye botones **Marcar pulso** y **Marcar presión** para insertar marcadores de hora antes de realizar la medición desde la pantalla del reloj. Esos botones no activan mediciones ni mandan comandos.
- Mantiene los datos crudos en HEX, su checksum y la opción Copiar tramas DF.
- Cuarta prueba unitaria con las tramas capturadas a las 11:20:12 y 11:21:31; incremento hexadecimal 79 en el campo candidato, acorde con los 78,723 segundos transcurridos.
- Referencia externa de la hipótesis de subcomandos: https://github.com/victorbillyph/chinese-smartwatch-homeassistant/blob/main/docs/PROTOCOL.md
- No habilita comandos OTA, reseteos, ajustes ni escrituras propietarias.

## Versión 0.8.0 — Lecturas experimentales sujetas a verificación
- En **Servicios** se muestran el último pulso candidato y la última presión candidata del reloj: son **hipótesis**, no lecturas certificadas ni datos médicos.
- Interpreta exclusivamente tramas DF completas y con checksum válido, CMD 05 / SUB 04 con un posible valor de pulso en el último byte y CMD 05 / SUB 05 con dos posibles valores de presión en los últimos dos bytes.
- Usa comprobaciones de rango razonable para evitar presentar paquetes evidentemente incompatibles. La validación del protocolo real requiere comparar con la pantalla del reloj.
- Los datos mostrados son temporales en memoria y se borran al reconectar; no se comparten con servicios de terceros.
- Pruebas unitarias con los dos mensajes reales de 11:31 y un paquete con checksum alterado.
- Sin nuevos comandos Bluetooth, sin escrituras al protocolo propietario y sin actualizaciones OTA.

## Versión 0.9.0 — Pantalla de mediciones e historial local
- Se confirmó que dos tramas CMD 05 SUB 04/05 del Ultra 3 coincidían exactamente con lecturas vistas en el reloj: pulso 90 lpm y presión 111/82 mmHg. La equivalencia de bytes y pantalla no valida precisión médica.
- La pantalla **Dispositivo** ahora muestra las últimas lecturas recibidas (pulso y presión) con fecha/hora, y permite activar o detener UART sin entrar en Servicios.
- Nueva pestaña **Historial**: conserva hasta 100 lecturas decodificadas con fecha/hora y la trama HEX correspondiente.
- Almacenamiento privado mediante SharedPreferences; no se envían datos de salud a servidores ni se exportan automáticamente.
- Botones **Copiar** para exportación manual al portapapeles y **Borrar todo** con confirmación.
- El backup automático de Android está desactivado en el manifiesto para reducir exposición de los datos.
- La captura solo funciona mientras la app está activa y conectada con la recepción UART habilitada; no hay servicio BLE en segundo plano ni reconexión automática.
- No se recupera el historial de versiones anteriores, que no guardaban mediciones en el teléfono.
- Las lecturas provienen del reloj genérico y **no deben utilizarse para diagnóstico ni decisiones médicas**.
- No se envían nuevos comandos propietarios ni se modifica el firmware.

## Versión 1.0.0 — Solicitar mediciones desde Ultra 3 Connect
Se analizó el registro HCI enviado por el propietario el 10 de noviembre de 2026 y se aislaron las órdenes de HryFine:
- Pulso: `DF 00 06 06 02 10 0D 00 01 01`. En la captura, HryFine la envió y el reloj respondió ~5 segundos después con CMD 05 SUB 04.
- Presión: `DF 00 06 07 02 10 0E 00 01 01`. En la captura, el reloj respondió ~11 segundos después con CMD 05 SUB 05.
- Escribir en Nordic UART `6E400002` usando WRITE_NO_RESPONSE; escuchar `6E400003`. Esta correspondencia se verificó con la tabla GATT del dispositivo.
- En **Dispositivo**, activar primero **recepción de lecturas**, luego pulsar **Medir pulso** o **Medir presión**.
- La aplicación no inicia mediciones automáticamente, impide solicitudes simultáneas, registra la confirmación FD (si se recibe) y espera hasta 45 segundos por una respuesta válida.
- Si no recibe respuesta, no reintenta ni envía comandos alternativos. Los controles se prueban en el teléfono real.
- No se incluyen otros comandos de la captura (ID de vinculación, perfil, ajuste de tiempo, firmware, factory reset, etc.) y el ZIP original privado **no** se subió al repositorio.
- Los datos de salud del reloj siguen sin validación clínica: no utilizarlos para diagnosticar ni tomar decisiones médicas.

## Versión 1.0.1 — Corrección de desplazamiento en teléfonos
- Se corrige un fallo de UI en pantallas compactas: en **Dispositivo** la zona de mediciones ocupaba el alto disponible e impedía deslizarse hasta el campo MAC y la lista de dispositivos BLE.
- El contenido de **Dispositivo** ahora se desplaza verticalmente dentro del área visible, respetando la barra de navegación inferior.
- La lista de BLE utiliza una columna regular en esa pantalla para evitar LazyColumns anidadas dentro de un área de altura ilimitada.
- Cuando se detecta un periférico con nombre exacto **Ultra 3**, se muestra el acceso rápido **Conectar Ultra 3 detectado** debajo de la tarjeta de conexión.
- Ninguna característica GATT, UUID, comando DF ni mecanismo de lectura cambia en esta versión.
