package org.wangshu.scan;

import java.io.*;

/** Save a text receipt once to an explicitly chosen, empty, non-truncating provider descriptor. */
final class TextDocumentSave {
    interface Target {Handle open()throws Exception;}
    interface Handle extends AutoCloseable {boolean regular();long bytes();OutputStream output();void close()throws Exception;}
    static void requireEmpty(boolean regular,long bytes)throws IOException{
        if(!regular||bytes!=0)throw new IOException("文字只能另存到已确认为空的新文件；所选目标非空或状态未知，未覆盖，请重新选择");
    }
    static void save(SaveJobStore jobs,String token,Target target)throws Exception{
        try(SaveJobStore.Claim claim=jobs.claim(token)){
            try{
                if(!"text/plain".equals(claim.job.mime))throw new IOException("保存请求不是文字副本");
                try(Handle destination=target.open()){
                    requireEmpty(destination.regular(),destination.bytes());
                    OutputStream out=destination.output();if(out==null)throw new IOException("新文字文件无法写入");
                    byte[] bytes=new byte[65536];int count;
                    while(true){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("文字保存已中断，目标可能不完整");count=claim.input.read(bytes);if(count<0)break;if(count>0)out.write(bytes,0,count);}
                    out.flush();if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("文字保存已中断，目标可能不完整");
                }
                jobs.done(token);
            }catch(Exception failure){
                // Keep LIVE_WRITERS ownership until failure state is durable; a concurrent
                // reselect must not race a late uncertain() after the claim was released.
                try{jobs.uncertain(token);}catch(Exception state){failure.addSuppressed(state);}throw failure;
            }
        }
    }
}
