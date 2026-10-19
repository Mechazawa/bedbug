# Servers and LAN

## Set up a server

1. Install Bedbug on the server and on every client.
2. Put the packs in the server's `addons/` folder. Clients need no packs.

The server runs the game. The client draws it.

## What a joining player gets

A client that joins a Bedbug server gets the server's add-ons during the join. No restart is needed.

- The client downloads the add-ons it does not have into `.mcaddon/received/`.
- For the visit, the client plays with the server's add-ons and no others.
- An add-on that only the client has stays loaded but does nothing until the client leaves.
- An add-on that the server has in a different release is replaced by the server's release.
- When the client leaves, its own add-ons come back.
- A downloaded add-on is kept for the next visit. If no server asks for it in a month, it is deleted.

A join can take up to ten minutes while the client downloads and converts. The server disconnects a
client that stops answering, or one that sends nothing for a minute during the download.

## LAN

A world opened to LAN works the same way. The host gives its add-ons to each guest. The host itself
never downloads or changes anything.

## What a client refuses

The client shows a message that gives the reason:

- The server runs a different release of Bedbug.
- One add-on is larger than 256 MB, the total is more than 1 GB, there are more than 64 add-ons, or
  the disk has no room.
- An archive does not match the bytes the server announced, or it unpacks into more than 50000 files
  or more than 1 GB.
- The client already loaded a release of the add-on whose items, blocks or entities have a different
  shape, for example a block with a different set of states. The message names the add-on. Remove or
  replace it in `addons/` and start the game again.

A client without Bedbug cannot join a server that has add-ons. It is told which mod to install. A
Bedbug client on a server without Bedbug plays with its own add-ons.

## Server administration

An operator types these after `/bedrock`, or the console types them. `save query`, `setmaxplayers`,
`packstack`, `permission` and `ops` need more authority than a function has, so a pack's function
cannot run them.

| Command | What it does |
| --- | --- |
| `save query` | `save query` writes out what `save hold` held back and lists each world file with its length. |
| `setmaxplayers <n>` | Sets the player limit for this session only. |
| `packstack server` | Lists the data packs in the order they apply, with each converted add-on. |
| `packstack client` | Lists the add-on resource packs. |
| `permission`, `ops` | List operators and visitors, read `ops.json` again, and make a player an operator, a member or a visitor. |
| `changesetting allow-cheats false` | Stops all players, operators too, from typing commands that Bedrock marks as cheats. |
| `reloadconfig` | Reads the script configuration files again. |
| `reloadpacketlimitconfig` | Reads `packetlimitconfig.json` again for every connected player. |
| `serveridentity save`, `delete`, `status` | Manage the server identity key. |

### Visitors

A visitor can only look around, as in Bedrock. The server refuses these actions from a visitor, on
any client:

- Using, placing or breaking a block.
- Using an item that places a block or an entity.
- Opening an item frame, a chest minecart, a chest boat or the inventory of a mount.
- Every hit and all damage.

A visitor can trade, ride and use the items it carries. A script's `playerPermissionLevel` gives
`Visitor` for it.

### Cheats

After `changesetting allow-cheats false`, the console, command blocks, functions, scripts and entity
commands still run every command. The setting lasts until it is turned on again or the server stops.
A dedicated server reads `allow-cheats` from `server.properties` at start. If the line is missing,
cheats are on.

### Packet rate limiter

Set `enable-packet-rate-limiter=true` in `server.properties` to turn it on. It is off when the line
is missing, as in Bedrock.

- The server reads `packetlimitconfig.json` from its own folder, in Bedrock's format.
- Each `BucketPacketLimitAlgorithm` gives each connection a bucket per Bedrock packet id.
- A `defaultAlgorithm` gives a bucket per Java packet type once the player is in the world.
- A packet with no room in its bucket is dropped and logged as a `Packet Limit Violation`.
- Each Bedrock id maps to the Java packets that carry the same message. For example, `9` limits chat
  and `77` limits commands. An id that no Java client sends, such as a skin upload, is named in the
  log.

### Script configuration

Scripts read the server configuration through `@minecraft/server-admin`.

- Put `variables.json`, `secrets.json` and `permissions.json` in `config/default/` for all packs.
- Put them in `config/<script module uuid>/` for one pack. Its keys win over the default ones.
- A script can read a variable's value. It can read only the name of a secret.
- If a pack depends on a beta script API that `allowed_modules` does not list, its script does not
  load.
- `opPlayer` and `deopPlayer` change the Java operator list.
- The allow list, level storage, kicks, transfers and the join event are reported as not implemented.

### Server identity key

- At start, the server reads the unencrypted P-384 private key at `keys/server_identity_key.pem`.
  SEC1 and PKCS#8 PEM both work. `openssl ecparam -name secp384r1 -genkey -noout` writes one.
- With no key file, the server makes a key and keeps it in memory. Clients then see a new server at
  each start until you save a key.
- If the key file cannot be read, the server logs it and uses a generated key.
- Each Bedbug client keeps the key a server showed, by address, in `mcaddon_trusted_servers.json`.
  If a known address shows a different key, the client asks the player. Accept keeps the new key.
  Refuse disconnects. A world the client hosts is not checked.
