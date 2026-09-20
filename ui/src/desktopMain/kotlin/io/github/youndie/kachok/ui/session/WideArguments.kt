package io.github.youndie.kachok.ui.session

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * The arguments Windows really passed, rather than the ones the JVM could spell.
 *
 * **A `.torrent` with a Russian name did not open this client at all.** Every non-ASCII character
 * arrived as `?`, `Path.of` refused the path as illegal, and the window never appeared — a person
 * double-clicking a torrent got a message box naming nothing and an error file they had to go and
 * find ([B-116](../../../../../../../../docs/backlog/B-116-a-torrent-whose-name-is-not-ascii-cannot-be-opened-on-windows.md)).
 *
 * **The characters are lost inside the JVM's own launcher, not by the shell and not by `jpackage`.**
 * Measured on the owner's machine: `sun.jnu.encoding` there is `Cp1252`, which has no room for
 * Cyrillic, and the wide command line is converted through it before `main` is reached — the same
 * `?` comes out of a plain `java` as out of the packaged launcher. Setting `-Dsun.jnu.encoding=UTF-8`
 * does not help and cannot: the property is read before the command line is, and it still reads
 * `Cp1252` afterwards. What *is* intact is everything else — the file API sees the real name, lists
 * it, opens it — so the only broken thing is the one string this file replaces.
 *
 * `GetCommandLineW` is the line as Windows has it, and `CommandLineToArgvW` splits it the way the
 * C runtime would. Taking the **last [args] entries** rather than working out where the JVM's own
 * arguments end is what makes this safe under both launchers: a packaged `kachok.exe` passes only
 * the application's arguments and a development `java -cp ... MainKt` passes several of its own
 * first, but under both, what the JVM handed over is the tail of that list.
 */
internal fun wideArguments(
    args: Array<String>,
    onWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows"),
    wide: () -> List<String> = ::windowsCommandLine,
): Array<String> {
    if (!onWindows || args.isEmpty()) return args
    val line =
        try {
            wide()
        } catch (unavailable: Throwable) {
            // A JVM without the foreign API, a Windows without `Shell32` - neither is worth failing
            // to start over, and what is lost is what was already lost before this existed.
            System.err.println("kachok: cannot read the real command line: ${unavailable.message}")
            return args
        }
    // Fewer than the JVM reported means the two do not describe the same launch, and guessing which
    // is which would be inventing a mapping. The flattened arguments at least run.
    if (line.size < args.size) return args
    return line.takeLast(args.size).toTypedArray()
}

/** `GetCommandLineW` and `CommandLineToArgvW`, through the foreign API and with no library of ours. */
private fun windowsCommandLine(): List<String> =
    Arena.ofConfined().use { arena ->
        val linker = Linker.nativeLinker()
        val getCommandLine =
            linker.downcallHandle(
                SymbolLookup.libraryLookup("Kernel32", arena).findOrThrow("GetCommandLineW"),
                FunctionDescriptor.of(ValueLayout.ADDRESS),
            )
        val toArgv =
            linker.downcallHandle(
                SymbolLookup.libraryLookup("Shell32", arena).findOrThrow("CommandLineToArgvW"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            )
        val count = arena.allocate(ValueLayout.JAVA_INT)
        val argv = toArgv.invokeExact(getCommandLine.invokeExact() as MemorySegment, count) as MemorySegment
        val size = count.get(ValueLayout.JAVA_INT, 0)
        val pointers = argv.reinterpret(ValueLayout.ADDRESS.byteSize() * size)
        (0 until size).map { at -> nullTerminated(pointers.getAtIndex(ValueLayout.ADDRESS, at.toLong())) }
    }

/** A UTF-16 string of a length only its terminator knows. */
private fun nullTerminated(at: MemorySegment): String {
    val chars = at.reinterpret(Long.MAX_VALUE)
    val text = StringBuilder()
    var index = 0L
    while (true) {
        val character = chars.get(ValueLayout.JAVA_CHAR, index * Char.SIZE_BYTES)
        if (character.code == 0) return text.toString()
        text.append(character)
        index++
    }
}
