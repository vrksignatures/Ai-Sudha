package com.vrksignatures.sudhaautobot.core;

import java.io.File;

/**
 * Supplies the directory the {@link DailyLedger} lives in.
 *
 * <p>One line in production ({@code context.getFilesDir()}), but kept behind an
 * interface so the ledger's persistence — atomic writes, day rollover, corrupt
 * file recovery — can be exercised on the JVM without an Android device.</p>
 */
public interface FilesDirProvider {
    File getFilesDir();
}
