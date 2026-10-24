ALTER TABLE branches
    ADD COLUMN calendar_business_days_mask SMALLINT NOT NULL DEFAULT 31,
    ADD CONSTRAINT chk_branches_calendar_business_days
        CHECK (calendar_business_days_mask BETWEEN 0 AND 127);

CREATE TABLE staff_invitations
(
    id                       UUID PRIMARY KEY,
    tenant_id                UUID         NOT NULL,
    branch_id                UUID         NOT NULL,
    email                    VARCHAR(320) NOT NULL,
    full_name                VARCHAR(160) NOT NULL,
    role_code                VARCHAR(40)  NOT NULL,
    token_hash               CHAR(64)     NOT NULL,
    invited_by_membership_id UUID         NOT NULL,
    expires_at               TIMESTAMPTZ  NOT NULL,
    accepted_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_staff_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_staff_invitations_branch
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    CONSTRAINT fk_staff_invitations_role
        FOREIGN KEY (role_code) REFERENCES roles (code),
    CONSTRAINT fk_staff_invitations_inviter
        FOREIGN KEY (tenant_id, invited_by_membership_id)
            REFERENCES tenant_memberships (tenant_id, id),
    CONSTRAINT chk_staff_invitations_branch_role
        CHECK (role_code IN ('BRANCH_DIRECTOR', 'TEACHER', 'ASSISTANT')),
    CONSTRAINT chk_staff_invitations_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_staff_invitations_open
    ON staff_invitations (tenant_id, branch_id, lower(email), expires_at)
    WHERE accepted_at IS NULL;

CREATE TABLE branch_calendar_settings_history
(
    id                       UUID PRIMARY KEY,
    tenant_id                UUID        NOT NULL,
    branch_id                UUID        NOT NULL,
    previous_business_days_mask SMALLINT  NOT NULL,
    new_business_days_mask   SMALLINT    NOT NULL,
    changed_by_membership_id UUID        NOT NULL,
    changed_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_branch_calendar_history_branch
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    CONSTRAINT fk_branch_calendar_history_editor
        FOREIGN KEY (tenant_id, changed_by_membership_id)
            REFERENCES tenant_memberships (tenant_id, id),
    CONSTRAINT chk_branch_calendar_history_masks
        CHECK (previous_business_days_mask BETWEEN 0 AND 127
            AND new_business_days_mask BETWEEN 0 AND 127)
);

CREATE INDEX idx_branch_calendar_history
    ON branch_calendar_settings_history (tenant_id, branch_id, changed_at DESC);

CREATE TABLE class_group_staff_assignments
(
    tenant_id                UUID        NOT NULL,
    branch_id                UUID        NOT NULL,
    class_group_id            UUID        NOT NULL,
    tenant_membership_id      UUID        NOT NULL,
    assigned_by_membership_id UUID        NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_class_group_staff_assignments
        PRIMARY KEY (tenant_id, branch_id, class_group_id, tenant_membership_id),
    CONSTRAINT fk_group_staff_group
        FOREIGN KEY (tenant_id, branch_id, class_group_id)
            REFERENCES class_groups (tenant_id, branch_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_group_staff_membership
        FOREIGN KEY (tenant_id, tenant_membership_id)
            REFERENCES tenant_memberships (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_group_staff_assigner
        FOREIGN KEY (tenant_id, assigned_by_membership_id)
            REFERENCES tenant_memberships (tenant_id, id)
);

CREATE INDEX idx_group_staff_membership
    ON class_group_staff_assignments (tenant_id, tenant_membership_id, branch_id);

CREATE TABLE class_group_change_history
(
    id                       UUID PRIMARY KEY,
    tenant_id                UUID        NOT NULL,
    branch_id                UUID        NOT NULL,
    class_group_id           UUID        NOT NULL,
    previous_name            VARCHAR(100) NOT NULL,
    new_name                 VARCHAR(100) NOT NULL,
    previous_capacity        INTEGER     NOT NULL,
    new_capacity             INTEGER     NOT NULL,
    previous_min_age_months INTEGER,
    new_min_age_months      INTEGER,
    previous_max_age_months INTEGER,
    new_max_age_months      INTEGER,
    previous_status          VARCHAR(20) NOT NULL,
    new_status               VARCHAR(20) NOT NULL,
    changed_by_membership_id UUID        NOT NULL,
    changed_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_group_history_group
        FOREIGN KEY (tenant_id, branch_id, class_group_id)
            REFERENCES class_groups (tenant_id, branch_id, id),
    CONSTRAINT fk_group_history_editor
        FOREIGN KEY (tenant_id, changed_by_membership_id)
            REFERENCES tenant_memberships (tenant_id, id)
);

CREATE INDEX idx_group_history_latest
    ON class_group_change_history (tenant_id, branch_id, class_group_id, changed_at DESC);

CREATE TABLE enrollment_status_history
(
    id                       UUID PRIMARY KEY,
    tenant_id                UUID         NOT NULL,
    branch_id                UUID         NOT NULL,
    enrollment_id            UUID         NOT NULL,
    previous_status          VARCHAR(20),
    new_status               VARCHAR(20)  NOT NULL,
    previous_end_date        DATE,
    new_end_date             DATE,
    reason                   VARCHAR(500),
    changed_by_membership_id UUID         NOT NULL,
    changed_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_enrollment_history_enrollment
        FOREIGN KEY (tenant_id, enrollment_id)
            REFERENCES enrollments (tenant_id, id),
    CONSTRAINT fk_enrollment_history_branch
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    CONSTRAINT fk_enrollment_history_editor
        FOREIGN KEY (tenant_id, changed_by_membership_id)
            REFERENCES tenant_memberships (tenant_id, id),
    CONSTRAINT chk_enrollment_history_status
        CHECK (new_status IN ('PENDING', 'ACTIVE', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX idx_enrollment_history_latest
    ON enrollment_status_history (tenant_id, enrollment_id, changed_at DESC);

CREATE TABLE schedule_packages
(
    id                         UUID PRIMARY KEY,
    tenant_id                  UUID          NOT NULL,
    branch_id                  UUID          NOT NULL,
    package_code               VARCHAR(40)   NOT NULL,
    version                    INTEGER       NOT NULL,
    name                       VARCHAR(120)  NOT NULL,
    contracted_minutes_per_day INTEGER       NOT NULL,
    overtime_tolerance_minutes INTEGER       NOT NULL DEFAULT 0,
    overtime_hourly_rate       NUMERIC(12,2) NOT NULL,
    currency                   CHAR(3)       NOT NULL,
    effective_from             DATE          NOT NULL,
    effective_to               DATE,
    status                     VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id   UUID          NOT NULL,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_schedule_packages_tenant_branch_id
        UNIQUE (tenant_id, branch_id, id),
    CONSTRAINT uq_schedule_packages_version
        UNIQUE (tenant_id, branch_id, package_code, version),
    CONSTRAINT fk_schedule_packages_branch
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    CONSTRAINT fk_schedule_packages_creator
        FOREIGN KEY (tenant_id, created_by_membership_id)
            REFERENCES tenant_memberships (tenant_id, id),
    CONSTRAINT chk_schedule_packages_version CHECK (version > 0),
    CONSTRAINT chk_schedule_packages_minutes
        CHECK (contracted_minutes_per_day > 0 AND overtime_tolerance_minutes >= 0),
    CONSTRAINT chk_schedule_packages_rate CHECK (overtime_hourly_rate >= 0),
    CONSTRAINT chk_schedule_packages_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_schedule_packages_dates
        CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT chk_schedule_packages_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_schedule_packages_branch_status
    ON schedule_packages (tenant_id, branch_id, status, effective_from DESC);

CREATE TABLE student_package_assignments
(
    id                       UUID PRIMARY KEY,
    tenant_id                UUID        NOT NULL,
    branch_id                UUID        NOT NULL,
    student_id               UUID        NOT NULL,
    schedule_package_id      UUID        NOT NULL,
    effective_from           DATE        NOT NULL,
    effective_to             DATE,
    assigned_by_membership_id UUID       NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_student_package_assignments_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_student_package_student
        FOREIGN KEY (tenant_id, branch_id, student_id)
            REFERENCES students (tenant_id, branch_id, id),
    CONSTRAINT fk_student_package_package
        FOREIGN KEY (tenant_id, branch_id, schedule_package_id)
            REFERENCES schedule_packages (tenant_id, branch_id, id),
    CONSTRAINT fk_student_package_assigner
        FOREIGN KEY (tenant_id, assigned_by_membership_id)
            REFERENCES tenant_memberships (tenant_id, id),
    CONSTRAINT chk_student_package_dates
        CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE INDEX idx_student_package_effective
    ON student_package_assignments
        (tenant_id, branch_id, student_id, effective_from DESC, effective_to);

CREATE TABLE calendar_day_overrides
(
    id                       UUID PRIMARY KEY,
    tenant_id                UUID         NOT NULL,
    branch_id                UUID         NOT NULL,
    calendar_date            DATE         NOT NULL,
    day_type                 VARCHAR(20)  NOT NULL,
    is_business_day          BOOLEAN      NOT NULL,
    is_reportable             BOOLEAN      NOT NULL,
    name                     VARCHAR(160) NOT NULL,
    source                   VARCHAR(200) NOT NULL,
    updated_by_membership_id UUID         NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_calendar_day_overrides_date
        UNIQUE (tenant_id, branch_id, calendar_date),
    CONSTRAINT fk_calendar_day_overrides_branch
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    CONSTRAINT fk_calendar_day_overrides_editor
        FOREIGN KEY (tenant_id, updated_by_membership_id)
            REFERENCES tenant_memberships (tenant_id, id),
    CONSTRAINT chk_calendar_day_overrides_type
        CHECK (day_type IN ('HOLIDAY', 'CLOSURE', 'SPECIAL_OPENING')),
    CONSTRAINT chk_calendar_day_overrides_name CHECK (length(trim(name)) > 0),
    CONSTRAINT chk_calendar_day_overrides_source CHECK (length(trim(source)) > 0)
);

CREATE TABLE calendar_day_override_history
(
    id                       UUID PRIMARY KEY,
    tenant_id                UUID         NOT NULL,
    branch_id                UUID         NOT NULL,
    calendar_date            DATE         NOT NULL,
    previous_day_type        VARCHAR(20)  NOT NULL,
    previous_business_day    BOOLEAN      NOT NULL,
    previous_reportable      BOOLEAN      NOT NULL,
    previous_name            VARCHAR(160) NOT NULL,
    previous_source          VARCHAR(200) NOT NULL,
    changed_by_membership_id UUID         NOT NULL,
    changed_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_calendar_history_branch
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    CONSTRAINT fk_calendar_history_editor
        FOREIGN KEY (tenant_id, changed_by_membership_id)
            REFERENCES tenant_memberships (tenant_id, id)
);

CREATE INDEX idx_calendar_history_date
    ON calendar_day_override_history
        (tenant_id, branch_id, calendar_date, changed_at DESC);

CREATE TABLE audit_events
(
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    branch_id           UUID,
    actor_membership_id UUID,
    action              VARCHAR(80)  NOT NULL,
    entity_type         VARCHAR(80)  NOT NULL,
    entity_id           UUID         NOT NULL,
    details             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_audit_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_audit_branch
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    CONSTRAINT fk_audit_actor
        FOREIGN KEY (tenant_id, actor_membership_id)
            REFERENCES tenant_memberships (tenant_id, id)
);

CREATE INDEX idx_audit_events_entity
    ON audit_events (tenant_id, entity_type, entity_id, created_at DESC);

CREATE INDEX idx_audit_events_branch
    ON audit_events (tenant_id, branch_id, created_at DESC);
