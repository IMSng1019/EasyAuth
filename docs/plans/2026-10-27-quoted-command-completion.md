# Quoted external-player command completion

**Problem:** `.4` accepts allocated external names on the server, but stock clients still parse bare `[MUA]Name` as invalid. The server cannot replace the client's built-in argument parser. Existing names and UUIDs must stay stable.

**Required behavior:** Tab completion for vanilla entity-target arguments offers `"[MUA]Name"`, including when the user has typed `[M` or started a quoted name. Selecting it must produce syntax accepted by the unmodified client parser. Manually typed unquoted special names can still be red; document this boundary.

## Steps

1. Extend the real 1.20.4 command regression with completion requests, quoted/bare prefixes, normal names and selector controls. Verify the command tree sent to a client serializes the server suggestion provider and leaves the live server dispatcher unchanged. Run RED.
2. Add narrow server-side completion adaptation and opt entity arguments into `minecraft:ask_server` on the copied client command tree. Preserve any existing custom provider. Do not change identity allocation or command permissions.
3. Verify stock parser acceptance of the selected completion, real teleport execution, all existing unit/GameTests, and remapped jar contents. Review client-tree permissions and completion scope independently.
4. Build `.6`, retain the opt-in diagnostics, update the command guidance and the latest successful real combat evidence. Give a concrete external-skin client setup only if supported by upstream sources; do not claim a server-side skin trust fix.
