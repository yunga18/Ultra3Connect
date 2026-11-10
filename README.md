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
