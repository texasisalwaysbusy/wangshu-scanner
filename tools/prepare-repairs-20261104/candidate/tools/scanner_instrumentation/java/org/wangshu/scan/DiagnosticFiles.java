package org.wangshu.scan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;

/** Per-phase diagnostic files. A failed or duplicate write never replaces evidence.
 * The caller owns the fresh run directory. Partial files remain for diagnosis.
 * This is best-effort diagnostic I/O, never an acceptance or cancellation receipt.
 */
public final class DiagnosticFiles {
    public static final String FORMAT="wangshu.scan.program-stacks.v2";
    private DiagnosticFiles() {}

    public static String filename(String stage,String id) {
        if(!"startup".equals(stage)&&!"preparation".equals(stage)&&!"case".equals(stage))
            throw new IllegalArgumentException("Invalid diagnostic phase");
        if(id==null||!id.matches("[A-Z][A-Z0-9_]{0,79}"))
            throw new IllegalArgumentException("Invalid diagnostic identifier");
        return "program-stacks-"+stage+"-"+id.toLowerCase(Locale.ROOT).replace('_','-')+".json";
    }

    public static Path write(Path directory,String stage,String id,byte[] content)throws IOException {
        String name=filename(stage,id);Objects.requireNonNull(content,"content");
        if(directory==null||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Existing run evidence directory required");
        Path destination=directory.resolve(name);
        try(FileChannel output=FileChannel.open(destination,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            ByteBuffer bytes=ByteBuffer.wrap(content);
            while(bytes.hasRemaining())output.write(bytes);
            output.force(true);
        }
        return destination;
    }
}
