# The rules this project adds to Compose's own (`default-compose-desktop-rules.pro`), read by the
# release build (B-155). Each one is here because of a failure, which is written above it; a rule
# with no failure behind it is a class kept against a guess.

# **ProGuard does not know that `MethodHandle.invokeExact` is signature-polymorphic.** The FFM
# downcalls in `WideArguments.kt` (B-116) return `MemorySegment`, and a polymorphic method is
# declared `(Object...)Object` and called with whatever descriptor the call site has — so ProGuard
# reads `MemorySegment invokeExact(MemorySegment, MemorySegment)` as a method that does not exist,
# warns twice, and refuses to write anything. The call is what the JVM resolves at link time;
# there is nothing missing to keep.
-dontwarn java.lang.invoke.MethodHandle
