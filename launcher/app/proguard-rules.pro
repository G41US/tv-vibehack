# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# Run from the shell by name (app_process), so nothing in the app references it.
-keep class com.gathin.tvhome.AcceptWifi { public static void main(java.lang.String[]); }
