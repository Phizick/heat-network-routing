-- Результаты расчётов: сводка (jsonb) и выход GeoJSON (текст, до 500 МБ по ТЗ)
create table solution (
    id          uuid primary key,
    dataset_id  uuid        not null references dataset (id) on delete cascade,
    mode        text        not null default '2d',
    status      text        not null,
    created_at  timestamptz not null default now(),
    finished_at timestamptz,
    error       text,
    summary     jsonb,
    result      text
);

create index solution_dataset_idx on solution (dataset_id, created_at desc);
