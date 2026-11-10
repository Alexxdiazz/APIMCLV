<<<<<<< HEAD
# API — Mujeres Celebran la Vida

Esta versión usa únicamente las tablas del esquema definitivo PostgreSQL `mujeres_celebran_la_vida`. No ejecuta scripts de creación o modificación de tablas (`spring.sql.init.mode=never`) y Hibernate/JPA fue retirado: las consultas se realizan con JDBC contra las tablas existentes.

## Antes de ejecutarla

1. Hacé una copia de seguridad de la carpeta de la API anterior.
2. Abrí `src/main/resources/application.properties`.
3. Si tu usuario PostgreSQL tiene contraseña, definí la variable de entorno `DB_PASSWORD` con tu contraseña local. No la compartas ni la subas a GitHub. También se puede ajustar `DB_USERNAME` si tu usuario no es `postgres`.
4. Confirmá que PostgreSQL esté iniciado y que exista la base `mujeres_celebran_la_vida`.
5. Desde esta carpeta, en Windows ejecutá `mvn spring-boot:run` si Maven está en PATH. Si no está, usá la ruta de Maven instalado en tu PC, por ejemplo `& "C:\Users\Usuario\apache-maven-3.10.0\bin\mvn.cmd" spring-boot:run`. Requiere Java 17 o posterior e Internet la primera vez para descargar dependencias. El ZIP no incluye los componentes internos del Maven Wrapper, por eso no uses `mvnw.cmd` en esta copia.
6. Comprobá `http://localhost:8080/api/salud`. Debe devolver estado `ok`.

## Rutas

Se exponen CRUD (`GET`, `GET /{id}`, `POST`, `PUT /{id}`, `DELETE /{id}`) para los recursos que corresponden a las tablas finales:

- `/api/articulos` → `articulo` y lectura de cantidades desde `stock`
- `/api/stock` → `stock`
- `/api/categorias` → `categoria`
- `/api/ubicaciones` → `ubicacion`
- `/api/entidades` y `/api/terceros` → `tercero`
- `/api/usuarios` → `usuario`
- `/api/roles` y `/api/usuario_rol`
- `/api/donaciones` y `/api/detalle_donacion`
- `/api/entregas` y `/api/detalle_entrega`
- `/api/prestamos` y `/api/detalle_prestamo`
- `/api/devoluciones` y `/api/detalle_devolucion`
- `/api/reservas` y `/api/detalle_reserva`
- `/api/fotografias` → `fotografia`
- `/api/movimientos` y `/api/historial` → `movimiento`

También existe `POST /api/auth/login` para validar credenciales contra `usuario`. Este inicio de sesión es básico y no reemplaza una implementación de seguridad con contraseñas cifradas.

## Importante sobre el esquema

Las operaciones compuestas de donación, préstamo, entrega, reserva y devolución se gestionan con rutas de negocio transaccionales que actualizan los detalles, el stock y el historial. Las reservas pueden cancelarse o completarse como una entrega definitiva. El stock usa una fila por artículo y estado, de acuerdo con la restricción única del esquema. La API al iniciar intenta alinear las secuencias PostgreSQL con los IDs ya existentes para evitar colisiones después de insertar IDs manuales.

El tipo de tercero representa `PERSONA`, `FAMILIA`, `INSTITUCION` u `ORGANIZACION`; cualquiera de estos terceros puede actuar como donante o destinatario. La tabla actual no almacena email de terceros. Antes de usarla con datos reales, realizá una prueba de aceptación completa en una copia de la base de datos.
=======
# APIMCLV
>>>>>>> a26b97c4c4fda263b7c00b357f94d5fd27bb1af0
