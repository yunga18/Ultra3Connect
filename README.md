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
