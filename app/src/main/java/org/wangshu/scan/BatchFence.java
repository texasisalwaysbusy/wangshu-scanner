package org.wangshu.scan;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Per-journal in-process fence. Caller must serialize discovery and journal operations. */
final class BatchFence {
    static final class Uncertain extends IOException {
        Uncertain(String reason, Throwable cause) { super("批次记录结果未确认；原图及所有候选保留。" + reason, cause); }
        Uncertain(String reason) { this(reason, null); }
    }
    interface Probe { void inspect(Path p) throws IOException; }
    interface Hook { void run(String at) throws IOException; }
    static final Hook NONE = at -> {};
    private static final Probe ATTR = p -> Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    private static final int LIMIT = 512 * 1024;
    static Path guard(Path base) { return base.resolveSibling(base.getFileName().toString() + ".guard"); }
    static boolean present(Path p, Probe probe) throws IOException {
        try { probe.inspect(p); return true; }
        catch (NoSuchFileException absent) { return false; }
        catch (IOException | SecurityException e) { throw new Uncertain("无法确认路径状态。", e); }
    }
    private static boolean present(Path p) throws IOException { return present(p, ATTR); }
    private static void noSidecars(Path base) throws IOException {
        if (present(base.resolveSibling(base.getFileName()+".new")) || present(base.resolveSibling(base.getFileName()+".bak")))
            throw new Uncertain("发现待核对的 .new/.bak。");
    }
    private static byte[] read(Path p, int max) throws IOException {
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile()) throw new IOException("非普通文件");
        try (InputStream in = Files.newInputStream(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) { if (out.size() + n > max) throw new IOException("文件过大"); out.write(b,0,n); }
            return out.toByteArray();
        }
    }
    private static String sha(byte[] b) throws IOException {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(b); StringBuilder s = new StringBuilder(); for (byte v : digest) s.append(String.format(Locale.ROOT,"%02x",v & 255)); return s.toString(); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    private static String state(Path base) throws IOException {
        Path g=guard(base); if (!present(g)) return null;
        try { return new String(read(g,256),StandardCharsets.US_ASCII); }
        catch (IOException | SecurityException e) { throw new Uncertain("无法读取保护记录。",e); }
    }
    static byte[] readConfirmed(Path base) throws IOException {
        noSidecars(base); String state=state(base);
        if (state!=null && !state.matches("v1:C:[a-f0-9]{64}\\n")) throw new Uncertain("保护记录未完成或损坏。");
        byte[] bytes;
        try { bytes=read(base,LIMIT); }
        catch (IOException | SecurityException e) { if (state!=null) throw new Uncertain("已保护记录无法读取。",e); throw new IOException("无法读取旧记录。",e); }
        noSidecars(base);
        if (state!=null && !state.equals("v1:C:"+sha(bytes)+"\n")) throw new Uncertain("记录与保护摘要不一致。");
        return bytes;
    }
    private static void writeGuard(Path base, String value, boolean fresh) throws IOException {
        Set<OpenOption> opts=new HashSet<>(); opts.add(StandardOpenOption.WRITE); opts.add(LinkOption.NOFOLLOW_LINKS);
        if(fresh) opts.add(StandardOpenOption.CREATE_NEW); else opts.add(StandardOpenOption.TRUNCATE_EXISTING);
        try(FileChannel f=FileChannel.open(guard(base),opts)) { ByteBuffer b=ByteBuffer.wrap(value.getBytes(StandardCharsets.US_ASCII)); while(b.hasRemaining()) f.write(b); f.force(true); }
    }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("操作已取消。");
    }
    /** Read-only listing; no returned ID implies a verified document association or valid JSON. */
    static final class Candidates {
        final boolean directoryMissing;
        final List<String> ids;
        Candidates(boolean missing, Collection<String> values) {
            directoryMissing=missing; ids=Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
    interface DirectoryReader { DirectoryStream<Path> open(Path dir) throws IOException; }
    static Candidates enumerate(Path dir) throws IOException { return enumerate(dir, Files::newDirectoryStream); }
    static Candidates enumerate(Path dir, DirectoryReader reader) throws IOException {
        checkInterrupted();
        try {
            BasicFileAttributes a;
            try { a=Files.readAttributes(dir,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS); }
            catch (NoSuchFileException absent) { return new Candidates(true,Collections.emptyList()); }
            if (!a.isDirectory() || a.isSymbolicLink()) throw new Uncertain("批次目录不是可信的普通目录。");
            TreeSet<String> ids=new TreeSet<>(); int count=0;
            try (DirectoryStream<Path> paths=reader.open(dir)) {
                for (Path path:paths) {
                    checkInterrupted();
                    if (++count>4096) throw new Uncertain("批次目录项目超出只读检查上限。");
                    String id=candidateId(path.getFileName().toString());
                    if(id==null) throw new Uncertain("发现无法识别的批次目录项目；归属未知，均已保留。");
                    ids.add(id);
                }
            }
            checkInterrupted();
            return new Candidates(false,ids);
        } catch (Uncertain | InterruptedIOException e) { throw e; }
        catch (IOException | DirectoryIteratorException | SecurityException e) { throw new Uncertain("无法完整读取批次目录；未推断任何文稿归属。",e); }
    }
    /** New identity requires base absence. A guard-only artifact must be listed as uncertain. */
    static void begin(Path base, boolean newIdentity, Hook hook) throws IOException {
        checkInterrupted(); // no filesystem access or side effects
        boolean started=false;
        try {
            noSidecars(base); String previous=state(base); boolean exists=present(base);
            if(newIdentity && (exists || previous!=null)) throw new Uncertain("新批次编号已有候选。");
            if(!newIdentity) { if(!exists) throw new Uncertain("已有批次的主记录缺失。"); readConfirmed(base); }
            String pending="v1:P:"+UUID.randomUUID()+"\n";
            checkInterrupted();
            hook.run("before-guard"); checkInterrupted();
            started=true; writeGuard(base,pending,previous==null); hook.run("after-guard"); checkInterrupted();
            if(!pending.equals(state(base))) throw new IOException("保护记录校验失败");
        } catch(Uncertain e) { throw e; }
        catch(InterruptedIOException e) { if (!started) throw e; throw new Uncertain("保护写入已开始，取消后的状态未知。",e); }
        catch(IOException | RuntimeException e) { throw new Uncertain("写前保护未确认；不得自动重试。",e); }
    }
    /** Invoke only after publication; all readback failures remain uncertain. */
    static void confirm(Path base, byte[] expected, Hook hook) throws IOException {
        try {
            noSidecars(base); String p=state(base); if(p==null || !p.matches("v1:P:[a-f0-9-]{36}\\n")) throw new IOException("保护记录无效");
            hook.run("before-readback"); byte[] bytes=read(base,LIMIT); noSidecars(base);
            if(!Arrays.equals(bytes,expected)) throw new IOException("发布内容不一致");
            String done="v1:C:"+sha(bytes)+"\n"; hook.run("before-confirm"); writeGuard(base,done,false); hook.run("after-confirm");
            if(!done.equals(state(base))) throw new IOException("确认记录校验失败");
        } catch(Uncertain e) { throw e; }
        catch(IOException | RuntimeException e) { throw new Uncertain("发布或核验结果未知；不得自动重试。",e); }
    }
    /** Caller enumerates all four suffixes, including an orphan guard-only batch. */
    static String candidateId(String name) {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^([a-f0-9-]{36})\\.json(?:\\.guard|\\.new|\\.bak)?$").matcher(name);
        if(!m.matches()) return null;
        try { return UUID.fromString(m.group(1)).toString().equals(m.group(1)) ? m.group(1) : null; } catch(IllegalArgumentException e) { return null; }
    }
}
