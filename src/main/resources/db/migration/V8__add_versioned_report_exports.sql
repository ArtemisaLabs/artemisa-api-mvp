CREATE TABLE report_template_versions
(
    id               UUID PRIMARY KEY,
    tenant_id        UUID         NOT NULL,
    template_code    VARCHAR(80)  NOT NULL,
    version          INTEGER      NOT NULL,
    source_type      VARCHAR(20)  NOT NULL,
    file_name        VARCHAR(200) NOT NULL,
    sha256           CHAR(64)     NOT NULL,
    storage_key      VARCHAR(500),
    active           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by_membership_id UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_report_template_version
        UNIQUE (tenant_id, template_code, version),
    CONSTRAINT uq_report_template_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT fk_report_template_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_report_template_creator
        FOREIGN KEY (tenant_id, created_by_membership_id)
            REFERENCES tenant_memberships (tenant_id, id),
    CONSTRAINT chk_report_template_version CHECK (version > 0),
    CONSTRAINT chk_report_template_source CHECK (source_type IN ('INTERNAL', 'OFFICIAL'))
);

CREATE UNIQUE INDEX uq_report_template_active
    ON report_template_versions (tenant_id, template_code)
    WHERE active = TRUE;

CREATE TABLE export_jobs
(
    id                  UUID PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    branch_id           UUID         NOT NULL,
    report_month        DATE         NOT NULL,
    template_version_id UUID        NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    requested_by_membership_id UUID  NOT NULL,
    idempotency_key    VARCHAR(128) NOT NULL,
    storage_key         VARCHAR(500),
    sha256              CHAR(64),
    file_size_bytes     BIGINT,
    student_count       INTEGER,
    failure_code        VARCHAR(80),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,

    CONSTRAINT uq_export_jobs_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_export_jobs_idempotency
        UNIQUE (tenant_id, branch_id, idempotency_key),
    CONSTRAINT fk_export_jobs_branch
        FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    CONSTRAINT fk_export_jobs_template
        FOREIGN KEY (tenant_id, template_version_id)
            REFERENCES report_template_versions (tenant_id, id),
    CONSTRAINT fk_export_jobs_requester
        FOREIGN KEY (tenant_id, requested_by_membership_id)
            REFERENCES tenant_memberships (tenant_id, id),
    CONSTRAINT chk_export_jobs_month
        CHECK (EXTRACT(DAY FROM report_month) = 1),
    CONSTRAINT chk_export_jobs_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_export_jobs_file_size
        CHECK (file_size_bytes IS NULL OR file_size_bytes >= 0)
);

CREATE INDEX idx_export_jobs_tenant_created
    ON export_jobs (tenant_id, created_at DESC);
