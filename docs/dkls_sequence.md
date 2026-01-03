# Device 1 (Initiator) and Device 2 (Acceptor) execute a 2-of-2 DKG protocol via Firebase-mediated invites and network interface.

The timeline focuses on core function calls and Firebase interactions, omitting UI triggers and logging.

## Initiation Phase (Initiator)

1. `handleAddDevice(otherClientId)` → Check Firebase `presence/{otherClientId}` (online?).
2. `sendDKGInvite(otherClientId, threshold=2, total=2)`:
    - `InstanceId.fromEntropy()`.
    - `DkgNode.starter(instanceId, 2u)`.
    - `ownNode.myVk.toBytes()`.
    - Write Firebase `invites/{otherClientId}/{ownClientId}` with `instanceIdBase64`, `partyVKsBase64=[ownVK]`, `status=pending`.
3. Adds Listener on `invites/{otherClientId}/{ownClientId}` for status change.

## Acceptance Phase (Acceptor)

1. `handleAcceptInvite(invite)`:
    - `invite.toInstanceId()` → `InstanceId.fromBytes(base64decode)`.
    - `DkgNode.new(instanceId, 2u, invite.getPartyVKs())` (uses initiator's VK).
    - `ownNode.myVk.toBytes()`.
    - Update Firebase `invites/{ownClientId}/{initiatorId}`: `status=accepted`, `partyVKsBase64=[initiatorVK, ownVK]`.
    - `performDKG(ownNode, instanceIdBase64, otherClientId)`.

## Invite Confirmation (Initiator)

- Listener detects `status=accepted`.
- Parse `targetVKs` from updated `partyVKsBase64`.
- `ownNode.addParty(NodeVerifyingKey.fromBytes(targetVKBytes))`.
- `performDKG(ownNode, instanceIdBase64, otherClientId)`.


## Parallel DKG Execution

**Initiator** and **Acceptor** (both call `performDKG(node, instanceIdBase64, peerClientId)`):

1. Create `FirebaseNetworkInterface(database, instanceIdBase64, ownClientId, peerClientId)`:
    - Listener on `dkgmessages/{instanceIdBase64}` for peer→own messages (queue + ack-delete).
2. `node.doKeygen(networkInterface)` → Async DKG protocol:
    - Internally: `createnetworkrelay(interface)` → `send()`/`receive()` loops over Firebase messages.
3. On success:
    - Store `dklsKeyshare`.
    - Cleanup: Delete `dkgmessages/{instanceIdBase64}`.
    - Delete peer's invite entry.

## Post-DKG State

Both devices reach `STATUS_KEY_READY`.