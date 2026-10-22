# Contexto del proyecto

## Resumen

**Nombre:** draftBeam
**Lema:** From measurement to CAD
**Identificador Android:** `cl.fernandocuevas.draftbeam`
**Propósito:** investigar y desarrollar una aplicación Android propia para recibir mediciones de un telémetro láser Milessey M120 Pro, construir planos sencillos y exportarlos a DXF.
**Etapa:** prototipo Android BLE/editor geométrico con importación y exportación DXF estándar; falta validación visual en LibreCAD y persistencia nativa de proyectos.

**Distribución:** proyecto Android de código abierto bajo licencia MIT (`LICENSE`); mínimo configurado Android 7.0 / API 24 con BLE obligatorio. La compatibilidad real entre versiones/dispositivos requiere pruebas. Las capturas públicas de la app se ubican en `docs/screenshots/`, separadas de los mockups de `android/Design/`.

La investigación BLE detallada se conserva en notas personales externas que no forman parte de este repositorio público. Este `CONTEXT.md` es la referencia compartible para el alcance y las observaciones técnicas confirmadas.

## Alcance acordado del MVP

1. Escanear y conectar por BLE al medidor.
2. Suscribirse a las notificaciones de la característica `0xFFB2`, mostrar lecturas y convertir metros a milímetros.
3. Guardar DXF localmente y cargarlo de nuevo para editarlo, sin cuenta ni servidor.
4. Elegir eje y sentido (`X+`, `X−`, `Y+`, `Y−`) para dibujar segmentos rectos de pared.
5. Guardar/abrir proyectos y exportar el plano a DXF.

La edición de secuencias muro–puerta–muro queda fuera de la primera versión.

## Dominio y hallazgos confirmados

- El dispositivo aparece como `M130` y se identifica en las pruebas como Milessey M120 Pro.
- Servicio BLE propietario observado: `0xFFB0`.
- `0xFFB1` expone `NOTIFY`, `READ` y `WRITE NO RESPONSE`; una lectura observada devuelve `V1.1.2`.
- `0xFFB2` expone `NOTIFY` y `WRITE NO RESPONSE`; es la característica observada que notifica mediciones.
- Se han observado cinco muestras con formato ASCII de tres decimales en metros, seguido de `m`, CR, LF y NUL (ejemplos: `1.150m\r\n\0`, `1.975m\r\n\0`). La app decodifica ese formato a milímetros enteros y conserva el hexadecimal original; aún conviene validar más muestras y casos límite.
- El dispositivo parece aceptar una conexión central a la vez. BLE no requiere necesariamente emparejamiento persistente.
- Existe otro servicio propietario con características descritas como `Img Identify` y `Img Block`; su función no está confirmada. No escribir en ellas ni enviar comandos desconocidos.

No asumir que el formato, UUID o comportamiento descritos aquí son exhaustivos. Consultar las notas vinculadas para los detalles y las incertidumbres actualizadas.

## Tecnología y arquitectura

- Plataforma prevista: Android.
- Lenguaje recomendado en las notas: Kotlin.
- Android Studio y un teléfono Android 16 son las herramientas/dispositivo de prueba previstos.
- La arquitectura de la aplicación aún no está decidida ni implementada. Mantenerla simple y modular; registrar decisiones significativas antes de fijar estructura o dependencias.
- El prototipo del plano usa origen `(0,0)`, coordenadas cartesianas y milímetros; X+ es derecha y Y+ es arriba. Los segmentos pueden tener ángulos arbitrarios y conservan su punto inicial, longitud y ángulo.
- Cada nueva notificación FFB2 que se decodifica correctamente agrega automáticamente un muro usando la dirección actualmente seleccionada; no se requiere un botón de confirmación por lectura.
- Cada muro muestra su longitud. Tocar su cuerpo lo selecciona en naranja sin abrir el editor; el botón “Editar” abre la hoja para modificarlo o borrarlo individualmente. Borrar una línea no modifica las coordenadas de las demás; por tanto, el dibujo puede quedar abierto o desconectado, lo cual es válido en DXF.
- Se puede elegir cualquier extremo de muro como punto de continuación; después de agregar una nueva línea, el punto activo avanza a su nuevo extremo. Los extremos de la línea central y de la arista paralela de los muros con ancho se indican visualmente y se pueden elegir como inicio de otra línea. Tocar cualquier arista del muro, incluida la paralela, permite seleccionarlo. Sin muro seleccionado, arrastrar desde un extremo crea un segmento. Con un muro seleccionado, arrastrar uno de sus extremos lo mueve y ajusta al eje más cercano dentro de 8°. Si la coordenada pertenece a más de un muro, se pregunta si mover solo ese extremo o toda la unión; al moverla completa, todos los extremos coincidentes siguen la posición ajustada según el muro seleccionado.
- La pantalla principal muestra «Conectar BLE» al iniciar; al buscar, los dispositivos disponibles se presentan en un modal. Al escoger uno se cierra el modal y el nombre se marca conectado en verde solo tras completar conexión GATT y suscripción FFB2. El menú superior permite buscar/cambiar de medidor o desconectar.
- El mapa dibuja los ejes X/Y y el origen. Arrastrar desde el punto activo crea un segmento provisional; el ángulo se ajusta a múltiplos de 90° cuando queda a 8° o menos de un eje. La longitud dibujada con el dedo es aproximada.
- El viewport permite zoom con gesto de pinza; arrastrar desde el espacio vacío o sobre una línea desplaza el plano. Arrastrar desde un extremo crea un muro si no hay selección, y mueve el extremo del muro seleccionado si lo hay, con ajuste a ejes dentro de 8°. “Ajustar plano” vuelve a encuadrar el contenido. El extremo activo se identifica con un target cuadrado con +. Las medidas muestran mm, se orientan paralelas a la línea y solo incluyen grados en segmentos diagonales. El dibujo se recorta al área blanca.
- “Ajustar plano” centra los extremos de todos los segmentos y escala para mostrarlos completos con margen reducido. El autoencuadre usa las dimensiones reales del plano, sin un tamaño mínimo artificial; un eje sin extensión no reduce el zoom. Las modificaciones geométricas no reencuadran automáticamente la vista; se puede usar “Ajustar plano” manualmente. Al importar un DXF se encuadra el plano importado.
- Las etiquetas de muros horizontales se dibujan horizontalmente; las de muros verticales se giran 90° para leerse de derecha a izquierda a lo largo del muro. Las etiquetas diagonales se mantienen paralelas al muro.
- Las notificaciones BLE recibidas permanecen ocultas hasta pulsar “Mostrar mediciones”; el botón indica cuántas hay y permite desplegar/ocultar detalle crudo y lectura interpretada.
- El botón “Deshacer” revierte la última acción geométrica: agregar un muro (a mano o desde BLE), editar sus propiedades, mover un extremo/unión o eliminarlo. Se desactiva cuando no hay acciones que deshacer; no afecta BLE ni las notificaciones.
- El lienzo de la pantalla principal ocupa el espacio flexible entre cabecera, mediciones y barra inferior; fullscreen mantiene su viewport compartido. «Ajustar plano» está como botón flotante dentro del plano; «Deshacer» se ubica en la barra inferior. Al soltar una línea a 40 dp o menos en pantalla de otro extremo existente, se pregunta si se desea conectar: aceptar ajusta el extremo exactamente al punto; rechazar conserva el extremo dibujado.
- Las uniones de segmentos se marcan en el lienzo con puntos amarillos de diámetro igual al grosor normal de las líneas. Son solo decoración de pantalla: no alteran los segmentos ni se incluyen como geometría DXF.
- Cada segmento puede tener ancho en mm y lado de crecimiento izquierda/derecha relativo al sentido desde inicio a fin; en ejes la UI indica el sentido X/Y resultante. Ancho 0 conserva la representación de línea. Con ancho positivo el lienzo representa un rectángulo cuyo borde es la línea original, con los dos bordes longitudinales y las líneas de cierre en ambos extremos. El ancho se puede ingresar o medir con M130.
- Para líneas ortogonales, el selector describe el crecimiento como arriba/abajo en horizontales y izquierda/derecha en verticales, invirtiendo correctamente la etiqueta según el sentido de la línea. En diagonales permanece izquierda/derecha relativo al sentido del muro.
- Las opciones de lado se ordenan visualmente de arriba a abajo o de izquierda a derecha según corresponda. Un muro nuevo usa por defecto el lado opuesto a su etiqueta, calculado según el sentido de la línea.
- En una unión exacta de extremos, la huella derivada de muro de mayor ancho se recorta contra la huella más estrecha para quitar el solape; un simple cruce sin extremos compartidos permanece intacto. Las longitudes y líneas medidas no se alteran. Etiquetas diagonales se centran en la mitad del muro; en segmentos cortos pasan a texto horizontal centrado.
- Se puede abrir un lienzo ampliado a pantalla completa y volver a la vista de edición conservando escala/centro. Durante un dibujo, una lupa desplazada del dedo aparece cuando este se acerca a un extremo conectable (40 dp medidos en pantalla, independientes del zoom); el zoom sigue la punta móvil de la línea y muestra el punto candidato.
- La identidad visual de la aplicación sigue `BRAND_GUIDELINES.md`: draftBeam, estilo Tokyo Night + Blueprint CAD, con fondos `#061326`/`#030B1C`, paneles UI `#111720`, superficies del editor `#101D3B`/`#142654`, azul principal `#3478F6`, cian láser `#61D5FF` y violeta `#B454F3`. Los tokens HEX de la guía son una propuesta visual aproximada. El plano conserva retícula discreta, muros azules y textura `ar-conc` naranja; al seleccionar un muro, la línea y sus manejadores resaltan en naranja. La línea provisional trazada con el dedo y su cota también se muestran naranjas durante el arrastre; al confirmarse, el muro usa el azul normal. La dirección activa se resalta en azul/cian y las demás quedan en estado secundario.
- Los botones X−/X+/Y+/Y− se ubican en los bordes izquierdo/derecho/superior/inferior del lienzo, con forma redondeada, contorno técnico azul, flechas y dirección activa destacada. Una cruz fija al centro muestra las coordenadas del centro de la vista en formato `x,y` igual que el origen; el marcador 0,0 sigue señalando el origen real cuando está visible.
- Las coordenadas junto a la cruz central se muestran en tamaño reducido. Las líneas de muro y ambos bordes son azules; solo la huella con ancho se rellena en naranja translúcido.
- El tema Blueprint CAD sobre Tokyo Night es fijo. Los controles de dirección se ubican en el centro de cada borde del lienzo, con margen hacia dentro y fuera de las esquinas para mantener despejada la zona central.
- La cuadrícula cartesiana y sus marcas de regla se recalculan durante el zoom con intervalos métricos legibles (serie 1–2–5 × 10ⁿ mm); las etiquetas usan metros desde 1 m, centímetros entre 1 cm y 1 m, y milímetros para divisiones menores.
- Guardar DXF abre el selector Android empezando en Descargas; cargar DXF abre el selector de documentos y reemplaza el plano actual con confirmación y opción Deshacer.
- El DXF generado usa unidades mm (`$INSUNITS=4`) y entidades estándar `LINE`, `LWPOLYLINE` y `HATCH`: líneas medidas azules en capas `FDXL_AXIS_nnnn`, y contornos/hatch naranja `ar-conc` escala 5 en capas `FDXL_WALL_nnnn`. Las capas de ejes permanecen visibles y separadas de las de muro para conservar la reconstrucción al importar. El ancho es geometría real, no grosor visual de línea; las huellas conectadas usan el recorte geométrico del editor. La app representa una textura de áridos equivalente.
- El encabezado/estilo DXF usa los valores observados en `prueba.dxf`: unidades mm, estilo tipográfico `unicode`, texto de cota 100 mm, flechas 50 mm, hueco 50 mm, extensión 50/100 mm, escala/factor 1, cero decimales y texto aligned paralelo a la línea.
- La exportación DXF incluye una entidad estándar `DIMENSION` tipo aligned por cada muro, en capa `FDXL_DIMS`, desplazada fuera de su ancho y mostrando su longitud real. La app al importar conserva líneas/muros y avisa/omite las cotas DXF, que no forman parte del modelo editable actual.
- El importador reconoce las capas FDXL para reconstruir muros con ancho/lado y admite líneas y polilíneas DXF comunes como geometría editable. Convierte unidades DXF habituales a mm; informa cuando omite entidades no compatibles. No conserva entidades DXF avanzadas (arcos, splines, bloques) ni es un editor DXF general.
- Una vista vacía inicia en formato cartesiano centrado en (0,0), con cuadrícula de 1 m y encuadre inicial de −5 a +5 m por eje. No se muestra cuadrado de referencia ni cotas; la cuadrícula permanece durante la edición.
- En diagonales, el rótulo se normaliza para leerse derecho aunque se invierta el sentido de dibujo, y se desplaza al lado opuesto del ancho del muro. Las cotas del plano muestran el valor numérico sin sufijo `mm`; las diagonales conservan además el ángulo en grados. En el editor, largo y ancho comienzan vacíos, muestran sus valores actuales como referencia y conservan el valor actual si se deja un campo sin cambios.
- Todas las etiquetas de muro compensan la línea base del texto para mantener la misma separación visual respecto de la línea azul principal, al lado opuesto al crecimiento del ancho.
- Las etiquetas de longitud y su separación escalan con el zoom del viewport (altura base 130 mm, mínimo 5 dp; separación base 80 mm), también en líneas provisionales y en la lupa.
- La pantalla principal usa cabecera transparente y compacta con Abrir/Guardar DXF arriba; el nombre draftBeam tiene tamaño aumentado 30 %. El rótulo Plano/conteo, cuadrícula y Ampliar están como overlay sin tarjeta dentro del canvas dominante. El panel de última medición y la barra inferior mantienen sus fondos. La barra inferior ordena Nueva línea, Editar, Eliminar, Ancho, Agregar nota y Deshacer; Editar/Eliminar/Ancho requieren seleccionar un muro. Nueva línea abre una tarjeta con largo, ancho, dirección y lado de crecimiento, y crea desde el punto activo. Agregar nota solicita una línea de texto (máximo 255 caracteres), luego la coloca al tocar el plano; las notas se seleccionan tocando su rótulo y admiten edición, eliminación y Deshacer. Se guardan y recuperan como entidades DXF `TEXT` en `FDXL_NOTES`; el encuadre también incluye sus límites al importar o ajustar manualmente. «Capas» sigue visible/deshabilitado hasta implementar esa función.
- El editor de muro es una hoja inferior superpuesta que mantiene visible el plano. Presenta largo/ancho/ángulo en una fila; la siguiente fila ofrece solo los dos sentidos perpendiculares aplicables (arriba/abajo para horizontales, izquierda/derecha para verticales; diagonales usan izquierda/derecha); la última fila ofrece Eliminar muro, Cancelar y Guardar.
- El menú desplegable, el panel de mediciones BLE y la barra de acciones inferiores usan el fondo `#111720`; encabezado y overlay del resumen Plano son transparentes. La hoja de edición de muro mantiene su superficie independiente.
- Al editar un muro, si «Largo (mm)» tiene el foco, la siguiente lectura M130 válida reemplaza el contenido y se guarda automáticamente en ese muro, conservando ancho y lado actuales y cerrando la hoja; no agrega otro muro. Los campos conservan estilos tipográficos explícitos al cambiar el foco.
- Tocar el cuerpo de un segmento abre la hoja para modificar largo, ancho y lado manualmente; las lecturas M130 válidas agregan muros nuevos, no reemplazan muros existentes.
- Editar o borrar un segmento solo cambia esa entidad; otras líneas conservan sus coordenadas.
- Los segmentos viven en memoria durante la sesión; guardar un proyecto nativo con estado adicional sigue pendiente. La exportación/importación DXF está implementada, pero aún debe probarse en un dispositivo y abrirse en LibreCAD para confirmar visualmente la compatibilidad.

## Restricciones y seguridad

- No depender de ZHouse o JoyPlan como parte del producto; la app propia debe comunicarse directamente con el dispositivo.
- La investigación está limitada a entender la comunicación con el dispositivo. No intentar desbloquear funciones premium.
- No redistribuir APK propietario ni código reconstruido.
- No publicar direcciones MAC, capturas o logs que puedan contener datos personales.
- Conservar los bytes originales de las notificaciones para facilitar verificación y diagnóstico.
- La etapa actual usa una gramática estricta para la medición observada (`dígitos.dígitos-de-tres-cifras m CR LF NUL`); formatos distintos se conservan como payload crudo y no se convierten.

## Fuentes y estándares

- Fuente de requisitos e investigación: notas README enlazadas arriba.
- Proceso y colaboración: ADF; véase `AGENTS.md`.
- Actualizar este documento cuando cambien el alcance estable, la arquitectura, la tecnología o los hallazgos confirmados. El trabajo activo va en `SESSION.md`.
