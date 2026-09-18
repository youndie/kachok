package io.github.youndie.kachok.control

import java.nio.file.Path

/**
 * The platform's own place for what this client owns: the settings file, the torrent list, and the
 * lock that says which process is the client.
 *
 * Not beside the downloads. The download directory is one of the settings, and a file that moves
 * when you change a setting is a file you lose.
 *
 * **Here rather than in the window**, since
 * [B-117](../../../../../../../../../docs/backlog/B-117-one-client-for-the-window-and-the-agent.md):
 * a headless `kachok mcp` has to look for the running client's lock in the same directory the
 * window writes it to, and two functions that answer "where does this user's kachok keep things"
 * is one bug away from a process that looks in the wrong place and quietly starts a second client.
 */
public fun configDirectory(): Path {
    val home = System.getProperty("user.home").orEmpty()
    val os = System.getProperty("os.name").orEmpty()
    return when {
        os.startsWith("Mac") -> {
            Path.of(home, "Library", "Application Support", "kachok")
        }

        os.startsWith("Windows") -> {
            System.getenv("APPDATA")?.let { Path.of(it, "kachok") } ?: Path.of(home, "kachok")
        }

        else -> {
            System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "kachok") }
                ?: Path.of(home, ".config", "kachok")
        }
    }
}
