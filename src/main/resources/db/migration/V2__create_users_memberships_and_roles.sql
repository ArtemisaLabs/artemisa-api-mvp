CREATE TABLE app_users
(
    id            UUID PRIMARY KEY,
    email         VARCHAR(320) NOT NULL,
    full_name     VARCHAR(160) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_app_users_status
        CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE UNIQUE INDEX uq_app_users_email_lower
    ON app_users (lower(email));

CREATE TABLE tenant_memberships
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    app_user_id UUID        NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'INVITED',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_membership_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_membership_user
        FOREIGN KEY (app_user_id) REFERENCES app_users (id),
    CONSTRAINT uq_membership_tenant_user
        UNIQUE (tenant_id, app_user_id),
    CONSTRAINT uq_membership_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT ck_membership_status
        CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'REVOKED'))
);

CREATE INDEX ix_tenant_memberships_user_status
    ON tenant_memberships (app_user_id, status);

CREATE TABLE roles
(
    code        VARCHAR(40) PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    scope       VARCHAR(16)  NOT NULL,
    description VARCHAR(300) NOT NULL,
    system_role BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_roles_scope
        CHECK (scope IN ('TENANT', 'BRANCH'))
);

INSERT INTO roles (code, name, scope, description)
VALUES ('TENANT_ADMIN', 'Administrador de institución', 'TENANT',
        'Administra la institución y sus sucursales'),
       ('BRANCH_DIRECTOR', 'Directora de sucursal', 'BRANCH',
        'Administra una sucursal'),
       ('TEACHER', 'Maestra', 'BRANCH',
        'Registra asistencia y actividades del grupo asignado'),
       ('ASSISTANT', 'Asistente', 'BRANCH',
        'Apoya las actividades de una sucursal'),
       ('GUARDIAN', 'Tutor', 'TENANT',
        'Accede únicamente a los niños vinculados con su perfil');

CREATE TABLE membership_role_assignments
(
    id                   UUID PRIMARY KEY,
    tenant_id            UUID        NOT NULL,
    tenant_membership_id UUID        NOT NULL,
    role_code            VARCHAR(40) NOT NULL,
    branch_id            UUID,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_role_assignment_membership
        FOREIGN KEY (tenant_id, tenant_membership_id)
            REFERENCES tenant_memberships (tenant_id, id)
            ON DELETE CASCADE,
    CONSTRAINT fk_role_assignment_role
        FOREIGN KEY (role_code) REFERENCES roles (code),
    CONSTRAINT fk_role_assignment_branch
        FOREIGN KEY (tenant_id, branch_id)
            REFERENCES branches (tenant_id, id),
    CONSTRAINT ck_role_assignment_scope
        CHECK (
            (role_code IN ('TENANT_ADMIN', 'GUARDIAN') AND branch_id IS NULL)
                OR
            (role_code IN ('BRANCH_DIRECTOR', 'TEACHER', 'ASSISTANT')
                AND branch_id IS NOT NULL)
            )
);

CREATE UNIQUE INDEX uq_membership_tenant_role
    ON membership_role_assignments (tenant_membership_id, role_code)
    WHERE branch_id IS NULL;

CREATE UNIQUE INDEX uq_membership_branch_role
    ON membership_role_assignments
        (tenant_membership_id, role_code, branch_id)
    WHERE branch_id IS NOT NULL;