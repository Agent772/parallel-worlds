# Sub-Agent Instructions: Epic 10 — Networking & Client Sync

## Objective
Sync dimension state to clients for proper rendering, UI messages, and reset warnings.

## Context
- **Depends on**: Epic 2 (dimensions to sync)
- **NeoForge API**: `RegisterPayloadHandlersEvent`, `PayloadRegistrar`, `PacketDistributor`

## Tasks

### 1. Packet Payloads
`src/main/java/com/agent772/parallelworlds/network/payload/`:

**DimensionSyncPayload** (record, implements CustomPacketPayload):
- `ResourceLocation dimensionId, boolean exists`
- `StreamCodec` for serialization
- `CustomPacketPayload.Type<DimensionSyncPayload> TYPE`

**DimensionResetPayload** (record, implements CustomPacketPayload):
- `ResourceLocation dimensionId, long resetTime`
- `StreamCodec` for serialization

**ResetWarningPayload** (record, implements CustomPacketPayload):
- `int minutesRemaining, String message`
- `StreamCodec` for serialization

### 2. PWNetworking
`src/main/java/com/agent772/parallelworlds/network/PWNetworking.java`:
- `registerPayloads(RegisterPayloadHandlersEvent event)`:
  - `registrar.playToClient(DimensionSyncPayload.TYPE, DimensionSyncPayload.STREAM_CODEC, PWClientHandler::handleDimensionSync)`
  - Same for DimensionResetPayload, ResetWarningPayload
- `sendDimensionSync(ServerPlayer player, ResourceLocation dim, boolean exists)`:
  - `PacketDistributor.sendToPlayer(player, new DimensionSyncPayload(dim, exists))`
- `sendDimensionSyncToAll(ResourceLocation dim, boolean exists)`:
  - `PacketDistributor.sendToAllPlayers(new DimensionSyncPayload(dim, exists))`
- `sendResetWarning(ServerPlayer, int minutesRemaining, String message)`

### 3. Client Handler
`src/main/java/com/agent772/parallelworlds/client/PWClientHandler.java`:
- `handleDimensionSync(DimensionSyncPayload data, IPayloadContext context)`:
  - Track known dimensions in client-side set
- `handleDimensionReset(DimensionResetPayload data, IPayloadContext context)`:
  - Display chat message about dimension reset
- `handleResetWarning(ResetWarningPayload data, IPayloadContext context)`:
  - Display on action bar: "Mining dimension resets in X minutes"

### 4. Send Points
- On server start (after dimension creation): sync all dims to all players
- On player join: sync all active dimensions
- On dimension creation: sync to all players
- On dimension removal: sync (exists=false) to all players
- Periodically (configurable): send reset warnings to players in mining dims

## Verification
- Client receives dimension sync on join → dimensions visible in F3 or similar
- New dimension created → all connected clients notified
- Reset warning appears on action bar
- No desync after teleport between dimensions
- Payloads register without error in dev environment
