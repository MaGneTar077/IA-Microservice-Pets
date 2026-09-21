-- ============================================================
-- MyAnimaLog — IA-service
-- Ejecutar en Supabase: SQL Editor > New query > Run
--
-- Regla: NINGUNA foreign key hacia tablas de otros microservicios.
-- pet_id y user_id son uuid planos. La integridad la garantiza
-- el servicio dueño de esos datos, no esta base.
-- ============================================================


-- ------------------------------------------------------------
-- Hilos de conversación del asistente
-- ------------------------------------------------------------
create table if not exists public.ai_conversation (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid        not null,
    titulo     text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_ai_conversation_user
    on public.ai_conversation (user_id, updated_at desc);

comment on table public.ai_conversation is
    'Hilos de chat del asistente. user_id sin FK: pertenece a user-service.';


-- ------------------------------------------------------------
-- Turnos de la conversación (el historial que se manda a Gemini)
-- ------------------------------------------------------------
create table if not exists public.ai_message (
    id              uuid primary key default gen_random_uuid(),
    conversation_id uuid        not null
                    references public.ai_conversation (id) on delete cascade,
    rol             text        not null
                    check (rol in ('user', 'model', 'tool')),
    contenido       text,
    -- URLs en Supabase Storage de las imágenes, audios o PDFs del turno
    adjuntos        jsonb       not null default '[]'::jsonb,
    -- Solo para rol='tool'
    tool_name       text,
    tool_args       jsonb,
    tool_result     jsonb,
    created_at      timestamptz not null default now()
);

create index if not exists idx_ai_message_conversation
    on public.ai_message (conversation_id, created_at);

comment on column public.ai_message.adjuntos is
    'Array de URLs de Supabase Storage. Los binarios NO se guardan en Postgres.';


-- ------------------------------------------------------------
-- Acciones propuestas por la IA a la espera de confirmación
-- (tools con requiresConfirmation() == true)
-- ------------------------------------------------------------
create table if not exists public.ai_pending_action (
    id              uuid primary key default gen_random_uuid(),
    conversation_id uuid        not null
                    references public.ai_conversation (id) on delete cascade,
    user_id         uuid        not null,
    tool_name       text        not null,
    tool_args       jsonb       not null,
    estado          text        not null default 'PENDIENTE'
                    check (estado in ('PENDIENTE', 'CONFIRMADA', 'RECHAZADA', 'EXPIRADA')),
    created_at      timestamptz not null default now(),
    resolved_at     timestamptz,
    expires_at      timestamptz not null default (now() + interval '1 hour')
);

create index if not exists idx_ai_pending_action_pendientes
    on public.ai_pending_action (user_id, estado)
    where estado = 'PENDIENTE';


-- ------------------------------------------------------------
-- Sugerencias de cuidado generadas a partir de eventos
-- ------------------------------------------------------------
create table if not exists public.ai_suggestion (
    id         uuid primary key default gen_random_uuid(),
    pet_id     uuid        not null,
    user_id    uuid        not null,
    origen     text        not null
               check (origen in ('TREATMENT', 'EXAM', 'SURGERY')),
    origen_id  uuid,
    contenido  text        not null,
    leida      boolean     not null default false,
    created_at timestamptz not null default now()
);

create index if not exists idx_ai_suggestion_pet
    on public.ai_suggestion (pet_id, created_at desc);

comment on table public.ai_suggestion is
    'pet_id y user_id sin FK: pertenecen a pet-service y user-service.';


-- ------------------------------------------------------------
-- Idempotencia de Pub/Sub (entrega at-least-once)
-- ------------------------------------------------------------
create table if not exists public.ai_processed_event (
    message_id   text primary key,
    topic        text,
    processed_at timestamptz not null default now()
);

create index if not exists idx_ai_processed_event_fecha
    on public.ai_processed_event (processed_at);


-- ------------------------------------------------------------
-- updated_at automático en las conversaciones
-- ------------------------------------------------------------
create or replace function public.ai_touch_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists trg_ai_conversation_updated_at on public.ai_conversation;
create trigger trg_ai_conversation_updated_at
    before update on public.ai_conversation
    for each row execute function public.ai_touch_updated_at();


-- ------------------------------------------------------------
-- Seguridad: bloquear el acceso vía PostgREST / anon key.
-- El servicio se conecta por JDBC con el rol postgres, que ignora
-- RLS. Sin políticas definidas, nadie más puede leer estas tablas.
-- ------------------------------------------------------------
alter table public.ai_conversation    enable row level security;
alter table public.ai_message         enable row level security;
alter table public.ai_pending_action  enable row level security;
alter table public.ai_suggestion      enable row level security;
alter table public.ai_processed_event enable row level security;


-- ------------------------------------------------------------
-- Verificación
-- ------------------------------------------------------------
select table_name
from information_schema.tables
where table_schema = 'public'
  and table_name like 'ai\_%'
order by table_name;
