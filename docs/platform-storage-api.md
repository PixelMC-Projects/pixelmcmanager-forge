# Platform storage API · 0.6.0

Manager owns the global UUID directory, web accounts/session/CSRF, avatar cache, real-player admission and backup policy. Module-local cleanup must retain the global identity and avatar. Archive play statistics remain separate from global title and fortune collections.

## Register

The server-only game API keeps `register(id,name,version,capabilities)` and adds `registerStorage(id,manifestJson)`. Register metadata first. Duplicate metadata/manifest ownership conflicts fail visibly; this is not a mod security sandbox, since server mods share one JVM.

```json
{
  "version": 1,
  "name": "ExamplePlugin",
  "label": "示例插件",
  "scope": "global",
  "tables": [],
  "fields": [
    {"id":"enabled","type":"boolean","scope":"global","default":false,"maxBytes":32},
    {"id":"visits","type":"integer","scope":"archive","default":0,"maxBytes":64}
  ],
  "capabilities": []
}
```

Namespaces and field IDs use `[a-z][a-z0-9_.-]{0,63}`. Up to 128 fields and 64 KiB per value are supported; the default quota is 16 KiB. Types are `boolean`, `integer`, `number`, `string`, `object`, `array`; null values are rejected. Type/default/scope changes require an explicit migration rather than overwriting existing field definitions. The schema version cannot decrease, or change its manifest without incrementing.

Generic modules use `pm_values`, so a third module can add fields without changing routing or adding SQL tables. Native service adapters declare their ordered business tables (`mt_*`, `df_*`) and optional player cleanup ownership. Only native adapters may own SQL tables. Register dependent tables after their referenced tables. All registered durable tables are included in one repeatable-read backup, and a missing registered table makes backup fail rather than produce an incomplete archive.

## Bound service hook

Business services accept `attachPlatform(Function<JsonObject,JsonObject>)`. Manager binds the caller namespace; a request cannot select another namespace. A null hook preserves standalone behavior. No Minecraft or duplicated API interfaces are bundled into this service hook.

| Operation | Request | Result |
| --- | --- | --- |
| `profiles.list` | `{op}` | `{players:[{id,name,firstJoinedAt,joinedAt,lastSeen,revision}],revision}` |
| `profiles.get` | `{op,player}` | `{profile:object or null}` |
| `profiles.online` | `{op}` | `{players:[UUID]}` |
| `profiles.loginContext` | `{op,player}` | `{admitted,eventId,firstGlobalLogin,firstArchiveLogin,archiveId}` |
| `storage.get` | `{op,player,field,archiveId?}` | `{value,revision,scope}` |
| `storage.set` | `{op,player,field,archiveId?,expectedRevision,value}` | `{value,revision,scope}` |

Unknown historical timestamps remain 0. Directory projections must never call a module login, create a participation record, grant rewards or issue receipt tokens. `admitted` means the player is currently admitted through a fresh authenticated bridge presence, not that an old login exists. A login context is not inferred from wall-clock proximity. `eventId` comes from the real durable login journal. Namespace calls fail closed if the platform cannot be reached.

An unmaterialized field returns its registered default at revision 0. Writes require the exact current revision. Archive fields require a valid `archiveId`; archived scopes are read-only. Unknown players, fields, cross-namespace requests, incompatible types and oversized values are rejected.

The game bridge calls `ManagerBridgeApi.call(id,"platform",requestJson)` for generic operations. Authenticated web administrators can POST the same storage body to `/api/extensions/<id>/storage/get` or `/set`; the route binds that namespace. Existing auth, origin and CSRF rules apply.

## Avatars and HTTP

Manager resolves Mojang heads first and keeps the hat layer. `client-avatar` is an optional registered provider capability; fallback requests are sent only to admitted players and use bounded, expiring nonces. A module can respond through `avatars.upload` with `{player,nonce,png}` and current admission proof. Modules can query `avatars.version` / `avatars.head` through their platform hook. There is no dependency on Titles being enabled for platform accounts, cached heads, storage or HTTP.

The native Titles adapter accepts `(TitleService, Function<JsonObject,JsonObject>)` and handles authenticated business API calls only. It cannot open a listener or create an account/avatar store. All backup routes are handled before module routing and return complete platform backups.

## Global deletion and recovery

These administrator APIs provide a platform operation without adding a new page layout:

- POST `/api/manager/player-delete/preview` with `{player}`.
- POST `/api/manager/player-delete/confirm` with `{player,stamp,confirmName,confirm:true}`.
- GET `/api/manager/player-recoveries`.
- POST `/api/manager/player-restore` with `{recovery}`.

The player must be offline; associated web/admin accounts require separate handling. Preview covers the global identity, archive statistics, registered player fields, native module rows and avatar. Confirmation rechecks the data stamp. SQL changes are one transaction and avatar changes have rollback. One recovery record restores that operation, never an entire platform snapshot. A new identity, module data, changed avatar, changed title/fortune definitions or schema produces a conflict instead of an overwrite. Platform audit and protected recovery records remain administrator-visible.

Deletion keeps a tombstone and cumulative counter baselines: replayed login journals and old local statistics cannot immediately resurrect a cleared offline profile. A real subsequent admission can register the player again without treating them as a first-ever player or restoring old cumulative totals.

## Backup and lifecycle

`platform-init` adds registry/storage tables and purely backfills directory identities after copying legacy stores into a new schema. It never redirects, deletes or drops original databases. Fresh new configurations use one database; old split database configurations require explicit migration.

Full backup format 3 includes registered persistent tables, SQL schema, platform accounts, cached avatars, module configuration assets and bridge credentials. Performance retention remains separate and is excluded. Restore is offline, checksum-verified, empty-target only and checks row fingerprints before committing; an interrupted restore leaves a startup-blocking marker for an explicit resume. Backup policy belongs to Manager; the old title retention control delegates changes to platform policy for compatibility.
