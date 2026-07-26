# Call types are persisted by name in the Room log and read back with
# valueOf(), so the constant names must survive minification.
-keepclassmembers enum org.jarsi.arkphone.data.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Room resolves this entity's columns by field name through generated code.
-keep class org.jarsi.arkphone.data.WhatsAppCallEntity { *; }

# Release builds must not carry the call-path diagnostics: they exist for
# field debugging and some of them describe who is calling.
-assumenosideeffects class android.util.Log {
    public static int i(...);
    public static int d(...);
    public static int v(...);
}
