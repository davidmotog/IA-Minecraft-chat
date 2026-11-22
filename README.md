# MinecraftIA

Plugin para **PaperMC 26.3 y Java 25** que responde preguntas en español desde el chat,
analiza imágenes enlazadas y muestra recetas registradas en el servidor.
No requiere mods en el cliente. Compilado contra `paper-api:26.3.build.257-stable`.

## Instalación

1. Compila el proyecto con Java 25 y Maven usando `mvn clean verify`.
   Con el servidor apagado, copia `target/MinecraftIA-1.2.0.jar` a su carpeta `plugins`.
2. Inicia Paper con Java 25. El plugin crea `plugins/MinecraftIA/config.yml`.
3. Crea `plugins/MinecraftIA/.env` con la línea `OPENAI_API_KEY=tu_clave`.
   Si guardas la clave en otra carpeta, configura esa ruta en `openai.env-file`.
   El archivo `.env` debe quedarse en el servidor.
   Si el archivo existe tiene prioridad, incluso si está vacío. Si ambos archivos
   faltan, se usa la variable de entorno OPENAI_API_KEY y después openai.api-key.
4. Ejecuta `ia recargar` desde la consola o `/ia recargar` como operador.
   Los cambios de variables de entorno requieren reiniciar el proceso del servidor.
5. Comprueba `/ia receta piston` y `/ia ¿Cómo encuentro diamantes?`.

`/ia estado` comprueba la configuración local; no realiza una petición de red ni
certifica que la clave tenga acceso o saldo. La primera pregunta verifica la conexión.

La clave se obtiene en [OpenAI Platform](https://platform.openai.com/api-keys).
La API puede generar costes: consulta la facturación de tu cuenta y los
[precios del proveedor](https://developers.openai.com/api/docs/pricing).
El modelo predeterminado es `gpt-5.4-mini`, configurable; el modelo elegido debe
aceptar imágenes y JSON Schema estricto mediante Responses API. El plugin no
necesita que compartas la clave con quien te lo instala: puedes editarla tú en el servidor.

## Uso

| Entrada | Resultado |
| --- | --- |
| `/ia ¿Cómo encuentro diamantes?` | Respuesta privada en español. |
| `@ia ¿Cómo consigo obsidiana?` | Invocación desde el chat, configurable. |
| `/ia receta pistón` | Abre la receta registrada, sin gastar API. |
| `/ia receta minecraft:diamond_pickaxe` | Busca directamente por identificador. |
| `/ia receta <nombre desconocido>` | Pide a la IA identificar el objeto y consulta su receta. |
| `/ia foto https://ejemplo.com/imagen.png ¿Cómo se fabrica?` | Analiza el enlace y ofrece un botón para consultar la receta si reconoce el objeto. |
| `/ia ayuda` | Ayuda y explicación del envío de datos. |
| `/ia estado` | Estado local de configuración, solo administrador. |
| `/ia recargar` | Recarga la configuración, solo administrador. |

El plugin mantiene en memoria hasta cuatro intercambios recientes por jugador.
Expiran después de 30 minutos sin actividad o al reiniciar Paper. Usa `/ia olvidar`
para iniciar otra conversación. Puedes desactivar esta función con `memory.enabled: false`.
El texto reciente se incluye en la siguiente consulta a OpenAI. Las fotos anteriores se
recuerdan por el texto de la respuesta y el objeto identificado.
Las respuestas se envían al jugador que preguntó. La invocación `@ia` cancela su
publicación en el chat normal. Otros plugins del servidor pueden registrar chat o comandos.
Las consultas de IA envían únicamente la pregunta, el enlace opcional y la versión
de Minecraft al proveedor, sin UUID, nombre del jugador ni datos del mundo.
Las recetas por identificador o alias local no hacen llamadas externas.

## Imágenes y recetas visuales

El chat vanilla no tiene un selector de archivos. Sube la imagen a un servicio que
te permita obtener un enlace HTTPS público y directo, y usa `/ia foto`.
El enlace debe funcionar sin iniciar sesión. No son válidas rutas locales, enlaces
HTTP ni direcciones de una red privada. No se puede comprobar desde aquí que el
proveedor logre descargar cada enlace: los enlaces caducados o páginas HTML dan error.
El servidor de Minecraft no descarga la imagen; OpenAI recibe el enlace y la procesa.

La salida visual de una receta es un **inventario de consulta con iconos del juego**,
no una fotografía generada ni un PNG adjunto en el chat. Muestra la cuadrícula de
3 × 3, resultado, alternativas de ingredientes y botones para cambiar entre recetas.
El visor no entrega objetos ni consume recursos. Bloquea clics, arrastres y atajos
de inventario mientras está abierto.

Se admiten recetas con forma, sin forma, horno, alto horno, ahumador, hoguera,
cortapiedras y transformaciones de herrería. Se leen del registro del servidor,
incluidas recetas compatibles de datapacks/plugins. Los identificadores de objetos
son los de Material; los objetos personalizados con el mismo material base no
se identifican por sus metadatos. El resultado de herrería muestra el objeto base
de la receta, sin simular los componentes que se heredan del equipo del jugador.
Recetas especiales dinámicas (por ejemplo adornos o teñido), pociones y comercio
no se representan en este visor. Si no hay receta compatible se indica explícitamente.
La IA puede confundir una imagen; el botón permite revisar qué objeto identificó.

Los nombres habituales en español están en `aliases` dentro de `config.yml`.
Se ignoran tildes y mayúsculas. Puedes añadir alias y ejecutar `/ia recargar`.
Los identificadores vanilla y alias locales funcionan sin IA.

## Permisos y límites

Cada instalación permite hasta **2.000.000 de tokens por día UTC** entre todos los jugadores.
El plugin reserva por adelantado la entrada, una cantidad conservadora para una foto y
la salida máxima. Al recibir el consumo de OpenAI ajusta el contador al total real.
Si la respuesta se pierde, conserva la reserva. Puede denegar una consulta cerca
del límite. Las fotos con este control admiten gpt-5.4-mini y su versión 2026-03-17.
El día corresponde al envío de la petición. Los archivos diarios se conservan
en `plugins/MinecraftIA/cuota/` y deben acompañar al plugin si migras el servidor.
El comando `/ia estado` muestra el total usado o reservado. Este contador pertenece
a esta instalación. Otros servicios o servidores con la misma clave consumen aparte.

| Permiso | Por defecto |
| --- | --- |
| `minecraftia.use` | Todos los jugadores: preguntas y recetas. |
| `minecraftia.image` | Todos los jugadores: consultas con fotos. |
| `minecraftia.admin` | Operadores: estado y recarga. |

La configuración limita por defecto las consultas a una cada 8 segundos por
jugador, una petición activa por jugador, 4 simultáneas en el servidor y 20 por
minuto en total. El límite por minuto se reinicia al reiniciar el plugin/servidor;
no es un presupuesto monetario. Ajusta los límites de gasto también en el proveedor.
Las recetas locales no consumen esa cuota. El tiempo de espera predeterminado
es 40 segundos. Las tareas de red se ejecutan fuera del hilo principal; toda
interacción con jugadores, recetas e inventarios vuelve al hilo del servidor.

No hay reintentos automáticos que puedan duplicar consumo. La petición usa
`store: false`; esto no sustituye las políticas de tratamiento de datos del proveedor.
No se imprime la clave ni el cuerpo de los errores de la API. Las respuestas se
renderizan como texto literal; el único comando de los botones es `/ia receta`
con un identificador validado y reconocido por el servidor.

## Compilar y comprobar

Requiere JDK 25 y Maven 3.9.11 o compatible:

```powershell
mvn --batch-mode --no-transfer-progress clean verify
```

En este equipo puedes usar `./compilar.ps1`, que detecta Java y Maven portátiles
en `.tools`. Esa carpeta no forma parte del ZIP de distribución. El resultado
se genera en `target/MinecraftIA-1.2.0.jar`. Gson va incluido y reubicado dentro del
JAR; no hay que instalar dependencias adicionales en el servidor.

Se han ejecutado **29 pruebas**, sin fallos, sobre memoria, lectura de claves, cuota diaria,
reservas simultáneas, persistencia, cambio de día, transporte HTTP con servidor
local simulado, creación de consultas con imagen y esquema JSON, respuestas
incompletas/rechazadas, errores del proveedor sin filtración de detalles, validación
de URL, control de concurrencia/cuotas y distribución de la cuadrícula de recetas.
En Paper 26.3 build 257 con Java 25 se conectaron dos clientes de prueba. Se comprobó
la privacidad del chat, una respuesta real de texto, el análisis de una foto por
enlace, el botón de receta, la navegación y los permisos. Una textura pequeña
dio una identificación incorrecta. Con la imagen ampliada se identificó el pico
de diamante y se abrió su receta. Se reprodujo el bloqueo de cuota en el servidor
de prueba con un contador de 2.000.000 y se comprobó la persistencia tras reiniciar.

Pruebas que puedes repetir en tu servidor:

1. `/plugins` incluye MinecraftIA y `/ia ayuda` responde.
2. `/ia receta piston` y `/ia receta diamond_pickaxe` muestran los patrones correctos.
3. Una receta con variantes muestra alternativas y navegación entre recetas.
4. Clic, shift-clic, números, doble clic y arrastre no permiten extraer los iconos.
5. Una pregunta y una imagen real responden con la clave configurada.
6. `@ia hola` no aparece a otros jugadores y los permisos restringidos se respetan.

## Referencias

- [Configuración de proyectos Paper 26.3](https://docs.papermc.io/paper/dev/project-setup/)
- [Java requerido por Paper](https://docs.papermc.io/paper/getting-started/)
- [Imágenes en la API de OpenAI](https://developers.openai.com/api/docs/guides/images-vision)
- [Modelo GPT-5.4 mini](https://developers.openai.com/api/docs/models/gpt-5.4-mini)
