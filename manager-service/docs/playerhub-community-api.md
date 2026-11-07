# PlayerHub community v1

Local Forge 1.20.1 implementation contract. Player module `community` resolves to `com.pixelmc.pixelmcmanager.api.PlayerHubCommunityApi`. Java protocol remains 1. Discovery requires `communityApi: 1`. Service tokens use `communityRevision`, piggybacked on the existing hello, checkpoint and administrator bootstrap flow.

## Player requests

All input objects are strict allowlists. Player writes derive the UUID from the authenticated connection. Public reads may specify a target `playerId` that has a recorded membership in the current authenticated Manager instance.

| Action | Payload |
| --- | --- |
| snapshot | `{}` |
| profile | `{playerId?}` |
| profileOptions | `{kind, page?}` where kind is titles, fortunes or memories |
| memories | `{playerId?, page?}` |
| memory | `{playerId?, archiveId, page?}` |
| memoryOptions | `{archiveId, page?}` |
| saveProfile | `{patch, expectedRevision}` |
| saveNote | `{archiveId, text, expectedRevision}` |
| appendSupplement | `{archiveId, text, operationId}` with UUID operationId |
| deleteSupplement | `{archiveId, supplementId, expectedRevision}` |
| saveCollections | `{archiveId, selection, expectedRevision}` |

Profile patch fields are `bio`, `background`, `profilePattern`, `featuredTitleId`, `featuredFortuneDate`, `featuredMemoryId`. The three selected fields accept null. Names, identity and acquisition records retain their business owner. Collection selection is `{titleEventId,fortuneEventId}`, each nullable. Revisions are opaque strings. All timestamp fields are epoch milliseconds. Fortune date keys remain YYYY-MM-DD.

## Responses

- `snapshot` contains `{profile,options,memories,revision,serverTime}`. `profile` and `options` match the profile response. `memories` is the first paginated archive envelope.
- `profile` returns `{profile,options?,revision,serverTime}`. Profile has `playerId,name,isSelf,joinedAt,title,bio,background,profilePattern,featuredTitleId,featuredFortuneDate,featuredMemoryId,revision,updatedAt,featuredTitle,featuredFortune,featuredMemory`. Other-player responses include only selected public objects and omit private options.
- Self `options` contains `backgrounds,patterns,titles,fortunes,memories` and per-list `titlesTotal,titlesHasMore` etc. First pages contain up to 100 records. `profileOptions` returns `{playerId,kind,rows,total,page,size:100,hasMore,revision,serverTime}`.
- `memories` returns `{playerId,playerName,isSelf,archives,total,page,size:100,hasMore,revision,serverTime}`. Archives have `id,name,version,start,end,isCurrent,description,coverPattern,coverColor,onlineMillis,hours,visits,collections,moment,momentText`. Missing historical prose remains empty. Current archive sorts first, then newest start first.
- `memory` returns `{playerId,archiveId,archive,isSelf,note,supplements,supplementsTotal,supplementsPage,supplementsHasMore,collections,candidates?,candidatesTotal?,candidatesHasMore?,revision,serverTime}`. Note is `{text,revision,updatedAt,deleted}`. Supplement is `{id,text,createdAt,updatedAt,revision,canDelete,deleteUntil}`. `collections` has `{selection,revision,updatedAt,items}`. Each public selected item is `{eventId,kind,obtainedAt,item}`. Candidate arrays are self/admin-only. `memoryOptions` returns `{playerId,archiveId,rows,total,page,size:100,hasMore,revision,serverTime}`, up to 100 candidates per page.
- Player writes return the corresponding current profile or memory envelope after commit. Append is idempotent by actor/archive/operationId. Reusing a deleted operation never recreates content. Successful identical append retries do not advance the committed-change token.

## Administrator HTTP

Prefix `/api/playerhub/community`. Existing authenticated session and CSRF apply. GET endpoints are `players`, `profile`, `profile-options`, `archives`, `archive-players`, `memory`, `memory-options`. POST endpoints are `save-profile`, `save-note`, `delete-note`, `save-supplement`, `delete-supplement`, `save-collections`. Administrator target `playerId` is explicit. All archive requests include `archiveId`. The player-card roster is the global registered-player directory; `instance` scopes the archive catalogue and archive-member queries. List queries accept `page,size,search,presence,sort,direction` with finite bounds. Instance filtering uses the existing `instance` query parameter.

Admin note/supplement updates bypass player deadline/archive immutability while retaining membership, source acquisition constraints, revision protection and audit. Admin deletion needs `{confirm:true}` and the current revision. Public access never grants these actions.

## Ownership and storage

Community preferences, notes, supplements and selected collection references use four `ph_community_*` tables. They join the PlayerHub native manifest, complete backup/restore and global player deletion/recovery. The self featured fortune remains the existing DailyFortunes registered `featured_date` field. Profile and that field commit in one unified database transaction. Titles and archived fortunes are obtained from their source, and archive candidates are filtered by actual acquisition timestamps. Missing historical acquisitions never receive invented dates.

## Local verification

PlayerHubCommunityChecks uses an owned disposable loopback MariaDB and temporary HTTP service. The 2026-11-07 run passed 234 assertions covering v2-to-v3 native manifest migration, administrator authentication and CSRF, player connection proof, self/public boundaries, acquisition membership and paging, note/supplement restrictions, concurrent CAS, atomic profile/owner-preference rollback, full empty-schema backup restore and player deletion/recovery. Evidence is local development verification; production deployment and game acceptance remain unperformed.


## Administrator Web integration

The authenticated App routes `hub-players` and `hub-history` use the same presentation as the isolated preview, with separate asynchronous data adapters. Production depends on `src/playerhub/communityApi.js` and same-origin session/CSRF requests. Preview-only imports live in `src/hub-preview/communityPreviewApi.js`; Vite rejects community demo/storage/administrator-preview modules in the production dependency graph.

The player roster remains global. Filtering, all six sort keys, presence counts and pagination are authoritative SQL results. Archive/member lists, profile choices, acquisition candidates and supplements expose their additional pages. Selected objects outside the first 100 choices remain visible. Existing SSE `communityRevision` and completed mutations trigger bounded refreshes; there is no community polling timer. Revision conflicts retain the unsaved draft.

Isolated browser checks covered real login, profile save, fortune choice beyond the first 100 followed by save/reopen, historical note save/confirmed deletion, archived collection save/reopen, two-window revision conflict with draft retention, supplement page 2 containing five of 25 records, and dark/light desktop presentation. A browser viewport override was unavailable, so narrow-width validation remains unverified. The isolated database and HTTP processes were closed after testing.

Archive covers currently use the explicit server defaults `arcs` with `sage` for current and `mist` for historical. Per-archive cover administration is outside this increment because no historical artwork metadata exists in Manager's archive source. Historical prose and missing acquisitions are never filled from Web fixtures.

The DailyFortunes change is limited to three trusted unified-service read adapters and a public acquisition projection in `FortuneBackend`. It is compiled into Manager service; this integration does not require a changed standalone DailyFortunes game JAR or alter the fortune-barrel behavior.
