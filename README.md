BetterHomes

A simple homes plugin for Paper servers with a GUI, teleport warmup, and SQLite storage.

✨ Features

- Create, teleport to, and delete homes.
- Easy-to-use GUI with pagination.
- Teleport warmup cancelled by movement or damage.
- Configurable home limits and messages.
- Optional Bedrock GUI support with Geyser + Floodgate.

📦 Requirements

- Paper 26.2+
- Java 25
- Geyser + Floodgate (optional)

🔧 Installation

1. Download the plugin JAR.
2. Place it in your server's "plugins" folder.
3. Restart your server.

🎮 Commands

Command| Description
"/home"| Open your homes menu
"/home <name>"| Teleport to a home
"/homes"| Open your homes menu
"/homes <player>"| View a player's homes (admin)
"/sethome"| Create a home
"/delhome <name>"| Delete a home
"/betterhomes reload"| Reload configuration

🔑 Permissions

- "betterhomes.use" — Use home commands.
- "betterhomes.admin" — Admin commands.
- "betterhomes.bypass.delay" — Skip teleport warmup.
- "betterhomes.limit.<number>" — Set a home limit.

🛠️ Build

Requires Maven and Java 25.

mvn clean package

The JAR will be in "target/BetterHomes-1.1.0.jar".
