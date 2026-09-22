create extension if not exists postgis;

create table dataset (
    id          uuid primary key,
    name        text,
    status      text        not null,
    file_size   bigint,
    created_at  timestamptz not null default now(),
    finished_at timestamptz,
    error       text,
    report      jsonb
);

-- Объекты входа. Геометрия в расчётной CRS (EPSG:32637), id хранится как в файле вместе с его типом.
create table feature (
    id                bigserial primary key,
    dataset_id        uuid    not null references dataset (id) on delete cascade,
    src_id            text    not null,
    src_id_is_number  boolean not null,
    object_type       text    not null,
    restriction_type  text,
    props             jsonb   not null,
    geom              geometry(Geometry, 32637) not null
);

create index feature_dataset_type_idx on feature (dataset_id, object_type, restriction_type);
create index feature_geom_idx on feature using gist (geom);
