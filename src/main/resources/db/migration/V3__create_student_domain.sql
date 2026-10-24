CREATE TABLE students
(
    id             UUID PRIMARY KEY,
    tenant_id      UUID         NOT NULL,
    branch_id      UUID         NOT NULL,
    student_number VARCHAR(40),
    first_name     VARCHAR(100) NOT NULL,
    last_name      VARCHAR(100) NOT NULL,
    date_of_birth  DATE         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_students_tenant_id
        UNIQUE (tenant_id, id),

    CONSTRAINT uq_students_tenant_branch_id
        UNIQUE (tenant_id, branch_id, id),

    CONSTRAINT uq_students_branch_number
        UNIQUE (tenant_id, branch_id, student_number),

    CONSTRAINT fk_students_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),

    CONSTRAINT fk_students_branch
        FOREIGN KEY (tenant_id, branch_id)
            REFERENCES branches (tenant_id, id),

    CONSTRAINT chk_students_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'WITHDRAWN'))
);

CREATE INDEX idx_students_branch_status
    ON students (tenant_id, branch_id, status);


CREATE TABLE guardians
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    app_user_id UUID,
    first_name  VARCHAR(100) NOT NULL,
    last_name   VARCHAR(100) NOT NULL,
    email       VARCHAR(320),
    phone       VARCHAR(40),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_guardians_tenant_id
        UNIQUE (tenant_id, id),

    CONSTRAINT uq_guardians_tenant_app_user
        UNIQUE (tenant_id, app_user_id),

    CONSTRAINT fk_guardians_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),

    CONSTRAINT fk_guardians_app_user
        FOREIGN KEY (app_user_id) REFERENCES app_users (id),

    CONSTRAINT chk_guardians_status
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_guardians_tenant_email
    ON guardians (tenant_id, email);


CREATE TABLE student_guardians
(
    tenant_id          UUID        NOT NULL,
    student_id         UUID        NOT NULL,
    guardian_id        UUID        NOT NULL,
    relationship       VARCHAR(40) NOT NULL,
    is_primary_contact BOOLEAN     NOT NULL DEFAULT FALSE,
    can_pick_up        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_student_guardians
        PRIMARY KEY (tenant_id, student_id, guardian_id),

    CONSTRAINT fk_student_guardians_student
        FOREIGN KEY (tenant_id, student_id)
            REFERENCES students (tenant_id, id),

    CONSTRAINT fk_student_guardians_guardian
        FOREIGN KEY (tenant_id, guardian_id)
            REFERENCES guardians (tenant_id, id)
);

CREATE UNIQUE INDEX uq_student_guardians_primary_contact
    ON student_guardians (tenant_id, student_id) WHERE is_primary_contact = TRUE;

CREATE INDEX idx_student_guardians_guardian
    ON student_guardians (tenant_id, guardian_id);


CREATE TABLE class_groups
(
    id             UUID PRIMARY KEY,
    tenant_id      UUID         NOT NULL,
    branch_id      UUID         NOT NULL,
    name           VARCHAR(100) NOT NULL,
    capacity       INTEGER      NOT NULL,
    min_age_months INTEGER,
    max_age_months INTEGER,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_class_groups_tenant_branch_id
        UNIQUE (tenant_id, branch_id, id),

    CONSTRAINT uq_class_groups_branch_name
        UNIQUE (tenant_id, branch_id, name),

    CONSTRAINT fk_class_groups_branch
        FOREIGN KEY (tenant_id, branch_id)
            REFERENCES branches (tenant_id, id),

    CONSTRAINT chk_class_groups_capacity
        CHECK (capacity > 0),

    CONSTRAINT chk_class_groups_age_range
        CHECK (
            min_age_months IS NULL
                OR max_age_months IS NULL
                OR min_age_months <= max_age_months
            ),

    CONSTRAINT chk_class_groups_status
        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_class_groups_branch_status
    ON class_groups (tenant_id, branch_id, status);


CREATE TABLE enrollments
(
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    branch_id      UUID        NOT NULL,
    student_id     UUID        NOT NULL,
    class_group_id UUID        NOT NULL,
    start_date     DATE        NOT NULL,
    end_date       DATE,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_enrollments_tenant_id
        UNIQUE (tenant_id, id),

    CONSTRAINT fk_enrollments_student_branch
        FOREIGN KEY (tenant_id, branch_id, student_id)
            REFERENCES students (tenant_id, branch_id, id),

    CONSTRAINT fk_enrollments_group_branch
        FOREIGN KEY (tenant_id, branch_id, class_group_id)
            REFERENCES class_groups (tenant_id, branch_id, id),

    CONSTRAINT chk_enrollments_dates
        CHECK (end_date IS NULL OR end_date >= start_date),

    CONSTRAINT chk_enrollments_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'COMPLETED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uq_enrollments_one_active_per_student
    ON enrollments (tenant_id, student_id) WHERE status = 'ACTIVE';

CREATE INDEX idx_enrollments_group_status
    ON enrollments (tenant_id, branch_id, class_group_id, status);

CREATE INDEX idx_enrollments_student_dates
    ON enrollments (tenant_id, student_id, start_date DESC);