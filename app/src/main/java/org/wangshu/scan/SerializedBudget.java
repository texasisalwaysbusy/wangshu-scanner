package org.wangshu.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Counts the actual serializer output, including escapes, metadata and every page. */
final class SerializedBudget {
    static final int MANIFEST_BYTES=2*1024*1024;
    static byte[] manifest(String serialized)throws IOException {
        if(serialized==null)throw new IOException("文稿序列化未返回结果；当前草稿保留");
        byte[] bytes=serialized.getBytes(StandardCharsets.UTF_8);
        if(!serialized.equals(new String(bytes,StandardCharsets.UTF_8)))throw new IOException("文稿包含无法无损保存的文字编码；当前草稿保留");
        if(bytes.length>MANIFEST_BYTES)throw new IOException("整本文稿参数超过 2 MiB（含全部页面和 JSON 转义）；请缩短本页文字，当前输入保留，尚未保存");
        return bytes;
    }
}
