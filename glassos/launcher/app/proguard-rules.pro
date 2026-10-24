# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# The phone remote key helper is started by name with app_process (see InjectServer).
-keep class com.gathin.tvhome.InjectServer { public static void main(java.lang.String[]); }
