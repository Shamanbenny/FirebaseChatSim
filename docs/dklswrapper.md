# DKLS UniFFI API Documentation

The DKLS library exposes a Rust interface via UniFFI, generating Kotlin bindings callable from `dkls.kt`. This documentation details public functions and constructors marked with `uniffi::export` or `#[uniffi::constructor]` in `types.rs` and `dkg.rs`, cross-referenced with usage in `MainActivity.kt`. All functions return `Arc<Self>` for handle-managed objects or specified types, with async methods supporting suspend coroutines in Kotlin.[file:3][file:4]

## InstanceId API

| Function | Purpose | Parameters | Return Type |
|----------|---------|------------|-------------|
| `from_entropy()` | Generates a cryptographically secure random 32-byte instance ID using ChaCha20 RNG. | None | `Arc<InstanceId>` [file:3] |
| `from_bytes(bytes: Vec<u8>)` | Creates an InstanceId from exactly 32 bytes, failing on invalid length. | `bytes: Vec<u8>` | `Result<Arc<InstanceId>, GeneralError>` [file:3] |
| `to_bytes(&self)` | Serializes the 32-byte instance ID to a vector. | None | `Vec<u8>` [file:3] |

## NodeVerifyingKey API

| Function | Purpose | Parameters | Return Type |
|----------|---------|------------|-------------|
| `from_bytes(bytes: Vec<u8>)` | Deserializes from bytes into a wrapper over `sldkls23::setup::NoVerifyingKey`. | `bytes: Vec<u8>` | `Result<NodeVerifyingKey, GeneralError>` [file:3] |
| `to_bytes(&self)` | Serializes the verifying key bytes. | None | `Vec<u8>` [file:3] |

## DkgNode Constructors

| Constructor | Purpose | Parameters | Return Type |
|-------------|---------|------------|-------------|
| `starter(instance: Arc<InstanceId>, threshold: u8)` | Creates a starter node for party ID 0 with a single zero-indexed verifying key. | `instance: Arc<InstanceId>`, `threshold: u8` | `Arc<DkgNode>` [file:4] |
| `new(instance: Arc<InstanceId>, threshold: u8, party_vk: Vec<Arc<NodeVerifyingKey>>)` | Builds node as last party, appending its own generated verifying key. | `instance: Arc<InstanceId>`, `threshold: u8`, `party_vk: Vec<Arc<NodeVerifyingKey>>` | `Arc<DkgNode>` [file:4] |
| `for_id(instance: Arc<InstanceId>, threshold: u8, num_parties: u8, party_id: u8)` | Initializes specific party in fixed-size group, generating placeholder VKs. | `instance: Arc<InstanceId>`, `threshold: u8`, `num_parties: u8`, `party_id: u8` | `Arc<DkgNode>` [file:4] |

## DkgNode Methods

| Function | Purpose | Parameters | Return Type |
|----------|---------|------------|-------------|
| `add_party(&self, party_vk: Arc<NodeVerifyingKey>)` | Adds a party verifying key to the node's list. | `party_vk: Arc<NodeVerifyingKey>` | `()` [file:4] |
| `my_vk(&self)` | Retrieves this node's own verifying key. | None | `Arc<NodeVerifyingKey>` [file:4] |
| `party_vk(&self)` | Returns clone of all known party verifying keys. | None | `Vec<Arc<NodeVerifyingKey>>` [file:4] |
| `do_keygen(&self, interface: Arc<dyn NetworkInterface>)` | Performs async DKG protocol over network interface using relay adapter. | `interface: Arc<dyn NetworkInterface>` | `Result<Keyshare, KeygenError>` (suspend) [file:4] |

## Keyshare Methods

| Function | Purpose | Parameters | Return Type |
|----------|---------|------------|-------------|
| `print(&self)` | Prints hex-encoded public key and signing key share to stdout. | None | `()` [file:3] |
| `keyshare_string(&self)` | Formats hex-encoded "PK SK" string for keyshare. | None | `String` [file:3] |

## DKGRunner API

| Function | Purpose | Parameters | Return Type |
|----------|---------|------------|-------------|
| `new()` | Creates runner with simple message relay coordinator and lazy Tokio runtime. | None | `Arc<DKGRunner>` [file:4] |
| `initialize_tokio_runtime(&self)` | Initializes multi-threaded Tokio runtime in OnceLock. | None | `()` [file:4] |
| `run(&self, node: DkgNode)` | Blocks on node's DKG via runner's runtime and relay connection. | `node: Arc<DkgNode>` | `Result<Keyshare, KeygenError>` (suspend) [file:4] |

## NetworkInterface Trait

Foreign callback trait for async send/receive over byte vectors, implemented client-side (e.g., Firebase in MainActivity.kt).[file:3]

| Method | Purpose | Parameters | Return Type |
|--------|---------|------------|-------------|
| `send(&self, data: Vec<u8>)` | Sends bytes asynchronously. | `data: Vec<u8>` | `Result<(), NetworkError>` (suspend) [file:3] |
| `receive(&self)` | Receives bytes asynchronously. | None | `Result<Vec<u8>, NetworkError>` (suspend) [file:3] |

## NetworkInterfaceTester API

| Function | Purpose | Parameters | Return Type |
|----------|---------|------------|-------------|
| `new(interface: Arc<dyn NetworkInterface>)` | Wraps interface for testing send/receive relay. | `interface: Arc<dyn NetworkInterface>` | `NetworkInterfaceTester` [file:3] |
| `test(&self)` | Tests basic send/receive roundtrip with fixed bytes. | None | `Result<(), NetworkError>` (suspend) [file:3] |
| `test_relay(&self, data: Vec<u8>)` | Tests relaying arbitrary data via dual network relays. | `data: Vec<u8>` | `Result<(), NetworkError>` (suspend) [file:3] |
