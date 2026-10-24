# Artemisa API: backend MVP

Este paquete agrega las rutas de operación que faltaban sobre las migraciones V1–V5 del proyecto.
El backend sigue como monolito modular con PostgreSQL, Spring MVC, Spring Security y Flyway.

## Alcance entregado

- Invitación de personal a una sucursal con token aleatorio de un solo uso y expiración de 48 horas.
- Grupos, capacidad, rango de edad y asignación de maestras/asistentes a grupos.
- Matrículas `PENDING`, `ACTIVE`, `COMPLETED` y `CANCELLED`, con transiciones y control concurrente de capacidad.
- Paquetes versionados, asignación histórica por alumno y cálculo de tiempo extra con `BigDecimal`.
- Calendario semanal por sucursal y excepciones de cierre, feriado y apertura especial.
- Entrada/salida con hora del servidor, zona horaria de la sucursal, `Idempotency-Key` y correcciones append-only con motivo.
- Exportación mensual asíncrona: un libro `.xlsx` por alumno, ZIP con manifiesto SHA-256 y estado consultable.
- Auditoría mínima de altas, asignaciones y correcciones de los flujos nuevos.
- Pruebas de integración de matrícula, invitación, alcance por grupo, asistencia e idempotencia; pruebas unitarias del redondeo y del libro Excel.

## Migraciones nuevas

Flyway aplicará automáticamente al arrancar:

| Versión | Cambio |
|---|---|
| V6 | Personal por invitación, asignación de personal a grupos, paquetes, asignaciones, calendario y auditoría. |
| V7 | Eventos de entrada/salida y correcciones append-only. |
| V8 | Versiones de plantilla interna y trabajos de exportación con idempotencia. |

No edites migraciones V1–V5 que ya estén instaladas. Si alguna de estas nuevas migraciones ya se ejecutó en otro entorno, cualquier ajuste posterior debe ir en V9 o superior.

## Rutas principales

Las rutas protegidas requieren `Authorization: Bearer <accessToken>`. El `tenantId` siempre se obtiene del JWT. Las rutas administrativas permiten `TENANT_ADMIN` o `BRANCH_DIRECTOR` asignado a la sucursal. Maestras/asistentes requieren asignación al grupo correspondiente.

| Método | Ruta | Uso |
|---|---|---|
| `POST` | `/api/v1/branches/{branchId}/staff-invitations` | Invitar directora, maestra o asistente. |
| `POST` | `/api/v1/staff-invitations/accept` | Aceptar una invitación y crear la cuenta/membresía. |
| `POST`, `GET` | `/api/v1/branches/{branchId}/groups` | Crear y consultar grupos visibles. |
| `PUT` | `/api/v1/branches/{branchId}/groups/{groupId}` | Cambiar grupo sin reducir capacidad bajo la matrícula actual. |
| `POST`, `DELETE` | `/api/v1/branches/{branchId}/groups/{groupId}/staff[/{membershipId}]` | Asignar o retirar personal. |
| `GET` | `/api/v1/branches/{branchId}/groups/{groupId}/students` | Consultar la lista de alumnos activos del grupo. |
| `POST`, `GET` | `/api/v1/branches/{branchId}/groups/{groupId}/enrollments` | Crear o consultar matrículas. |
| `PATCH` | `/api/v1/branches/{branchId}/groups/{groupId}/enrollments/{enrollmentId}/status` | Cambiar el estado con transiciones permitidas. |
| `POST`, `GET` | `/api/v1/branches/{branchId}/packages` | Crear una nueva versión o consultar paquetes de la sucursal. |
| `POST`, `GET` | `/api/v1/branches/{branchId}/students/{studentId}/packages` | Asignar o consultar el historial de paquetes del alumno. |
| `GET`, `PUT` | `/api/v1/branches/{branchId}/calendar/settings` | Consultar/configurar días habituales de servicio. |
| `GET`, `PUT` | `/api/v1/branches/{branchId}/calendar[/{date}]` | Consultar rango de calendario o registrar una excepción. |
| `POST` | `/api/v1/branches/{branchId}/attendance/check-ins` | Registrar entrada; requiere `Idempotency-Key`. |
| `POST` | `/api/v1/branches/{branchId}/attendance/check-outs` | Registrar salida; requiere entrada anterior y clave idempotente. |
| `GET` | `/api/v1/branches/{branchId}/attendance?groupId=...&date=YYYY-MM-DD` | Consultar lista diaria del grupo. |
| `POST` | `/api/v1/branches/{branchId}/attendance/events/{eventId}/corrections` | Corregir evento con motivo; conserva el valor original. |
| `POST` | `/api/v1/branches/{branchId}/exports/attendance` | Crear trabajo mensual; requiere `Idempotency-Key`. |
| `GET` | `/api/v1/export-jobs/{jobId}` | Consultar estado y checksum del trabajo. |
| `GET` | `/api/v1/export-jobs/{jobId}/file` | Descargar el ZIP una vez finalizado. |

## Flujo de validación

1. Arranca PostgreSQL: `sudo docker compose up -d postgres`.
2. Desde la raíz del proyecto exporta las variables locales y ejecuta pruebas:

   ```bash
   set -a
   source .env
   set +a
   ./mvnw test
   ```

3. Verifica Flyway:

   ```bash
   sudo docker compose exec postgres sh -c \
     'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;"'
   ```

4. Arranca la API con el mismo entorno:

   ```bash
   set -a
   source .env
   set +a
   ./mvnw spring-boot:run
   ```

La API almacena los ZIP fuera de los recursos web, en `REPORT_STORAGE_DIRECTORY` o `./var/reports` por omisión. La ruta `/var/reports/` está ignorada por Git.

## Reglas que se deben probar con Postman

- Una matrícula `PENDING` reserva capacidad igual que una `ACTIVE`.
- Un alumno no puede tener otra matrícula activa o pendiente al mismo tiempo.
- La edad se calcula a la fecha de inicio de matrícula y debe estar dentro del rango del grupo.
- La maestra solo puede consultar/operar grupos presentes en el claim `group_ids` del token.
- Un check-out sin check-in devuelve `409`; repetir la misma clave idempotente devuelve el mismo evento.
- Una corrección requiere rol administrador/directora y un motivo no vacío. No puede cambiar el día local ni dejar la salida antes de la entrada.
- El calendario habitual usa una máscara semanal (lunes en bit 0 hasta domingo en bit 6). Los días festivos/cierres deben capturarse explícitamente por sucursal; no se infieren como feriados oficiales.
- El tiempo extra es `max(0, estancia - minutos_contratados - tolerancia)`. Cada fracción de hora se cobra como hora completa. Confirma esta regla con las directoras antes de aplicarla a cargos.

## Puertas pendientes antes de llamarlo listo para producción

El documento de arquitectura exige que el archivo mensual coincida con la plantilla oficial aprobada. Este proyecto no traía esa plantilla ni ejemplos aceptados/rechazados; por eso la exportación incluida usa un formato interno versionado `ARTEMISA_ATTENDANCE_XLSX_V1`. No debe presentarse como formato oficial. Al recibir la plantilla vigente y casos de prueba, se implementa el mapeo como una nueva versión inmutable y se añaden pruebas de regresión del archivo real.

También faltan servicios de entrega de invitaciones por correo, almacenamiento privado S3, cola durable/outbox, reintento de trabajos fallidos, MFA, política de retención/consentimiento y un simulacro de restauración. Los ZIP actuales se guardan en disco local y el escaneo de trabajos pendientes es adecuado para desarrollo/piloto de una sola instancia, no para varias réplicas. Pagos en línea, CFDI, gastos y portal de tutores pertenecen a fases posteriores del documento, salvo que el cliente los confirme como requisito del MVP.

Antes de pilotear con datos reales de menores, valida el formato de exportación con la institución, configura almacenamiento y correo privados, revisa permisos y privacidad, y prueba recuperación de base y archivos.
