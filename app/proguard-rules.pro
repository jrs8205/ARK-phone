# Call types are persisted by name in the Room log and read back with
# valueOf(), so the constant names must survive minification.
-keepclassmembers enum org.jarsi.arkphone.data.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Room resolves this entity's columns by field name through generated code.
-keep class org.jarsi.arkphone.data.WhatsAppCallEntity { *; }

# The call-path diagnostics stay in release builds: field problems only ever
# reproduce on a real phone with a real network, and every one of these lines
# was added to solve a bug that could not be reproduced any other way. They
# already carry no numbers or caller names.
