# DKLS Kotlin API Reference

## Main Types You'll Use

| Type | Function/Method | Parameters | Return Type | Purpose |
|------|-----------------|------------|-------------|---------|
| **`InstanceId`** | `fromEntropy()` | None | `InstanceId` | **Generates cryptographically secure random instance ID** for DKG sessions. Required for all operations. |
| **`DKGNode`** | `starter(instance: InstanceId, threshold: UByte)` | `instance: InstanceId`<br>`threshold: UByte` | `DKGNode` | **Creates starter node** (party 0) with empty party list. Call first. |
| **`DKGNode`** | `new(instance: InstanceId, threshold: UByte, partyVks: List<NodeVerifyingKey>)` | `instance: InstanceId`<br>`threshold: UByte`<br>`partyVks: List<NodeVerifyingKey>` | `DKGNode` | **Creates joiner node** with known party public keys. |
| **`DKGNode`** | `addParty(partyVk: NodeVerifyingKey)` | `partyVk: NodeVerifyingKey` | `Unit` | **Adds another party's verifying key** to this node's party list. |
| **`DKGNode`** | `myVk()` | None | `NodeVerifyingKey` | **Returns this node's own verifying key** (share to other parties). |
| **`DKGNode`** | `partyVk()` | None | `List<NodeVerifyingKey>` | **Returns all known party verifying keys**. |
| **`DKGNode`** | `doKeygen(interface: NetworkInterface)` | `interface: NetworkInterface` | `Result<Keyshare, KeygenError>` | **Runs full DKG protocol** over network interface (async). |
| **`DKGRunner`** | `new()` | None | `DKGRunner` | **Creates DKG coordinator**. Call once. |
| **`DKGRunner`** | `initializeTokioRuntime()` | None | `Unit` | **Initializes internal Tokio runtime**. Required before `run()`. |
| **`DKGRunner`** | `run(node: DKGNode)` | `node: DKGNode` | `Result<Keyshare, KeygenError>` | **Executes DKG protocol** for given node (async). |
| **`Keyshare`** | `print()` | None | `Unit` | **Prints keyshare** in format `"PK: <hex> SK: <hex>"` for debugging. |
| **`NodeVerifyingKey`** | `toNoVk()` | None | `NoVerifyingKey` | **Converts to SL-DKLS23 internal format**. Used in setup messages. |
| **`NetworkInterfaceTester`** | `new(interface: NetworkInterface)` | `interface: NetworkInterface` | `NetworkInterfaceTester` | **Creates test helper** for network interface validation. |
| **`NetworkInterfaceTester`** | `test()` | None | `Result<Unit, NetworkError>` | **Tests network interface** with echo loopback (send `[1,2,3,4]` → receive). |

## Error Types

| Error Type | Common Causes |
|------------|---------------|
| **`KeygenError`** | `InvalidMessage`, `InvalidCommitmentHash`, `MissingMessage`, `AbortProtocol`, `InvalidContext` |
| **`NetworkError`** | `MessageSendError` (network failures) |

## Usage Notes

- **All async methods** return `Result<..., Error>` and require `.await()`
- **`DKGNode`** manages party state (secret key, party index, verifying keys)
- **`DKGRunner`** provides Tokio runtime + MPC relay coordination
- **`InstanceId`** ensures session isolation (use fresh one per DKG)
