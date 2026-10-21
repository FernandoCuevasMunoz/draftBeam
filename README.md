# draftBeam

**From measurement to CAD**

Aplicación Android para capturar mediciones BLE de un telémetro láser, construir planos sencillos y exportarlos a DXF editable.

## Proyecto Android

El código Android y el wrapper Gradle están en [`android/`](android/).

Para ejecutar las pruebas y comprobaciones locales:

```sh
cd android
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Consulta [`CONTEXT.md`](CONTEXT.md) para el alcance, las decisiones de producto y las restricciones de seguridad BLE/DXF.
