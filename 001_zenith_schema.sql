-- ZENITH Supabase schema. Run in Supabase SQL Editor as project owner.
-- Never place the service_role key in the Android app.

create extension if not exists pgcrypto;

create table if not exists public.user_profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null default 'ZENITH user' check (char_length(display_name) between 1 and 40),
  avatar_url text,
  bio text not null default '' check (char_length(bio) <= 300),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.user_playlists (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 80),
  description text not null default '' check (char_length(description) <= 500),
  is_public boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.playlist_tracks (
  playlist_id uuid not null references public.user_playlists(id) on delete cascade,
  track_key text not null,
  title text not null,
  artist text not null default '',
  artwork_url text,
  position integer not null default 0,
  added_at timestamptz not null default now(),
  primary key (playlist_id, track_key)
);

create table if not exists public.user_follows (
  follower_id uuid not null references auth.users(id) on delete cascade,
  followed_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (follower_id, followed_id),
  check (follower_id <> followed_id)
);

create table if not exists public.track_likes (
  user_id uuid not null references auth.users(id) on delete cascade,
  track_key text not null,
  created_at timestamptz not null default now(),
  primary key (user_id, track_key)
);

create table if not exists public.playlist_comments (
  id uuid primary key default gen_random_uuid(),
  playlist_id uuid not null references public.user_playlists(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  body text not null check (char_length(body) between 1 and 1000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists user_playlists_owner_updated_idx on public.user_playlists(owner_id, updated_at desc);
create index if not exists playlist_comments_playlist_created_idx on public.playlist_comments(playlist_id, created_at desc);
create index if not exists user_follows_followed_idx on public.user_follows(followed_id);

alter table public.user_profiles enable row level security;
alter table public.user_playlists enable row level security;
alter table public.playlist_tracks enable row level security;
alter table public.user_follows enable row level security;
alter table public.track_likes enable row level security;
alter table public.playlist_comments enable row level security;

-- Profiles are readable to signed-in users; users may only change their own profile.
create policy "profiles are readable by authenticated users" on public.user_profiles
  for select to authenticated using (true);
create policy "users create own profile" on public.user_profiles
  for insert to authenticated with check (auth.uid() = id);
create policy "users update own profile" on public.user_profiles
  for update to authenticated using (auth.uid() = id) with check (auth.uid() = id);

create policy "public playlists or own playlists readable" on public.user_playlists
  for select to authenticated using (is_public or owner_id = auth.uid());
create policy "users create own playlists" on public.user_playlists
  for insert to authenticated with check (owner_id = auth.uid());
create policy "users update own playlists" on public.user_playlists
  for update to authenticated using (owner_id = auth.uid()) with check (owner_id = auth.uid());
create policy "users delete own playlists" on public.user_playlists
  for delete to authenticated using (owner_id = auth.uid());

create policy "tracks in visible playlists readable" on public.playlist_tracks
  for select to authenticated using (exists (select 1 from public.user_playlists p where p.id = playlist_id and (p.is_public or p.owner_id = auth.uid())));
create policy "playlist owners manage tracks" on public.playlist_tracks
  for all to authenticated using (exists (select 1 from public.user_playlists p where p.id = playlist_id and p.owner_id = auth.uid()))
  with check (exists (select 1 from public.user_playlists p where p.id = playlist_id and p.owner_id = auth.uid()));

create policy "follows visible to signed in users" on public.user_follows
  for select to authenticated using (true);
create policy "users follow as themselves" on public.user_follows
  for insert to authenticated with check (follower_id = auth.uid());
create policy "users unfollow as themselves" on public.user_follows
  for delete to authenticated using (follower_id = auth.uid());

create policy "likes readable to signed in users" on public.track_likes
  for select to authenticated using (true);
create policy "users like as themselves" on public.track_likes
  for insert to authenticated with check (user_id = auth.uid());
create policy "users unlike as themselves" on public.track_likes
  for delete to authenticated using (user_id = auth.uid());

create policy "comments readable on visible playlists" on public.playlist_comments
  for select to authenticated using (exists (select 1 from public.user_playlists p where p.id = playlist_id and (p.is_public or p.owner_id = auth.uid())));
create policy "users create own comments" on public.playlist_comments
  for insert to authenticated with check (user_id = auth.uid() and exists (select 1 from public.user_playlists p where p.id = playlist_id and (p.is_public or p.owner_id = auth.uid())));
create policy "users edit own comments" on public.playlist_comments
  for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "users delete own comments" on public.playlist_comments
  for delete to authenticated using (user_id = auth.uid());

-- Do not grant admin powers through client-settable profile fields. Moderation/admin roles
-- must be assigned in trusted server-side tooling and enforced by separate policies/functions.
