# OBD Dash

App Android (Kotlin + Jetpack Compose) para adaptadores **ELM327 Bluetooth clásico**.

## Pantallas

| Pestaña | Qué muestra |
|---|---|
| **Panel** | Tacómetro semicircular con RPM grandes, tarjetas de temperatura del motor, combustible, consumo, velocidad, batería y aire de admisión, y una franja con distancia, tiempo, nombre del auto y código de la ECU |
| **Temp.** | Motor, aceite, aire de admisión, ambiente y catalizador con barras |
| **Velocidad** | Arco de velocidad, máxima, promedio, distancia y consumo del viaje (con botón para reiniciar) |
| **Errores** | Códigos almacenados y pendientes con su significado en español, borrado de DTC y estado de monitores (ej. 7 / 8) |
| **Más** | Conexión Bluetooth, lista de dispositivos emparejados y modo demo |

- Layout adaptable: celular vertical, celular horizontal y pantallas de auto (ej. 1280x720)
- Android 5.0 (API 21) en adelante
- **Modo demo** para probar el diseño sin el auto

## Compilar

1. Abre la carpeta con **Android Studio** (Koala o más nuevo).
2. Espera el *Sync* de Gradle (necesita internet).
3. Conecta el celular (o la pantalla del auto por USB/ADB) y pulsa **Run**.
   Para un APK suelto: *Build → Build Bundle(s) / APK(s) → Build APK(s)*.

## Usar

1. Enchufa el ELM327 al puerto OBD2 y enciende el contacto.
2. Empareja el adaptador en **Ajustes → Bluetooth** del dispositivo (PIN `1234` o `0000`).
3. Abre la app → pestaña **Más** → elige el adaptador.

## Personalizar

- Nombre en la franja inferior: `AppConfig.CAR_NAME` en `ui/Theme.kt`.
- Cilindrada y eficiencia para el cálculo de consumo: `obd/Fuel.kt`.
- Colores: `Palette` en `ui/Theme.kt`.

## Notas

- **Temperatura de aceite y catalizador**: solo aparecen si la ECU soporta esos datos (PID `015C` y `013C`). Muchos autos, incluidos varios Honda, no los exponen; en ese caso se ve `--`.
- **Consumo**: es una estimación. Si la ECU no tiene sensor MAF, se calcula con la presión de admisión (MAP), la temperatura del aire, las RPM y la cilindrada. Ajusta `DISPLACEMENT_L` en `obd/Fuel.kt` a tu motor.
- La **gasolina** del tablero (`Nivel Combustible`) depende de que la ECU soporte el PID `012F`.
- Los códigos `P1xxx`, `B`, `C` y la mayoría de los `U` son del fabricante; la base local trae los genéricos más comunes y unos pocos de Honda. Los que no conoce se muestran con una explicación general.
- La lectura de códigos prueba los dos formatos de respuesta más comunes (con y sin byte de conteo en CAN).
- Archivos: `obd/Elm327.kt` (protocolo), `obd/DtcDatabase.kt` (significados), `obd/Fuel.kt` (consumo), `ui/Gauges.kt` (dial y tarjetas), `ui/Icons.kt` (iconos), `ui/Screens.kt` (pantallas).

## Compilar con GitHub Actions

El workflow `.github/workflows/build.yml` compila el APK en cada push a `main`, en pull requests y a mano
(pestaña **Actions → Build APK → Run workflow**). El APK queda en los *Artifacts* de la ejecución como
`ObdDash-debug-apk`. Si subes un tag que empiece con `v` (por ejemplo `v1.0`), además se publica en *Releases*.
